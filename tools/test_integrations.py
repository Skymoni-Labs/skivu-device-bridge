"""Exercise exported packages outside the repository, without a real phone."""
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from zipfile import ZipFile

import export_integrations
import sync_skill
from tls_test_support import PIN, wrap_server

class IntegrationPackageTest(unittest.TestCase):
    def test_installable_skill_is_synchronized(self):
        self.assertEqual([], sync_skill.sync(check=True), "Run python tools/sync_skill.py")

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="phone use ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / "export"
        export_integrations.export(self.output)
        self.installed = self.root / "用户 installed"
        for name in export_integrations.PACKAGES:
            with ZipFile(self.output / (name + ".zip")) as bundle:
                bundle.extractall(self.installed)

    def run_script(self, script, *args, input=None):
        return subprocess.run([sys.executable, str(script), *map(str, args)], input=input,
                              text=True, encoding="utf-8", capture_output=True,
                              cwd=self.root, timeout=20)

    def test_archive_excludes_files_added_after_export(self):
        secret = self.output / "phoneuse-mcp/.phoneuse.json"
        secret.write_text('{"token":"not-for-distribution"}', encoding="utf-8")
        export_integrations.export(self.output)
        self.assertTrue(secret.exists())
        for name, files in export_integrations.PACKAGES.items():
            with ZipFile(self.output / (name + ".zip")) as bundle:
                self.assertEqual({f"{name}/{path}" for path in files}, set(bundle.namelist()))
                self.assertFalse(any(b"not-for-distribution" in bundle.read(path) for path in bundle.namelist()))
        self.assertEqual((export_integrations.ROOT / "tools/phoneuse_client.py").read_text(encoding="utf-8"),
                         (self.installed / "phone-use/scripts/phoneuse_client.py").read_text(encoding="utf-8"))

    def test_generated_json_launches_mcp_without_phone(self):
        config = self.root / "empty registry.json"
        result = self.run_script(self.installed / "phoneuse-mcp/configure.py", "--config", config)
        self.assertEqual(0, result.returncode, result.stderr)
        server = json.loads(result.stdout)["mcpServers"]["phone-use"]
        messages = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-11-25"}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        ]
        result = subprocess.run([server["command"], *server["args"]], cwd=self.root,
                                input="\n".join(map(json.dumps, messages)) + "\n", text=True,
                                encoding="utf-8", capture_output=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        replies = {item["id"]: item for item in map(json.loads, result.stdout.splitlines())}
        self.assertEqual("2025-11-25", replies[1]["result"]["protocolVersion"])
        self.assertIn("phoneuse_connect", {tool["name"] for tool in replies[2]["result"]["tools"]})

    @unittest.skipIf(sys.version_info < (3, 11), "TOML parser is in Python 3.11+ stdlib")
    def test_codex_toml_preserves_paths_and_arguments(self):
        import tomllib
        result = self.run_script(self.installed / "phoneuse-mcp/configure.py", "--host", "codex",
                                 "--name", '中文 "AI"', "--url", "127.0.0.1", "--config", self.root / "配置.json")
        self.assertEqual(0, result.returncode, result.stderr)
        server = tomllib.loads(result.stdout)["mcp_servers"]["phone-use"]
        self.assertEqual(sys.executable, server["command"])
        self.assertEqual('中文 "AI"', server["args"][server["args"].index("--name") + 1])
        self.assertEqual("https://127.0.0.1:8443", server["args"][-1])
        self.assertTrue(Path(server["args"][0]).is_file())

    def test_output_does_not_overwrite_existing_settings(self):
        output = self.root / "existing.json"
        output.write_text("keep this", encoding="utf-8")
        result = self.run_script(self.installed / "phoneuse-mcp/configure.py", "--output", output)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("keep this", output.read_text(encoding="utf-8"))

    def test_address_rejects_credentials_paths_domains_and_bad_ports(self):
        for address in ("https://user:secret@127.0.0.1", "http://127.0.0.1", "https://127.0.0.1/mcp",
                        "https://127.0.0.1:0", "https://127.0.0.1:65536", "https://example.org"):
            with self.subTest(address=address):
                result = self.run_script(self.installed / "phoneuse-mcp/configure.py", "--url", address)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)

    def test_skill_client_works_from_extracted_package(self):
        result = self.run_script(self.installed / "phone-use/scripts/phoneuse_client.py",
                                 "--config", self.root / "new.json", "list-devices", "--cached")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], json.loads(result.stdout)["devices"])
        self.assertTrue((self.installed / "phone-use/references/api.md").is_file())

    def test_skill_observation_saves_image_and_does_not_expose_token(self):
        identity = {"device_id": "test-phone", "service_instance_id": "run-1", "name": "测试手机"}
        seen = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, value):
                data = json.dumps(value).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                seen.append((self.path, self.headers.get("Authorization")))
                self.reply(identity)

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                seen.append((payload["tool"], self.headers.get("Authorization")))
                self.reply({**identity, "observation_id": "obs-1", "screenshot": {
                    "available": True, "data_base64": base64.b64encode(b"image-fixture").decode(),
                    "scale_x": 2, "scale_y": 2, "offset_x": 0, "offset_y": 0}})

        server = wrap_server(ThreadingHTTPServer(("127.0.0.1", 0), Handler))
        worker = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
        worker.start()
        try:
            config = self.root / "paired.json"
            config.write_text(json.dumps({"version": 2, "devices": {"test-phone": {
                **identity, "url": f"https://127.0.0.1:{server.server_port}",
                "tls_pin": PIN, "token": "private-test-token", "client_id": "test-client"}}}), encoding="utf-8")
            image = self.root / "截图.jpg"
            result = self.run_script(self.installed / "phone-use/scripts/phoneuse_client.py",
                                     "--config", config, "call", "observe", "--device-id", "test-phone",
                                     "--screenshot-out", image)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b"image-fixture", image.read_bytes())
            self.assertTrue(json.loads(result.stdout)["screenshot"]["image_data_omitted"])
            self.assertNotIn("private-test-token", result.stdout + result.stderr)
            self.assertEqual([("/identity", None), ("observe", "Bearer private-test-token")], seen)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


if __name__ == "__main__":
    unittest.main()
