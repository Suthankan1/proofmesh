// Package executionevidence persists invocation intent and observed outcomes.
// It never treats a network error as proof that a remote side effect did not occur.
package executionevidence

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"time"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
)

const finishTimeout = 2 * time.Second

var ErrEvidenceUnavailable = errors.New("executionevidence: evidence unavailable")

// Outcome contains bounded metadata only; no token, payload or raw error.
type Outcome struct {
	Status        string
	ResponseHash  string
	ResponseBytes int64
}

// Store transitions an already-admitted grant; it cannot admit or reauthorize one.
type Store interface {
	Start(context.Context, pep.BoundToolCall) error
	Finish(context.Context, pep.BoundToolCall, Outcome) error
}
type Executor struct {
	store Store
	tool  pep.ToolExecutor
}

func NewExecutor(store Store, tool pep.ToolExecutor) (*Executor, error) {
	if store == nil || tool == nil {
		return nil, ErrEvidenceUnavailable
	}
	return &Executor{store: store, tool: tool}, nil
}
func (e *Executor) Execute(ctx context.Context, call pep.BoundToolCall) (pep.ToolResult, error) {
	if e == nil || e.store == nil || e.tool == nil {
		return pep.ToolResult{}, ErrEvidenceUnavailable
	}
	if err := e.store.Start(ctx, call); err != nil {
		return pep.ToolResult{}, ErrEvidenceUnavailable
	}
	result, toolErr := e.tool.Execute(ctx, call)
	outcome := Outcome{Status: "UNKNOWN"}
	if toolErr == nil {
		hash := sha256.Sum256(result.Payload)
		outcome = Outcome{Status: "RESULT_RECEIVED", ResponseHash: fmt.Sprintf("%x", hash), ResponseBytes: int64(len(result.Payload))}
	}
	// A caller disconnect must not suppress outcome recording. Bound this independent write.
	finishCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), finishTimeout)
	defer cancel()
	if err := e.store.Finish(finishCtx, call, outcome); err != nil {
		return pep.ToolResult{}, ErrEvidenceUnavailable
	}
	if toolErr != nil {
		return pep.ToolResult{}, pep.ErrToolExecutionFailed
	}
	return result, nil
}
