# ProofMesh

ProofMesh is an in-progress, security-focused runtime governance and execution control plane for autonomous AI agents. It decouples governance decisions from tool execution: a Spring Boot control plane acts as the Policy Decision Point (PDP) to evaluate policy, manage human approvals, and issue short-lived execution grants, while a high-performance Go gateway acts as the sole Policy Enforcement Point (PEP) to verify grants offline, enforce exact request binding, guarantee single-use replay protection, and invoke protected tools.

> **Status:** Active development. Core execution-grant verification, durable PostgreSQL replay protection, exact attempt binding, protected HTTP tool execution, runtime composition, strict HTTP ingress handling, and gateway server bootstrap with graceful shutdown are implemented; operational telemetry and deployment hardening are in progress.

---

## Why ProofMesh

Autonomous AI agents are increasingly entrusted with high-impact tools—initiating financial transactions, mutating infrastructure, querying sensitive databases, and invoking external APIs. In standard architectures, runtime security is vulnerable to several structural failures:

1. **Coupled Decision and Execution:** Agents or tool wrappers evaluate permissions locally. A compromised agent process can bypass advisory checks or execute unauthorized tools directly.
2. **Coarse-Grained Approval Semantics:** Human approval is often treated as blanket permission rather than being cryptographically bound to the exact tool, operation, and payload parameters reviewed by the operator.
3. **Replay Vulnerability:** Intercepted execution tokens or approval decisions can be reused maliciously or replayed by buggy retry loops.
4. **Uncontrolled Outbound Routing:** Agents specify arbitrary target URLs or endpoints, creating Server-Side Request Forgery (SSRF) and credential leakage vectors.
5. **Fail-Open Defaults:** Timeouts, indeterminate evaluation outcomes, or parser errors often default to execution.

ProofMesh addresses these challenges by enforcing a strict architectural boundary: the control plane never executes tools, the gateway never issues private signing keys, and protected tools only accept traffic forwarded through the hardened enforcement gateway.

---

## Architecture

ProofMesh enforces a clean separation of concerns between policy decisions (PDP) and policy enforcement (PEP):

```mermaid
flowchart LR
    subgraph Clients ["AI Agents & Operators"]
        A[AI Agent]
        H[Human Operator]
    end

    subgraph ControlPlane ["Spring Boot Control Plane (PDP)"]
        CP[Governance Engine]
        POL[Policy & Risk Evaluation]
        APP[Approval Lifecycle]
        SIGN[ES256 Grant Signer]
    end

    subgraph Storage ["Authoritative Storage"]
        PG[(PostgreSQL State & Replay DB)]
    end

    subgraph Gateway ["Go Gateway (PEP)"]
        ING[Strict HTTP Ingress\nPOST /v1/executions]
        VER[Offline ES256 Verifier\n& JWKS Cache]
        BIND[Exact Attempt Binder\n& RFC 8785 Hasher]
        REP[Durable Replay Guard]
        EXEC[Protected HTTP ToolExecutor]
    end

    subgraph Infrastructure ["Protected Infrastructure"]
        TOOL[Trusted Downstream Tool]
    end

    A -->|1. Propose Governed Action| CP
    CP --> POL
    POL -->|Require Approval| APP
    H -->|2. Approve Pending Request| APP
    APP -.->|Approved State| CP
    A -->|3. Exact Retry| CP
    CP --> SIGN
    SIGN -->|4. Mint Short-Lived Grant| A
    A -->|5. Bearer Grant + Envelope| ING
    ING --> VER
    VER --> BIND
    BIND --> REP
    REP -->|6. Atomic Claim jti| PG
    REP --> EXEC
    EXEC -->|7. Forward Clean Payload| TOOL
```

### Architectural Roles

