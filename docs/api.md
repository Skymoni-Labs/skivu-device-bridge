# MVP API 协议 v0.1

## 接入与身份

监听 IPv4 `0.0.0.0:8443`，仅 TLS 1.2/1.3。私钥保存在 Android Keystore，应用自动生成自签证书。客户端通过手机上显示的 SHA-256 指纹建立信任；示例 Python 客户端在发送任何请求前校验指纹。

`POST /pair`：`{"code":"123456","client_name":"laptop"}`。

成功返回 `client_id`、仅返回一次的 `token` 和 `certificate_sha256`。手机手动打开配对窗口，两分钟有效，五次尝试后关闭，一次成功即作废；最多保留 16 个客户端。应用仅持久化 token 的 SHA-256 摘要。关闭服务关闭配对窗口，已配对凭证仍保留。手机可撤销单个凭证，当前控制者被撤销时排队输入也失效。备份和设备迁移不包含凭证。

其他端点必须提供 `Authorization: Bearer TOKEN`。所有 POST 请求使用 `Content-Type: application/json`；支持 Content-Length 和 chunked，正文最多 64 KiB。拒绝浏览器 Origin，不提供 CORS。每个连接只处理一个 HTTP 请求并关闭；不会因为 HTTP 响应完成而撤销租约。连接池和等待队列有上限。

## 普通 API

`POST /api` 正文：

```json
{"tool":"observe","arguments":{"mode":"both","max_dimension":1280}}
```

响应直接返回工具结果。设备错误使用 HTTP 200 和 `state/error`；鉴权、HTTP 格式错误使用对应 HTTP 错误码。所有动作参数名称和类型由 API/MCP 共用的 ToolCatalog 校验；未知字段拒绝。

| 工具 | 参数 |
| --- | --- |
| get_capabilities | 无 |
| get_status | 可选 request_id，只能查询本客户端结果；lease_id 仅返回给当前控制者 |
| acquire_control | ttl_seconds：5–300，默认 60；续租必须提供当前 lease_id |
| release_control | lease_id；撤销当前租约及排队输入 |
| cancel | lease_id；可选 request_id，不传则取消本客户端所有未完成输入 |
| observe | mode：tree / screenshot / both（默认）；max_dimension：320–2048，默认 1280 |
| list_apps | 无；仅列出系统允许发现且带启动入口的应用 |
| wait_for_change | ui_version；timeout_ms：1–10000，默认 5000 |
| tap | element_id，或 x/y |
| long_press | element_id，或 x/y；坐标动作 duration_ms：500–5000，默认 600 |
| swipe | points：2–64 个 {x,y}；duration_ms：1–5000，默认 600 |
| scroll | element_id 或 region:{left,top,right,bottom}；direction |
| input_text | observation_id、element_id、text、mode:replace/insert |
| back / home / recents | 无额外参数 |
| launch_app | package_name |

### 动作公共字段

所有 `tap` 至 `launch_app` 输入动作必填 `lease_id`、`request_id`。`request_id` 最多 128 字符。可选：

- `observation_id`：用于检查界面是否过期；指定元素时必填。
- `expected_window_id`：预期的活动窗口。
- `observe_after`：动作后附带新观察，默认 false。

节点与坐标不可混用。节点操作只使用节点明确支持的操作，不自动转成坐标点击。节点滚动支持 forward/backward；区域滚动支持 forward/down、backward/up、left/right，方向指内容浏览方向。文本最多 10000 字符，允许空串清空。

`replace` 使用 ACTION_SET_TEXT 替换全部内容。`insert` 读取现有文本和 selection，将选区替换为给定文本，再通过 ACTION_SET_TEXT 写入组合结果，并尝试恢复插入位置的光标。密码、不可读文本、未知选区或无法设置选区的字段会返回 ACTION_UNSUPPORTED。插入后 `cursor_restored` 表示 Android 是否接受光标恢复；null 表示无须恢复或取消后未尝试。它不是完整输入法，富文本、特殊编辑器和应用行为需要实机验证。

## 观察与坐标

观察包含 observation_id、ui_version、captured_at_ms（Unix 毫秒）、window_id、package_name、display、tree、screenshot。

- 坐标是默认显示器当前旋转方向下的**物理像素**，左上角为 (0,0)，包含系统栏和键盘区域。不是 dp，也不是裁剪后的应用坐标。
- JPEG 默认长边不超过 1280，质量 80。返回宽高、scale_x/scale_y、offset_x/offset_y。
- 映射：`screen_x = image_x * scale_x + offset_x`，y 同理。动作须传屏幕坐标。
- 旋转值为 0/90/180/270。旋转或几何变化会使关联快照失效。
- tree.elements 包含文本、描述、view_id、class、bounds、可见/可编辑状态、支持的动作以及 element_id。未命名 Android 动作以十进制 ID 表示。
- Phone Use 自身的配对/授权界面不返回树内容，也不接受远程点击或文本操作（LOCAL_CONTROL_UI）；可通过 home/back/recents/launch_app 离开。仅支持默认显示器，其他显示器返回 DISPLAY_UNSUPPORTED。
- 元素 ID 是快照内部路径，最多保留八份快照，每份最长 30 秒；不能当作永久 ID。
- 最多采集 1500 个节点，深度最多 40，单个文本/描述截断至 1024 字符。password 文本不返回。节点指纹使用完整字段的摘要，不依赖截断文本。
- 截图失败时 `screenshot.available=false`，附带原因和 Android 错误码；与 `tree.available=false` 分别报告。未请求的部分为 null。
- 采集前后检查界面事件版本、窗口、应用和显示几何；观察中途变化返回 OBSERVATION_INCONSISTENT，不自动重试。操作前再次检查，并重新解析、刷新、验证节点指纹。

