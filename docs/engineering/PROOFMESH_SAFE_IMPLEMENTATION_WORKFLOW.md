# ProofMesh Safe Prompt-Driven Implementation Workflow

This workflow is for building ProofMesh with a coding agent while protecting correctness, architecture, and code quality.

## Why this workflow exists

AI coding tools are productive when the task boundary is explicit. They become risky when asked to “build the next feature” without constraints because they may:

- refactor unrelated code;
- invent APIs that conflict with existing code;
- weaken tests;
- introduce unnecessary dependencies;
- duplicate domain concepts;
- ignore database concurrency behavior;
- silently cross security boundaries.

ProofMesh is a security-sensitive project, so the workflow deliberately separates **inspection**, **implementation**, and **verification**.

## The golden rule

One bounded feature slice -> green tests -> reviewed diff -> commit -> push -> next slice.

Never stack several uncommitted architectural changes.

## Prompt pattern

Every implementation prompt should contain these sections:

### 1. Goal

Describe one observable capability only.

Example:

> Establish the execution-grant domain representation and validation rules. Do not implement signing, persistence, HTTP endpoints, or gateway verification in this slice.

### 2. Current context

Mention only facts the agent needs.

Example:

- Runtime governance already persists authoritative risk and decision state.
- REQUIRE_APPROVAL materializes one authoritative approval.
- Approved retries expose the same APPROVED approval.
- The governance decision remains REQUIRE_APPROVAL.

### 3. Hard architecture constraints

State what must not change.

Example:

- Spring must not invoke protected tools.
- Approval must not execute the action.
- No grant for pending/rejected/expired approval.
- Replay cannot mint a grant.
- Exact payload hash binding is mandatory.

### 4. Scope

List expected areas/files and explicitly exclude adjacent work.

Good scope:

- Add execution-grant domain value objects.
- Add unit tests.
- No Flyway migration.
- No signer.
- No controller.
- No Go code.

### 5. Quality rules

Examples:

- Reuse current value-object conventions.
- Avoid nullable security claims.
- Defensively validate TTL/timestamps.
- Avoid stringly typed state where a value object already exists.
- No unrelated formatting/refactoring.

### 6. Acceptance criteria

Make the result testable.

Examples:

- A valid grant representation can be created for one exact action.
- Mismatched organization/agent/action/payload identity is rejected.
- `expiresAt` must be after `issuedAt`.
- Existing full suite remains green.

### 7. Verification commands

Give exact commands.

### 8. Diff contract

Tell the agent what must be reported:

- changed files;
- why each changed;
- test outputs;
- any deviation from planned files;
- `git diff --check` result.

## Two-prompt safety pattern

For security-sensitive or architecture-sensitive slices, use two separate prompts.

### Prompt A — discovery

The agent may only inspect.

Ask it to return:

- current types and signatures;
- dependencies already present;
- DB/schema constraints;
- tests that encode current behavior;
- smallest design;
- expected diff.

Review the report before allowing edits.

### Prompt B — implementation

Give the approved design back to the agent and explicitly authorize only that slice.

This greatly reduces accidental redesign.

## Review checklist after an AI change

Ask these questions before committing:

- Did the diff touch only expected files?
- Did any existing validation disappear?
- Were tests weakened or deleted?
- Did the agent add a dependency?
- Did it introduce a second representation of an existing domain concept?
- Did it invent a new source of truth instead of using PostgreSQL authoritative state?
- Did it extend TTLs on retry?
- Did it use caller-supplied identity where server-resolved identity is required?
- Did it introduce live execution into Spring?
- Could DENY, PENDING, REJECTED, EXPIRED, REPLAY, or failure now mint execution authority?
- Is payload hash binding preserved?
- Are organization/tenant predicates present?
- Are secrets or tokens logged?
- Are timestamps tested at boundary conditions?

## Debugging rule

When a test fails, do not ask the agent to “fix all tests.”

Use:

> Diagnose this failure without changing production code. Identify the exact invariant being violated, whether the failure is test setup, stale persistence context, database concurrency, or production logic, and propose the smallest fix. Do not edit anything yet.

Then approve a fix separately.

## Refactoring rule

Do not combine refactors with security behavior changes.

If a refactor is genuinely required:

1. perform behavior-preserving refactor;
2. run full tests;
3. commit;
4. then implement the new security behavior.

## Migration rule

For Flyway changes:

- discovery first;
- inspect all prior constraints/triggers/indexes touching the lifecycle;
- add a new migration;
- include database-level integration tests;
- verify rollback is via code/git/database reset in development, not by editing migration history.

## Suggested repository structure

```text
proofmesh/
├── AGENTS.md
├── docs/
│   └── engineering/
│       └── PROOFMESH_SAFE_IMPLEMENTATION_WORKFLOW.md
└── prompts/
    └── day-07/
        └── 07A-01_EXECUTION_GRANT_DISCOVERY.md
```

Keep completed task prompts if they are useful as engineering history, or archive them under `prompts/archive/`.

## How to work with ChatGPT

Send ChatGPT:

- the coding agent's discovery report;
- failed test output;
- `git status --short`;
- `git diff --check`;
- relevant diff;
- or a file the agent changed.

ChatGPT should then produce the next bounded prompt rather than dumping a broad replacement implementation.
