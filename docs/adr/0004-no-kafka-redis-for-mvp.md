# ADR-0004: No Kafka or Redis for the MVP

- Status: Accepted
- Date: 2026-08-27

## Context

Kafka and Redis are common infrastructure choices, but introducing them
without concrete requirements increases deployment, debugging, security,
testing, and operational complexity.

The current MVP can satisfy its core consistency and workflow requirements
using PostgreSQL and synchronous application boundaries.

## Decision

Do not introduce Kafka or Redis during the MVP unless a measured requirement
demonstrates that PostgreSQL and the current architecture cannot satisfy the
needed behavior.

## Consequences

### Positive

- Smaller operational surface.
- Faster development.
- Easier local environments and CI.
- Fewer distributed failure modes.

### Negative

- Some asynchronous or high-throughput features may need architectural
  changes later.
- Redis-backed caching or Kafka-backed event streaming are not immediately
  available.

## Alternatives Considered

### Kafka from day one

Rejected because there is currently no throughput, durability, or event
distribution requirement that requires it.

### Redis from day one

Rejected because there is currently no demonstrated caching, ephemeral state,
or coordination requirement that requires it.