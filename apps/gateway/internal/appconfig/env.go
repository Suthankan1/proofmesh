package appconfig

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/runtime"
	httpexecutor "github.com/Suthankan1/proofmesh/apps/gateway/internal/toolexecutor/http"
)

// Environment variable keys required to initialize the gateway Runtime.
const (
	EnvDatabaseURL            = "PROOFMESH_DATABASE_URL"
	EnvExecutionGrantIssuer   = "PROOFMESH_EXECUTION_GRANT_ISSUER"
	EnvExecutionGrantAudience = "PROOFMESH_EXECUTION_GRANT_AUDIENCE"
	EnvJWKSURL                = "PROOFMESH_JWKS_URL"
	EnvToolTargetsJSON        = "PROOFMESH_TOOL_TARGETS_JSON"
)

// Sentinel errors returned by the environment configuration loader.
// Raw environment values, connection strings, credentials, and JSON snippets
// are strictly sanitized and never returned across this boundary.
var (
	ErrMissingEnvironment = errors.New("appconfig: missing required environment variable")
	ErrInvalidEnvironment = errors.New("appconfig: invalid environment variable")
)

// lookupEnv resolves an environment variable by name.
type lookupEnv func(string) (string, bool)

// Load reads and validates required environment variables using os.LookupEnv
// and maps them into a runtime.Config.
func Load() (runtime.Config, error) {
	return load(os.LookupEnv)
}

func load(lookup lookupEnv) (runtime.Config, error) {
	if lookup == nil {
		lookup = os.LookupEnv
	}

	dbURL, err := requireNonBlankEnv(lookup, EnvDatabaseURL)
	if err != nil {
		return runtime.Config{}, err
	}

	issuer, err := requireNonBlankEnv(lookup, EnvExecutionGrantIssuer)
	if err != nil {
		return runtime.Config{}, err
	}

	audience, err := requireNonBlankEnv(lookup, EnvExecutionGrantAudience)
	if err != nil {
		return runtime.Config{}, err
	}

	jwksURL, err := requireNonBlankEnv(lookup, EnvJWKSURL)
	if err != nil {
		return runtime.Config{}, err
	}

	targetsJSON, err := requireNonBlankEnv(lookup, EnvToolTargetsJSON)
	if err != nil {
		return runtime.Config{}, err
	}

	targets, err := parseToolTargets(targetsJSON)
	if err != nil {
		return runtime.Config{}, err
	}

	return runtime.Config{
		DatabaseURL: dbURL,
		Issuer:      issuer,
		Audience:    audience,
		JWKSURL:     jwksURL,
		ToolTargets: targets,
	}, nil
}

func requireNonBlankEnv(lookup lookupEnv, key string) (string, error) {
	val, ok := lookup(key)
	if !ok {
		return "", fmt.Errorf("%w: %s is not set", ErrMissingEnvironment, key)
	}
	if strings.TrimSpace(val) == "" {
		return "", fmt.Errorf("%w: %s is blank", ErrInvalidEnvironment, key)
	}
	return val, nil
}

type targetJSON struct {
	ToolName      string `json:"tool_name"`
	OperationName string `json:"operation_name"`
	URL           string `json:"url"`
}

type targetKey struct {
	toolName      string
	operationName string
}

func parseToolTargets(raw string) ([]httpexecutor.Target, error) {
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.DisallowUnknownFields()

	var rawTargets []targetJSON
	if err := decoder.Decode(&rawTargets); err != nil {
		return nil, fmt.Errorf("%w: %s contains malformed JSON", ErrInvalidEnvironment, EnvToolTargetsJSON)
	}

	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return nil, fmt.Errorf("%w: %s contains trailing data after JSON array", ErrInvalidEnvironment, EnvToolTargetsJSON)
	}

	if len(rawTargets) == 0 {
		return nil, fmt.Errorf("%w: %s contains empty target list", ErrInvalidEnvironment, EnvToolTargetsJSON)
	}

	targets := make([]httpexecutor.Target, 0, len(rawTargets))
	seen := make(map[targetKey]struct{}, len(rawTargets))

	for _, t := range rawTargets {
		if strings.TrimSpace(t.ToolName) == "" ||
			strings.TrimSpace(t.OperationName) == "" ||
			strings.TrimSpace(t.URL) == "" {
			return nil, fmt.Errorf("%w: %s contains target with missing or blank fields", ErrInvalidEnvironment, EnvToolTargetsJSON)
		}

		key := targetKey{
			toolName:      t.ToolName,
			operationName: t.OperationName,
		}
		if _, exists := seen[key]; exists {
			return nil, fmt.Errorf("%w: %s contains duplicate target mapping", ErrInvalidEnvironment, EnvToolTargetsJSON)
		}
		seen[key] = struct{}{}

		targets = append(targets, httpexecutor.Target{
			ToolName:      t.ToolName,
			OperationName: t.OperationName,
			URL:           t.URL,
		})
	}

	return targets, nil
}
