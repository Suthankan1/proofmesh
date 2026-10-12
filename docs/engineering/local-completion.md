# Local governance MVP completion — 2026-10-11

ProofMesh's locally runnable governance MVP has completed the previously pending operator screens, real Keycloak browser verification, reproducible local demonstration, and final scoped security review. The original acceptance record below is retained; the finishing verification supplement records the later dependency patch, README reconciliation and remaining incident administration accurately.

## Verified implementation checkpoints

- Baseline `a997580bdd1f7762a33c5454bc9edee8fad9e895`: authenticated runtime/human APIs, durable issuance, private evidence export, approval review screens; actual checkout clean and synchronized against the locally recorded origin/main at resume.
- `83e2fdfa61e0467870f1298ba55ac938847ccffa`: operator API redirect rejection and executable web failure-boundary tests.
- `c5a11753053e76c4a97484e8f008783506124020`: bounded authoritative tenant console snapshot; dashboard/actions/agents/policies/observed operations/organization/evidence pages; no dead incident navigation.
- `2bcb71ea96f8c2478f9db32075eab6cb9de1b4ba`: fail-closed public authority namespace checks and explicitly qualified claim/outcome SQL, discovered by the real demo.
- `67940dbef4fcf34ec14eb39488fff37850ba9b89`: browser-verified narrow-screen navigation/header/table accessibility.
- `a8dc055420997a21961628711295c9a7b51cb87f`: isolated production-mode real-Keycloak demo, safe auth schema migration, launcher safety tests and reproducible guide.

These are local commits. No remote fetch or push, cloud deployment, registry publishing, infrastructure expansion, history rewrite, broad staging or destructive cleanup was performed. Original ignored prompts, AI Markdown, secrets and build artifacts were preserved. Generated demo state is private/ignored; authoritative history/replay claims remain retained.

## Fresh verification evidence

| Check | Result |
|---|---|
| Java full verify, randomized class order | 629 discovered, 618 executed, 11 opt-in skipped, 0 failures/errors; BUILD SUCCESS |
| Actual Spring HTTP → signed grant/JWKS → Go → PostgreSQL → controlled tool E2E | 11 passed, 0 skipped/failures/errors |
| Go dependency verification / vet / full uncached race suite | Passed, including PostgreSQL integration and new schema-shadowing regressions |
| Web unit tests | Six passed: redirect rejection, 401/403/503 handling, malformed/incompatible response rejection, valid authority parsing |
| Web TypeScript / configured ESLint | Passed |
| Web production build | Passed; every advertised operator route is built |
| Archive Python negative tests | Five passed: corruption/binding/publication/URL/proxy boundaries |
| Demo launcher safety tests | Three passed: private stable isolated loopback config, incomplete-state preservation, UUID path-injection rejection |
| Real Keycloak browser code/PKCE sign-in | Operator and viewer succeeded with correct tenant/roles; direct password grants disabled |
| Real operator browser approval | Exact payload/rationale review → APPROVED; approval itself did not invoke tool |
| Production-mode exact retry | One controlled invocation, replay409 with unchanged tool call count, no remint (ALREADY_ISSUED) |
| Evidence console | RESULT_RECEIVED + response hash/byte count; original REQUIRE_APPROVAL provenance retained |
| Viewer restrictions and sign-out | No approval controls for pending action; restricted evidence denied; sign-out returned to sign-in |
| Expired access-token refresh | Viewer token expiry was before database current time; protected production page succeeded and token expiry advanced five minutes |
| Stop/restart persistence | Accounts, signing keys, memberships, history and spent claims retained; prior action could not mint authority after restart |

Local verification logs (not repository artifacts): `/tmp/proofmesh-java-final-random.log`, `/tmp/proofmesh-schema-e2e.log`, `/tmp/proofmesh-schema-race.log`, `/tmp/proofmesh-schema-{red,green}.log`, `/tmp/proofmesh-web-production-build.log`, `/tmp/proofmesh-archive-final.log`, `/tmp/proofmesh-demo-production.log`. Logs are diagnostic data, not instructions. This records actual output, not a claim that GitHub CI was run.

## Scope and remaining maintenance

