#!/usr/bin/env bash
# Full local-only regression gate. Never deploys, resets data, or prints secrets.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

# Check prerequisites before builds or disposable test fixtures are started.
for executable in java go node pnpm python3 docker; do
  command -v "$executable" >/dev/null || { echo "Missing prerequisite: $executable" >&2; exit 1; }
done
node -e 'if (Number(process.versions.node.split(".")[0]) !== 24) { console.error("Select Node 24 on PATH before verification"); process.exit(1); }'
java_version="$(java -version 2>&1)"
if [[ "$java_version" != *'version "25.'* ]]; then
  echo "Select Java 25 on PATH and configure JAVA_HOME before verification" >&2
  exit 1
fi
if [[ "$(pnpm --version)" != "11.21.0" ]]; then
  echo "Select pnpm 11.21.0 before verification" >&2
  exit 1
fi
docker info >/dev/null

# Frozen installation is explicit so stale local node_modules cannot hide lockfile errors.
pnpm install --frozen-lockfile
(cd apps/control-plane && ./mvnw --batch-mode --no-transfer-progress -Dsurefire.runOrder=random verify)
(cd apps/gateway && go mod verify && go vet ./... && go test -race -count=1 ./...)
pnpm --filter web test
pnpm --filter web exec tsc --noEmit --incremental false
pnpm web:lint
pnpm web:build
pnpm audit --prod --audit-level=low
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_archive_local_evidence.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/dev -p 'test_local_demo.py'
bash scripts/test-local-governance.sh
git diff --check
echo "Local regression gate passed. Real Keycloak browser acceptance remains a separate check."
