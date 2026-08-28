# ProofMesh Frontend Architecture

Status: Day 3 frontend architecture baseline
Date: 2026-08-28

---

# 1. Purpose

This document defines the target frontend architecture for the
ProofMesh operator console.

The frontend is responsible for presenting the human control and
investigation surface for ProofMesh.

It is not itself the authoritative authorization boundary.

The Spring Boot Control Plane remains authoritative for:

- operator authorization;
- organization isolation;
- approval state transitions;
- policy lifecycle operations;
- incident mutations;
- replay authorization;
- evidence access.

The Next.js application provides:

- authentication orchestration;
- secure browser session handling;
- Backend-for-Frontend behaviour;
- operator workflows;
- server-side data access;
- role-aware presentation;
- forms and validation;
- loading and error states;
- accessibility;
- responsive layouts.

---

# 2. High-Level Architecture

```mermaid
flowchart LR

    Browser[Browser]

    Next[Next.js Operator Console]

    Session[Secure Application Session]

    BFF[Next.js BFF Layer]

    Keycloak[Keycloak]

    Spring[Spring Boot Control Plane]

    Postgres[(PostgreSQL)]

    Browser --> Next

    Next --> Session

    Next -->|OIDC Authorization Code + PKCE| Keycloak

    Keycloak --> Next

    Next --> BFF

    BFF -->|Bearer access token| Spring

    Spring --> Postgres