package appconfig

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/runtime"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/telemetry"
	httpexecutor "github.com/Suthankan1/proofmesh/apps/gateway/internal/toolexecutor/http"
)

// Environment variable keys required to initialize the gateway Runtime.
const (
	EnvDatabaseURL            = "PROOFMESH_DATABASE_URL"
	EnvExecutionGrantIssuer   = "PROOFMESH_EXECUTION_GRANT_ISSUER"
	EnvExecutionGrantAudience = "PROOFMESH_EXECUTION_GRANT_AUDIENCE"
	EnvJWKSURL                = "PROOFMESH_JWKS_URL"
	EnvToolTargetsJSON        = "PROOFMESH_TOOL_TARGETS_JSON"
	EnvOTLPEndpoint           = "OTEL_EXPORTER_OTLP_ENDPOINT"
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

// environFunc returns all environment variables in KEY=VALUE format.
type environFunc func() []string

// Config aggregates all runtime and operational configuration for the gateway application.
// It embeds runtime.Config so runtime fields can be accessed transparently.
type Config struct {
	runtime.Config
	Telemetry telemetry.Config
}

// Load reads and validates required environment variables using os.LookupEnv
// and maps them into an application Config.
func Load() (Config, error) {
	return load(os.LookupEnv, os.Environ)
}

func load(lookup lookupEnv, environ environFunc) (Config, error) {
	if lookup == nil {
		lookup = os.LookupEnv
	}
	if environ == nil {
		environ = os.Environ
	}

	dbURL, err := requireNonBlankEnv(lookup, EnvDatabaseURL)
	if err != nil {
		return Config{}, err
	}

	issuer, err := requireNonBlankEnv(lookup, EnvExecutionGrantIssuer)
	if err != nil {
		return Config{}, err
	}

	audience, err := requireNonBlankEnv(lookup, EnvExecutionGrantAudience)
	if err != nil {
		return Config{}, err
	}

	jwksURL, err := requireNonBlankEnv(lookup, EnvJWKSURL)
	if err != nil {
		return Config{}, err
	}

	targetsJSON, err := requireNonBlankEnv(lookup, EnvToolTargetsJSON)
	if err != nil {
		return Config{}, err
	}

	targets, err := parseToolTargets(targetsJSON)
	if err != nil {
		return Config{}, err
	}

	telCfg, err := parseTelemetryConfig(lookup, environ)
	if err != nil {
		return Config{}, err
	}

	return Config{
		Config: runtime.Config{
			DatabaseURL: dbURL,
			Issuer:      issuer,
			Audience:    audience,
			JWKSURL:     jwksURL,
			ToolTargets: targets,
		},
		Telemetry: telCfg,
	}, nil
}

func parseTelemetryConfig(lookup lookupEnv, environ environFunc) (telemetry.Config, error) {
	val, ok := lookup(EnvOTLPEndpoint)
	if !ok {
		return telemetry.Config{}, nil
	}
	if strings.TrimSpace(val) == "" {
		return telemetry.Config{}, nil
	}

	if val != strings.TrimSpace(val) {
		return telemetry.Config{}, fmt.Errorf("%w: %s has surrounding whitespace", ErrInvalidEnvironment, EnvOTLPEndpoint)
	}

	if err := validateEndpointURL(val); err != nil {
		return telemetry.Config{}, fmt.Errorf("%w: %s is invalid", ErrInvalidEnvironment, EnvOTLPEndpoint)
	}

	if environ != nil {
		for _, entry := range environ() {
			key, _, _ := strings.Cut(entry, "=")
			if strings.HasPrefix(key, "OTEL_") && key != EnvOTLPEndpoint {
				return telemetry.Config{}, fmt.Errorf("%w: unsupported environment variable %s", ErrInvalidEnvironment, key)
			}
		}
	}

	return telemetry.Config{
		Endpoint: val,
	}, nil
}

func validateEndpointURL(raw string) error {
	if strings.ContainsAny(raw, " \t\r\n") {
		return errors.New("whitespace in endpoint")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return errors.New("parse error")
	}
	if u.Opaque != "" {
		return errors.New("opaque url")
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" {
		return errors.New("unsupported scheme")
	}
	if u.User != nil {
		return errors.New("userinfo not allowed")
	}
	if u.RawQuery != "" || u.ForceQuery {
		return errors.New("query not allowed")
	}
	if u.Fragment != "" {
		return errors.New("fragment not allowed")
	}
	host := u.Hostname()
	if host == "" {
		return errors.New("missing hostname")
	}
	portStr := u.Port()
	if portStr != "" {
		port, err := strconv.Atoi(portStr)
		if err != nil || port < 1 || port > 65535 {
			return errors.New("invalid port")
		}
	}
	if strings.HasPrefix(u.Host, "[") {
		idx := strings.Index(u.Host, "]")
		if idx == -1 {
			return errors.New("unclosed IPv6 bracket")
		}
		ipStr := u.Host[1:idx]
		ip := net.ParseIP(ipStr)
		if ip == nil || ip.To16() == nil {
			return errors.New("invalid IPv6 address")
		}
	} else if ip := net.ParseIP(host); ip != nil {
		// Valid IPv4
	} else {
		if strings.ContainsAny(host, ":/\\?#@") {
			return errors.New("invalid host characters")
		}
	}
	return nil
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
