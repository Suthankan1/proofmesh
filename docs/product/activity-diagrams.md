# ProofMesh Activity Diagrams

Status: Day 3 product-design baseline
Date: 2026-08-28

> These diagrams describe the target ProofMesh MVP behaviour.
> Some supporting foundations are already implemented, while runtime
> governance, approval, execution, evidence and replay capabilities will
> be implemented incrementally.

---

# 1. Purpose

ProofMesh governs AI-agent actions before those actions reach protected
tools, APIs, databases or external systems.

The core workflow is not simply:

AI Agent -> Tool

Instead it becomes:

AI Agent
    ->
ProofMesh Gateway
    ->
Identity validation
    ->
Policy evaluation
    ->
Risk evaluation
    ->
Decision
    ->
Optional human approval
    ->
Authorized execution
    ->
Evidence

The purpose of these activity diagrams is to define the branches and
security boundaries that must exist during this lifecycle.

---

# 2. Complete Governed Action Lifecycle

This is the most important activity diagram in ProofMesh.

```mermaid
flowchart TD

    Start([Agent submits action])

    Gateway[Go Gateway receives request]

    Parse[Parse and validate request envelope]

    ParseValid{Request structurally valid?}

    RejectMalformed[Reject malformed request]

    Authenticate[Authenticate agent]

    AgentKnown{Known and active agent?}

    DenyIdentity[Create DENY decision]

    ResolveOrg[Resolve organization context]

    ResolveTool[Resolve protected tool and operation]

    ToolValid{Tool and operation valid?}

    DenyTool[Create DENY decision]

    Canonicalize[Canonicalize request payload]

    Hash[Calculate canonical payload hash]

    CreateAction[Create governed action]

    LoadPolicy[Load active published policy version]

    EvaluatePolicy[Evaluate deterministic policy rules]

    EvaluateRisk[Evaluate deterministic risk context]

    Decision{Decision?}

    Deny[Record DENY]

    Approval[Create approval request]

    Wait[Wait for human decision]

    ApprovalResult{Approval result?}

    Rejected[Record rejection]

    Approved[Record exact-request approval]

    Authorize[Create execution authorization]

    Grant[Issue short-lived execution grant]

    Execute[Gateway invokes protected tool]

    Result{Execution outcome?}

    Success[Record SUCCEEDED]

    Failure[Record FAILED]

    Unknown[Record EXECUTION_UNKNOWN]

    Evidence[Append evidence]

    End([Lifecycle complete])

    Start --> Gateway
    Gateway --> Parse
    Parse --> ParseValid

    ParseValid -->|No| RejectMalformed
    RejectMalformed --> End

    ParseValid -->|Yes| Authenticate

    Authenticate --> AgentKnown

    AgentKnown -->|No| DenyIdentity
    DenyIdentity --> Evidence

    AgentKnown -->|Yes| ResolveOrg

    ResolveOrg --> ResolveTool
    ResolveTool --> ToolValid

    ToolValid -->|No| DenyTool
    DenyTool --> Evidence

    ToolValid -->|Yes| Canonicalize

    Canonicalize --> Hash
    Hash --> CreateAction

    CreateAction --> LoadPolicy
    LoadPolicy --> EvaluatePolicy
    EvaluatePolicy --> EvaluateRisk

    EvaluateRisk --> Decision

    Decision -->|DENY| Deny
    Deny --> Evidence

    Decision -->|REQUIRE_APPROVAL| Approval
    Approval --> Wait
    Wait --> ApprovalResult

    ApprovalResult -->|Rejected| Rejected
    Rejected --> Evidence

    ApprovalResult -->|Approved| Approved
    Approved --> Authorize

    Decision -->|ALLOW| Authorize

    Authorize --> Grant
    Grant --> Execute

    Execute --> Result

    Result -->|Confirmed success| Success
    Result -->|Confirmed failure| Failure
    Result -->|Outcome uncertain| Unknown

    Success --> Evidence
    Failure --> Evidence
    Unknown --> Evidence

    Evidence --> End