# ADR-0002: Go Gateway as the Policy Enforcement Point

- Status: Accepted
- Date: 2026-08-27

## Context

Protected tool calls must pass through a trusted enforcement boundary before
they can reach downstream systems.

The enforcement path should remain small, stateless, predictable, and
independent from the control plane's broader governance responsibilities.

## Decision

Use a dedicated Go gateway as the Policy Enforcement Point (PEP).

The Spring Boot control plane acts as the Policy Decision Point (PDP).

The gateway is the only ProofMesh component permitted to invoke protected
tools through the governed execution path.

## Consequences

### Positive

- Clear separation between policy decision and enforcement.
- Small and auditable runtime enforcement surface.
- Go is well suited to lightweight concurrent network services.
- Gateway can scale independently later.

### Negative

- Introduces a second implementation language.
- Requires an explicit contract between gateway and control plane.
- Network failures between PEP and PDP must be handled safely.

## Alternatives Considered

### Enforcement inside the Spring control plane

Rejected because it mixes decision-making with runtime enforcement and
weakens the architectural trust boundary.

### Enforcement inside individual agents

Rejected because agents are not trusted to enforce their own authorization
decisions.