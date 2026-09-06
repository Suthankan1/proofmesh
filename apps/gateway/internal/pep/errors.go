package pep

import "errors"

var (
	// ErrNilVerifier indicates the enforcer was constructed with a nil verifier.
	ErrNilVerifier = errors.New("pep: verifier must not be nil")

	// ErrNilAuthority indicates the enforcer was constructed with a nil execution authority.
	ErrNilAuthority = errors.New("pep: authority must not be nil")

	// ErrNilExecutor indicates the enforcer was constructed with a nil tool executor.
	ErrNilExecutor = errors.New("pep: executor must not be nil")

	// ErrNilClock indicates the enforcer was constructed with a nil clock.
	ErrNilClock = errors.New("pep: clock must not be nil")

	// ErrInvalidEnforcer indicates the enforcer instance has not been properly initialized.
	ErrInvalidEnforcer = errors.New("pep: enforcer is not properly initialized")

	// ErrVerificationFailed indicates that execution grant token verification failed.
	ErrVerificationFailed = errors.New("pep: execution grant verification failed")

	// ErrBindingFailed indicates that the execution attempt failed to bind to the verified grant.
	ErrBindingFailed = errors.New("pep: execution attempt binding failed")

	// ErrInvalidBoundAttempt indicates that the bound attempt object failed sanity or zero-value checks.
	ErrInvalidBoundAttempt = errors.New("pep: invalid or zero-value bound execution attempt")

	// ErrExecutionGrantExpired indicates that the grant has expired at the execution boundary.
	ErrExecutionGrantExpired = errors.New("pep: execution grant expired")

	// ErrExecutionAuthorityFailed indicates that the execution authority failed or returned an invalid result.
	ErrExecutionAuthorityFailed = errors.New("pep: execution authority failed")

	// ErrExecutionReplay indicates that the execution grant has already been claimed and admitted.
	ErrExecutionReplay = errors.New("pep: execution replay detected")

	// ErrExecutionCanceled indicates that execution was canceled via context.
	ErrExecutionCanceled = errors.New("pep: execution canceled")

	// ErrToolExecutionFailed indicates that downstream tool execution failed.
	ErrToolExecutionFailed = errors.New("pep: tool execution failed")
)
