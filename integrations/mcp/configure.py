#!/usr/bin/env python3
"""Print a host configuration for the adjacent Phone Use stdio bridge."""
import argparse
import ipaddress
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit


def phone_address(value):
    value = value if "://" in value else "https://" + value
    url = urlsplit(value)
    if (url.scheme != "https" or not url.hostname or url.username is not None
            or url.password is not None or url.path not in ("", "/") or url.query or url.fragment):
        raise argparse.ArgumentTypeError("Use https://PHONE_IPV4:PORT without credentials or a path")
    try:
        ipaddress.IPv4Address(url.hostname)
        port = url.port if url.port is not None else 8443
        if not 1 <= port <= 65535:
            raise ValueError("Invalid port")
    except ValueError as error:
        raise argparse.ArgumentTypeError("Phone Use requires an IPv4 address and a valid port") from error
    return f"https://{url.hostname}:{port}"


def configuration(host, client, python, address=None, name="Phone Use AI", config=None):
    args = [str(client.resolve())]
    if config is not None:
        args += ["--config", str(config.expanduser().resolve())]
    args += ["mcp-stdio", "--name", name]
    if address:
        args += ["--url", address]
    # JSON strings/arrays are also valid TOML here; no shell interpolation.
    quote = lambda value: json.dumps(value, ensure_ascii=False)
    if host == "codex":
        return (f'[mcp_servers.phone-use]\ncommand = {quote(python)}\n'
                f'args = {quote(args)}\ntool_timeout_sec = 120\n')
    return json.dumps({"mcpServers": {"phone-use": {"command": python, "args": args}}},
                      ensure_ascii=False, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", choices=("json", "codex"), default="json")
    parser.add_argument("--url", type=phone_address, help="Optional phone IPv4 address")
    parser.add_argument("--name", default="Phone Use AI")
    parser.add_argument("--config", type=Path, help="Optional separate credential registry")
    parser.add_argument("--output", type=Path, help="Write a new snippet file; never overwrite an existing file")
    args = parser.parse_args()
    client = Path(__file__).resolve().with_name("phoneuse_client.py")
    if not client.is_file():
        parser.error("Use the exported MCP package: python tools/export_integrations.py")
    content = configuration(args.host, client, sys.executable, args.url, args.name, args.config)
    if args.output:
        try:
            with args.output.expanduser().open("x", encoding="utf-8", newline="\n") as stream:
                stream.write(content)
        except OSError as error:
            parser.error(str(error))
    else:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8")
        print(content, end="")


if __name__ == "__main__":
    main()
