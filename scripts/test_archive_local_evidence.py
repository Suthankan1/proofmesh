import importlib.util
from pathlib import Path
import tempfile
import unittest
import uuid
import threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("archive", Path(__file__).with_name("archive-local-evidence.py"))
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)


class ArchiveTests(unittest.TestCase):
    def fixture(self):
        organization, action, agent = (str(uuid.uuid4()) for _ in range(3))
        return organization, action, {"schemaVersion": 1, "organizationId": organization,
                "action": {"id": action, "organization_id": organization, "agent_id": agent}, "executions": []}

    def test_atomic_copy_verifies_and_corruption_is_rejected(self):
        organization, action, bundle = self.fixture()
        with tempfile.TemporaryDirectory() as temporary:
            result = archive.archive(bundle, temporary, organization, action)
            archive.verify(result)
            self.assertEqual((result / "evidence.json").stat().st_mode & 0o777, 0o600)
            (result / "evidence.json").write_text("tampered")
            with self.assertRaises(ValueError):
                archive.verify(result)

    def test_tenant_or_execution_binding_mismatch_cannot_publish(self):
        organization, action, bundle = self.fixture()
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                archive.archive(bundle, temporary, str(uuid.uuid4()), action)
            bundle["executions"] = [{"organization_id": organization, "governed_action_id": action, "agent_id": str(uuid.uuid4())}]
            with self.assertRaises(ValueError):
                archive.archive(bundle, temporary, organization, action)
            self.assertEqual(list(Path(temporary).iterdir()), [])

    def test_interrupted_publication_leaves_no_partial_archive(self):
        organization, action, bundle = self.fixture()
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(archive.os, "rename", side_effect=OSError("unavailable")):
                with self.assertRaises(OSError):
                    archive.archive(bundle, temporary, organization, action)
            self.assertEqual(list(Path(temporary).iterdir()), [])

    def test_nonlocal_url_is_rejected_before_sending_secret(self):
        with patch.object(archive.urllib.request, "build_opener") as opener:
            with self.assertRaises(ValueError):
                archive.fetch("https://example.com", "/evidence", "private-test-token")
            opener.assert_not_called()

    def test_proxy_environment_cannot_receive_local_bearer(self):
        hits = []
        class Target(BaseHTTPRequestHandler):
            def do_GET(self):
                hits.append("target")
                self.send_response(200); self.end_headers(); self.wfile.write(b'{"source":"target"}')
            def log_message(self, *args):
                pass
        class Proxy(Target):
            def do_GET(self):
                hits.append("proxy")
                self.send_response(200); self.end_headers(); self.wfile.write(b'{"source":"proxy"}')
        target, proxy = ThreadingHTTPServer(("127.0.0.1",0),Target), ThreadingHTTPServer(("127.0.0.1",0),Proxy)
        threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (target,proxy)]
        for thread in threads: thread.start()
        try:
            proxy_url = "http://127.0.0.1:" + str(proxy.server_port)
            with patch.dict(archive.os.environ, {"HTTP_PROXY":proxy_url,"http_proxy":proxy_url,"NO_PROXY":"","no_proxy":""}):
                response=archive.fetch("http://127.0.0.1:"+str(target.server_port),"/evidence","private-test-token")
            self.assertEqual(response["source"],"target")
            self.assertEqual(hits,["target"])
        finally:
            for server in (target,proxy): server.shutdown(); server.server_close()
            for thread in threads: thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
