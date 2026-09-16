# MVP API 协议 v0.4

## 接入与身份

监听 IPv4 `0.0.0.0:8443`，使用明文 HTTP，不生成证书、不要求指纹校验。端口沿用 8443，所有入口改用 `http://`。配对授权、Bearer token 和撤销机制保留。

### 配对请求 → 手机批准 → 领取凭证

以下是原生客户端内部协议。AI 使用 MCP stdio 的 `phoneuse_connect`，无需直接处理领取秘密或 token。已完成的授权无会话期限，App/服务重启后仍有效；关闭连接不解除配对。

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

`GET /` 提供内置 Web 控制台，`GET /app.js` 提供脚本，无须认证。`GET /api/tools` 需已配对客户端认证，返回 `{"tools":[...]}`，与 MCP `tools/list` 共用完整参数定义。

API/MCP 端点接受原生客户端的 `Authorization: Bearer TOKEN` 或浏览器自动携带的配对 Cookie。两者同时提供时以 Authorization 为准。所有 POST 请求使用 `Content-Type: application/json`；支持 Content-Length 和 chunked，正文最多 64 KiB。仅允许本服务同源浏览器请求，不提供跨域 CORS；Host 必须为连接所用手机 IPv4 和端口（例如 `192.168.1.20:8443`），Origin 若存在必须为相应 `http://IP:PORT`。域名或反向代理接入暂不支持。无 Origin 的原生客户端仍可使用。

浏览器使用 `POST /browser/pair/request` 和 `POST /browser/pair/status`，请求参数与原生配对接口一致。批准响应不含 `token/token_type`，改为 `Set-Cookie: phoneuse_client_PORT=...; Path=/; HttpOnly; SameSite=Strict; Max-Age=34560000`。Cookie 最长保存 400 天，每次恢复会话时续期；浏览器自身的清理策略仍可能提前移除。当前使用 HTTP，因此不设置 Secure。页面 JavaScript 不读取长期凭证，关闭标签页或重启后由浏览器自动携带。

- `GET /browser/session`：HTTP 200 返回 `{"paired":true,"client_id":"..."}` 或 `{"paired":false,"client_id":null}`，验证已有授权并续期/清除 Cookie，不签发新身份。
- `POST /browser/session`：携带已有 Bearer 或 Cookie，正文 `{}`，换成同一身份的持久 Cookie。用于旧版打开标签页的自动迁移，成功后删除 sessionStorage 中的旧 token。
- `POST /browser/forget`：需认证，正文 `{}`；撤销该客户端、清除 Cookie 与配对暂存响应，使排队动作失效。关闭页面本身不调用此接口。

页面启动、重新获得焦点和网络恢复时检查连接；启动连接失败每五秒重试读取会话，保留配对。任何输入动作都不自动重试。网页不再导出长期凭证，旧导出配置仍可由 Python 使用。

### AI 的持久连接

`python tools/phoneuse_client.py mcp-stdio --url http://PHONE:8443` 在未配对或手机离线时也可处理 initialize。一个桥接管理多台设备；`phoneuse_connect` 按地址添加或按 `device_id` 重连，`phoneuse_list_devices` 返回安装级 ID、名称、型号、Android 版本、地址、在线状态和模拟器标记。后续每次设备调用必须显式指定 `device_id`，不维护共享“当前设备”。`phoneuse_call` 是未刷新列表宿主的备用调用入口；配对后也直接提供手机工具。

`GET /identity` 无需凭证，返回 `device_id/name/model/android_version/is_emulator/service_instance_id`。App 安装级 UUID 与地址分离，名称可在手机本地修改；身份与配对偏好设置排除备份/设备迁移。桥接在发送认证请求前先验证公开设备 ID，地址对应其他安装时不发送旧 token。换地址重连也必须匹配已保存身份。旧配置缺少可信设备 ID 时需要重新在手机批准，不能只凭 IP 迁移凭证。此预检用于防止意外连错设备；当前明文 HTTP 不提供对恶意网络端点的身份认证。

