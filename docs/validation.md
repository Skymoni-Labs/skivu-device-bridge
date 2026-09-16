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
8. 观察后手动切页/旋转/打开键盘，以旧 observation_id 操作应拒绝。再次观察后用新元素 ID 成功。
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
