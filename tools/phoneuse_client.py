#!/usr/bin/env python3
"""Python 3.10+ client and MCP stdio bridge. No third-party dependencies.

Uses the phone's HTTP endpoint and a saved pairing credential.
No action retries are performed, including on a broken connection.
"""
import argparse
import http.client
import json
import os
from pathlib import Path
import sys
import tempfile
import time
from urllib.parse import urlsplit


class HttpError(RuntimeError):
    def __init__(self, status, value):
        error = value.get("error", {})
        self.status = status
        self.code = error.get("code") if isinstance(error, dict) else None
        super().__init__(f"HTTP {status}: {error.get('message', self.code) if isinstance(error, dict) else error}")


def origin(value):
    url = urlsplit(value)
    if url.scheme != "http" or not url.hostname or url.username or url.password or url.path not in ("", "/") or url.query or url.fragment:
        raise ValueError("url must be an http://host:port origin")
    return f"http://{url.hostname}:{url.port or 8443}"


def post(config, endpoint, payload, authenticate=True):
    url = urlsplit(origin(config["url"]))
    connection = http.client.HTTPConnection(url.hostname, url.port or 8443, timeout=25)
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if authenticate:
        headers["Authorization"] = "Bearer " + config["token"]
    if endpoint == "/mcp":
        headers["MCP-Protocol-Version"] = "2025-11-25"
    try:
        connection.request("POST", endpoint, json.dumps(payload, ensure_ascii=False).encode("utf-8"), headers)
        response = connection.getresponse()
        body = response.read(16 * 1024 * 1024 + 1)
        if len(body) > 16 * 1024 * 1024:
            raise ValueError("Response too large")
        if response.status == 202 and not body:
            return None
        value = json.loads(body)
        if response.status not in (200, 202):
            raise HttpError(response.status, value)
        return value
    finally:
        connection.close()


class Connection:
    """Own credentials here; expose only connection status to the model."""
    def __init__(self, path, url=None, name="AI client"):
        self.path, self.url, self.name = path, origin(url) if url else None, name
        self.config = {}
        self.reload()

    def reload(self):
        self.config = json.loads(self.path.read_text(encoding="utf-8")) if self.path.exists() else {}
        if self.url and self.config.get("url") and origin(self.config["url"]) != self.url:
            raise ValueError("Saved pairing belongs to another URL; use its address or a separate --config path")
        if self.url:
            self.config["url"] = self.url

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".phoneuse-", dir=self.path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(self.config, stream, indent=2)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def send(self, endpoint, payload):
        if not self.config.get("token"):
            raise RuntimeError("Not paired. Call phoneuse_connect and approve on the phone first")
        try:
            return post(self.config, endpoint, payload)
        except HttpError as error:
            if error.status == 401:
                self.config.pop("token", None)
                self.config.pop("client_id", None)
                self.config.pop("pending", None)
                self.save()
                raise RuntimeError("Pairing was revoked. Call phoneuse_connect to request approval again") from None
            raise

    def connect(self):
        self.reload()
        if not self.config.get("url"):
            raise ValueError("Configure mcp-stdio --url http://PHONE:8443 first")
        if self.config.get("token"):
            self.send("/api", {"tool": "get_status"})
            return {"status": "paired", "message": "Connected using saved pairing. Device tools are ready; no token is needed."}
        pending = self.config.get("pending")
        if pending and time.time() >= pending["deadline"]:
            self.config.pop("pending")
            self.save()
            raise RuntimeError("Pairing expired; call phoneuse_connect again to request approval")
        if pending:
            try:
                result = post(self.config, "/pair/status", {
                    "pairing_id": pending["pairing_id"], "pairing_secret": pending["pairing_secret"]
                }, authenticate=False)
            except HttpError as error:
                if error.code in ("PAIRING_EXPIRED", "PAIRING_DENIED"):
                    self.config.pop("pending")
                    self.save()
                raise
            if result["status"] == "denied":
                self.config.pop("pending")
                self.save()
                raise RuntimeError("Pairing denied on the phone")
            if result["status"] == "approved":
                self.config.update(token=result["token"], client_id=result["client_id"])
                self.config.pop("pending")
                self.save()
                return {"status": "paired", "message": "Pairing saved. Device tools are ready; future sessions connect automatically."}
        else:
            pending = post(self.config, "/pair/request", {"client_name": self.name}, authenticate=False)
            pending["deadline"] = time.time() + pending["expires_in"]
            self.config["pending"] = pending
            self.save()
        return {"status": "pending", "verification_code": pending["verification_code"],
                "poll_interval": pending["poll_interval"],
                "message": "Approve this code in Phone Use on the phone, then call phoneuse_connect again. Do not request or read a token."}