* **Spring Boot Control Plane (PDP):** Authoritative policy decision point. Evaluates policies and risk signals, manages human operator approvals, owns private ES256 signing keys, publishes public keys via JWKS, and mints short-lived execution grants. **The control plane never invokes protected tools.**
* **Go Gateway (PEP):** Sole policy enforcement point. Exposes a strict execution ingress endpoint, validates ES256 grants offline against cached public keys, calculates canonical request payload hashes, enforces exact cryptographic binding, atomically claims grants in PostgreSQL, and dispatches requests to preconfigured downstream tools. **The gateway never receives private signing keys.**
* **PostgreSQL:** Authoritative persistence for policy models, governance decisions, approval requests, and durable first-writer-wins execution grant replay claims (`execution_grant_claims`).

---

## How Execution Works

The runtime execution lifecycle follows seven deterministic steps:

1. **Action Proposal:** The AI agent proposes a governed action to the control plane, including tool identity, operation, and intended payload.
2. **Policy Evaluation:** The control plane evaluates the action against active policies and risk assessments. If the action requires human oversight, a governance decision of `REQUIRE_APPROVAL` is persisted and an approval request is created in `PENDING` state.
3. **Human Approval:** An authorized human operator reviews the request via Keycloak/OIDC authentication and approves it. Approval transitions the request to `APPROVED` within a time-limited validity window. *Approval authorizes but does not execute the tool.*
4. **Exact Retry:** The agent retries the exact approved action. The control plane checks approval validity using `isApprovedAndValidAt(now)` and confirms exact parameter identity.
5. **Grant Issuance:** The control plane constructs an `ExecutionGrantClaims` record and signs an asymmetric ES256 token with a short TTL (target ~30 seconds). The original decision is not mutated to `ALLOW`; provenance remains intact.
6. **Gateway Submission:** The agent calls the gateway's `POST /v1/executions` endpoint, providing the grant in the `Authorization: Bearer` header and the execution envelope in the request body.
7. **Enforcement & Invocation:** The gateway validates the grant offline, verifies that all 7 binding claims match the request envelope, atomically records the grant ID (`jti`) in PostgreSQL, and forwards the clean payload to the configured downstream tool.

---

## Security Model & Invariants

ProofMesh enforces the following non-negotiable security invariants:

* **Fail-Closed by Default:** Any token expiration, signature invalidity, schema anomaly, database outage, or hash mismatch fails closed immediately.
* **DENY Never Executes:** Actions resolved as `DENY` cannot yield an execution grant and cannot be executed under any circumstance.
* **Approval Is Not Execution:** Human approval creates a time-bounded authorization window for an exact client retry; it never triggers execution directly.
* **Exact 7-Dimensional Binding:** An execution grant is cryptographically bound to:
  1. Organization ID (`organization_id`)
  2. Agent ID (`agent_id`)
  3. Governed Action ID (`governed_action_id`)
  4. Governance Decision ID (`governance_decision_id`)
  5. Tool Name (`tool_name`)
  6. Operation Name (`operation_name`)
  7. Canonical Payload Hash (`payload_hash` via RFC 8785)
  Any variation between the grant claims and the execution envelope is rejected with HTTP 403 Forbidden.
* **Short-Lived Ephemeral Authority:** Grants use a short TTL (~30 seconds) to minimize the window of opportunity for stolen credentials.
* **Asymmetric Key Boundary:** The control plane holds private keys; the gateway verifies signatures using public keys fetched from JWKS. Compromising the gateway cannot leak signing capability.
* **Durable First-Writer-Wins Replay Protection:** Replay protection is enforced via PostgreSQL unique primary key constraints on `grant_id`. Retrying a used grant returns HTTP 409 Conflict.
* **Trusted Static Outbound Routing:** The gateway routes tool calls solely through operator-defined mappings in `PROOFMESH_TOOL_TARGETS_JSON`. Agents cannot specify destination URLs, mitigating SSRF risks.
* **No Credential Forwarding:** The gateway strips incoming execution grants and bearer tokens before dispatching payloads to downstream tools.
* **Deterministic Executor Posture:** The protected HTTP executor enforces strict timeouts, never follows redirects, and never performs automatic retries.

