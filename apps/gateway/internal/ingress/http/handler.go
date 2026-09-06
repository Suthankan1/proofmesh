package httpingress

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/google/uuid"

	"github.com/Suthankan1/proofmesh/apps/gateway/internal/executionattempt"
	"github.com/Suthankan1/proofmesh/apps/gateway/internal/pep"
)

const (
	// DefaultMaxRequestBodyBytes is the centralized maximum request body size (1 MiB).
	DefaultMaxRequestBodyBytes int64 = 1 << 20

	// ExecutionEndpoint is the sole protected execution path served by this handler.
	ExecutionEndpoint = "/v1/executions"
)

var (
	// ErrNilEnforcer indicates the handler was constructed with a nil enforcer.
	ErrNilEnforcer = errors.New("httpingress: enforcer must not be nil")

	errMalformedJSON        = errors.New("malformed json")
	errRootNotObject        = errors.New("json root must be an object")
	errUnknownField         = errors.New("unknown field in execution envelope")
	errDuplicateKey         = errors.New("duplicate member in execution envelope")
	errMissingRequiredField = errors.New("missing required field in execution envelope")
	errTrailingContent      = errors.New("trailing content after json object")
	errInvalidUUID          = errors.New("invalid or nil uuid")
	errBlankToolName        = errors.New("tool name must not be blank")
	errBlankOperationName   = errors.New("operation name must not be blank")
	errEmptyPayload         = errors.New("payload must not be empty")
)

// Handler is the ingress HTTP handler for POST /v1/executions.
// It strictly validates transport inputs, builds an ExecutionAttempt,
// and invokes only the injected PEP Enforcer.
type Handler struct {
	enforcer     *pep.Enforcer
	maxBodyBytes int64
}

// Compile-time check that Handler implements http.Handler.
var _ http.Handler = (*Handler)(nil)

// NewHandler constructs a new Handler with explicit non-nil enforcer dependency.
func NewHandler(enforcer *pep.Enforcer) (*Handler, error) {
	if enforcer == nil {
		return nil, ErrNilEnforcer
	}
	return &Handler{
		enforcer:     enforcer,
		maxBodyBytes: DefaultMaxRequestBodyBytes,
	}, nil
}

// ServeHTTP handles requests for POST /v1/executions.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// 1. Strict route matching
	if r.URL.Path != ExecutionEndpoint {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}

	// 2. Strict method matching
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}

	// 3. Strict Authorization header validation
	authHeaders := r.Header.Values("Authorization")
	if len(authHeaders) != 1 {
		writeUnauthorized(w, "unauthorized")
		return
	}

	token, err := extractBearerToken(authHeaders[0])
	if err != nil {
		writeUnauthorized(w, "unauthorized")
		return
	}

	// 4. Bounded body read
	r.Body = http.MaxBytesReader(w, r.Body, h.maxBodyBytes)
	body, err := io.ReadAll(r.Body)
	if err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			writeError(w, http.StatusRequestEntityTooLarge, "payload_too_large")
			return
		}
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}

	if len(bytes.TrimSpace(body)) == 0 {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}

	// 5. Strict envelope parsing
	attempt, err := parseEnvelope(body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}

	// 6. Invoke Enforcer as sole execution boundary
	res, err := h.enforcer.Execute(r.Context(), token, attempt)
	if err != nil {
		h.mapEnforcerError(w, err)
		return
	}

	// 7. Success response
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	if len(res.Payload) > 0 {
		_, _ = w.Write(res.Payload)
	}
}

// extractBearerToken extracts and validates a single Bearer token from the Authorization header.
func extractBearerToken(authHeader string) (string, error) {
	if strings.Contains(authHeader, ",") {
		return "", errors.New("comma-separated credentials rejected")
	}

	parts := strings.Fields(authHeader)
	if len(parts) != 2 {
		return "", errors.New("invalid authorization header format")
	}

	if !strings.EqualFold(parts[0], "bearer") {
		return "", errors.New("invalid authorization scheme")
	}

	token := parts[1]
	if token == "" {
		return "", errors.New("empty token")
	}

	return token, nil
}

