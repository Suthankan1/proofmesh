# ProofMesh Frontend Implementation Plan

Status: Day 3 implementation baseline
Date: 2026-08-28

---

# 1. Purpose

This document converts the ProofMesh product design and frontend
architecture into an executable implementation plan.

It maps:

Figma screens
    ->
Next.js routes
    ->
feature modules
    ->
components
    ->
backend APIs
    ->
implementation order
    ->
tests

This document distinguishes:

DESIGNED TARGET

from:

CURRENTLY IMPLEMENTED

The complete operator console is the target MVP.

As of the Day 3 baseline, the Spring backend already provides the
identity/security foundation, while most runtime governance APIs will
be implemented incrementally.

---

# 2. Current Backend Reality

The frontend must not assume that all target APIs already exist.

Currently implemented and verified:

```text
Keycloak human authentication foundation

Spring OAuth2 Resource Server

JWT validation

ProofMesh roles

server-side organization resolution

GET /api/v1/identity/me

GET /api/v1/identity/context

Flyway identity schema

authorization integration tests