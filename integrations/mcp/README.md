# Phone Use MCP

**English** | [简体中文](README.zh-CN.md)

This directory provides the offline MCP distribution. Online installation can use `npx -y --package=github:nidhoggfgg/PhoneUse phone-use`; append `print-config` or `print-config --host codex` to generate host settings. Online setup needs Node.js, Git, and Python 3.10+. See the [full integration guide](https://github.com/nidhoggfgg/PhoneUse/blob/HEAD/docs/integrations.md).

## Offline setup

Export with `python tools/export_integrations.py` from the repository root, then extract `dist/integrations/phoneuse-mcp.zip` to a permanent directory. The archive includes the bridge script beside `configure.py`. Python 3.10+ is the only offline runtime dependency; no third-party Python packages, Android SDK, or ADB are required on the client.

From the extracted directory:

```sh
python configure.py --host json --output phoneuse-mcp.json
```

Merge the generated `mcpServers.phone-use` entry into a compatible host's MCP configuration, preserving other services. For Codex:

```sh
python configure.py --host codex --output phoneuse-codex.toml
```

Merge `[mcp_servers.phone-use]` into `~/.codex/config.toml`; replace an existing section with that name rather than appending duplicates. The generator records absolute paths to the current interpreter and bridge. Regenerate after moving files or changing machines. `--output` refuses to overwrite an existing file; omitting it prints the snippet and does not change host settings.

Optional arguments: `--url https://192.168.1.20:8443` supplies an initial address; `--name "My AI"` sets the pairing name; `--config /absolute/path/registry.json` separates stored credentials. Without an address, the agent supplies it through `phoneuse_connect`.

## Connect

Start Phone Use on the phone and enable accessibility for device interaction. Reload MCP in the AI host, then ask:

> Connect to https://192.168.1.20:8443 with Phone Use. Show the verification code, wait for my approval on the phone, then observe the current screen.

The agent calls `phoneuse_connect({"address":"https://192.168.1.20:8443"})`. After the user explicitly confirms the code match and approves on the phone, it calls `phoneuse_connect({"device_id":"RETURNED_DEVICE_ID","confirm_pairing":true})` again to claim authorization. Each device operation needs an explicit `device_id`. MCP can initialize offline; reconnection does not require deleting pairing.

Supported MCP versions: 2025-03-26, 2025-06-18, 2025-11-25, using line-delimited JSON over stdio. The bridge discovers phone tools and returns native screenshot image blocks. Use `phoneuse_call` if a host does not refresh tools. Allow at least 120 seconds for tool calls; observations may take up to the 90-second HTTP read limit, and long batches need more time or splitting.

The client must reach the phone IPv4. Transport is pinned HTTPS. First compare all eight digits with the phone, approve there, and confirm the match to the AI; it then calls `phoneuse_connect` with `confirm_pairing:true`. Cloud hosts without local execution or LAN access cannot use this package directly. Credentials are stored automatically in `~/.phoneuse.json`; never distribute that file.

Configuration references: [MCP local servers](https://modelcontextprotocol.io/docs/develop/connect-local-servers), [Codex MCP](https://developers.openai.com/codex/mcp).
