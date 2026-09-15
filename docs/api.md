# MVP API 协议 v0.3

## 接入与身份

监听 IPv4 `0.0.0.0:8443`，使用明文 HTTP，不生成证书、不要求指纹校验。端口沿用 8443，所有入口改用 `http://`。配对授权、Bearer token 和撤销机制保留。

### 配对请求 → 手机批准 → 领取凭证

旧的 `POST /pair` 和配对码输入流程已移除；已有客户端凭证仍有效。

1. `POST /pair/request`，正文 `{"client_name":"laptop"}`，无须 Bearer token。HTTP 202 返回：

   ```json
   {"pairing_id":"UUID","pairing_secret":"随机秘密","verification_code":"123456","status":"pending","expires_in":120,"poll_interval":2}
   ```

2. 手机 App 显示客户端自报名称、实际来源 IP、核对码和剩余时间。用户核对两端核对码，选择授权或拒绝。没有远程批准接口；通知栏提示点击打开 App。
3. 客户端每两秒 `POST /pair/status`，正文 `{"pairing_id":"UUID","pairing_secret":"随机秘密"}`。HTTP 200 返回 `{"status":"pending"}` 或 `{"status":"denied"}`。批准后返回：

   ```json
   {"status":"approved","client_id":"UUID","token":"TOKEN","token_type":"Bearer","api_path":"/api","mcp_path":"/mcp"}
   ```

4. 此 token 可同时用于 API 和 MCP 的 `Authorization: Bearer TOKEN`。

请求从创建起两分钟过期（批准不延长），过期或服务重启返回 `PAIRING_EXPIRED`。`pairing_secret` 是领取凭证的秘密，不能用核对码代替，也不能写入 URL。为允许丢包后的重复领取，同一请求在过期前返回相同凭证，不会重复签发。仅 token 摘要持久化；完整配对响应仅暂存在内存。关闭服务清空请求，已配对凭证仍保留。

最多保留 16 个请求和 16 个客户端；新请求全局间隔至少五秒，超限 HTTP 429 / `PAIRING_BUSY`。错误 secret 返回 `PAIRING_DENIED`，参数错误返回 `INVALID_ARGUMENT`。手机可撤销单个凭证，撤销也会清除该请求暂存的 token，当前控制者被撤销时排队输入失效。备份和设备迁移不包含凭证。

### Web 入口与传输

`GET /` 是不含凭证和设备数据的公开页面，允许从 App/其他页面跳转（包括 `Sec-Fetch-Site: cross-site`），仍校验 Host；脚本、配对和 API 请求继续使用同源策略。页面禁止被嵌入 frame。

`GET /` 提供内置 Web 控制台，`GET /app.js` 提供脚本，无须认证。`GET /api/tools` 需 Bearer token，返回 `{"tools":[...]}`，与 MCP `tools/list` 共用完整参数定义。

API/MCP 端点必须提供 `Authorization: Bearer TOKEN`。所有 POST 请求使用 `Content-Type: application/json`；支持 Content-Length 和 chunked，正文最多 64 KiB。仅允许本服务同源浏览器请求，不提供跨域 CORS；Host 必须为连接所用手机 IPv4 和端口（例如 `192.168.1.20:8443`），Origin 若存在必须为相应 `http://IP:PORT`。域名或反向代理接入暂不支持。无 Origin 的原生客户端仍可使用。

Web 凭证只保存在当前标签页 sessionStorage；导出的 JSON 包含 `url/token/client_id`，可直接交给 Python 客户端和 MCP stdio 桥接。退出网页仅清除本页凭证，撤销授权需在手机执行。

每个连接只处理一个 HTTP 请求并关闭；不会因为 HTTP 响应完成而撤销租约。连接池和等待队列有上限。

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

通知“断开”撤销当前租约，已配对凭证仍可重新申请租约；要禁止某客户端再次连接，请撤销它的凭证。“暂停”撤销当前租约及排队输入，需要手机端恢复后重新申请。停止关闭 HTTP 入口，并使所有尚未执行输入失效。

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
