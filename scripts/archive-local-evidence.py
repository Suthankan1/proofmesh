#!/usr/bin/env python3
"""Archive exact-action evidence copies; never mutate authoritative database state."""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAX_BYTES = 4 * 1024 * 1024


def validate(bundle, organization, action):
    if not isinstance(bundle, dict):
        raise ValueError("Evidence must be an object")
    organization, action = str(uuid.UUID(organization)), str(uuid.UUID(action))
    if bundle.get("schemaVersion") != 1 or bundle.get("organizationId") != organization:
        raise ValueError("Evidence tenant or schema mismatch")
    record = bundle.get("action", {})
    if record.get("id") != action or record.get("organization_id") != organization:
        raise ValueError("Evidence action mismatch")
    for name in ("riskAssessment", "decision", "approval", "issuance"):
        value = bundle.get(name)
        if value is not None and (value.get("organization_id") != organization or value.get("governed_action_id") != action):
            raise ValueError("Evidence provenance mismatch")
    policy = bundle.get("policyVersion")
    if policy is not None and policy.get("organization_id") != organization:
        raise ValueError("Evidence policy tenant mismatch")
    for value in bundle.get("executions", []):
        if (value.get("organization_id") != organization or value.get("governed_action_id") != action
                or value.get("agent_id") != record.get("agent_id")):
            raise ValueError("Execution evidence binding mismatch")


def archive(bundle, destination, organization, action):
    validate(bundle, organization, action)
    root = Path(destination)
    if root.is_symlink():
        raise ValueError("Archive destination must not be a symbolic link")
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".pending-", dir=root))
    try:
        data = (json.dumps(bundle, sort_keys=True, separators=(",", ":")) + "\n").encode()
        manifest = {"schemaVersion": 1, "organizationId": organization, "actionId": action,
                    "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                    "integrityMeaning": "Corruption detection only; not an authenticated signature"}
        for name, content in (("evidence.json", data), ("manifest.json", (json.dumps(manifest, sort_keys=True) + "\n").encode())):
            descriptor = os.open(stage / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(content)
                handle.flush()
                os.fsync(handle.fileno())
        descriptor = os.open(stage, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        final = root / ("action-" + action + "-" + str(uuid.uuid4()))
        os.rename(stage, final)
        descriptor = os.open(root, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        return final
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def verify(directory):
    root = Path(directory)
    if root.is_symlink() or any((root / name).is_symlink() for name in ("manifest.json", "evidence.json")):
        raise ValueError("Archive must not use symbolic links")
    manifest = json.loads((root / "manifest.json").read_text())
    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported archive manifest")
    if (root / "evidence.json").stat().st_size > MAX_BYTES:
        raise ValueError("Archive exceeds verification bound")
    data = (root / "evidence.json").read_bytes()
    if manifest.get("bytes") != len(data) or not hmac.compare_digest(manifest.get("sha256", ""), hashlib.sha256(data).hexdigest()):
        raise ValueError("Archive integrity check failed")
    validate(json.loads(data), manifest["organizationId"], manifest["actionId"])
    return manifest


def fetch(base, path, token):
    parsed = urllib.parse.urlsplit(base)
    if (parsed.scheme not in ("http", "https") or parsed.hostname not in ("localhost", "127.0.0.1", "::1")
            or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise ValueError("Use a loopback control-plane base URL")
    request = urllib.request.Request(base.rstrip("/") + path, headers={"Authorization": "Bearer " + token, "Accept": "application/json"})
    # Do not follow redirects with the human bearer credential.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect).open(request, timeout=10) as response:
        data = response.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise ValueError("Evidence response exceeds local archive bound")
        return json.loads(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--action")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--output", default="local-evidence")
    parser.add_argument("--verify")
    arguments = parser.parse_args()
    try:
        if arguments.verify:
            verify(arguments.verify)
            print("Archive integrity verified (not signature/authenticity proof)")
            return
        if not arguments.action:
            parser.error("--action or --verify is required")
        action = str(uuid.UUID(arguments.action))
        token = os.environ.get("PROOFMESH_HUMAN_TOKEN")
        if not token:
            raise ValueError("Set PROOFMESH_HUMAN_TOKEN for an authorized human")
        context = fetch(arguments.base_url, "/api/v1/identity/context", token)
        bundle = fetch(arguments.base_url, "/api/v1/evidence/actions/" + action, token)
        result = archive(bundle, arguments.output, context["organizationId"], action)
        verify(result)
        print(result)
    except urllib.error.HTTPError as error:
        raise SystemExit("Control plane rejected export: HTTP " + str(error.code)) from None
    except (ValueError, OSError, KeyError, TypeError):
        raise SystemExit("Evidence archive failed; no successful export claimed") from None


if __name__ == "__main__":
    main()