---

## Implemented Features vs. Roadmap

### Implemented (Control Plane)
* [x] Governed action ingestion, validation, and idempotency tracking
* [x] Canonical JSON payload hashing (RFC 8785 / JCS)
* [x] Policy modeling, versioning, and agent policy binding
* [x] Risk assessment and automated risk signal evaluation
* [x] Immutable governance decisions (`ALLOW`, `DENY`, `REQUIRE_APPROVAL`)
* [x] Exact-bound approval request lifecycle (`PENDING -> APPROVED | REJECTED | EXPIRED`)
* [x] Time-bounded approval authorization checks (`isApprovedAndValidAt`)
* [x] Execution grant claims domain model (`ExecutionGrantClaims`)
* [x] Keycloak / OIDC resource server integration
* [x] 12 Flyway schema migrations (`V1` through `V12`)

### Implemented (Gateway)
* [x] RFC 8785 / JCS canonical payload hashing (`deszhou/jcs`)
* [x] Offline ES256 / P-256 JWS execution-grant verification (`lestrrat-go/jwx/v3`)
* [x] Thread-safe in-memory JWKS resolver with automatic refresh and caching
* [x] Exact 7-dimensional execution-attempt binding verification
* [x] Durable PostgreSQL execution authority with atomic single-use grant claims (`pgx/v5`)
* [x] Protected HTTP `ToolExecutor` with strict timeouts, no redirects, and no retries
* [x] Policy Enforcement Point (`pep.Enforcer`) orchestrating verification, binding, replay admission, and tool execution
* [x] Production runtime dependency composition (`runtime.New`)
* [x] Typed environment configuration loader (`appconfig.Load`) with validation
* [x] Strict execution ingress HTTP handler (`POST /v1/executions`) with bounded request bodies (1 MiB), strict JSON envelope validation, single Bearer token extraction, and sanitized HTTP error responses (`Cache-Control: no-store`)

### Roadmap / In Progress
* [x] Gateway standalone server bootstrap (`main.go` and network listener lifecycle)
* [x] Graceful shutdown and OS signal handling in gateway runtime
* [ ] Automated database migration runner for gateway deployment
* [ ] Structured observability (OpenTelemetry distributed tracing and Prometheus metrics)
* [ ] Execution audit log archival and automated retention policies
* [ ] Dynamic service discovery for downstream tool endpoints

---

## Tech Stack

| Component | Technology | Version | Purpose |
| :--- | :--- | :--- | :--- |
| **Control Plane Language** | Java | 25 | Authoritative PDP platform |
| **Control Plane Framework** | Spring Boot / Spring Modulith | 4.1.1 / 2.1.1 | Modular governance backend |
| **Gateway Language** | Go | 1.26.0 | High-performance PEP runtime |
| **Primary Database** | PostgreSQL | 18.6 (Alpine) | Authoritative lifecycle & replay store |
| **Database Migrations** | Flyway | Spring Boot starter | Control plane forward schema migrations |
| **Identity & Access** | Keycloak / OIDC | 26.7.2 | Operator authentication & token issuance |
| **Cryptography & Tokens** | ES256 (P-256) / JWKS | `lestrrat-go/jwx/v3` (v3.2.0) | Asymmetric execution grant signing/verification |
| **Canonicalization** | RFC 8785 (JCS) | `deszhou/jcs` / `java-json-canonicalization` | Deterministic payload hashing |
| **Database Driver (Go)** | `pgx/v5` | 5.10.0 | High-performance pooled PostgreSQL driver |
| **Testing & Isolation** | Testcontainers | 0.44.0 (Go) / Spring Boot Testcontainers | Ephemeral PostgreSQL test containers |

---

## Repository Structure

