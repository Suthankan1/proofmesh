#!/usr/bin/env bash
# Real Spring -> Go -> PostgreSQL -> local tool integration, with disposable fixtures.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/proofmesh-crossservice.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT
(cd "$root/apps/gateway" && go build -o "$work/crossservice" ./testsupport/crossservice)
(cd "$root/apps/control-plane" && PROOFMESH_CROSSSERVICE_BINARY="$work/crossservice" \
 ./mvnw --batch-mode --no-transfer-progress -Dproofmesh.crossservice=true \
 -Dtest=CrossServiceGovernanceIntegrationTest test)
