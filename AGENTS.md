# ProofMesh Coding Agent Instructions

This file is the repository-wide operating contract for AI-assisted implementation.

## Project identity

ProofMesh is a runtime trust, governance, approval, execution-authority, evidence, and replay control plane for autonomous AI agents.

Primary runtime boundaries:

- Go gateway = sole Policy Enforcement Point (PEP) for protected-tool invocation.
- Spring Boot control plane = Policy Decision Point (PDP) and authoritative governance service.
- PostgreSQL = authoritative lifecycle state.
- Human operators authenticate through Keycloak/OIDC.
- The control plane must never invoke protected tools.
- Replay must never execute a protected tool or mint live execution authority.

## Non-negotiable security invariants

Do not weaken these invariants to simplify implementation:

1. DENY never invokes a protected tool.
2. REQUIRE_APPROVAL is not ALLOW.
3. Approval authorizes but does not execute.
4. The agent/client must retry the exact approved action.
5. Approval is bound to the exact organization, agent, governed action, governance decision, tool, operation, and canonical payload hash.
6. A changed payload requires a new governance path.
7. A live protected-tool call requires a valid short-lived signed execution grant.
8. No live execution grant may be minted for DENY, pending approval, rejected approval, expired approval, replay, or indeterminate/fail-closed outcomes.
9. Spring owns the private signing capability; the gateway must only need public verification material.
10. PostgreSQL authoritative state wins over caller-proposed IDs/timestamps/state on equivalent retries.
11. Published policy versions and persisted governance decisions are historical provenance and must not be mutated.
12. High-risk uncertainty fails closed.

## Current approval semantics

The implemented approval lifecycle is:

PENDING -> APPROVED
PENDING -> REJECTED
PENDING -> EXPIRED

There is intentionally no CONSUMED approval state in the current implementation.

APPROVED is historical state. Time-valid authorization must be checked separately using the approval validity rule. Do not rewrite an APPROVED row to EXPIRED merely because wall-clock time later passes its TTL.

The original governance decision remains REQUIRE_APPROVAL after human approval. Do not mutate it to ALLOW.

## Implementation style

Prefer small domain-oriented slices.

Before modifying code:

1. Inspect the exact existing classes, tests, migrations, package boundaries, and dependency versions.
2. Reuse established project patterns where they are still correct.
3. Identify the smallest change set that satisfies the task.
4. State the files expected to change before editing.

While modifying code:

- Do not perform unrelated refactors.
- Do not rename public/domain types unless explicitly required.
- Do not mass-format untouched files.
- Do not silently alter architectural boundaries.
- Do not add a framework/library when the JDK or an existing dependency is adequate.
- Do not downgrade existing security checks.
- Do not weaken assertions just to make tests pass.
- Do not catch broad exceptions to hide failures.
- Do not introduce fallback-to-allow behavior.
- Do not log secrets, bearer tokens, signing private keys, raw agent credentials, or sensitive payloads.
- Prefer immutable domain values and explicit validation.
- Keep time sources/TTLs centralized instead of scattering magic numbers.
- Preserve organization scoping on all authoritative queries and transitions.

## Database rules

Flyway owns schema evolution.

- Never edit an already-applied migration merely to change current behavior.
- Add a new forward migration when schema evolution is required.
- Never add broad destructive DELETE cleanup to integration tests.
- Risk assessments and governance decisions have immutability constraints; respect them.
- Approval requests cannot be deleted and have immutable binding/timing constraints.
- For concurrency behavior, let PostgreSQL be the arbitration point.
- Preserve unique business keys and tenant-scoped constraints.

Test isolation:

- Prefer `@Transactional` rollback where compatible with the test.
- Where executor threads require committed data, use randomized/fresh identifiers and non-destructive fixtures.
- Never make test cleanup depend on deleting immutable lifecycle rows.

## Spring / Java rules

Baseline: Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1.

- Respect existing module/package boundaries.
- Domain logic must not depend directly on HTTP/UI concerns.
- Prefer ports/interfaces at domain boundaries and adapters for persistence/transport/crypto.
- Keep constructors explicit and null-safe.
- Use records/value objects where consistent with the existing codebase.
- Avoid speculative abstractions; introduce one only when the current slice needs it.
- Do not create microservices, Kafka, Redis, WebSockets, or ML components without an explicit requirement.

## Cryptographic / execution-grant rules

Target MVP execution authority:

- asymmetric ES256-signed short-lived execution grant;
- control plane signs;
- gateway verifies with public material;
- intended audience: ProofMesh gateway;
- issuer: ProofMesh control plane;
- grant bound to exact governed action and canonical payload identity;
- expected conceptual claims include unique grant ID, action ID, organization ID, agent ID, tool/operation identity, payload hash, governance decision ID, issued/expiry timing;
- target TTL is short (architecture baseline uses approximately 30 seconds), but do not scatter a literal throughout code;
- never invent custom cryptographic primitives;
- use mature standard/JDK or already-approved project crypto/JWT facilities.

Before implementing signing, inspect the current dependency tree and security configuration. Do not add a JWT/JOSE dependency until the existing stack has been checked.

## AI-agent work protocol

For every requested feature, use this sequence.

### Phase 1 — Inspect only

Do not edit files.

Return:

- current relevant architecture;
- exact files/classes/migrations involved;
- existing patterns to reuse;
- conflicts or uncertainties;
- proposed smallest implementation slice;
- exact expected changed-file list;
- focused test plan.

### Phase 2 — Implement one slice

Only after the plan is accepted.

- Modify only the approved files.
- Add/update focused tests with the production change.
- Do not start the next slice.
- Stop on the first meaningful failing test and diagnose it.

### Phase 3 — Verification

Run, as applicable:

1. focused unit tests;
2. focused integration tests;
3. full test suite;
4. randomized test order when relevant;
5. `git diff --check`;
6. `git status --short`;
7. `git diff --stat`;
8. inspect the actual diff for only intended changes.

Never claim success without command output proving success.

### Phase 4 — Commit checkpoint

Every small feature must reach green and be committed before beginning the next one.

Stage exact files only.

Before commit:

- inspect `git diff --cached --check`;
- inspect `git diff --cached --stat`;
- inspect staged diff for security-sensitive changes.

After commit:

- verify `git status --short`;
- push;
- only then continue.

## Response format for implementation tasks

When asked to implement a feature, respond in this order:

1. `Current state`
2. `Proposed slice`
3. `Files to change`
4. `Implementation`
5. `Tests`
6. `Risks / invariants checked`
7. `Commands to run`
8. `Stop condition`

If the request is an inspection/discovery prompt, do not include code changes.

## Stop conditions

Stop and report instead of improvising if:

- required current code cannot be found;
- a migration or DB constraint contradicts the proposed design;
- a failing test suggests a stale persistence-context/concurrency issue;
- implementing the task would cross the Go PEP / Spring PDP boundary;
- an architecture invariant would need to change;
- a new external dependency seems necessary but has not been justified;
- the diff unexpectedly touches unrelated files;
- tests fail for reasons not understood.

Do not “fix around” these conditions.
