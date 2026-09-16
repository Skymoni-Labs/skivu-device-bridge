# Phone Use — Android MVP

在手机本机运行的界面观察与操作服务。Android 11+，不依赖 root、ADB、电脑或模型服务。

## 已实现

- 默认精简无障碍树、按需完整树/JPEG 截图、有上限的稳定重采，以及目标/容器/窗口校验。
- 节点或坐标点击、长按、路径滑动、滚动、中文文本替换/插入、返回/主页/最近任务、打开应用。
- 授权客户端共用串行队列、取消、请求去重、未知结果查询。
- HTTP 直连 JSON API、MCP Streamable HTTP，以及 Python MCP stdio 桥接。
- 一个桥接管理多设备，安装级身份、设备命名、明确 device_id 路由和独立设备并发。
- 按客户端分配快照额度，错误原因诊断、可选限时测试占用，以及批量结果汇总。
- 内置 API 调用页面：接口列表与搜索、参数表单、JSON 编辑、响应查看，以及 API/MCP 连接信息。
- 手机审批后客户端可直接调用 API；授权持续有效，可在手机端暂停服务或撤销客户端。
- 通知栏暂停/恢复和停止；不使用悬浮窗。

## 构建与启动

项目使用 Gradle 9.7.1、AGP 9.4、SDK 37 配置；使用 JDK 25。

```powershell
$env:JAVA_HOME = '你的 JDK 25 路径'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

1. 安装并打开应用。配对及调试状态 API 无须开启无障碍；要操作设备时，在系统无障碍设置中开启 **Phone Use**。部分系统对侧载应用需要先允许“受限设置”。
2. 点击“启动 HTTP 服务”，允许通知。Android 17+ 还需要局域网权限。
3. 在电脑浏览器打开手机显示的 `http://手机IPv4:8443/`，直接进入控制台。也可使用下述 Python 客户端。
4. 点击网页“请求配对”，手机 App 中显示客户端名称、来源 IP 和核对码。核对两端核对码，点击“授权”；网页自动领取凭证并加载接口列表。
5. 从 API 列表选择接口，填写参数或直接编辑 JSON，点击“调用 API”查看响应。输入动作前保持手机亮屏、解锁；重试沿用原 request_id，新动作点击“生成新动作 ID”。

网页面向有一定技术基础的用户，直接展示 API 列表、参数与响应。截图仅在主动调用 observe 后作为响应附件展示。首次配对在手机批准，之后浏览器自动保存并携带授权；关闭标签页、重启浏览器或 App 后均可继续连接。页面和工具响应不显示长期 token，也无需复制或导出凭证。旧版仍打开的标签页会自动迁移已有凭证。手机端“解除配对”或网页“解除此浏览器配对”可撤销授权。

App 记住服务启用与暂停状态。重新打开 App 会恢复之前启用的服务；系统回收进程时允许恢复前台服务。主动停止后不会因重开 App 自动启动。系统强行停止、手机重启或厂商后台限制可能需要重新打开 App，但已完成的配对仍保留。不自动重放任何输入动作。

配对按客户端保存：网页与 MCP 各批准一次。清除浏览器 Cookie、删除客户端配置、卸载/清除手机 App 数据或主动解除配对后需要重新授权。使用固定手机地址；浏览器换地址属于另一个站点。首次尚未完成的配对请求两分钟有效，服务重启会清除未完成请求。

### 本机转发与连接排查

当前使用明文 HTTP，端口继续使用 **8443**，保留现有转发配置。配对 token、截图和操作请求不做传输加密。

```powershell
adb forward tcp:8443 tcp:8443
curl.exe http://127.0.0.1:8443/
```

浏览器直接打开 `http://127.0.0.1:8443/`，不需要接受证书。Windows PowerShell 中 `curl` 可能是 `Invoke-WebRequest` 的别名，示例明确使用 `curl.exe`。

从 HTTPS 版本覆盖升级后，已有客户端 token 保留。已有配置将 `url` 的 `https://` 改成 `http://` 即可，`fingerprint` 字段不再使用，可删除。网页因协议不同属于新来源，需要重新配对；不需要卸载应用。

## Python 客户端

Python 3.10+，仅使用标准库。首次请求配对，手机批准后自动保存 token，后续 API/MCP 调用自动携带。输入输出默认 UTF-8。

```sh
python tools/phoneuse_client.py pair --url http://192.168.1.20:8443 --name laptop
python tools/phoneuse_client.py list-devices
python tools/phoneuse_client.py call get_capabilities --device-id DEVICE_ID
python tools/phoneuse_client.py call observe '{"mode":"tree"}' --device-id DEVICE_ID
python tools/phoneuse_client.py call observe --device-id DEVICE_ID --screenshot-out screenshot.jpg
python tools/phoneuse_client.py call observe '{"mode":"tree"}' --device-id DEVICE_ID --full-tree
```