// parseEnvelope parses the strict JSON execution envelope and constructs an ExecutionAttempt.
func parseEnvelope(body []byte) (executionattempt.ExecutionAttempt, error) {
	dec := json.NewDecoder(bytes.NewReader(body))

	// Envelope must be a JSON object
	firstTok, err := dec.Token()
	if err != nil {
		return executionattempt.ExecutionAttempt{}, errMalformedJSON
	}
	delim, ok := firstTok.(json.Delim)
	if !ok || delim != '{' {
		return executionattempt.ExecutionAttempt{}, errRootNotObject
	}

	var (
		rawOrgID    string
		rawAgentID  string
		rawActionID string
		rawDecID    string
		toolName    string
		opName      string
		rawPayload  json.RawMessage

		hasOrgID    bool
		hasAgentID  bool
		hasActionID bool
		hasDecID    bool
		hasTool     bool
		hasOp       bool
		hasPayload  bool
	)

	seen := make(map[string]struct{}, 7)

	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return executionattempt.ExecutionAttempt{}, errMalformedJSON
		}
		key, ok := keyTok.(string)
		if !ok {
			return executionattempt.ExecutionAttempt{}, errMalformedJSON
		}

		if _, exists := seen[key]; exists {
			return executionattempt.ExecutionAttempt{}, errDuplicateKey
		}
		seen[key] = struct{}{}

		switch key {
		case "organization_id":
			if err := dec.Decode(&rawOrgID); err != nil {
				return executionattempt.ExecutionAttempt{}, errMalformedJSON
			}
			hasOrgID = true
		case "agent_id":
			if err := dec.Decode(&rawAgentID); err != nil {
				return executionattempt.ExecutionAttempt{}, errMalformedJSON
			}
			hasAgentID = true
		case "governed_action_id":
			if err := dec.Decode(&rawActionID); err != nil {
				return executionattempt.ExecutionAttempt{}, errMalformedJSON
			}
			hasActionID = true
		case "governance_decision_id":
			if err := dec.Decode(&rawDecID); err != nil {
				return executionattempt.ExecutionAttempt{}, errMalformedJSON
			}
			hasDecID = true
		case "tool_name":
			if err := dec.Decode(&toolName); err != nil {
				return executionattempt.ExecutionAttempt{}, errMalformedJSON
			}
			hasTool = true
		case "operation_name":
			if err := dec.Decode(&opName); err != nil {
				return executionattempt.ExecutionAttempt{}, errMalformedJSON
			}
			hasOp = true
		case "payload":
			if err := dec.Decode(&rawPayload); err != nil {
				return executionattempt.ExecutionAttempt{}, errMalformedJSON
			}
			hasPayload = true
		default:
			return executionattempt.ExecutionAttempt{}, errUnknownField
		}
	}

	endTok, err := dec.Token()
	if err != nil {
		return executionattempt.ExecutionAttempt{}, errMalformedJSON
	}
	endDelim, ok := endTok.(json.Delim)
	if !ok || endDelim != '}' {
		return executionattempt.ExecutionAttempt{}, errMalformedJSON
	}

	// Reject trailing JSON or non-whitespace content
	if _, err := dec.Token(); err != io.EOF {
		return executionattempt.ExecutionAttempt{}, errTrailingContent
	}

	// Require all 7 fields
	if !hasOrgID || !hasAgentID || !hasActionID || !hasDecID || !hasTool || !hasOp || !hasPayload {
		return executionattempt.ExecutionAttempt{}, errMissingRequiredField
	}

	// Validate UUIDs
	orgID, err := uuid.Parse(rawOrgID)
	if err != nil || orgID == uuid.Nil {
		return executionattempt.ExecutionAttempt{}, errInvalidUUID
	}
	agentID, err := uuid.Parse(rawAgentID)
	if err != nil || agentID == uuid.Nil {
		return executionattempt.ExecutionAttempt{}, errInvalidUUID
	}
	actionID, err := uuid.Parse(rawActionID)
	if err != nil || actionID == uuid.Nil {
		return executionattempt.ExecutionAttempt{}, errInvalidUUID
	}
	decID, err := uuid.Parse(rawDecID)
	if err != nil || decID == uuid.Nil {
		return executionattempt.ExecutionAttempt{}, errInvalidUUID
	}

	// Validate non-blank tool and operation
	if strings.TrimSpace(toolName) == "" {
		return executionattempt.ExecutionAttempt{}, errBlankToolName
	}
	if strings.TrimSpace(opName) == "" {
		return executionattempt.ExecutionAttempt{}, errBlankOperationName
	}

	// Validate non-empty payload
	if len(bytes.TrimSpace(rawPayload)) == 0 {
		return executionattempt.ExecutionAttempt{}, errEmptyPayload
	}

	return executionattempt.ExecutionAttempt{
		OrganizationID:       orgID,
		AgentID:              agentID,
		GovernedActionID:     actionID,
		GovernanceDecisionID: decID,
		ToolName:             toolName,
		OperationName:        opName,
		Payload:              rawPayload,
	}, nil
}

// mapEnforcerError maps PEP sentinel errors to sanitized HTTP error responses.
func (h *Handler) mapEnforcerError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, pep.ErrVerificationFailed), errors.Is(err, pep.ErrExecutionGrantExpired):
		writeUnauthorized(w, "invalid_token")
	case errors.Is(err, pep.ErrBindingFailed):
		writeError(w, http.StatusForbidden, "forbidden")
	case errors.Is(err, pep.ErrExecutionReplay):
		writeError(w, http.StatusConflict, "conflict")
	case errors.Is(err, pep.ErrExecutionAuthorityFailed):
		writeError(w, http.StatusServiceUnavailable, "service_unavailable")
	case errors.Is(err, pep.ErrToolExecutionFailed):
		writeError(w, http.StatusBadGateway, "bad_gateway")
	case errors.Is(err, pep.ErrExecutionCanceled):
		writeError(w, http.StatusGatewayTimeout, "execution_canceled")
	default:
		writeError(w, http.StatusInternalServerError, "internal_error")
	}
}

// writeError writes a sanitized JSON error response with Cache-Control: no-store.
func writeError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(`{"error":"` + code + `"}` + "\n"))
}

// writeUnauthorized writes a 401 response with WWW-Authenticate: Bearer and Cache-Control: no-store.
func writeUnauthorized(w http.ResponseWriter, code string) {
	w.Header().Set("WWW-Authenticate", "Bearer")
	writeError(w, http.StatusUnauthorized, code)
}
