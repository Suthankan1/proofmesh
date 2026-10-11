# Reproducible local ProofMesh demonstration

This is a local development demonstration, with no cloud deployment. Docker Compose uses the distinct `proofmesh-local-demo` project, its own retained volumes, and loopback-only published ports. It never edits `.env`, `.env.local`, existing project databases, or prompt files. PostgreSQL and Keycloak are isolated from the original Compose project. The protected mock tool has no published port. The gateway runs its reviewed non-root, read-only image with capabilities dropped.

## Start

Prerequisites: Docker Desktop running (host.docker.internal support), Node **24** on PATH, Java **25** with JAVA_HOME configured, Go **1.26+**, Python 3, OpenSSL, and pnpm **11.21.0**. Install the committed dependency lockfile with `pnpm install --frozen-lockfile` once. The demo builds Java and the current gateway image locally; first run needs access to the official dependency/image registries.

```sh
python3 scripts/dev/local-demo.py up
```

Keep this command running in a terminal. It creates `.local-demo/` with mode 0700, private generated test credentials and P-256 signing keys, and private diagnostic logs. It applies explicit gateway migrations with `search_path=public`, starts Spring, seeds a deterministic approval policy and agent in the new demo tenant, migrates the operator session schema, builds the web application and starts Next.js in production mode. For hot reloading during development, use `up --dev`. Existing occupied demo ports cause a stop before service mutation. Ctrl-C stops the launcher’s Java/web processes and its Compose services. **Volumes, keys, users and evidence are retained**; subsequent runs reuse them. Do not delete or reset authoritative state as a retry mechanism.

| Component | Local address |
|---|---|
| Operator console | http://localhost:13000 |
| Spring API | http://localhost:18080 |
| Keycloak | http://localhost:18081 |
| Gateway | http://localhost:19090 |
| Demo PostgreSQL | 127.0.0.1:55440 |

Display the generated test account credentials in a separate terminal:

```sh
python3 scripts/dev/local-demo.py credentials
```

`operator@proofmesh.local` has PLATFORM_ADMIN, APPROVER and SECURITY_OPERATOR. `viewer@proofmesh.local` has VIEWER only. These are local test accounts, not production identities. Browser sign-in uses the real Keycloak authorization-code/PKCE flow; direct password grants remain disabled. Signing keys never enter the gateway container. Runtime agent credentials are stored only as SHA-256 hashes in PostgreSQL. The local private state contains the generated agent secret for the demo client; no raw bearer grant is saved or printed.

## Demonstrate governed execution

1. Sign in through Keycloak as the demo operator.
2. In another terminal, submit a controlled refund:

   ```sh
   python3 scripts/dev/local-demo.py submit
   ```

3. Open Approvals, review the exact payload, provide a rationale and approve. Approval must not invoke the tool. The original decision remains REQUIRE_APPROVAL.
4. Use the returned governedActionId for the exact retry:

   ```sh
   python3 scripts/dev/local-demo.py execute ACTION_UUID
   ```

   This uses the saved exact request, obtains one short-lived grant, invokes the mock tool exactly once, verifies that replay returns 409 without another invocation, and verifies that reminting returns ALREADY_ISSUED. Lost or expired authority cannot be reminted; do not treat unknown execution as safe to retry.
5. Open Governed Actions → the action’s evidence link. Security operators/admins see policy/risk/approval/issuance provenance and RESULT_RECEIVED evidence containing only response hash and byte count. Local archive instructions are in [local evidence archives](local-evidence-archives.md).
6. Sign out and sign in as the viewer. The viewer may review pending requests but has no approval controls and cannot inspect restricted evidence. Other-tenant and forged-organization requests are rejected by authoritative backend scoping.

Unknown operation metadata deliberately scores CRITICAL/100; the demo policy requires human approval for payments/refund. No new runtime allow fallback is introduced. To demonstrate rejection, submit another action and reject it; `execute` must refuse with no live grant.

## Checks and diagnostics

```sh
python3 -m unittest discover -s scripts/dev -p 'test_local_demo.py'
python3 -m unittest discover -s scripts -p 'test_archive_local_evidence.py'
bash scripts/test-local-governance.sh
```

Ordinary Java `verify`, Go `go test -race ./...`, and web test/lint/typecheck/build commands remain separate. The demo is a browser acceptance flow, not a substitute for those suites. Private logs live under `.local-demo/`; `last-command-error.log` is diagnostic data and may contain local configuration, so do not publish it. Docker Desktop is the verified environment; Linux requires deliberate host-network reachability configuration rather than exposing services publicly. The generated agent credential expires after 30 days; an administrator must explicitly rotate it for continued use beyond that period.
