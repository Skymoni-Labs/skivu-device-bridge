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

Windows / Android Studio JBR 25 / Android SDK 37：Debug APK 构建成功，19 项 JVM 测试通过（8 项控制层、6 项配对状态、4 项浏览器来源策略和 1 项模板测试），Lint 无错误（保留已有依赖版本与模板未使用资源提示）。Python 客户端 4 项测试通过，覆盖 HTTP 202 响应、等待授权与落盘、拒绝、过期。

HTTP 迁移回归：`HttpServerInstrumentedTest` 在 Pixel 8 / Android 17 模拟器上使用生产 HTTP 服务，覆盖 HTML/JS、配对待批准/批准领取、API 状态和 MCP 工具目录，以及 App 跳转首页、跨站配对拒绝和无 token 的鉴权。通过普通 TCP Socket 访问，不依赖 TLS 或证书。测试批准通过内部方法完成，不代替手动审批 UI 验收。

另通过现有 8443 端口转发完成真实联调：普通 `curl.exe http://127.0.0.1:8443/` 返回 200；Python 客户端发起请求、App 中点击授权、保存凭证、调用 get_status 和 MCP stdio initialize 成功。测试凭证已在 App 撤销并清理配置文件。从 App 的“打开 Web 控制台”进入模拟器 Chrome，直接显示 HTTP 页面，无证书错误页。

运行设备回归（保留 APK，避免测试结束后卸载应用）：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

Playwright + 系统 Edge 使用模拟手机 HTTP 响应验证 Web 页面：请求配对、等待期间刷新恢复、授权后调用 API、控制权、动作 ID 保留及新建、凭证下载、撤销后重新配对、拒绝与过期、CSP 和桌面/390px 手机布局，未发现页面运行错误。截图输出在 `app/build/reports/web-console-desktop.png` 和 `web-console-mobile.png`。该浏览器测试使用非安全 HTTP 来源，额外验证局域网 HTTP 下动作 ID 的生成；真实 Android HTTP 服务由上面的设备测试覆盖，实体手机流程仍待验收。

## 实机流程（待验证）

1. Android 11、Android 14+、Android 17+ 分别验证授权和前台启动；通知被禁用时引导系统设置。
2. 浏览器通过 HTTP 直接打开首页，不出现证书错误页；无 token 的 /api、/api/tools 和 /mcp 返回 401。客户端请求后手机显示名称、来源 IP、核对码；批准前无凭证，拒绝后不可领取，过期后不可批准或领取，重复领取不重复签发。通知提示打开 App 授权。
3. 客户端 A acquire_control 后，B 申请返回 CONTROL_BUSY；A 续租不更换 ID，过期 ID 不可续租。
4. 进入普通应用，分别 observe tree/screenshot/both；截图与树边界对照，检查状态栏、导航栏、键盘与旋转后的坐标。
5. launch_app → observe → tap 输入框 → 新 observe → input_text replace 中文 → 新 observe → input_text insert 中文。检查选区替换和光标恢复。
6. 节点 scroll 或区域 scroll 后重新 observe，再点击列表元素；检查不支持节点操作时没有自动重复点击。
7. home/back/recents 跨应用导航，确认返回的是系统接受结果而非业务成功。
8. 观察后手动切页/旋转/打开键盘，以旧 observation_id 操作应拒绝。再次观察后用新元素 ID 成功。
9. 重发完全相同 request_id，确认输入仅一次；修改参数同 ID 返回冲突；查询 request_id 能得到终态。
10. 一个长手势执行中排队第二个动作，通过通知暂停。第一手势可能完成，第二个不得执行；恢复后旧租约仍不可使用。
11. 通知断开后旧租约不能操作；撤销客户端凭证后，读操作也必须失败。停止服务后端口关闭；重启不恢复输入。
12. 请求已发出后断网：不得换 ID 自动重试。重连在保留期内查询原 ID。杀进程后需重新申请控制并观察，不假定去重记录还存在。
13. 锁屏/熄屏返回明确错误；密码节点不暴露 text；安全窗口截图返回能力失败原因，界面树可独立返回。
14. 用 MCP stdio 桥接完成 initialize → tools/list → acquire_control → observe → home；stdout 只有 JSON-RPC 消息，截图作为 image 内容块。
15. 快速连续截图、动态列表、后台运行十分钟、厂商省电限制分别记录失败码与恢复方式。
16. 未开启无障碍时启动服务，浏览器打开根地址，完成配对、查看工具定义、调用 get_capabilities/get_status、导出凭证；Python 使用导出凭证调用 API，MCP 桥接使用同一凭证。无障碍动作应返回明确的不可用错误。
17. Web 页面检查桌面和手机宽度；验证等待、拒绝、过期、刷新后恢复、凭证撤销后的重新配对。动作网络异常时保留 request_id，不自动重放；新动作可显式生成新 ID。
18. 跨站 Origin、伪造域名 Host 被拒绝，同源浏览器和无 Origin 的原生客户端可以接入。第五秒内重复申请返回 429；停止服务清空未完成请求。

记录设备/系统版本、调用延迟、节点支持情况、截图失败码、重复输入次数、暂停后排队行为。不要用模拟设备执行器的测试结果替代实机结论。
