package pep

import (
	"context"
)

// ClaimResult defines the outcome of an atomic execution-authority claim attempt.
type ClaimResult uint8

const (
	// ClaimResultUnknown represents an uninitialized or unrecognized claim result.
	ClaimResultUnknown ClaimResult = iota

	// ClaimAcquired indicates that the atomic claim for this grant ID succeeded.
	ClaimAcquired

	// ClaimReplay indicates that this grant ID has already been claimed.
	ClaimReplay
)

// String returns a human-readable representation of ClaimResult.
func (r ClaimResult) String() string {
	switch r {
	case ClaimAcquired:
		return "ACQUIRED"
	case ClaimReplay:
		return "REPLAY"
	default:
		return "UNKNOWN"
	}
}

// ExecutionAuthority abstracts single-use replay-admission authority for protected tool execution.
// Implementations MUST ensure that a given execution grant ID (jti) is admitted at most once.
//
// ExecutionAuthority only arbitrates execution admission; it does not execute tools,
// manage retry policies, or unclaim grants on downstream failures.
type ExecutionAuthority interface {
	Claim(ctx context.Context, call BoundToolCall) (ClaimResult, error)
}
