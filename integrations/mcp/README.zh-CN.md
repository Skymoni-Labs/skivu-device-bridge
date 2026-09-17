# Phone Use MCP

[English](README.md) | **简体中文**

本目录提供离线 MCP 分发说明。在线用户可直接使用 `npx -y --package=github:nidhoggfgg/PhoneUse phone-use`，或追加 `print-config --host codex` / `print-config` 生成配置，无需下载 ZIP。在线安装需要 Node.js、Git 和 Python 3.10+。完整说明见 [AI 接入指南](https://github.com/nidhoggfgg/PhoneUse/blob/HEAD/docs/integrations.zh-CN.md)。

在仓库根目录运行 `python tools/export_integrations.py`，将生成的 `dist/integrations/phoneuse-mcp.zip` 解压到固定位置；包内桥接脚本与 `configure.py` 同级。离线运行只需 Python 3.10+，无第三方依赖。在解压目录执行：

```sh
python configure.py --host json --output phoneuse-mcp.json
```

将生成文件的 `mcpServers.phone-use` 合并到支持此格式的 AI 客户端 MCP 配置中（例如 Claude Desktop 的开发者配置）。保留其他服务配置。Codex 使用：

```sh
python configure.py --host codex --output phoneuse-codex.toml
```

将生成的 `[mcp_servers.phone-use]` 段合并到 `~/.codex/config.toml`；已存在同名段时替换该段，不要重复追加。生成器记录当前 Python 和桥接脚本的绝对路径，移动文件或更换电脑后重新生成。`--output` 拒绝覆盖已有文件；省略时输出到终端，不会自动修改 AI 设置。

可选参数 `--url http://192.168.1.20:8443` 提供初始手机地址，`--name "My AI"` 设置配对名称，`--config /absolute/path/registry.json` 隔离凭证存储。不传地址时，由 AI 在 phoneuse_connect 中提供。

手机启动 Phone Use 服务并开启无障碍，AI 客户端重新加载 MCP 后，让 AI：

> 用 Phone Use 连接 http://我的手机IPv4:8443，显示配对核对码，等我在手机批准后观察当前界面。

AI 调用 `phoneuse_connect({"address":"http://手机IPv4:8443"})`，首次会返回核对码。手机批准后，AI 再调用 `phoneuse_connect({"device_id":"返回的设备ID"})`。之后每次设备操作必须带 `device_id`。手机离线也能初始化 MCP；重连不需要删除配对。

支持 MCP 2025-03-26、2025-06-18、2025-11-25，stdio 逐行 JSON。桥接动态发现手机工具并返回截图图片块；宿主未刷新工具列表时可用 phoneuse_call。长观察可达 90 秒，宿主工具超时建议至少 120 秒，长批量需更大预算或拆分调用。

客户端机器必须能访问手机 IPv4，当前使用明文 HTTP，仅在可信网络使用。云端 AI 若不能运行本地程序或访问局域网，不能直接使用此包。凭证自动保存到用户目录 `.phoneuse.json`，不要分发该文件。无需安装 Android SDK、ADB 或 Python 依赖。

配置格式参考：[MCP 本地服务接入](https://modelcontextprotocol.io/docs/develop/connect-local-servers)、[Codex MCP](https://developers.openai.com/codex/mcp)。
