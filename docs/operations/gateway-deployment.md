# Gateway release and migration gate

The gateway and migration runner are separate artifacts built from the **same
reviewed Git commit** using the `gateway-runtime` and `migrate-runtime` targets
in `apps/gateway/Dockerfile`. Publish both through your approved registry workflow
and record their immutable image digests. This helper does not build, publish,
verify registry provenance, or deploy the Spring control plane, Keycloak, or
PostgreSQL.

## Prerequisites

Use Python 3.10+ and kubectl compatible with your cluster. Provision a namespace
with restricted Pod Security admission, registry pull access, PostgreSQL, and
reachable control-plane JWKS and approved tool endpoints. Supply these existing
resources through your secret/configuration management system:

| Resource | Required keys | Purpose |
| --- | --- | --- |
| Secret `proofmesh-migration-db` | `database-url` | Dedicated schema-owner DSN for the one-shot migration Job |
| Secret `proofmesh-gateway-db` | `database-url` | Runtime DSN with CONNECT, schema USAGE and SELECT/INSERT on `execution_grant_claims`; no schema ownership or DDL privileges |
| ConfigMap `proofmesh-gateway-config` | `PROOFMESH_EXECUTION_GRANT_ISSUER`, `PROOFMESH_EXECUTION_GRANT_AUDIENCE`, `PROOFMESH_JWKS_URL`, `PROOFMESH_TOOL_TARGETS_JSON` | Exact trusted runtime configuration |

Both DSNs must point to the same gateway database/schema. Before the initial
release, provision schema access and schema-owner default privileges granting
SELECT/INSERT on newly created tables to the runtime role. For an existing
schema, explicitly grant access to `execution_grant_claims` and retain those
grants across releases. Review default privileges for later schema changes. Never give the
gateway migration credentials. Require verified TLS for database and outbound
connections in production; supply private CA trust through reviewed image/volume
configuration when needed. Public CA trust is included in the runtime images.
No private signing keys belong in either gateway artifact or secret.

The release operator needs namespace-scoped permission to create/get/watch Jobs,
apply Deployments and Services, and inspect rollout status. Application pods
receive no mounted API token. Serialize releases to the same namespace in your
CI/CD system: the migration advisory lock serializes schema work, not concurrent
gateway rollouts. The operator must verify both image digests came from the
specified commit; the revision annotation alone is not a provenance proof.

## Review and release

Use a fresh release identifier (lowercase letters/digits/hyphens, at most 40
characters), an exact full Git commit hash, an explicit cluster context, and two
registry image references containing `@sha256:<64 lowercase hex characters>`.

```sh
python3 infrastructure/kubernetes/deploy_gateway.py \
  --context "$PROOFMESH_KUBE_CONTEXT" \
  --namespace "$PROOFMESH_KUBE_NAMESPACE" \
  --release "$PROOFMESH_RELEASE_ID" \
  --revision "$PROOFMESH_REVISION" \
  --gateway-image "$PROOFMESH_GATEWAY_IMAGE" \
  --migrate-image "$PROOFMESH_MIGRATE_IMAGE" \
  --render > /tmp/proofmesh-release.json
```

`--render` contacts no cluster and prints no secret values. Review the generated
resources and validate them against your target cluster with server-side dry runs
under your approved deployment workflow. **Do not apply the rendered List
as a release:** that would bypass ordering. Run the same command with `--deploy`
in place of `--render` once deployment is authorized.

The helper creates a new `proofmesh-migrate-<release>` Job, waits for its `Complete`
condition, and only then applies the gateway Deployment and internal ClusterIP
Service and waits for rollout. A preexisting Job causes creation to fail even
when historically successful. Migration create/wait errors stop before any
Deployment/Service apply; rollout errors return failure without automatic
rollback. Use a fresh release ID after diagnosing a failed attempt.

The Job has no automatic retries, a 90-second execution deadline, and a one-day
finished-Job TTL. The helper waits at most 120 seconds per Job/rollout step with a
bounded subprocess timeout. Normal gateway startup and readiness never run
migrations. `/readyz` checks database reachability only, not schema compatibility.
The explicit migration gate supplies ordering; it cannot make incompatible
schema changes safe during rolling updates. Use additive, backward-compatible
migrations and an expand/contract plan for later breaking changes.

Both workloads use UID/GID 65532, a read-only root filesystem, dropped
capabilities, no privilege escalation, RuntimeDefault seccomp, and bounded
resources. Gateway replicas expose HTTP on 8080, use `/healthz` for startup and
liveness and `/readyz` for readiness, and allow 30 seconds for shutdown (the
application bounds HTTP and telemetry shutdown separately). Resource sizes and
two replicas are starting values to validate under measured load.

## Failure response and deployment-specific work

Inspect the exact Job and pod status/events; migration failure messages are
sanitized. Preserve database and audit evidence. Do not reset the database,
remove replay claims, or edit applied migrations to recover a release. If
rollout fails, inspect current replicas and explicitly choose a compatible
previous gateway digest only after checking schema compatibility. Never roll
back database state automatically.

This slice provides migration ordering and pod hardening. Production deployment
still requires target-cluster validation, ingress TLS/access controls, NetworkPolicy
rules tailored to actual DB/JWKS/tool addresses, secret encryption/rotation,
registry provenance/scanning, database backups and restore exercises, load and
failure testing, and approved control-plane/identity deployment. The internal
Service is not a public ingress or an authentication boundary. Optional tracing
is not configured by this helper yet.

Local verification:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s infrastructure/kubernetes -p 'test_*.py'
```

Reference semantics: [Kubernetes Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/job/)
and [application security checklist](https://kubernetes.io/docs/concepts/security/application-security-checklist/).
