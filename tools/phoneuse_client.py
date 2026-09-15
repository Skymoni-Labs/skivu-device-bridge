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
import time
from urllib.parse import urlsplit


def post(config, endpoint, payload, authenticate=True):
    url = urlsplit(config["url"])
    if url.scheme != "http" or not url.hostname or url.username or url.password or url.path not in ("", "/") or url.query or url.fragment:
        raise ValueError("url must be an http://host:port origin")
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
            raise RuntimeError(f"HTTP {response.status}: {value}")
        return value
    finally:
        connection.close()


def bridge(config):
    # MCP stdio is newline-delimited JSON-RPC, not Content-Length framed LSP.
    for line in sys.stdin:
        request = None
        try:
            request = json.loads(line)
            response = post(config, "/mcp", request)
            if response is not None:
                print(json.dumps(response, ensure_ascii=False), flush=True)
        except Exception as error:
            print(f"Phone Use: {error}", file=sys.stderr, flush=True)
            if isinstance(request, dict) and "id" in request:
                print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "error": {
                    "code": -32000, "message": "Transport failed; action outcome may be unknown. Query its request_id; do not replay with a new ID."
                }}), flush=True)


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
    sub.add_parser("mcp-stdio")
    args = parser.parse_args()
    if args.command == "pair":
        if args.config.exists():
            parser.error("Config already exists; choose a new --config path to avoid overwriting a credential")
        config = {"url": args.url}
        pending = post(config, "/pair/request", {"client_name": args.name}, authenticate=False)
        print(f"Open Phone Use on the phone and approve '{args.name}'. Verification code: {pending['verification_code']}", flush=True)
        deadline = time.monotonic() + pending["expires_in"]
        while True:
            if time.monotonic() >= deadline:
                raise RuntimeError("Pairing expired; run pair again")
            time.sleep(pending["poll_interval"])
            result = post(config, "/pair/status", {
                "pairing_id": pending["pairing_id"], "pairing_secret": pending["pairing_secret"]
            }, authenticate=False)
            if result["status"] == "denied":
                raise RuntimeError("Pairing denied on the phone")
            if result["status"] == "approved":
                break
        config.update(token=result["token"], client_id=result["client_id"])
        fd = os.open(args.config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(config, stream, indent=2)
        print(f"Paired. Credential saved to {args.config}")
        return
    config = json.loads(args.config.read_text(encoding="utf-8"))
    if args.command == "mcp-stdio":
        bridge(config)
    else:
        raw = Path(args.arguments[1:]).read_text(encoding="utf-8") if args.arguments.startswith("@") else args.arguments
        arguments = json.loads(raw)
        if not isinstance(arguments, dict):
            parser.error("arguments must be a JSON object")
        print(json.dumps(post(config, "/api", {"tool": args.tool, "arguments": arguments}), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Phone Use: {error}", file=sys.stderr)
        sys.exit(1)