CONNECTION_TOOLS = [
    {"name": "phoneuse_connect", "description": "Connect with saved pairing, or request first-time approval on the phone. Call again after approval. Never returns credentials.",
     "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False}},
    {"name": "phoneuse_call", "description": "Call a phone tool using saved pairing. Useful if the host has not refreshed its tool list after pairing. No credentials needed.",
     "inputSchema": {"type": "object", "properties": {"tool": {"type": "string"}, "arguments": {"type": "object"}}, "required": ["tool"]}}
]


def tool_result(value, error=False):
    return {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}], "isError": error}


def bridge(connection):
    # MCP stdio is newline-delimited JSON-RPC, not Content-Length framed LSP.
    catalog = []
    known_paired = bool(connection.config.get("token"))
    def emit(value):
        print(json.dumps(value, ensure_ascii=False), flush=True)
    for line in sys.stdin:
        request = None
        list_changed = False
        try:
            request = json.loads(line)
            if not isinstance(request, dict) or request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str):
                emit({"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid request"}})
                continue
            if "id" not in request:
                continue  # Notifications never execute device actions.
            method = request["method"]
            params = request.get("params", {})
            if not isinstance(params, dict):
                raise ValueError("params must be an object")
            connection.reload()
            response = None
            if method == "initialize":
                version = params.get("protocolVersion")
                result = {"protocolVersion": version if version in ("2025-03-26", "2025-06-18", "2025-11-25") else "2025-11-25",
                          "capabilities": {"tools": {"listChanged": True}}, "serverInfo": {"name": "phone-use", "version": "0.2.0"},
                          "instructions": "Call phoneuse_connect if unpaired. Approval is needed only once. Credentials are managed by this bridge; never read credential files. After pairing refresh tools/list. Always supply a unique request_id for actions; do not replay actions after connection failures."}
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                if connection.config.get("token"):
                    try:
                        remote = connection.send("/mcp", request)
                        catalog = remote["result"]["tools"]
                    except (OSError, http.client.HTTPException, RuntimeError):
                        # Keep the bridge usable while the phone restarts. Never pair on a network failure.
                        pass
                if not connection.config.get("token"):
                    catalog = []
                result = {"tools": CONNECTION_TOOLS + catalog}
            elif method == "tools/call":
                name, arguments = params.get("name"), params.get("arguments", {})
                if not isinstance(arguments, dict):
                    raise ValueError("arguments must be an object")
                if name == "phoneuse_connect":
                    state = connection.connect()
                    result = tool_result(state)
                    list_changed = state["status"] == "paired"
                else:
                    if name == "phoneuse_call":
                        request = {**request, "params": {"name": arguments["tool"], "arguments": arguments.get("arguments", {})}}
                    response = connection.send("/mcp", request)
            else:
                emit({"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32601, "message": "Method not found"}})
                continue
            emit(response if response is not None else {"jsonrpc": "2.0", "id": request["id"], "result": result})
        except Exception as error:
            print(f"Phone Use: {error}", file=sys.stderr, flush=True)
            if isinstance(request, dict) and "id" in request:
                if request.get("method") == "tools/call":
                    emit({"jsonrpc": "2.0", "id": request["id"], "result": tool_result({"error": str(error),
                          "message": "If an action was sent, its outcome may be unknown. Query its request_id; do not replay with a new ID."}, True)})
                else:
                    emit({"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32000, "message": str(error)}})
            elif request is None:
                emit({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Invalid JSON"}})
        paired = bool(connection.config.get("token"))
        if paired != known_paired or list_changed:
            emit({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            known_paired = paired


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path.home() / ".phoneuse.json")
    sub = parser.add_subparsers(dest="command", required=True)
    pair = sub.add_parser("pair")
    pair.add_argument("--url", required=True)
    pair.add_argument("--name", default="Python client")
    call = sub.add_parser("call")
    call.add_argument("tool")
    call.add_argument("arguments", nargs="?", default="{}", help="JSON object, or @path to a UTF-8 JSON file")
    mcp = sub.add_parser("mcp-stdio")
    mcp.add_argument("--url", help="Phone address for first pairing; optional once saved")
    mcp.add_argument("--name", default="AI client")
    args = parser.parse_args()
    if args.command == "pair":
        connection = Connection(args.config, args.url, args.name)
        shown_code = None
        while True:
            result = connection.connect()
            if result["status"] == "paired":
                break
            if result["verification_code"] != shown_code:
                shown_code = result["verification_code"]
                print(f"Open Phone Use on the phone and approve '{args.name}'. Verification code: {shown_code}", flush=True)
            time.sleep(result["poll_interval"])
        print("Connected. Pairing is saved and will be reused automatically.")
        return
    if args.command == "mcp-stdio":
        bridge(Connection(args.config, args.url, args.name))
    else:
        raw = Path(args.arguments[1:]).read_text(encoding="utf-8") if args.arguments.startswith("@") else args.arguments
        arguments = json.loads(raw)
        if not isinstance(arguments, dict):
            parser.error("arguments must be a JSON object")
        print(json.dumps(Connection(args.config).send("/api", {"tool": args.tool, "arguments": arguments}), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Phone Use: {error}", file=sys.stderr)
        sys.exit(1)
