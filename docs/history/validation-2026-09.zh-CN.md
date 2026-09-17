# 历史验收记录：2026 年 9 月

[Current validation guide (English)](../validation.md) | [当前验收指南（简体中文）](../validation.zh-CN.md)

以下保留原始开发记录，包含已被后续实现替代的行为和本地报告路径；不是当前 API 契约或本次文档整理的测试结果。当前行为以 API 文档为准。

# MVP 验收

## 自动检查

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python -m py_compile tools/phoneuse_client.py
python -m unittest discover -s tools -p 'test_*.py'
# 已安装 Playwright 及其 Chromium 时：
node tools/test_web_console.cjs
# 使用系统 Edge 时，设置 PHONEUSE_BROWSER_CHANNEL=msedge
```

DeviceControllerTest 使用假的设备执行器，验证控制状态和请求可靠性，不宣称验证了 Android 无障碍操作。

### 首页整理、设备名称与应用语言（2026-09-17）

首页按本机、控制服务、连接与配对、已配对客户端分区；设备名默认展示为文字，旁边的修改按钮打开带保存/取消和输入校验的弹窗。原始型号、安装 ID 和协议路径按需展开。设置页提供跟随系统、中文和 English，切换后保留当前设置页面。

默认名称优先读取系统设备名及厂商 `device_name` 设置，无法读取时回退到品牌/型号，模拟器不再显示构建编码。手动命名优先于自动名称，安装 ID 不变。系统接口不保证所有厂商都返回市场名称，本轮没有连接小米真机。

应用语言使用 [Android 官方推荐的 AppCompat API](https://developer.android.com/guide/topics/resources/app-languages)，Android 11–12 自动保存语言，Android 13+ 与系统的应用语言同步。运行中的通知使用匹配语言的 Context；切换语言不重建控制服务或撤销配对。升级 Espresso 至 3.7.0 及 AndroidX Test JUnit 至 1.3.0，适配 Android 17 的输入与消息队列测试接口。

新增 JVM 用例覆盖设备可读名称、厂商名称回退、非法系统名称及模拟器；新增真实 Compose UI 用例覆盖空名/超长名拒绝、取消不写入、中文名称保存、重建后持久、默认隐藏 ID、配对审批，以及语言切换/重开应用后保留暂停服务和有效凭证。页面状态轮询使用实际时间的 Handler，避免持续协程计时与 Compose 测试的动画时钟相互推进。

截图保存在 `app/build/reports/ui-review/`，包含中英文首页和设置页，已检查布局。截图测试只临时移除测试 Activity 的安全窗口标志，并在 finally 中恢复；生产页面仍使用 FLAG_SECURE。

Windows / JBR 25 / SDK 37：Debug 构建、78 项 JVM 测试、Pixel 8 / Android 17 模拟器的全部 27 项设备测试通过，0 失败、0 跳过；Lint 为 0 错误、28 条依赖更新/旧模板资源/API 提示。完整设备结果保存在 `app/build/reports/ui-full-regression-results.xml`，构建日志为 `app/build/ui-final-verification.log`。Android 11–12 的语言持久化和厂商可读设备名称仍需对应系统/真机验证。

最终安装包再次通过构建、78 项 JVM 测试、Lint 和 2 项 UI 回归；日志为 `app/build/ui-package-verification.log`。

### 能力独立与执行层简化（2026-09-16，本轮）

按 `.dev/design.md` 实现：动作默认不等待页面、不采集动作后树/截图；独立观察默认 `stable_wait_ms:0`、`output:decision`，只对明确的窗口/应用/显示变化有限重采。节点校验只读取所选目标；移除整页版本、祖先/容器摘要、sibling 几何和活动窗口区域门槛。全屏坐标支持输入法与系统 UI，保留显示/实例/引用归属及 Phone Use 本地控制保护。

默认节点动作列表会随 Android 焦点变化，本轮只在分发前核验所请求的动作能力，不把完整动作列表当作身份。空框提示文字不作为已输入内容，可读空串的缺省选区归一为唯一位置 (0,0)；空框未声明设置选区能力不再阻断文本插入，光标恢复结果独立报告。文本动作不自动读回，返回 `text_verified:false`；插入仍检查文本与选区，替换忽略仅选区变化。树和截图独立报告可用性与采集时间，等待耗时和采集耗时分开报告。

本轮验证环境：Windows、Android Studio JBR 25、SDK 37、Pixel 8 / Android 17 模拟器。Debug 构建、74 项 JVM 测试、44 项 Python 测试与 py_compile 通过；app/fixture Lint 无错误（分别 22/13 条提示）。Web 使用仓库现有 Playwright + Edge 回归，并检查桌面/手机截图；无观察的动作成功正常显示，配对、断线恢复、未知/未完成结果和不重放回归通过。

最终完整回归：Pixel 8 / Android 17 模拟器 23 项设备测试全部通过（0 失败、0 跳过）。测试核对真实控件点击次数、scrollY 和最终文本值，覆盖：

- 中文替换/插入、空框插入与清空后再次插入、仅选区变化时拒绝旧位置插入但允许替换，以及显式附带观察与去重。
- 真实 Gboard 退格键经全屏截图坐标映射点击一次，最终文本由“中文测试”变为“中文测”；键盘显隐不改变显示坐标空间。
- 每 75 ms 刷新的全屏装饰文字下，观察不等待安静，沿同一观察连续执行两次节点点击和两次坐标点击，实际点击计数为 4。
- 后代重绑/重排/替换及没有逻辑条目约定的普通刷新不阻断所选列表滚动；同窗装饰、独立浮窗和 PiP 下的语义滚动检查实际 scrollY。
- 所选节点替换、目标移动、节点窗口变化和屏幕旋转仍拒绝失效引用；本地授权界面仍不可远程操作。
- 条件超时、动作后观察失败、拒绝后的恢复观察、设备身份、HTTP/MCP、服务恢复和请求去重。

测试只在场景初始化时明确等待窗口/键盘几何发布；轮播测试忽略持续内容事件，不借助 App 的默认安静等待。保护窗口的全屏截图可能返回经遮盖的画面或不可用状态，测试按平台返回事实检查各通道，不预设 FLAG_SECURE 必须令整屏截图 API 报错。

报告：`app/build/reports/androidTests/connected/debug/`、`app/build/reports/tests/testDebugUnitTest/`、`app/build/verification-final.log`。模拟器的软键盘显示和旋转设置在测试后恢复。

仅连接了模拟器；没有运行小米当前版闲鱼 20 轮搜索流程，也未修改两份既有闲鱼实测记录。实机需记录任务完成率、观察次数、误拒绝、响应字节数、调用耗时和重复动作，包含中文关键词和键盘显隐。模拟器结果不替代这项验收。

以下为历史验证记录，其中容器不变证明、sibling/高层窗口遮挡拒绝与默认安静等待属于旧策略，已由本轮设计取代。

### 真机体验改进的实现与验收（2026-09-16）

已实现条件观察与统一总预算、拒绝后的恢复观察、动作/观察进度分离、decision 树投影、子树/区域输出，以及受限的语义滚动与按动作区分的遮挡验证。普通 HTTP、MCP、CLI、Web 和批量调用同步处理未完成观察，不以 executed 代替条件达成，不自动重放输入。

最终检查：Debug APK 构建成功，88 项 JVM 测试全部通过；app 和 fixture 的 lint 均为 0 错误，分别保留 22 和 11 条警告。`git diff --check` 通过。使用 Gradle 9.7.1、Android Studio JBR 25 和 SDK 37。

纯逻辑测试覆盖条件 AND/唯一/存在匹配、重复/缺失/截断节点、长文本的完整证据匹配、持续变化与总超时、取消、最后有效观察与当前未知状态、输出状态/路径/未知动作、快照原采集时刻的 TTL、缓存预算和去重、观察期间的动作执行事实，以及嵌套身份/截图序列化。滚动反例包括固定行号下的数据重绑、逻辑 ID 改变/重复、重排、插入、滚动事件、日志缺失和验证期间变化；纯逻辑几何测试检查完整手势线段与非矩形区域的组成矩形。

Python 44 项测试及 py_compile 通过；Web 使用真实 Edge 无头浏览器、模拟手机 HTTP 响应验证条件超时/歧义/未知、accepted 与 executing 进度、恢复及历史截图展示、批量阻断和不重放，没有页面运行错误。这里不将模拟 HTTP 回归称为手机业务验收。

固定投影基线来自同一份 100 行、200 节点的内部样本，记录在 JVM 报告 `ConditionalObservationTest` 的 PROJECTION_BASELINE 输出中：

| 指标 | legacy compact | decision | 缩减 |
| --- | ---: | ---: | ---: |
| 导出节点 | 200 | 100 | 50.0% |
| elements JSON UTF-8 字节 | 32,523 | 10,172 | 68.7% |
| 序列化 MCP 文本块 UTF-8 字节 | 38,071 | 12,122 | 68.2% |

这是固定纯逻辑样本的输出基线，不是闲鱼任务耗时、模型总成本或截图成本的改进比例。实际业务收益仍需同设备同流程复测。

设备测试使用独立 debug 包 `fixture`，通过真实 Android 树、窗口、截图及控件接收计数/scrollY 验证结果。运行命令及各场景见 [测试页说明](../../fixture/README.zh-CN.md)；connectedDebugAndroidTest 依赖 fixture 安装任务，避免 Gradle 并行任务在测试中途安装或使用旧 APK。设备测试临时设置并恢复专用模拟器的无障碍服务列表，不适用于直接操作正在使用的实体手机。

Pixel 8 / Android 17 模拟器最终运行 15 项设备测试全部通过（0 失败、0 跳过），其中新增 10 项 fixture 测试覆盖延迟目标及截图、骨架屏与重复匹配、聚焦后输入、静态/动态文字列表的实际滚动、重绑/重排/替换/移位/用户滚动/未知逻辑身份拒绝、独立窗口及同窗遮挡、完整手势路径，以及条件超时和拒绝恢复后的请求去重。PiP 正例同时断言实际命中一个允许的覆盖例外、控件只收到一次语义滚动及 scrollY 改变。报告位于 `app/build/reports/androidTests/connected/debug/`。

实施中曾观察到 Android 17 模拟器的系统无障碍窗口表只包含状态栏，而活动根仍来自测试页；清缓存不能恢复完整系统列表，重启模拟器后静态列表实测恢复。服务现在将根窗口缺失明确归类为 window_inventory_incomplete 并重采，预算到期保持失败，没有放宽窗口检查来通过测试。该现象不能推断为此前小米/闲鱼树图混配的根因。

文字刷新后的滚动放行需要页面 provider 显式提供稳定逻辑条目 ID 及其它独立证据；普通第三方列表没有该约定时仍拒绝。部分窗口覆盖的语义滚动例外只面向平台标记的非聚焦角落 PiP，普通未知模态浮窗、点击和输入不使用例外。未暴露到无障碍树的视觉遮罩、非矩形输入区域的真实设备场景、Android 11/14 与小米闲鱼复测仍待验证；不声称同帧截图/树原子性或消除误拦截。

### 多设备与观察可靠性（2026-09-16）

- Gradle 9.7.1 / Android Studio JBR 25 / SDK 37：Debug APK、测试 APK 构建通过，42 项 JVM 测试通过，Lint 0 错误（22 条依赖、模板资源及 API/KTX 提示）。
- JVM 测试覆盖：跨客户端快照额度与总预算、过期/淘汰诊断、无关区域刷新、列表行复用、目标与祖先布局变化、控件状态变化、replace/insert 选区差异、持续动画的等待与采集次数上限、暂停取消、动作成功后观察失败仍保留原结果。
- 可选开发者占用覆盖：客户端与 lease_id 双重匹配、续期/过期、排队期间过期不执行、暂停/撤销清除占用、撤销与申请交错时不产生孤儿占用。
- Pixel 8 / Android 17 模拟器 5 项设备测试通过：安装身份持久与命名、生产 HTTP/MCP 公开身份与路由拒绝、服务重建后实例变化、嵌套动作/观察结果身份及去重，以及原服务恢复流程。测试 APK 留在模拟器中，未操作实体手机。
- Python 35 项测试通过，py_compile 通过：多设备配对/重连、旧配置安全迁移、路由与并发隔离、换机地址不发旧 token、断连不重放、响应身份缺失拒绝、MCP 协议错误/撤销通知、批量包含 get_status.request 的未知/未完成状态统计、截图保存和 ASCII 环境下 UTF-8 子进程输出。真实本地 HTTP 端点验证身份 GET 不含 Authorization、中文 POST 和设备/服务实例保护参数。
- 多设备桥接的自动测试使用独立模拟 HTTP 端点和故障注入；不将它们等同于两台真实手机联测。实体手机节点身份、动态列表、键盘/遮挡及厂商事件时序仍按下列流程复测。

本次新增实机验证重点：同一桥接注册实体机与模拟器，确认名称/型号/模拟器标记；每次明确 device_id，地址改指另一安装时旧凭证不能发送；一台断线时另一台操作继续；复用旧服务或其他客户端观察必须拒绝。无关区域文字刷新时稳定目标应可操作，目标/所属行/容器/布局/窗口变化必须拒绝；仅选区变化允许 replace、拒绝使用旧选区 insert。连续动态页面的 observe 必须在上限内结束，observe_after 失败不能重放动作。CLI 保存截图并核对中文 UTF-8 与图像坐标。

### 本次环境记录（2026-09-15）

Windows / Android Studio JBR 25 / Android SDK 37：Debug APK 构建成功，23 项 JVM 测试通过（12 项控制层、6 项配对状态、4 项浏览器来源策略和 1 项模板测试），Lint 无错误（保留已有依赖版本与模板未使用资源提示）。Python 客户端 4 项测试通过，覆盖 HTTP 202 响应、等待授权与落盘、拒绝、过期。

HTTP 迁移回归：`HttpServerInstrumentedTest` 在 Pixel 8 / Android 17 模拟器上使用生产 HTTP 服务，覆盖 HTML/JS、配对待批准/批准领取、API 状态和 MCP 工具目录，以及 App 跳转首页、跨站配对拒绝和无 token 的鉴权。通过普通 TCP Socket 访问，不依赖 TLS 或证书。测试批准通过内部方法完成，不代替手动审批 UI 验收。

另通过现有 8443 端口转发完成真实联调：普通 `curl.exe http://127.0.0.1:8443/` 返回 200；Python 客户端发起请求、App 中点击授权、保存凭证、调用 get_status 和 MCP stdio initialize 成功。测试凭证已在 App 撤销并清理配置文件。从 App 的“打开 Web 控制台”进入模拟器 Chrome，直接显示 HTTP 页面，无证书错误页。

