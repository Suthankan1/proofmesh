# Local ProofMesh threat model and completion security review

Reviewed 2026-10-11. Scope is the locally runnable governance MVP and operator console. Cloud infrastructure, deployment pipelines, automated destructive retention, dynamic discovery, an incident-management subsystem and production certification are excluded. Existing historical deployment scaffolding is not expanded or claimed verified.

## Assets and trust boundaries

Protect the Spring ES256 private key, operator sessions/OIDC tokens, opaque agent secrets, tenant governance history, exact-action approval bindings, durable grant claims and outcome evidence. Agents and HTTP callers are untrusted; agent possession of a runtime credential grants submission permission only. Keycloak authenticates humans, while Spring resolves active authoritative tenant membership and checks current roles for every API request. Next.js is a server-side token boundary. The gateway only receives public JWKS and narrowly bound execution grants; it alone invokes configured protected tools.

The local host administrator, database/schema owner, signing service, configured identity provider and reviewed static tool routes are trusted. A compromised host/DB administrator can tamper with local data or signing material; hashes and archive manifests do not prove authenticity against that attacker. The demonstration deliberately uses a database owner account in a private disposable topology. Least-privilege production provisioning and Internet exposure are outside this completion claim.

## Threats, controls and evidence

| Threat | Enforced control | Verification |
|---|---|---|
| Agent impersonation / stolen credential | 256 random bits, hash-only DB storage, tenant/agent binding, expiry, revocation/rotation, active identity checks | Credential service/HTTP and runtime security integration tests; signed HTTP E2E credential rotation |
| Agent approves its own action | Separate agent and human security chains; human OIDC APPROVER required | Runtime security negatives and authenticated E2E; real Keycloak viewer has no approval controls |
| Cross-tenant access / forged tenant header | Tenant derived from authoritative active human membership or agent credential, scoped queries and transitions | OperatorConsoleIntegrationTest; approval/credential tests; real E2E tenant evidence rejection |
| Approval replay or changed payload | Approval binds exact action, decision, organization, agent, tool, operation, canonical hash and validity interval | Approved exact retry, changed binding/payload, rejected, pending and expired approval E2E |
| Repeated execution | Immutable at-most-once HTTP grant issuance plus PostgreSQL atomic per-jti claims; no downstream retries | Parallel submissions mint one token; concurrent claim tests; gateway restart replay; real browser demo 409 and ALREADY_ISSUED |
| Namespace drift resurrects or hides authority | Effective public schema required at migration/startup; claim/outcome SQL explicitly public; DSNs explicitly search_path=public | New shadow-schema red/green integration tests; actual Keycloak demo uncovered and validated fix; existing test claims preserved |
| Forged / expired / wrongly targeted grant | ES256 only, issuer/audience/time validation, exact binding, bounded strict JSON, unique keys | Go verifier, claim parser, binder, ingress and canonicalization tests |
| Tool SSRF / token forwarding | Trusted static targets, redirect rejection, bounded timeouts/bodies, stripped credentials, no retries | Go HTTP executor tests; private demo tool has no host-published port |
| Operator token redirects / cache leakage | Server-only access/refresh tokens, encrypted OAuth account storage, no-store requests/responses, redirect rejection | Web transport regression tests, real Keycloak browser code/PKCE login/sign-out, typed contract failure tests |
| DB, issuance, JWKS or tool failure | Fail closed; no token on failed durable issuance; intent before invocation; unknown outcomes retain spent authority | Issuance persistence fault injection; JWKS/DB failure tests; unsuccessful tool E2E records UNKNOWN |
| Outcome overclaim / archive corruption | RESULT_RECEIVED denotes observed HTTP response, not business success; response hash/length only; private copy-only archives, binding/manifest checks | Outcome/migration tests; E2E export/CLI; five Python archive negatives including proxy/corruption/publication failure |
| Accidental external local-demo exposure | Loopback-only published ports, isolated volumes, no host tool port, generated private keys/secrets, pinned non-root gateway image | Real Docker topology/browser acceptance; three launcher safety tests; private retained state |

## Review findings and resolutions

1. The operator API client followed redirects. A red test reproduced acceptance; `redirect: error` now rejects them and keeps requests uncached.
2. Real Keycloak integration exposed PostgreSQL schema shadowing: the DB user `proofmesh` gained a same-name Spring schema, causing unqualified gateway state to move from public. Migrator/runtime now fail closed on a different effective namespace, and live SQL explicitly addresses public. The demo pins both DSNs. Its one historical test claim was copied through the permitted evidence lifecycle with exact binding/timestamps/outcome, and its original retained. **Never switch an existing authority namespace without reconciling all durable spent claims.** There is no automatic destructive relocation.
3. Narrow screens hid navigation and clipped the role header. A mobile navigation disclosure, wrapping header, keyboard-accessible horizontally scrollable review table and flexible evidence form were browser-verified.
4. The auth CLI could not import server-only configuration. A standalone migration uses the installed adapter/schema generator and the same account encryption setting; application server-only boundaries remain intact.

## Deliberate limits and availability tradeoffs

An expired or lost issued token cannot be reminted for the same action. A lost/failed response never establishes that a tool had no effect. Evidence write failure or process crash can leave STARTED/UNKNOWN ambiguity; there is no unsafe recovery execution. Signing/JWKS caches and OIDC token lifetimes bound revocation visibility; active membership and agent credential state are checked at request boundaries. No cross-system exactly-once business-side-effect guarantee is claimed.

Viewer reads are read-only. Credential lifecycle is available through tenant-scoped administrator APIs; policy/agent provisioning is local setup, not a new unrestricted console mutation workflow. Governed operations are observed records, not live discovery or arbitrary route registration. Console lists intentionally cap at 100; dashboard counts explicitly describe that scope. Approval authorization remains authoritative even if the screen has not refreshed. Archives retain authoritative DB rows and replay authority; manifest SHA-256 detects corruption, not malicious replacement authenticity. Local HTTP is permitted only in the loopback development topology; it is not a production TLS claim.

The final verification record and reproducible commands are in [local completion](../engineering/local-completion.md) and [local demo](../operations/local-demo.md). Future key/dependency maintenance is ongoing operational work, not proof of vulnerability-free software.