```text
proofmesh/
├── apps/
│   ├── control-plane/             # Spring Boot PDP & governance authority
│   │   ├── src/main/java/com/proofmesh/controlplane/
│   │   │   ├── agent/             # Agent registration & identity
│   │   │   ├── approval/          # Approval lifecycle & validity verification
│   │   │   ├── decision/          # Governance decisions (ALLOW / DENY / REQUIRE_APPROVAL)
│   │   │   ├── executiongrant/    # Execution grant claims & issuance
│   │   │   ├── governedaction/    # Governed actions & payload hashing
│   │   │   ├── identity/          # OIDC / Keycloak operator identity
│   │   │   ├── policy/            # Policy engine & bindings
│   │   │   ├── risk/              # Risk assessment & signal extraction
│   │   │   └── runtimegovernance/ # Runtime governance orchestrator
│   │   └── src/main/resources/db/migration/ # Flyway migrations (V1–V12)
│   ├── gateway/                   # Go PEP & tool execution gateway
│   │   ├── internal/
│   │   │   ├── appconfig/         # Typed environment variable configuration loader
│   │   │   ├── canonicalize/      # RFC 8785 JCS payload canonicalization
│   │   │   ├── executionattempt/  # Execution attempt binding model
│   │   │   ├── executionauthority/# Durable PostgreSQL replay claim
│   │   │   ├── executiongrant/    # Offline ES256 verification & JWKS cache
│   │   │   ├── ingress/http/      # Strict HTTP handler (POST /v1/executions)
│   │   │   ├── pep/               # Enforcer orchestration
│   │   │   ├── runtime/           # Gateway dependency composition
│   │   │   └── toolexecutor/http/ # Protected HTTP tool executor
│   │   └── migrations/            # Gateway schema migrations
│   └── web/                       # Next.js administrative console
├── compose.yaml                   # Local development services (PostgreSQL, Keycloak)
├── Makefile                       # Development & test orchestration targets
└── .github/workflows/ci.yml       # GitHub Actions CI pipeline
```

---

## Gateway Ingress Contract

The gateway exposes a single protected ingress endpoint:

```http
POST /v1/executions HTTP/1.1
Host: gateway.proofmesh.local
Authorization: Bearer <compact_es256_execution_grant>
Content-Type: application/json

{
  "organization_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "agent_id": "123e4567-e89b-12d3-a456-426614174000",
  "governed_action_id": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
  "governance_decision_id": "b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a22",
  "tool_name": "payment_gateway",
  "operation_name": "transfer",
  "payload": {
    "recipient": "acc_98765",
    "amount": 2500,
    "currency": "USD"
  }
}
```

### Ingress Behavior & Response Codes

* **200 OK:** Successful execution. Returns downstream tool payload as `application/octet-stream` with `Cache-Control: no-store`.
* **400 Bad Request:** Request body exceeds 1 MiB, JSON is malformed, required fields are missing, or unknown/duplicate keys are present (`{"error":"invalid_request"}`).
* **401 Unauthorized:** Missing, malformed, or multiple `Authorization` headers, non-Bearer scheme, or invalid/expired execution grant (`{"error":"invalid_token"}`). Includes `WWW-Authenticate: Bearer`.
* **403 Forbidden:** Grant claims do not match the execution envelope's parameters or canonical payload hash (`{"error":"forbidden"}`).
* **409 Conflict:** Grant ID (`grant_id`) has already been claimed in PostgreSQL; replay detected (`{"error":"conflict"}`).
* **502 Bad Gateway:** Downstream tool execution failed or returned a non-2xx status (`{"error":"bad_gateway"}`).
* **503 Service Unavailable:** Execution authority database connection unavailable (`{"error":"service_unavailable"}`).

---

## Configuration

The gateway's typed configuration loader (`appconfig.Load`) reads and validates five required environment variables:

