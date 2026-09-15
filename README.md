# Phone Use — Android MVP

在手机本机运行的界面观察与操作服务。Android 11+，不依赖 root、ADB、电脑或模型服务。

## 已实现

- 无障碍界面树、按需 JPEG 截图、快照元素引用及窗口/版本检查。
- 节点或坐标点击、长按、路径滑动、滚动、中文文本替换/插入、返回/主页/最近任务、打开应用。
- 单一控制租约、串行队列、取消、请求去重、未知结果查询。
- HTTPS 直连 JSON API、MCP Streamable HTTP，以及 Python MCP stdio 桥接。
- 手机端配对码、Android Keystore TLS 私钥、证书指纹校验、可撤销客户端凭证。
- 通知栏暂停/恢复、断开和停止；不使用悬浮窗。

## 构建与启动

项目保留原有 Gradle 9.6、AGP 9.4、SDK 37 配置；使用 JDK 25。

```powershell
$env:JAVA_HOME = '你的 JDK 25 路径'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

1. 安装并打开应用，在系统无障碍设置中开启 **Phone Use**。部分系统对侧载应用需要先允许“受限设置”。
2. 点击“启动 HTTPS 服务”，允许通知。Android 17+ 还需要局域网权限。
3. 点击“生成配对码”，记录手机显示的地址、证书 SHA-256 指纹和六位配对码。
4. 用下述客户端配对。完成后回到想要操作的应用，保持亮屏、解锁。

服务不会随开机或进程恢复自动启动。通知被系统隐藏、服务被系统回收或无障碍授权被撤销时，需要重新检查手机端状态。

## Python 客户端

Python 3.10+，仅使用标准库。客户端会在每次 TLS 连接中先核对证书指纹，再发送配对码或凭证。不要跳过指纹核对。

```sh
python tools/phoneuse_client.py pair --url https://192.168.1.20:8443 --fingerprint "手机显示的完整指纹" --code 123456 --name laptop
python tools/phoneuse_client.py call get_capabilities
python tools/phoneuse_client.py call acquire_control '{"ttl_seconds":60}'
python tools/phoneuse_client.py call observe '{"mode":"tree"}'
```

默认凭证保存在用户目录 `.phoneuse.json`，可用全局参数 `--config 路径` 指定位置。已有文件不会覆盖。凭证可从手机端撤销，不应提交到仓库。Windows 用户也可用 `@arguments.json` 避免终端 JSON 引号问题。

执行动作示例（将租约 ID 替换为实际值）：

```sh
python tools/phoneuse_client.py call home '{"lease_id":"LEASE","request_id":"home-001","observe_after":true}'
python tools/phoneuse_client.py call get_status '{"request_id":"home-001"}'
```

所有输入动作都必须携带 `lease_id` 和唯一 `request_id`。节点操作还需携带 `observation_id` 和 `element_id`。网络错误后查询原 ID 的状态；不要换 ID 自动重放。

## MCP 接入

直接入口为 `https://手机地址:8443/mcp`，需要 `Authorization: Bearer TOKEN`。支持 MCP `2025-03-26`、`2025-06-18`、`2025-11-25` 的 POST/JSON 传输；不提供 SSE 或服务器发起的请求。2026 版协议未实现。

对于不便配置自签证书指纹的 MCP 客户端，使用已配对的 stdio 桥接：

```json
{
  "mcpServers": {
    "phone-use": {
      "command": "python",
      "args": ["/absolute/path/tools/phoneuse_client.py", "--config", "/absolute/path/phoneuse.json", "mcp-stdio"]
    }
  }
}
```

桥接使用同一条 HTTPS 接口，不执行规划或动作重试。客户端环境须能访问手机地址；不包含互联网中继。

## 文档与验证

- [接口协议、错误和保留规则](docs/api.md)
- [实机验收清单](docs/validation.md)
- `DeviceControllerTest` 覆盖租约、去重、未知结果、取消/暂停、凭证撤销与参数校验。

截图与界面树不是原子采集；实现会拒绝检测到的变化，但不能保证动态界面绝对不变。节点返回成功仅代表 Android 接受操作，手势返回完成也不等于业务成功。实际输入兼容性、受保护窗口和厂商后台限制需要实机验证。