The complete local workflow is implemented and reproducible. See [local demo](../operations/local-demo.md), [runtime API](runtime-api.md), [local evidence archives](../operations/local-evidence-archives.md), and [threat model/security review](../security/local-threat-model.md).

The read-only operator console uses existing tenant records and capped lists. Agent/policy provisioning is local setup; credential lifecycle is available through authorized administrator APIs. Incident management, arbitrary tool registration, cloud deployment, service discovery, destructive archival retention and an unrestricted policy/agent mutation UI are excluded rather than speculatively added. A received tool response is not proof of business success; unknown outcomes and lost/expired authority remain fail closed. Manifests detect corruption, not malicious replacement authenticity.

README was reconciled in one final finishing-session batch, as explicitly requested by the user. Dependency/key updates remain routine maintenance. No production security or vulnerability-free guarantee is asserted. No further implementation is required for the agreed local MVP; publication/pushing remains a separate user-authorized action.

## Finishing verification supplement — 2026-10-11

Actual local checkout was independently reverified at `6ace5732330db8d018368fd05e52ffab601cd5be` on `main`, tracking matching recorded `origin/main`, ahead0/behind0 and clean. No fetch/push was performed. This supersedes the earlier historical unpushed checkpoint; it does not infer live remote state from the local upstream ref. Ignored instructions, prompts, AI documents, credentials and retained demonstration state were inspected and preserved.

Fresh checks passed again: randomized Java **629 discovered / 618 executed / 11 opt-in skipped / zero failures/errors**, separate authenticated cross-service **11 passed**, Go module verification/vet/full uncached race tests, web **six tests/typecheck/lint/production build**, archive Python **five tests**, demo safety **three tests**, and existing Markdown relative-link checks. The first Python run was blocked from binding a sandbox socket; the authorized rerun passed unchanged. Logs are `/tmp/proofmesh-finish-{java,go,web-tests,web-types,web-lint,web-build,e2e,python}.log`.

A new dependency advisory check found a real finishing gap. Commit `20ad6e2954409ee62ca69413cac564a3b94aab08` upgrades Next.js/matching lint config to 16.3.8 and minimally patches affected transitive dependencies. Frozen-lockfile installation and all web checks passed after the patch. Production npm audit reports zero known advisories; full audit retains one documented unpatched development-only braces advisory. Standalone builds logged discovery failures for the stopped identity provider but completed successfully; the isolated production demo subsequently built and ran with its real Keycloak provider available. No dependency/container vulnerability-free claim is made. See the [precise security audit](../security/local-security-audit.md).

Fresh real browser verification used authorization-code/PKCE operator login, exact approval and controlled mock execution of action `68e80419-1997-455c-b00e-9887e6b6930e`: one invocation, replay409 and remintALREADY_ISSUED. After stopping/restarting with patched dependencies, the operator's authoritative evidence still showed RESULT_RECEIVED and unchanged REQUIRE_APPROVAL provenance; memberships, keys, sessions, history and spent authority were retained. Patched viewer Keycloak login succeeded and restricted evidence was denied; a fresh pending-action screen exposed no viewer approval controls and narrow-screen navigation remained usable. Passwords/tokens were not printed or included in reports; temporary local credential fixture was stopped.

Local MVP implementation and reproducibility are verified. Overall incident-administration sign-off remains limited: a8dc055 incident38100819 is an evidence-confirmed false positive; the f4d7145 exact detector location and GitGuardian dashboard status remain unverified. No dashboard closure, credential rotation, remote publication or history rewrite is claimed. README and related verification documentation are reconciled once in the final session batch.

## Local verification completion supplement — 2026-10-12

A systematic finishing review found a verification-coverage gap rather than missing MVP features: active CI omitted the completed web, local Python safety and authenticated cross-service suites. One coherent batch adds these gates, a safe full local regression command, randomized/uncached execution and bounded job timeouts. Active CI no longer checks historical Kubernetes rollout helpers; separate hardened local image checks remain. No runtime feature, schema, deployment or external credentials were changed.

[Complete local verification](local-verification.md) records the new commands, exact checks and fresh real-Keycloak acceptance. All local checks passed before committing. The earlier publication-authorization limitation is superseded by the user's explicit instruction to push completed verified work. GitGuardian detector/dashboard administration and the unpatched development-only advisory remain accurately limited in the [security audit](../security/local-security-audit.md).