配对请求/领取结果含设备身份；未完成请求也持久保存，批准后原子保存凭证。MCP 结果不返回 token 或领取秘密。不同设备独立连接、锁与执行队列，可并行；同设备调用串行。单台离线不阻塞其他设备。观察 ID 同时绑定客户端、设备服务运行实例及无障碍服务实例，跨设备、跨客户端及重启后的引用拒绝执行。

配置默认位于用户目录 `.phoneuse.json`，由桥接读写，不加入 MCP 返回；未完成配对也保存到该文件以便桥接重启后继续。401 清除已撤销凭证并提示显式调用连接工具；网络错误不删除凭证、不发起配对、不重放动作。手机仍未恢复时初始化与连接工具可用。此机制不隔离与桥接使用同一系统账户的文件访问权限。

每个连接只处理一个 HTTP 请求并关闭；HTTP 连接关闭不影响配对授权。连接池和等待队列有上限。

## 普通 API

`POST /api` 正文：

```json
{"tool":"observe","arguments":{"mode":"both","max_dimension":1280}}
```

响应直接返回工具结果。设备错误使用 HTTP 200 和 `state/error`；鉴权、HTTP 格式错误使用对应 HTTP 错误码。所有动作参数名称和类型由 API/MCP 共用的 ToolCatalog 校验；未知字段拒绝。

| 工具 | 参数 |
| --- | --- |
| get_capabilities | 无 |
| get_status | 可选 request_id，只能查询本客户端结果；返回 paused、stopped、executing_request_id |
| cancel | 可选 request_id，不传则取消本客户端所有未完成输入 |
| observe | mode：tree / screenshot / both（默认）；max_dimension：320–2048，默认 1280；compact：默认 true；stable_wait_ms：0–2000，默认 600；max_attempts：1–3，默认 3 |
| acquire_device | 可选 ttl_ms：1000–300000，默认 60000；续期必须携带当前 lease_id |
| release_device | lease_id：释放本客户端的测试占用 |
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

所有 `tap` 至 `launch_app` 输入动作必填 `request_id`。`request_id` 最多 128 字符。可选：

- `observation_id`：用于检查界面是否过期；节点及坐标动作（tap/long_press/swipe/scroll/input_text）必填，导航/启动应用可选。
- `expected_window_id`：预期的活动窗口。
- `observe_after`：动作后附带新观察，默认 false。
- `lease_id`：可选的开发者测试占用；设备有占用时必须匹配占用客户端和 ID。没有占用时可直接执行动作。

所有工具可携带 `device_id`、`service_instance_id`，由 HTTP 入口在分发前核验。桥接自动添加这两个保护字段，直连 API 可自行指定。所有工具结果及嵌套动作/观察结果包含 `device_id` 和 `service_instance_id`。地址不匹配返回 `DEVICE_MISMATCH`，服务实例不匹配返回 `STALE_OBSERVATION`，均明确 `action_executed:false`。

节点与坐标不可混用。节点操作只使用节点明确支持的操作，不自动转成坐标点击。节点滚动支持 forward/backward；区域滚动支持 forward/down、backward/up、left/right，方向指内容浏览方向。文本最多 10000 字符，允许空串清空。

`replace` 使用 ACTION_SET_TEXT 替换全部内容。`insert` 读取现有文本和 selection，将选区替换为给定文本，再通过 ACTION_SET_TEXT 写入组合结果，并尝试恢复插入位置的光标。密码、不可读文本、未知选区或无法设置选区的字段会返回 ACTION_UNSUPPORTED。插入后 `cursor_restored` 表示 Android 是否接受光标恢复；null 表示无须恢复或取消后未尝试。它不是完整输入法，富文本、特殊编辑器和应用行为需要实机验证。

## 观察与坐标

观察包含 device_id、service_instance_id、observation_id、ui_version、captured_at_ms（Unix 毫秒）、window_id、package_name、display、tree、screenshot，以及 collection_attempts。