运行设备回归（保留 APK，避免测试结束后卸载应用）：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

Playwright + 系统 Edge 使用模拟手机 HTTP 响应验证 Web 页面：请求配对、等待期间刷新恢复、授权后直接调用 API、接口搜索与参数表单、动作 ID 保留及新建、凭证下载、撤销后重新配对、拒绝与过期、CSP 和桌面/390px 手机布局，未发现页面运行错误。截图输出在 `app/build/reports/web-console-desktop.png` 和 `web-console-mobile.png`。该浏览器测试使用非安全 HTTP 来源，额外验证局域网 HTTP 下动作 ID 的生成；真实 Android HTTP 服务由上面的设备测试覆盖，实体手机流程仍待验收。

## 实机流程（待验证）

1. Android 11、Android 14+、Android 17+ 分别验证授权和前台启动；通知被禁用时引导系统设置。
2. 浏览器通过 HTTP 直接打开首页，不出现证书错误页；无 Bearer 或配对 Cookie 的 /api、/api/tools 和 /mcp 返回 401。客户端请求后手机显示名称、来源 IP、核对码；批准前无凭证，拒绝后不可领取，过期后不可批准或领取，重复领取不重复签发。通知提示打开 App 授权。
3. 客户端 A、B 配对后直接调用动作，共用串行队列。A 取消请求或被撤销授权时，B 的排队请求继续执行。
4. 进入普通应用，分别 observe tree/screenshot/both；截图与树边界对照，检查状态栏、导航栏、键盘与旋转后的坐标。
5. launch_app → observe → tap 输入框 → 新 observe → input_text replace 中文 → 新 observe → input_text insert 中文。检查选区替换和光标恢复。
6. 节点 scroll 或区域 scroll 后重新 observe，再点击列表元素；检查不支持节点操作时没有自动重复点击。
7. home/back/recents 跨应用导航，确认返回的是系统接受结果而非业务成功。
8. 观察后旋转/改变显示尺寸或更换服务，以旧 observation_id 操作应拒绝。普通刷新、切换应用或打开/隐藏键盘不整体作废全屏坐标；节点所属窗口和目标身份仍须有效。显式 expected_window_id 不匹配仍拒绝。
9. 重发完全相同 request_id，确认输入仅一次；修改参数同 ID 返回冲突；查询 request_id 能得到终态。
10. 一个长手势执行中排队第二个动作，通过通知暂停。第一手势可能完成，第二个不得执行；恢复后已取消请求不重放，新请求可直接执行。
11. 撤销客户端凭证后，读写操作必须失败，该客户端排队动作被取消。停止服务后端口关闭；重启不恢复输入。
12. 请求已发出后断网：不得换 ID 自动重试。重连在保留期内查询原 ID。杀进程后需重新启动服务并观察，不假定去重记录还存在。
13. 锁屏/熄屏返回明确错误；密码节点不暴露 text；安全窗口截图返回能力失败原因，界面树可独立返回。
14. 用 MCP stdio 桥接完成 initialize → tools/list → observe → home；stdout 只有 JSON-RPC 消息，截图作为 image 内容块。
15. 快速连续截图、动态列表、后台运行十分钟、厂商省电限制分别记录失败码与恢复方式。
16. 未开启无障碍时启动服务，浏览器打开根地址，完成配对、查看工具定义、调用 get_capabilities/get_status；MCP 通过 phoneuse_connect 完成一次配对，此后直接调用设备工具。无障碍动作应返回明确的不可用错误。
17. Web 页面检查桌面和手机宽度；验证等待、拒绝、过期、刷新后恢复、凭证撤销后的重新配对。动作网络异常时保留 request_id，不自动重放；新动作可显式生成新 ID。
18. 跨站 Origin、伪造域名 Host 被拒绝，同源浏览器和无 Origin 的原生客户端可以接入。第五秒内重复申请返回 429；停止服务清空未完成请求。

