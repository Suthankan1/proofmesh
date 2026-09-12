package dbmigrate

import (
	"errors"
	"os"
	"strings"
)

// EnvDatabaseURL is the environment variable key required to configure migration execution.
const EnvDatabaseURL = "PROOFMESH_DATABASE_URL"

// Sentinel configuration errors.
// Raw environment values, connection strings, credentials, and error details
// are strictly sanitized and never returned across this boundary.
var (
	ErrMissingDatabaseURL = errors.New("dbmigrate: missing required database URL")
	ErrBlankDatabaseURL   = errors.New("dbmigrate: database URL is blank")
)

// LookupEnv resolves an environment variable by name.
type LookupEnv func(string) (string, bool)

// Config contains the minimal configuration required for gateway database migrations.
type Config struct {
	DatabaseURL string
}

// LoadConfig reads and validates required environment variables using os.LookupEnv.
func LoadConfig() (Config, error) {
	return LoadConfigWith(os.LookupEnv)
}

// LoadConfigWith reads and validates required environment variables using a custom lookup function.
func LoadConfigWith(lookup LookupEnv) (Config, error) {
	if lookup == nil {
		lookup = os.LookupEnv
	}

	val, ok := lookup(EnvDatabaseURL)
	if !ok {
		return Config{}, ErrMissingDatabaseURL
	}

	trimmed := strings.TrimSpace(val)
	if trimmed == "" {
		return Config{}, ErrBlankDatabaseURL
	}

	return Config{
		DatabaseURL: trimmed,
	}, nil
}
