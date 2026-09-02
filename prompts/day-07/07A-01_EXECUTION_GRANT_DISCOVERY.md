# Day 7 — 07A.1 Execution Grant Foundation Discovery

## Mode

**INSPECTION ONLY. DO NOT MODIFY ANY FILE.**

Do not generate patches.
Do not create files.
Do not run formatting.
Do not add dependencies.
Do not change migrations.
Do not commit.

## Goal

Prepare the smallest safe first implementation slice for ProofMesh execution grants after completion of the Day 6 approval lifecycle and runtime retry proof.

The immediate purpose is **not** to implement the whole execution system. It is to inspect the current repository and determine the correct first execution-grant boundary.

## Current confirmed state

Treat these as established behavior:

- Runtime governance resolves authoritative organization/agent/policy context.
- Authoritative risk assessments are persisted and converge on retry.
- Governance decisions are persisted and converge on retry.
- Runtime outcomes include DENY and REQUIRE_APPROVAL.
- REQUIRE_APPROVAL materializes exactly one authoritative approval request.
- Approval lifecycle is PENDING -> APPROVED / REJECTED / EXPIRED.
- There is intentionally no approval CONSUMED state in the current implementation.
- Human approval does not execute the protected action.
- Human approval does not rewrite the immutable REQUIRE_APPROVAL governance decision.
- Exact retry after approval returns the same authoritative APPROVED approval.
- Rejected and expired exact retries remain non-authorizing.
- APPROVED may remain historical state after TTL; current authorization validity is checked separately.
- Day 6 tests are green.

## Architecture invariants

The design must preserve:

1. Go gateway is the sole PEP that may invoke protected tools.
2. Spring control plane is the PDP and may issue execution authority, but must never call protected tools.
3. No valid signed execution grant = no live protected-tool call.
4. DENY must never produce a grant.
5. Pending, rejected, or expired approval must never produce a grant.
6. REQUIRE_APPROVAL alone must never produce a grant.
7. An exact retry with a currently valid APPROVED approval may become eligible for a grant.
8. Replay must never mint a live execution grant.
9. Execution grant must be bound to the exact action/payload identity.
10. A changed action, agent, organization, tool, operation, or canonical payload hash must not reuse prior authority.
11. Control-plane private signing material must never be exposed to the gateway or logs.
12. PostgreSQL remains authoritative for lifecycle state.
13. Do not invent custom cryptography.
14. Do not introduce unnecessary microservices, queues, caches, or external signing services for the MVP.

## Architecture baseline to validate against

The target MVP describes a short-lived asymmetric ES256-signed execution grant.

Conceptual claims include:

- issuer;
- audience;
- unique grant identifier / jti;
- governed action ID;
- organization ID;
- agent ID;
- tool identity;
- operation;
- canonical payload hash;
- governance decision ID;
- issued/expiry timing.

The architecture baseline uses approximately a 30-second grant TTL. Treat this as a policy value that should have one owner, not a magic literal scattered through code.

Do not assume the existing code uses JWT/JWS or any specific Java JOSE library. Inspect first.

## Inspection tasks

### A. Repository/package discovery

Inspect:

- `apps/control-plane/pom.xml`;
- runtime-governance packages;
- governed-action domain;
- governance-decision domain;
- approval domain;
- security configuration;
- any existing `execution`, `grant`, `jwt`, `jose`, `sign`, `key`, `JWK`, or crypto classes;
- Spring Modulith module descriptors/tests if present;
- Flyway migrations;
- configuration properties conventions;
- test fixture conventions.

Run searches similar to:

```bash
find src/main/java -type f | sort | grep -Ei 'execution|grant|jwt|jose|jwk|sign|key|crypto'

grep -RniE \
  'ExecutionGrant|execution grant|ES256|Nimbus|JOSE|JWK|JWT|JwtEncoder|JwtDecoder|ECPrivateKey|ECPublicKey' \
  src/main/java src/test/java pom.xml
```

Use repository-appropriate alternatives if paths differ.

### B. Dependency inspection

Determine whether the current dependency graph already provides an appropriate standards-based JOSE/JWT signing API.

Inspect `pom.xml` and, if useful:

```bash
./mvnw dependency:tree
```

Do not add a dependency.

Report:

- existing relevant Spring Security/JWT/JOSE libraries;
- whether signing support is already transitively available;
- whether an explicit dependency would eventually be preferable for build clarity.

### C. Domain identity mapping

Identify the exact current Java types representing:

- organization ID;
- agent ID;
- governed action ID;
- governance decision ID;
- tool identity/name;
- operation name;
- canonical request payload hash;
- timestamps.

Determine whether execution-grant claims should reuse these types internally rather than introduce duplicate string representations.

### D. Eligibility boundary

Inspect the current runtime-governance orchestration and determine the cleanest domain/application boundary for answering:

> “May this authoritative governed result mint execution authority at this instant?”

Consider both:

- immediate ALLOW path;
- REQUIRE_APPROVAL path with a currently valid authoritative APPROVED approval.

Do not implement it.

Report whether this should be a dedicated eligibility/authorization service rather than embedding signing logic directly into the orchestrator.

### E. Persistence decision

Determine whether the **first execution-grant slice** requires database persistence.

Do not assume a grant table is necessary.

Distinguish:

- signed authorization artifact;
- later execution attempt/idempotency/consumption state.

Explain which one needs persistence now, if either, and why.

### F. Signing-key boundary

Inspect existing configuration conventions and propose the safest MVP boundary for:

- private EC signing key ownership in Spring;
- key identifier (`kid`);
- public verification/JWK exposure to the gateway later;
- test key strategy;
- no secret material committed to Git.

Do not create keys or configuration yet.

### G. Tests

Identify the smallest tests for the first slice.

At minimum consider:

- valid internal execution-grant representation;
- immutable exact-action binding;
- invalid temporal interval;
- grant ineligibility for DENY;
- grant ineligibility for pending/rejected/expired approval;
- eligibility for immediate ALLOW;
- eligibility for exact REQUIRE_APPROVAL retry with currently valid APPROVED approval;
- no signing in replay.

Do not write them.

## Required output

Return a report with exactly these headings:

### 1. Current repository findings

Exact relevant files/classes and what each currently owns.

### 2. Existing dependency findings

Relevant JWT/JOSE/crypto dependencies already available.

### 3. Domain types to reuse

A table of execution-grant claim -> current ProofMesh type/source.

### 4. Recommended execution-grant architecture

Show the smallest proposed components and their responsibilities.

Clearly separate:

- eligibility;
- grant claims/domain representation;
- signing;
- public verification material;
- later execution-attempt persistence.

### 5. Proposed 07A.1 implementation slice

Choose **one small first slice only**.

Prefer a foundation that can be fully unit-tested and committed independently.

List exact files expected to be created/modified.

### 6. Explicitly deferred work

List everything that must NOT be included in 07A.1.

### 7. Test plan

Focused tests for only 07A.1.

### 8. Risks / architecture questions

Anything requiring a decision before coding.

### 9. Suggested verification commands

Focused test command(s), full suite, randomized suite if relevant, and Git diff checks.

## Quality bar

The report must be based on the repository as it exists now, not on invented class names.

When current code differs from the architecture document, call out the difference instead of silently replacing implemented semantics.

Do not propose an approval CONSUMED state merely because an older architecture baseline mentions one; current implemented approval semantics intentionally omit it.

Stop after the report. Do not modify the repository.