- 坐标是默认显示器当前旋转方向下的**物理像素**，左上角为 (0,0)，包含系统栏和键盘区域。不是 dp，也不是裁剪后的应用坐标。
- JPEG 默认长边不超过 1280，质量 80。返回宽高、scale_x/scale_y、offset_x/offset_y。
- 映射：`screen_x = image_x * scale_x + offset_x`，y 同理。动作须传屏幕坐标。
- 旋转值为 0/90/180/270。旋转或几何变化会使关联快照失效。
- tree.elements 默认精简：过滤不可见、空边界、屏幕外及无有效内容/动作的节点，保留必要父子结构，以 parent_id 指向最近保留父节点。element_id 保留原始树路径，不能用数组序号代替。默认省略 enabled:true、visible:true 和为 false 的状态字段，可读编辑字段提供 selection。`compact:false` 返回完整的有界树和状态字段。CLI 与 MCP 使用同一服务端输出。未命名 Android 动作以十进制 ID 表示。
- Phone Use 自身的配对/授权界面不返回树内容，也不接受远程点击或文本操作（LOCAL_CONTROL_UI）；可通过 home/back/recents/launch_app 离开。仅支持默认显示器，其他显示器返回 DISPLAY_UNSUPPORTED。
- 元素 ID 是快照内部路径，每个客户端最多保留八份快照，每份最长 30 秒；已缓存结构指纹总估算内存预算 4 MiB，不在快照缓存中保留截图。客户端只能淘汰自己的快照；预算不能容纳新快照时返回 BUSY、reason:snapshot_budget_exceeded，不挤掉其他客户端的有效引用。不能将元素 ID 当作永久 ID。
- 最多采集 1500 个节点，深度最多 40，单个文本/描述截断至 1024 字符。password 文本不返回。节点指纹使用完整字段的摘要，不依赖截断文本。
- 截图失败时 `screenshot.available=false`，附带原因和 Android 错误码；与 `tree.available=false` 分别报告。未请求的部分为 null。
- 采集前等待目标窗口短暂安静（120 ms），单次等待最多 stable_wait_ms，最多采集 max_attempts 次。持续动画不会无限等待；每次采集仍核验前后目标窗口事件、窗口、应用、显示几何及遮挡。次数耗尽返回 OBSERVATION_INCONSISTENT。只重采观察，绝不因此重放动作。
- 内容版本变化触发重新解析和目标校验，不再一律拒绝。目标字段、节点身份、祖先布局、所属容器内容、窗口或遮挡变化仍拒绝；列表节点复用不凭相同路径放行。replace 不因单纯选区变化失效，insert 必须匹配原文本和选区。坐标动作仍采用严格的观察上下文保护；节点失败不自动降级为坐标动作。
- 错误保持原 code，并补充 `error.reason`、可取得的 `snapshot_age_ms/snapshot_ui_version/current_ui_version/events`。区分过期、淘汰、客户端/实例变化、内容/窗口/布局/容器变化。动作前拒绝返回 `action_executed:false`；无法确认是否输入时该字段为 null。

界面事件异步到达，以上检查不能消除所有竞争条件。屏幕内容可在最终检查与系统动作之间变化。

## 授权、执行与去重

配对授权后可直接调用输入动作，无需申请或释放控制权。所有客户端的动作和即时读取共用单线程设备队列（最多排队 32 个），变化等待不占设备队列。只读请求也必须鉴权，暂停时观察/应用列表/等待被拒绝，状态与能力仍可查询。

连续开发测试可选用 `acquire_device` 取得最多五分钟的占用；有未完成动作时不能新建占用，避免接管中途动作。有效期内其他客户端或缺少正确 lease_id 的输入返回 DEVICE_RESERVED；过期、释放后的旧 lease_id 返回 LEASE_EXPIRED。入队及系统输入前都再次验证，排队期间过期的动作不会执行。占用不限制只读观察，不改变配对授权。本地暂停、停止和撤销占用客户端立即清除占用；已注入动作可能完成。get_status.reservation 返回占用状态和剩余时间，不泄漏占用 ID。

