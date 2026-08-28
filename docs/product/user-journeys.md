# ProofMesh User Journeys and User Flows

Status: Day 3 product-design baseline
Date: 2026-08-28

> These flows describe the target ProofMesh MVP experience.
> They are product-design inputs and do not imply that every screen or
> backend capability is already implemented.

---

# 1. Purpose

ProofMesh is an operator-facing governance platform for autonomous AI
agents.

The user experience must make it possible for humans to answer:

- What did an AI agent attempt?
- Why did ProofMesh allow, deny, or pause the action?
- What policy version was responsible?
- What risk signals were considered?
- Did a human approve the action?
- Was the exact approved request executed?
- What happened during execution?
- What evidence proves the history?
- Can the historical decision be reconstructed safely?

The primary UX object is therefore the Governed Action.

A Governed Action represents the lifecycle of one AI-agent request from
initial submission through decision, approval, execution and evidence.

---

# 2. Product-wide operator journey

```mermaid
flowchart LR

    Login[Operator signs in]

    Dashboard[Operational Dashboard]

    Actions[Governed Actions]
    Approvals[Approval Queue]
    Agents[Agent Registry]
    Tools[Tool Registry]
    Policies[Policies]
    Incidents[Incidents]
    Evidence[Evidence]
    Replay[Replay]

    Login --> Dashboard

    Dashboard --> Actions
    Dashboard --> Approvals
    Dashboard --> Agents
    Dashboard --> Tools
    Dashboard --> Policies
    Dashboard --> Incidents

    Actions --> Evidence
    Actions --> Replay
    Actions --> Incidents

    Approvals --> Actions

    Agents --> Actions
    Tools --> Actions
    Policies --> Actions