# Phone Use — Android MVP

在手机本机运行的界面观察与操作服务。Android 11+，不依赖 root、ADB、电脑或模型服务。

## 已实现

- 无障碍界面树、按需 JPEG 截图、快照元素引用及窗口/版本检查。
- 节点或坐标点击、长按、路径滑动、滚动、中文文本替换/插入、返回/主页/最近任务、打开应用。
- 单一控制租约、串行队列、取消、请求去重、未知结果查询。
- HTTP 直连 JSON API、MCP Streamable HTTP，以及 Python MCP stdio 桥接。
- 内置 Web 控制台、客户端发起配对请求、手机端审批、API/MCP 共用可撤销凭证。
- 通知栏暂停/恢复、断开和停止；不使用悬浮窗。

## 构建与启动

项目保留原有 Gradle 9.6、AGP 9.4、SDK 37 配置；使用 JDK 25。

```powershell
$env:JAVA_HOME = '你的 JDK 25 路径'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

1. 安装并打开应用。配对及调试状态 API 无须开启无障碍；要操作设备时，在系统无障碍设置中开启 **Phone Use**。部分系统对侧载应用需要先允许“受限设置”。
2. 点击“启动 HTTP 服务”，允许通知。Android 17+ 还需要局域网权限。
3. 在电脑浏览器打开手机显示的 `http://手机IPv4:8443/`，直接进入控制台。也可使用下述 Python 客户端。
4. 点击网页“请求配对”，手机 App 中显示客户端名称、来源 IP 和核对码。核对两端核对码，点击“授权”；网页自动领取凭证。
5. 网页中选择工具、编辑 JSON 参数并调用 API。输入动作先获取控制权；需要另一次动作时点击“生成新动作 ID”。操作前回到目标应用，保持亮屏、解锁。

网页支持工具参数定义、控制权申请/释放、JSON 结果和截图展示。凭证保存在当前标签页的 sessionStorage，可点击“导出连接凭证”保存为 `phoneuse.json`，直接供 Python/API/MCP 使用。手机端可撤销客户端。首次配对请求两分钟有效，只有在手机 App 中批准后才会签发凭证。

服务不会随开机或进程恢复自动启动。通知被系统隐藏、服务被系统回收或无障碍授权被撤销时，需要重新检查手机端状态。

### 本机转发与连接排查

当前使用明文 HTTP，端口继续使用 **8443**，保留现有转发配置。配对 token、截图和操作请求不做传输加密。

```powershell
adb forward tcp:8443 tcp:8443
curl.exe http://127.0.0.1:8443/
```

浏览器直接打开 `http://127.0.0.1:8443/`，不需要接受证书。Windows PowerShell 中 `curl` 可能是 `Invoke-WebRequest` 的别名，示例明确使用 `curl.exe`。

从 HTTPS 版本覆盖升级后，已有客户端 token 保留。已有配置将 `url` 的 `https://` 改成 `http://` 即可，`fingerprint` 字段不再使用，可删除。网页因协议不同属于新来源，需要重新配对；不需要卸载应用。

## Python 客户端

Python 3.10+，仅使用标准库。首次请求配对，手机批准后自动保存 token，后续 API/MCP 调用自动携带。

```sh
python tools/phoneuse_client.py pair --url http://192.168.1.20:8443 --name laptop
python tools/phoneuse_client.py call get_capabilities
python tools/phoneuse_client.py call acquire_control '{"ttl_seconds":60}'
python tools/phoneuse_client.py call observe '{"mode":"tree"}'
```

默认凭证保存在用户目录 `.phoneuse.json`，可用全局参数 `--config 路径` 指定位置。已有文件不会覆盖。凭证可从手机端撤销，不应提交到仓库。Windows 用户也可用 `@arguments.json` 避免终端 JSON 引号问题。

`pair` 会显示核对码并等待手机批准，批准后自动保存凭证。若使用网页导出的凭证，直接执行 `python tools/phoneuse_client.py --config phoneuse.json call get_capabilities`，无须再次配对。

执行动作示例（将租约 ID 替换为实际值）：

```sh
python tools/phoneuse_client.py call home '{"lease_id":"LEASE","request_id":"home-001","observe_after":true}'
python tools/phoneuse_client.py call get_status '{"request_id":"home-001"}'
```

所有输入动作都必须携带 `lease_id` 和唯一 `request_id`。节点操作还需携带 `observation_id` 和 `element_id`。网络错误后查询原 ID 的状态；不要换 ID 自动重放。

## MCP 接入

直接入口为 `http://手机地址:8443/mcp`，需要 `Authorization: Bearer TOKEN`。支持 MCP `2025-03-26`、`2025-06-18`、`2025-11-25` 的 POST/JSON 传输；不提供 SSE 或服务器发起的请求。2026 版协议未实现。

也可使用已配对的 stdio 桥接：

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

桥接使用同一条 HTTP 接口，不执行规划或动作重试。客户端环境须能访问手机地址；不包含互联网中继。

## 文档与验证

- [接口协议、错误和保留规则](docs/api.md)
- [实机验收清单](docs/validation.md)
- `DeviceControllerTest` 覆盖租约、去重、未知结果、取消/暂停、凭证撤销与参数校验。

截图与界面树不是原子采集；实现会拒绝检测到的变化，但不能保证动态界面绝对不变。节点返回成功仅代表 Android 接受操作，手势返回完成也不等于业务成功。实际输入兼容性、受保护窗口和厂商后台限制需要实机验证。