授权凭证持续有效，直到手机端撤销。暂停阻止新操作并取消排队输入，恢复后可直接提交新动作。进程和服务重启不重放请求；重新启动服务后仍须鉴权。

动作最长同步等待 15 秒；尚未完成时返回 accepted（排队）或 executing（执行中）。继续查询 get_status，不把执行中当作成功。终态：

```json
{
  "state":"executed",
  "action_executed":true,
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

通知提供暂停/恢复和停止服务。暂停使排队输入失效，恢复不会重放已取消动作。撤销客户端凭证会拒绝其后续读写请求并取消其排队输入，不影响其他客户端。停止关闭 HTTP 入口，并使所有尚未执行输入失效。

## 主要错误码

INVALID_ARGUMENT、UNKNOWN_TOOL、UNAUTHORIZED、PAUSED、STOPPED、CANCELLED、BUSY、DEVICE_MISMATCH、DEVICE_RESERVED、LEASE_EXPIRED、REQUEST_ID_CONFLICT、REQUEST_NOT_FOUND、ACCESSIBILITY_UNAVAILABLE、SCREEN_OFF、DEVICE_LOCKED、NO_ACTIVE_WINDOW、OBSERVATION_INCONSISTENT、STALE_OBSERVATION、STALE_ELEMENT、WINDOW_MISMATCH、ELEMENT_UNAVAILABLE、ACTION_UNSUPPORTED、ACTION_REJECTED、APP_UNAVAILABLE、RESULT_UNKNOWN。OBSERVATION_INCONSISTENT（采集中变化）、STALE_OBSERVATION（动作上下文失效）、STALE_ELEMENT（目标变化）分别保留，便于批量汇总。

## MCP

`POST /mcp` 使用 JSON-RPC 2.0。支持 initialize、ping、tools/list、tools/call、无响应的通知；不支持批处理。每个工具使用同一套执行代码。notifications/initialized 返回 HTTP 202；通知永远不会触发工具动作。

这是无服务端 MCP 会话的 Streamable HTTP POST/JSON 模式，不返回 Mcp-Session-Id。GET/DELETE 返回 405，无 SSE 推送。MCP 版本为 2025-03-26、2025-06-18 或 2025-11-25；缺省头兼容早期版本。2026-07-28 的无 initialize 协议暂不支持。

tools/call 结果为 text 内容块；观察中的图片另作为 image 内容块。文本保留截图映射信息，去除重复的 data_base64。工具错误通过 isError 表示。MCP JSON-RPC 的 id 不是动作去重 ID；仍须传 arguments.request_id。用 cancel 工具取消动作；通用 JSON-RPC 取消通知不直接撤销设备输入。

### 桥接批量测试

`phoneuse_batch` 接收 1–100 个显式调用，示例：

```json
{
  "calls": [
    {"device_id":"DEVICE_A","tool":"get_capabilities"},
    {"device_id":"DEVICE_B","tool":"get_capabilities"},
    {"device_id":"DEVICE_A","tool":"observe","arguments":{"mode":"tree"}}
  ],
  "summary_only": false,
  "continue_on_error": false
}
```

同设备按输入顺序执行，不同设备并行；结果恢复为输入顺序。可在每项 arguments 中传 lease_id，也可使用该项顶层 lease_id。默认某设备步骤失败、结果未知、仍执行中或附带观察失败时，跳过该设备后续步骤，其他设备继续。需要在失败后继续的独立测试可显式设置 continue_on_error；不会重试已发送动作。

summary 包含 total、succeeded、failed、pending、unknown、skipped，以及 states、error_codes、observation_error_codes。附带观察失败的已执行动作仍计为已执行，并单独统计观察错误。summary_only 省略逐项结果；批量结果不携带截图 base64。CLI 的 `batch @calls.json` 读取上述 calls 数组，`--summary-only` 与 `--continue-on-error` 对应同名选项。

## 实现参考

- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 17 局域网权限](https://developer.android.com/about/versions/17/behavior-changes-17)
- [MCP 2025-03-26 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
