# ADR-0001: Modular Monolith for the Control Plane

- Status: Accepted
- Date: 2026-08-27

## Context

ProofMesh requires clear domain boundaries across identity, policy, risk,
approval, execution, evidence, incidents, and replay.

The project is being developed as an MVP and does not currently have
requirements that justify the operational complexity of independently
deployed microservices.

## Decision

Implement the Java control plane as a Spring Boot modular monolith using
Spring Modulith to define and verify module boundaries.

Modules may evolve into independently deployed services later only when
runtime, scaling, ownership, or reliability requirements justify that change.

## Consequences

### Positive

- Lower operational complexity.
- Easier local development and testing.
- Transactional consistency is simpler.
- Domain boundaries can still be explicitly enforced.
- Future service extraction remains possible.

### Negative

- Modules share one deployment unit.
- Independent scaling is not available initially.
- Poor module discipline could lead to unwanted coupling.

## Alternatives Considered

### Microservices

Rejected for the MVP because the operational and distributed-systems
complexity is not justified by current requirements.

### Unstructured monolith

Rejected because ProofMesh has distinct domain responsibilities that should
be represented and verified explicitly.