界面事件异步到达，以上检查不能消除所有竞争条件。屏幕内容可在最终检查与系统动作之间变化。

## 执行、租约与去重

同一时刻一个客户端持有输入租约；动作和即时读取共用单线程设备队列（最多排队 32 个），变化等待不占设备队列。只读请求也必须鉴权，暂停时观察/应用列表/等待被拒绝，状态与能力仍可查询。

租约采用单调时钟，5–300 秒；请求本身不会自动续租。相同客户端续期必须提交 lease_id，已过期的 ID 不能复活。进程和服务重启不恢复租约、不重放请求。

动作最长同步等待 15 秒；尚未完成时返回 accepted（排队）或 executing（执行中）。继续查询 get_status，不把执行中当作成功。终态：

```json
{
  "state":"executed",
  "request_id":"tap-001",
  "execution":{"confirmation":"android_accepted","business_success":null},
  "observation":{}
}
```

- executed：Android 接受节点/导航/Intent 操作，或报告手势完成。不会声称业务成功。
- failed：校验或系统明确拒绝，含 error.code/message。
- cancelled：尚未执行的输入被取消或控制已失效。
- unknown：手势回调超时、部分手势取消或无法判定执行结果的异常。先重新观察，不自动重试。

执行成功但后续观察失败仍返回 executed；observation 单独包含错误。已经注入的动作不会因通知暂停而被伪装成撤销。cancel 返回 in_flight_may_complete；手势无法保证立即终止。

去重作用域为**当前服务进程的 client_id + request_id**，相同 ID 的参数和工具必须完全一致（对象键顺序忽略）。结果保留十分钟，最多 256 个 ID，并有约 800 万字符的结果缓存预算；预算满时拒绝新动作，不提前丢弃未到期的 ID。快照/截图/动作参数只在内存保存，服务停止或进程死亡后不恢复。应用不写操作日志或截图文件。

重发相同 ID 返回已有结果，不再次输入；不同参数返回 REQUEST_ID_CONFLICT。十分钟之后或进程重启后不能依据去重保证安全重放。客户端必须自行保留不确定请求，并重新观察。

通知“断开”撤销当前租约，已配对凭证仍可重新申请租约；要禁止某客户端再次连接，请撤销它的凭证。“暂停”撤销当前租约及排队输入，需要手机端恢复后重新申请。停止关闭 HTTPS 入口，并使所有尚未执行输入失效。

## 主要错误码

INVALID_ARGUMENT、UNKNOWN_TOOL、UNAUTHORIZED、CONTROL_BUSY、LEASE_EXPIRED、PAUSED、STOPPED、CANCELLED、BUSY、REQUEST_ID_CONFLICT、REQUEST_NOT_FOUND、ACCESSIBILITY_UNAVAILABLE、SCREEN_OFF、DEVICE_LOCKED、NO_ACTIVE_WINDOW、OBSERVATION_INCONSISTENT、STALE_OBSERVATION、STALE_ELEMENT、WINDOW_MISMATCH、ELEMENT_UNAVAILABLE、ACTION_UNSUPPORTED、ACTION_REJECTED、APP_UNAVAILABLE、RESULT_UNKNOWN。

## MCP

`POST /mcp` 使用 JSON-RPC 2.0。支持 initialize、ping、tools/list、tools/call、无响应的通知；不支持批处理。每个工具使用同一套执行代码。notifications/initialized 返回 HTTP 202；通知永远不会触发工具动作。

这是无服务端 MCP 会话的 Streamable HTTP POST/JSON 模式，不返回 Mcp-Session-Id。GET/DELETE 返回 405，无 SSE 推送。MCP 版本为 2025-03-26、2025-06-18 或 2025-11-25；缺省头兼容早期版本。2026-07-28 的无 initialize 协议暂不支持。

tools/call 结果为 text 内容块；观察中的图片另作为 image 内容块。文本保留截图映射信息，去除重复的 data_base64。工具错误通过 isError 表示。MCP JSON-RPC 的 id 不是动作去重 ID；仍须传 arguments.request_id。用 cancel 工具取消动作；通用 JSON-RPC 取消通知不直接撤销设备输入。

## 实现参考

- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 17 局域网权限](https://developer.android.com/about/versions/17/behavior-changes-17)
- [MCP 2025-03-26 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
