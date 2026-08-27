# ADR-0003: PostgreSQL as the Authoritative System of Record

- Status: Accepted
- Date: 2026-08-27

## Context

ProofMesh stores security-sensitive governance state including agents,
policies, approvals, decisions, executions, evidence, and incidents.

Correctness, transactional integrity, historical traceability, and durable
relationships are more important than introducing multiple specialized data
stores during the MVP.

## Decision

Use PostgreSQL as the authoritative system of record for the ProofMesh
control plane.

Database schema changes are managed exclusively through Flyway migrations.

Hibernate validates mappings but does not create or mutate the schema.

## Consequences

### Positive

- Strong transactional guarantees.
- Mature relational constraints.
- Good support for JSON where appropriate.
- Simpler backup, recovery, testing, and operations.
- One authoritative consistency boundary.

### Negative

- Some future workloads may require specialized stores.
- High-volume event or analytical workloads may eventually need additional
  infrastructure.

## Alternatives Considered

### Multiple databases from the beginning

Rejected because current requirements do not justify the operational and
consistency complexity.

### Hibernate schema generation

Rejected because production schema evolution must be explicit, reviewable,
versioned, and reproducible.