记录设备/系统版本、调用延迟、节点支持情况、截图失败码、重复输入次数、暂停后排队行为。不要用模拟设备执行器的测试结果替代实机结论。

### 授权与 API 清理回归（2026-09-15）

- 移除显式获取/释放控制权接口及动作租约参数，配对后可直接调用。清理网页按钮、服务通知和 MCP 初始化说明。
- JVM 测试覆盖长期授权、暂停后新动作、跨客户端串行执行、取消/撤销隔离、请求去重和未知结果。
- Playwright + Edge 覆盖配对、API 列表/搜索、参数表单、状态查询、动作 ID、退出、撤销、拒绝、过期、CSP 和桌面/390px 布局。配对后只加载目录，不自动调用任何工具。
- 本次未运行设备端测试；上面的 HTTP 迁移设备结果为此前验证记录。

### 一次配对与自动恢复（2026-09-16）

- Debug APK 构建与 23 项 JVM 测试通过，Lint 无错误。
- Python 客户端 12 项测试通过：首次配对、拒绝/过期、待批准请求恢复、已配对配置复用、地址不匹配拦截、断线不重放、撤销不自动申请、MCP 无凭证输出、离线初始化、恢复后刷新工具列表。
- Playwright + Edge 验证 HttpOnly Cookie、浏览器进程关闭后恢复持久 Cookie、服务暂时不可用后的自动恢复、旧标签页迁移、解除配对、撤销/拒绝/过期、API 参数与动作 ID、桌面与 390px 布局。测试过程中没有页面运行异常；恢复时没有增加配对请求。该测试使用模拟 HTTP 服务。
- Pixel 8 / Android 17 模拟器运行 3 项设备测试全部通过。其中生产 HTTP 测试重新创建 IdentityStore、DeviceController 和 HttpServer，确认相同 Cookie/Bearer 继续认证；覆盖无 token 的网页批准响应、Cookie 属性、跨站拒绝、旧 Bearer 迁移、解除配对后失效。
- ServiceRestorationTest 验证 App 打开后恢复启用服务、外部销毁服务后重新打开恢复、暂停状态保留，以及主动停止后不自动启动。此测试不代替厂商后台限制、系统进程回收时机和真实手机重启验收。

