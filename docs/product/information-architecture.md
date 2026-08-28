# ProofMesh Information Architecture and Screen Inventory

Status: Day 3 product-design baseline
Date: 2026-08-28

> This document describes the target ProofMesh MVP operator-console
> information architecture.
>
> It defines navigation, page responsibilities, role expectations,
> screen inventory and the relationship between frontend screens and
> backend domain capabilities.
>
> It does not imply that every screen or API is already implemented.

---

# 1. Purpose

ProofMesh is an operational governance system for autonomous AI agents.

The operator console must help users understand:

WHO performed or requested an action?

WHAT did the agent attempt?

WHY did ProofMesh make its decision?

WHICH policy version was involved?

DID human approval occur?

WAS execution confirmed?

WHAT evidence proves the history?

The information architecture should therefore optimize for:

UNDERSTANDING ACTIONS

rather than:

NAVIGATING CRUD TABLES

The core product object is the Governed Action.

Most major screens should eventually connect back to one or more
Governed Actions.

---

# 2. Core UX hierarchy

The product can be understood in four layers.

```text
LAYER 1 — OVERVIEW

Dashboard


LAYER 2 — OPERATIONS

Governed Actions
Approvals
Incidents


LAYER 3 — GOVERNANCE CONFIGURATION

Agents
Tools
Policies


LAYER 4 — ASSURANCE / ADMINISTRATION

Evidence
Organization / Administration