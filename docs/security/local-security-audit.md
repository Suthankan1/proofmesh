# Local finishing security audit — 2026-10-11

Scope: local governance MVP, retained isolated demonstration, reachable local Git history and npm lockfile advisories. This is evidence of specific checks, not a vulnerability-free or production certification. No cloud actions, external credential changes, history rewrite, push or dashboard incident mutation were performed.

## GitGuardian notification investigation

| Notification | Evidence and disposition | External status |
|---|---|---|
| `a8dc055420997a21961628711295c9a7b51cb87f`, Username Password, incident `38100819` | User supplied exact source context: `scripts/dev/local-demo.py` environment mapping. The detector treated the DB username and the following `OIDC_ISSUER_URI` configuration key as a credential pair. That key is not a password; the actual password comes from private generated state through `c['dbPassword']`. **Confirmed false positive for this exact occurrence.** | Dashboard requires sign-in; user deferred it. Resolution/closure not verified or changed. |
| `f4d71457c4ed14986801a01e2d1d1ae67cf0634a`, Generic High Entropy Secret | Notification read; exact detector file/line unavailable. Reviewed production migration code, changed test fixtures and Go dependency checksums. Tests use disposable DB credentials and explicit synthetic redaction canaries; checksums are public dependency integrity data. No confirmed real disclosure found. **Exact incident disposition remains pending its file/line evidence.** | Dashboard status not verified or changed. |

No secret values are reproduced here. No rotation is warranted by the confirmed false-positive occurrence; that does not resolve an unseen occurrence. If later evidence identifies a real deployed credential, stop its use, revoke/rotate through its owner, and assess exposure. External account changes or destructive history rewriting require explicit user consent. Deleting a value from HEAD does not revoke it or remove history.

## Independent local history scan

Gitleaks **v8.30.1**, default rules, scanned all locally reachable refs at baseline HEAD `6ace5732330db8d018368fd05e52ffab601cd5be`: **98 commits**, approximately **2.62 MB**, two findings. Reports used `--redact=100`; no allowlist or suppression was added. Exit status was 1 because findings exist. Both were manually reviewed:

- `2819825f37b94dd60fe083070e40f5dda3c86f52`, `apps/gateway/internal/telemetry/telemetry_test.go:888`, generic API key: explicit synthetic sanitizing-exporter error canary, not an issued service credential.
- `b916a52db550f5c5687f09026aedbcca73de380d`, `apps/control-plane/src/test/java/com/proofmesh/controlplane/executiongrant/internal/FileBasedExecutionGrantKeyProviderTest.java:74-85`, private key: deliberately malformed Base64 PEM in a negative parser test; not a usable private key. Positive key fixtures are generated per test.

Private comparison of current generated demo credential values and long credential-like values from local `.env` and web `.env.local` against reachable history found **zero matches**. Values were not printed. This comparison cannot identify unknown historical secrets and is not a substitute for detector review. The existing demo directory is mode 0700; its secret file and signing private key are mode 0600. Ignored private state remains outside Git.

Reproduce the independent scan without publishing findings:

```sh
go run github.com/zricethezav/gitleaks/v8@v8.30.1 git \
  --redact=100 --no-banner --report-format json \
  --report-path /tmp/proofmesh-gitleaks-redacted.json --log-opts='--all' .
```

## Dependency advisory remediation

The initial `pnpm audit --json` reported 16 advisories: 1 critical, 8 high, 6 moderate and 1 low. Updated Next.js and matching ESLint configuration from **16.3.3 to 16.3.8**. Minimal transitive overrides select **sharp 0.35.5**, **source-map-js 1.2.2**, and **brace-expansion 1.1.21 / 5.0.12**. The lockfile retains unrelated dependency versions and build-script restrictions.

After installation, `pnpm audit --prod --json` reports **zero known advisories**; the full audit retains **one high development-only advisory** for `braces 3.0.3` through ESLint → Next ESLint plugin → fast-glob → micromatch. The [upstream advisory](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) lists no patched version; the registry has no 3.0.4 release despite the audit feed suggesting that range. It can exhaust the stack on deeply nested attacker-controlled glob patterns. This path is development lint tooling; ProofMesh exposes no caller-supplied glob input to it. Do not use untrusted patterns in development tooling. Track an upstream fix; do not suppress the finding or force an unavailable/incompatible replacement.

The [critical Next.js advisory](https://github.com/advisories/GHSA-vcvr-r3jv-pc5j) requires attacker-controlled SVG input to Node `next/og` ImageResponse; the current app uses no ImageResponse/next/og route. The package was patched regardless. Runtime package advisory counts are time-bound metadata, not proof that application code or container images are vulnerability-free.

Local reports: `/tmp/proofmesh-finish-gitleaks.json`, `/tmp/proofmesh-finish-gitleaks.log`, `/tmp/proofmesh-finish-dependency-audit.json`, `/tmp/proofmesh-finish-dependency-audit-after.json`, `/tmp/proofmesh-finish-dependency-audit-production.json`. They are local diagnostics, not published dashboard dispositions.

## Reverification — 2026-10-12

At baseline HEAD `90781f7ff2f255cfc8b5f0c3eace534a97b01084`, the fresh fully redacted reachable-history scan again found only the two reviewed negative/synthetic test fixtures above; no suppressions were added. Review of f4d7145's changed migration/runtime/test/dependency files did not establish a real credential disclosure. The connected mailbox search did not return the f4d7145/a8dc055 notifications. Exact f4d7145 detector location and both dashboard dispositions remain unavailable and unverified. Neither incident is claimed closed. No credentials were rotated and no history was rewritten.

Fresh registry/feed checks still report braces **3.0.3** as the available release, one high development-only advisory in the full audit, and zero production advisories. No dependency change or advisory suppression is justified by this evidence. The active CI now gates production advisories while retaining this explicit development-tool limitation. Redacted scan/audit diagnostics are `/tmp/proofmesh-20261012-{gitleaks,audit,audit-prod}.json`; do not publish raw private logs. See [local verification](../engineering/local-verification.md) for the coherent CI/developer verification batch.