默认连接数据保存在用户目录 `.phoneuse.json`，可用全局参数 `--config 路径` 指定位置。同一配置管理多台设备；每次调用都指定配对/列表返回的 DEVICE_ID。客户端负责读写，用户与 AI 无需查看凭证文件。再次 `pair --url 新地址 --device-id DEVICE_ID` 可安全更新地址；桥接发送凭证前核验安装身份，连到其他设备时拒绝复用旧凭证。凭证可从手机端撤销，不应提交到仓库。Windows 用户也可用 `@arguments.json` 避免终端 JSON 引号问题。

`pair` 首次显示核对码并等待手机批准，之后重复执行只检查已保存的连接，不会重复配对。等待中的请求也会保存，客户端重开可继续领取。旧版配置若未保存安装级 device_id，首次升级需要重新在手机批准，避免仅按地址复用授权。新配对完成后恢复自动连接。

CLI 默认从 JSON 中省略截图 base64，保留尺寸及坐标映射；`--screenshot-out` 保存图片，`--include-image-data` 可显式保留 base64。MCP 仍以图片内容块返回截图。精简树默认省略 enabled:true、visible:true 和为 false 的状态字段，完整树使用 `compact:false` 或 CLI 的 `--full-tree`。

执行动作示例：

```sh
python tools/phoneuse_client.py call home '{"request_id":"home-001","observe_after":true}' --device-id DEVICE_ID
python tools/phoneuse_client.py call get_status '{"request_id":"home-001"}' --device-id DEVICE_ID
```

所有输入动作都必须携带唯一 `request_id`。节点操作携带 `observation_id` 和 `element_id`，坐标点击/滑动/区域滚动也必须关联 `observation_id`。网络错误后查询原 ID 的状态；不要换 ID 自动重放。动作成功但附带观察失败时保留 `state:executed`、`action_executed:true`，失败原因单独放在 observation 中。

连续测试可调用 `acquire_device` 取得有期限的 lease_id，并在后续动作中携带它，最后调用 `release_device`。没有占用时照常直接操作；手机本地暂停/停止始终有效。批量调用可用 `batch @calls.json` 或 MCP 的 `phoneuse_batch`；每项显式提供 device_id、tool、arguments，同设备顺序执行、不同设备并行，分别汇总动作错误和附带观察错误。默认停止该设备在失败或未完成步骤之后的调用；`--summary-only` 只显示汇总。批量接口不做任务规划或动作重试。

## MCP 接入

AI 推荐使用 stdio 桥接，只配置手机地址，无需预先配对或填写 token：

```json
{
  "mcpServers": {
    "phone-use": {
      "command": "python",
      "args": ["/absolute/path/tools/phoneuse_client.py", "mcp-stdio", "--url", "http://192.168.1.20:8443", "--name", "My AI"]
    }
  }
}
```

首次让 AI 调用 `phoneuse_connect({"address":"http://手机地址:8443"})`，手机显示核对码；手机批准后再次调用该工具并指定返回的 device_id 即可完成连接。工具返回明确的设备身份、配对状态和核对码，不返回 token 或领取秘密。批准后通过 `notifications/tools/list_changed` 更新设备工具列表。以后调用 `observe`、`tap` 等工具都必须带 device_id，桥接自动使用该设备的授权；已绑定身份的配对可跨桥接/手机服务重启保留。

使用 `phoneuse_list_devices` 查看设备名称、型号、Android 版本、地址、模拟器标记和在线状态。手机暂时离线时 MCP 仍可初始化且保留原配对；其他设备调用继续独立执行。服务恢复后按 device_id 调用 `phoneuse_connect` 刷新工具列表。若 AI 宿主未刷新列表，也可通过 `phoneuse_call` 传入 `device_id`、`tool` 和 `arguments` 调用设备接口。客户端环境须能访问手机地址；不包含互联网中继。

底层直连入口仍为 `http://手机地址:8443/mcp`，供自行管理 Bearer 凭证的程序使用。支持 MCP `2025-03-26`、`2025-06-18`、`2025-11-25` 的 POST/JSON 传输；不提供 SSE。2026 版协议未实现。

## 文档与验证

- [接口协议、错误和保留规则](docs/api.md)
- [实机验收清单](docs/validation.md)
- `DeviceControllerTest` 覆盖授权、串行队列、去重、未知结果、取消/暂停、凭证撤销与参数校验。

截图与界面树不是原子采集；实现会拒绝检测到的变化，但不能保证动态界面绝对不变。节点返回成功仅代表 Android 接受操作，手势返回完成也不等于业务成功。实际输入兼容性、受保护窗口和厂商后台限制需要实机验证。
