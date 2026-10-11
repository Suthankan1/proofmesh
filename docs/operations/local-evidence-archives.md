# Local evidence copies and retention

GET `/api/v1/evidence/actions/{actionId}` requires a verified human SECURITY_OPERATOR or PLATFORM_ADMIN with active organization membership. Agents and VIEWER cannot export. Exact-action evidence is assembled in one PostgreSQL statement snapshot: action identity/hash, risk assessment, immutable decision and published policy, approval binding/status/actor/time, issuance digest, and execution claims/outcomes. Every lookup is tenant-scoped. Unknown/cross-tenant action IDs return404; unavailable database or missing local gateway migrations returns503.

This local topology shares the PostgreSQL database between Spring's proofmesh schema and the gateway's public schema. Apply the one-shot Go migration runner first. The endpoint does not silently omit unavailable execution evidence. Request payloads, idempotency keys, approval rationale and raw request/response bodies are excluded. No agent credentials or bearer execution grants are exported. Policy definitions and actor identifiers remain sensitive metadata.

Archive a copy using a short-lived authorized human token in PROOFMESH_HUMAN_TOKEN (never put it in a committed file, URL, command-line argument or logs):

```bash
python3 scripts/archive-local-evidence.py --action ACTION_UUID --output local-evidence
python3 scripts/archive-local-evidence.py --verify local-evidence/RETURNED_ARCHIVE_DIRECTORY
```

The default base URL is http://127.0.0.1:8080. The client accepts only loopback URLs and refuses redirects before forwarding credentials. It derives expected tenant from the verified human context and checks tenant/action/execution binding before publication. Remote responses and verification inputs are bounded to4MiB. Files are private0600 in a private staging directory, flushed and atomically renamed as a complete archive with a SHA-256 manifest. On publication failure no partial archive is claimed. Existing archive copies are never overwritten or deleted. The default local-evidence directory is ignored by Git.

The manifest detects corruption; it is not a signature or authenticity proof. A local owner could change both evidence and manifest. Authenticity/provenance depends on the authorized database snapshot and trusted local custody. Keep copies on trusted local storage and use existing backup controls when needed. Never interpret RESULT_RECEIVED as independently proven business success. UNKNOWN, STARTED and LEGACY_UNKNOWN retain their uncertainty.

Retention policy is copy-only: preserve original immutable governance, issuance and approval records and durable replay claims indefinitely until an explicit reviewed retention policy exists. No TTL deletion, source cleanup, object storage, cloud publishing or archive scheduler is introduced. An archive represents its export-time snapshot; exporting again may capture a later terminal result. Credential/admission actor audit remains in its authoritative tables and is not falsely correlated to a particular action by this exporter.

Verification: Python tests exercise corruption, tenant/execution binding mismatch, interrupted publication and prevention of remote token forwarding. The opt-in local cross-service suite also exports and verifies an actual executed action using the CLI against the real Spring HTTP API and gateway PostgreSQL evidence.