### 实际 AI 客户端接入（2026-09-16）

- 使用 Codex CLI `0.154.0-alpha.6.2` 启动本地 Python stdio 桥接，连接用户已转发的 `http://127.0.0.1:8443`。由真实模型调用 `phoneuse_connect`，手机批准客户端 `Codex AI` 后保存配对。
- 配对后收到 `notifications/tools/list_changed`，发现 17 个工具（2 个桥接工具、15 个设备工具）。`get_status` 返回未暂停、未停止；`get_capabilities` 返回 Android API 37、无障碍已开启、亮屏且未锁定。
- 关闭桥接后，新桥接进程直接发现设备工具并调用 `get_status`，没有再次调用配对工具。随后启动新的 Codex 客户端，由真实模型直接调用 `get_status` 和 `get_capabilities`，均成功；模型输入与工具结果不含凭证。
- 最初的阻塞是 Codex 测试进程的工具审批策略。经用户授权，本次进程仅开放 `phoneuse_connect/get_status/get_capabilities`，并使用 `default_tools_approval_mode="approve"` 完成测试。未修改用户的全局 MCP 或审批配置。
- 原始结果保存在本地 `app/build/reports/mcp-live-protocol.json` 和 `app/build/reports/mcp-live-codex.jsonl`。本轮验证实际 AI 的配对、工具发现、只读调用与客户端重启复用授权；手机 App 重启属于上一节设备测试的覆盖范围。