| Environment Variable | Required | Description | Example (Development Only) |
| :--- | :--- | :--- | :--- |
| `PROOFMESH_DATABASE_URL` | Yes | PostgreSQL connection string for execution authority and replay tracking. | `postgres://proofmesh:proofmesh_dev_only@localhost:5432/proofmesh?sslmode=disable` |
| `PROOFMESH_EXECUTION_GRANT_ISSUER` | Yes | Expected issuer claim (`iss`) in signed execution grants. | `https://controlplane.proofmesh.local` |
| `PROOFMESH_EXECUTION_GRANT_AUDIENCE` | Yes | Expected audience claim (`aud`) in signed execution grants. | `proofmesh-gateway` |
| `PROOFMESH_JWKS_URL` | Yes | HTTP endpoint serving public keys for offline ES256 verification. | `http://localhost:8080/.well-known/jwks.json` |
| `PROOFMESH_TOOL_TARGETS_JSON` | Yes | JSON array mapping `tool_name` + `operation_name` pairs to trusted backend URLs. | See example below |

### Example `PROOFMESH_TOOL_TARGETS_JSON`

```json
[
  {
    "tool_name": "payment_gateway",
    "operation_name": "transfer",
    "url": "http://localhost:9001/api/v1/transfer"
  },
  {
    "tool_name": "database_admin",
    "operation_name": "run_migration",
    "url": "http://localhost:9002/api/v1/migrate"
  }
]
```

---

## Running Locally

### Prerequisites
* Docker and Docker Compose
* Java 25 (e.g., Eclipse Temurin) and Maven Wrapper (included)
* Go 1.26.0+

### 1. Start Infrastructure Services

Start the local PostgreSQL container using the provided Makefile targets:

```bash
# Start PostgreSQL container
make db-up

# Verify PostgreSQL container health
make db-status

# (Optional) Start Keycloak container for operator authentication
make auth-up
```

### 2. Run the Control Plane

The control plane can be launched with the local Spring profile:

```bash
make control-run
# or directly via Maven wrapper:
cd apps/control-plane && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### 3. Gateway Runtime

The Go gateway runtime composition, offline verifier, durable PostgreSQL replay authority, protected HTTP tool executor, and HTTP ingress handler are fully implemented and verified via unit and integration tests. Standalone process bootstrapping (`main.go`) with `http.Server` lifecycle orchestration and graceful OS signal shutdown is implemented; the project remains under active development for operational and deployment hardening.

---

## Testing

ProofMesh uses comprehensive unit and integration testing across both components, utilizing Testcontainers for ephemeral PostgreSQL instances and the Go race detector.

### Run Gateway Tests

Execute the complete gateway test suite with data race detection enabled:

```bash
cd apps/gateway
go test -race ./...
```

The gateway test suite verifies:
* RFC 8785 canonical JSON payload hashing and edge-case handling
* Offline ES256 grant verification, invalid signature rejection, and expired token handling
* JWKS cache resolution, key rotation, and network failure tolerance
* Exact 7-dimensional execution-attempt binding and mismatch rejection
* Durable PostgreSQL replay protection and concurrent first-writer-wins arbitration
* Protected HTTP tool execution, timeout enforcement, redirect rejection, and credential stripping
* Production runtime composition and typed environment variable validation
* Strict HTTP ingress handling, boundary enforcement, and error mapping

### Run Control Plane Tests

Execute the complete Spring Boot control plane test suite:

```bash
make control-test
# or directly via Maven wrapper:
cd apps/control-plane && ./mvnw clean verify
```

The control plane test suite verifies:
* Spring Modulith architectural boundaries and module isolation
* Policy models, risk assessment rules, and governance decision outcomes
* Approval request lifecycle and time-bounded validity logic
* Flyway forward migration application on PostgreSQL Testcontainers

---

## Project Status

ProofMesh is under active, test-driven development. The platform is built incrementally around non-negotiable security boundaries: strict separation between decision and enforcement, fail-closed defaults, exact cryptographic binding, and durable replay protection.
