package executiongrant

import "time"

// Clock provides current time for execution grant verification.
type Clock interface {
	Now() time.Time
}

// SystemClock returns current UTC system time.
type SystemClock struct{}

// Now returns time.Now().UTC().
func (SystemClock) Now() time.Time {
	return time.Now().UTC()
}
