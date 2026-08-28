# ProofMesh Operator Personas

Status: Day 3 product-design baseline
Date: 2026-08-28

## 1. Platform Administrator

### Primary responsibility

Configures and governs the ProofMesh platform.

### Main goals

- Register AI agents.
- Register protected tools and operations.
- Configure authorization policies.
- Publish policy versions.
- Manage platform configuration.
- Understand whether the runtime governance system is healthy.

### Typical questions

- Which agents are registered?
- Which tools may this agent access?
- Which policy version is currently active?
- Why was a particular action denied?
- Are there unsafe or incorrectly configured integrations?

### Important operations

- Create / update agent registrations.
- Create / update tool registrations.
- Create policies.
- Publish policy versions.
- Disable compromised agents.
- Inspect organization configuration.

### Security expectations

A Platform Administrator has powerful configuration authority,
but administrative authority must not bypass runtime evidence,
audit, or policy history.

---

## 2. Approver

### Primary responsibility

Makes human authorization decisions for sensitive AI-agent actions.

### Main goals

- Quickly understand what an AI agent wants to do.
- Understand why ProofMesh considers the action sensitive.
- Inspect relevant risk and policy context.
- Approve or reject the exact requested action.
- Record a meaningful rationale.

### Typical questions

- What is the agent trying to do?
- Which system will be affected?
- What data or parameters are involved?
- Why does this require approval?
- What would happen if I approve it?
- Has the request changed since it was evaluated?
- Has another approver already acted?

### Important operations

- View pending approval requests.
- Inspect action details.
- Inspect policy/risk explanation.
- Approve an eligible request.
- Reject an eligible request.
- Provide approval/rejection rationale.
- View previous decisions.

### Security expectations

Approval must authorize only the exact canonical request that was
reviewed.

Changing the tool, operation, organization, agent, or payload must
invalidate the previous approval.

---

## 3. Security Operator

### Primary responsibility

Investigates suspicious or important AI-agent activity.

### Main goals

- Understand what happened.
- Investigate denied, high-risk, or uncertain actions.
- Follow the evidence trail.
- Reconstruct runtime decisions.
- Replay historical policy evaluation safely.
- Identify incidents.

### Typical questions

- Why was this action allowed?
- Which policy version made this decision?
- Who approved the action?
- Was the external execution successful?
- Was execution outcome uncertain?
- Has any evidence been modified?
- Would today's policy make the same decision?

### Important operations

- Search governed actions.
- Inspect evidence.
- Inspect decision reasoning.
- Inspect execution state.
- Investigate EXECUTION_UNKNOWN.
- Perform side-effect-free replay.
- Investigate incidents.

### Security expectations

Replay must never invoke the real protected tool.

Evidence should make unauthorized modification detectable.

---

## 4. Viewer

### Primary responsibility

Observes ProofMesh activity without changing system state.

### Main goals

- Understand platform activity.
- View dashboards.
- Inspect agents, actions and evidence.
- Learn why governance decisions occurred.

### Important operations

- View dashboard.
- View actions.
- View agents.
- View tools.
- View policies.
- View evidence.
- View incidents.

### Security expectations

A Viewer must not:

- approve or reject requests;
- publish policies;
- modify agents;
- modify tools;
- mutate incidents;
- perform privileged administration.

---

# Jobs-to-be-Done

## Platform Administrator

When my organization introduces autonomous AI agents,
I want to register their identities, tools and governance policies,
so that the agents can operate without receiving unrestricted access
to sensitive systems.

## Approver

When an AI agent attempts a sensitive operation,
I want to understand the exact operation, associated risk and policy
reasoning,
so that I can safely decide whether that particular action should be
authorized.

## Security Operator

When an important or suspicious AI action occurs,
I want to reconstruct the complete decision and execution history,
so that I can determine what happened and whether the governance
controls behaved correctly.

## Viewer

When I need operational visibility,
I want to inspect ProofMesh activity without having permission to alter
the system,
so that I can understand system behaviour safely.