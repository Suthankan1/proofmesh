# Complete local verification and CI

## One local command

With Java 25 (`JAVA_HOME` configured), Go selected by `apps/gateway/go.mod`, Node 24, pnpm 11.21.0, Python 3 and Docker Desktop running:

```sh
bash scripts/verify-local.sh
```

Run from any directory. The script checks prerequisites before installation/tests, installs the frozen lockfile, runs randomized Java verification, Go module verification/vet/uncached race tests, web unit/type/lint/build checks, the production dependency audit, archive/demo safety tests and the 11 authenticated cross-service tests. It stops on the first failure and checks whitespace at the end. It performs no deployment, registry publication, data reset, Git staging, commit or push. Tests use disposable fixtures; existing private environment files, prompt Markdown and retained demo state are preserved. Installed dependencies and ignored build outputs are refreshed.

The web production build needs the server settings in `apps/web/.env.local` or environment variables described in [frontend architecture](../architecture/frontend-architecture.md). A build does not establish working authentication. Run the [real Keycloak browser demonstration](../operations/local-demo.md) separately for operator login, approval, exact retry, evidence and viewer restrictions. Local image checks remain separate:

```sh
docker build --target gateway-runtime -t proofmesh-gateway:local apps/gateway
docker build --target migrate-runtime -t proofmesh-migrate:local apps/gateway
python3 scripts/verify-gateway-images.py proofmesh-gateway:local proofmesh-migrate:local
```

## CI boundaries

The read-only-permission GitHub workflow runs four verification jobs on pushes/pull requests:

- Java randomized clean verification plus signed-OIDC Spring → Go → disposable PostgreSQL/tool integration.
- Go dependency verification, vet and uncached race tests.
- Local archive/demo safety tests and separate hardened gateway/migration image build and restricted missing-configuration smokes.
- Frozen web installation, unit tests, TypeScript, lint, production dependency audit and production build with clearly synthetic local settings.

Actions are pinned to reviewed immutable revisions. Jobs have bounded timeouts. No repository secrets are needed. The build settings never connect to a real database or identity account; identity discovery diagnostics may appear for its unavailable loopback provider. Real Keycloak browser acceptance remains separate. Images are built only on the runner and never published. Historical Kubernetes helpers remain untouched and outside active verification scope. No AWS, Helm, Terraform, deployment pipeline, hosting or paid service was introduced.

The production npm audit fails on known advisories of any reported severity. The full development audit is reviewed separately; the unpatched development-only braces finding is documented without suppression in [security audit](../security/local-security-audit.md). This gate does not claim complete vulnerability coverage for Java, Go or container dependencies, nor does it replace code/security review.

## Verified 2026-10-12

The complete script passed locally: Java 629 discovered / 618 executed / 11 opt-in skipped, no failures/errors; separate authenticated integration 11 passed; Go module/vet/uncached race passed; web six tests/type/lint/build passed; Python archive five and demo three passed; frozen installation and production audit passed. Unsupported Node 26 was rejected before installation/tests. Workflow actionlint validation passed, as did the exact synthetic CI build environment and both local image checks.

Real retained-state Keycloak browser acceptance passed: operator code/PKCE login, approval for action `19ea48d4-ee1f-4b3a-a3b7-f8578497620f`, one mock invocation, replay409, remint ALREADY_ISSUED, RESULT_RECEIVED evidence with original REQUIRE_APPROVAL provenance; viewer login, restricted evidence denial and no decision controls on a fresh pending request; narrow-layout navigation disclosure and sign-out. The audit-started demo was stopped through its own launcher, retaining volumes, keys, users and authoritative history. This records local output; the new workflow's remote result is checked separately after push.
