# Local runtime and approval API

Human requests use verified Keycloak/OIDC JWTs (issuer, audience, expiry and role claims). Agent requests use opaque per-agent credentials. Tenant and agent identity never come from headers or body assertions. Keep local HTTP listeners on loopback. Do not log authorization headers, issuance responses, grants or payloads.

## Credentials

A PLATFORM_ADMIN with exactly one active tenant membership may POST `/api/v1/agents/{agentId}/credentials/rotate` with `{"validitySeconds":3600}`. The 201 no-store response contains `id`, one-time `secret` and `expiresAt`. Only SHA-256 is stored; rotation revokes the previous credential. POST `/api/v1/agents/{agentId}/credentials/revoke` revokes without replacing and returns204. A disabled agent can still be revoked. Scoped credential changes and human actor audit commit in one transaction; audit failure rolls back the change. PLATFORM_ADMIN does not imply APPROVER.

## Submit an exact action

POST `/api/v1/runtime/actions` using `Authorization: Bearer pm_agent_...` and JSON:

```json
{
  "idempotencyKey": "refund-local-1",
  "toolName": "payments",
  "operationName": "refund",
  "requestPayloadJson": "{\"amount\":5000,\"paymentId\":\"local-test\"}"
}
```

Exactly these four fields are accepted. Duplicate envelope keys, trailing JSON, extra authority fields and invalid/non-object payload JSON are rejected. The envelope is bounded to128KiB and payload to64KiB. An existing key with changed canonical tool/operation/payload returns409; canonical-equivalent payloads resolve the authoritative existing action. Keys are scoped to the authenticated organization and agent.

The response reports server-derived organization/agent, action/decision IDs, payload hash, outcome, risk score, approval ID where required, executionGrant where eligible, and authorityStatus. No caller risk metadata is accepted. Existing unknown-operation handling remains CRITICAL risk100; local policy must explicitly handle the exact operation at that risk. Missing policy/uncertain governance produces FAILED_CLOSED and no token. Disabled signing returns no token and SIGNING_DISABLED, even if the policy outcome is ALLOW.

REQUIRE_APPROVAL remains the historical decision after approval. Pending/rejected/expired approvals and DENY never yield authority. After human approval the agent retries the exact same submission. A live token is ES256, bound to exact tenant/agent/action/decision/tool/operation/canonical payload and short-lived (default30s).

## Human approval

GET `/api/v1/approvals?limit=50` lists bounded pending metadata (limit1–100). GET `/api/v1/approvals/{id}` returns the exact canonical payload and binding for review, scoped to the human's tenant. APPROVER, VIEWER, SECURITY_OPERATOR and PLATFORM_ADMIN may read. Only APPROVER may POST `/api/v1/approvals/{id}/approve` or `/reject` with exactly `{"rationale":"Reviewed exact action"}`. Actor and time are server-derived. Cross-tenant IDs return404; conflicting/expired transitions409. Approval does not invoke tools or mint a grant.

## At-most-once authority and execution

The HTTP submission path locks the scoped action and commits one immutable runtime_grant_issuances row before returning the first eligible token. Only its SHA-256 is persisted, never the bearer token. Concurrent or subsequent submissions return ALREADY_ISSUED with no fresh token. Pending/ineligible or disabled-signing attempts do not consume issuance. Failed persistence never returns a token and rolls back issuance so a safe retry can proceed.

If the issuance response is lost, or an unused grant expires, the same action cannot obtain another grant. This is deliberate fail-closed at-most-once authority, not an exactly-once delivery guarantee. Investigate evidence before creating a genuinely new action/key; never automate a new key after an uncertain tool outcome. A database owner can bypass immutable triggers and must remain trusted.

Send the issued token to the Go gateway POST `/v1/executions` with snake_case organization_id, agent_id, governed_action_id, governance_decision_id, tool_name, operation_name and object payload. The gateway checks exact binding and atomically spends the grant ID before invocation; original-token replay remains blocked across restarts. There is no public generic grant-minting API. Domain services used by in-process tests remain application ports, not agent capabilities.

## Reproducible verification

Run `bash scripts/test-local-governance.sh` from repository root with Java25, Go and Docker available. It builds a temporary production-runtime helper, starts disposable PostgreSQL, real Spring HTTP/JWKS and Go HTTP listeners, a controlled local tool and a signed test OIDC provider. It verifies the real resource-server JWT decoder/role converter; this provider is test infrastructure, not a replacement authentication service. Ten scenarios cover existing grant/approval/evidence invariants plus the authenticated HTTP flow, rotation, same-action concurrent issuance and injected persistence failure. Tests do not deploy or reset an existing database. Local test signing keys and helper resources are removed on completion.
