package executionevidence

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"testing"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
)

type fakeStore struct {
	events              *[]string
	startErr, finishErr error
	outcome             Outcome
	finishCanceled      bool
}

func (s *fakeStore) Start(_ context.Context, _ pep.BoundToolCall) error {
	*s.events = append(*s.events, "start")
	return s.startErr
}
func (s *fakeStore) Finish(ctx context.Context, _ pep.BoundToolCall, outcome Outcome) error {
	*s.events = append(*s.events, "finish")
	s.outcome = outcome
	s.finishCanceled = ctx.Err() != nil
	return s.finishErr
}

type fakeTool struct {
	events *[]string
	err    error
	cancel context.CancelFunc
}

func (f fakeTool) Execute(_ context.Context, _ pep.BoundToolCall) (pep.ToolResult, error) {
	*f.events = append(*f.events, "tool")
	if f.cancel != nil {
		f.cancel()
	}
	return pep.ToolResult{Payload: []byte("result")}, f.err
}
func TestEvidenceOrderingAndFailureSemantics(t *testing.T) {
	failure := errors.New("private infrastructure error")
	for _, tc := range []struct {
		name                         string
		startErr, toolErr, finishErr error
		wantEvents, wantStatus       string
		cancel                       bool
		wantErr                      bool
	}{
		{name: "received result", wantEvents: "[start tool finish]", wantStatus: "RESULT_RECEIVED"},
		{name: "start unavailable", startErr: failure, wantEvents: "[start]", wantErr: true},
		{name: "tool error ambiguous", toolErr: failure, wantEvents: "[start tool finish]", wantStatus: "UNKNOWN", wantErr: true},
		{name: "result persistence unavailable", finishErr: failure, wantEvents: "[start tool finish]", wantStatus: "RESULT_RECEIVED", wantErr: true},
		{name: "cancellation still records uncertainty", toolErr: failure, cancel: true, wantEvents: "[start tool finish]", wantStatus: "UNKNOWN", wantErr: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			events := []string{}
			store := &fakeStore{events: &events, startErr: tc.startErr, finishErr: tc.finishErr}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			tool := fakeTool{events: &events, err: tc.toolErr}
			if tc.cancel {
				tool.cancel = cancel
			}
			executor, err := NewExecutor(store, tool)
			if err != nil {
				t.Fatal(err)
			}
			result, err := executor.Execute(ctx, pep.BoundToolCall{})
			if (err != nil) != tc.wantErr {
				t.Fatalf("unexpected error %v", err)
			}
			if err != nil && err.Error() == failure.Error() {
				t.Fatal("raw error leaked")
			}
			if fmt.Sprint(events) != tc.wantEvents {
				t.Fatalf("events %v", events)
			}
			if store.outcome.Status != tc.wantStatus {
				t.Fatalf("status %s", store.outcome.Status)
			}
			if store.finishCanceled {
				t.Fatal("audit finish inherits canceled context")
			}
			if !tc.wantErr && string(result.Payload) != "result" {
				t.Fatal("result lost")
			}
			if tc.wantStatus == "RESULT_RECEIVED" {
				hash := sha256.Sum256([]byte("result"))
				if store.outcome.ResponseHash != fmt.Sprintf("%x", hash) || store.outcome.ResponseBytes != 6 {
					t.Fatal("result fingerprint incorrect")
				}
			} else if store.outcome.ResponseHash != "" || store.outcome.ResponseBytes != 0 {
				t.Fatal("ambiguous outcome fabricated response evidence")
			}
		})
	}
}
func TestNilDependenciesFailClosed(t *testing.T) {
	if _, err := NewExecutor(nil, fakeTool{}); err == nil {
		t.Fatal("nil store accepted")
	}
	if _, err := NewExecutor(&fakeStore{}, nil); err == nil {
		t.Fatal("nil executor accepted")
	}
	var executor *Executor
	if _, err := executor.Execute(context.Background(), pep.BoundToolCall{}); err == nil {
		t.Fatal("nil receiver accepted")
	}
}
