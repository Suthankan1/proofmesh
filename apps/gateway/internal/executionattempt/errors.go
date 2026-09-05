package executionattempt

import "errors"

var (
	// ErrInvalidExecutionAttempt indicates the execution attempt has missing, nil, or invalid identifier/tool/operation fields.
	ErrInvalidExecutionAttempt = errors.New("execution attempt is invalid")

	// ErrInvalidAttemptPayload indicates the execution attempt payload is nil, blank, malformed, not a JSON object, or contains duplicate keys.
	ErrInvalidAttemptPayload = errors.New("execution attempt payload is invalid")

	// ErrGrantBindingMismatch indicates the execution attempt does not exactly match the verified execution grant across all governed dimensions.
	ErrGrantBindingMismatch = errors.New("execution grant does not bind to execution attempt")
)
