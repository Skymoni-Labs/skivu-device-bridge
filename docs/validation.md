# MVP 验收

## 自动检查

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python -m py_compile tools/phoneuse_client.py
```

DeviceControllerTest 使用假的设备执行器，验证控制状态和请求可靠性，不宣称验证了 Android 无障碍操作。

### 本次环境记录（2026-09-15）

Windows / Android Studio JBR 25 / Android SDK 37：Debug APK 构建成功，新增 8 项控制层测试通过，Lint 无错误（保留已有依赖版本与模板未使用资源提示）。Python 客户端通过语法检查和命令行入口检查。没有连接实机执行下列流程。

## 实机流程（待验证）

1. Android 11、Android 14+、Android 17+ 分别验证授权和前台启动；通知被禁用时引导系统设置。
2. 配对前核对指纹。错误指纹必须在发送配对码前被客户端拒绝；无 token 的 /api 和 /mcp 返回 401。配对窗口过期/五次错误/重复配对码均失败。
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

记录设备/系统版本、调用延迟、节点支持情况、截图失败码、重复输入次数、暂停后排队行为。不要用模拟设备执行器的测试结果替代实机结论。
