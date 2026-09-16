# Phone Use 可控设备测试页

独立包 `ai.nidhoggfgg.phoneuse.fixture`，只生成 debug 变体，不包含真实账号、业务数据或网络请求。测试通过真实无障碍服务观察和操作控件，并通过只读 CounterProvider 核对接收目标、动作次数及 scrollY。

在专用模拟器上运行：

```powershell
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat :fixture:installDebug :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=ai.nidhoggfgg.phoneuse.ImprovementFixtureTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

测试会保存并临时调整该设备的无障碍设置，结束时恢复；进程被强行结束时需手动恢复。不要指向正在使用的实体手机。

手动打开一个场景（`delay_ms` 控制延迟变化）：

```powershell
adb -s emulator-5554 shell am start -n ai.nidhoggfgg.phoneuse.fixture/.ScenarioActivity --es scenario text_refresh --ei delay_ms 2500
adb -s emulator-5554 shell content query --uri content://ai.nidhoggfgg.phoneuse.fixture.counters/state
```

场景包括 delayed、skeleton、duplicate、static、text_refresh、unidentified_refresh、rebind、reorder、replace、move、user_scroll、modal、popup、full_popup、same_window、descendant_overlay、focus 和 pip。`unidentified_refresh` 故意不提供逻辑条目契约，用于验证普通未知身份文字变化仍被拒绝。启动新场景前应 force-stop 测试包，确保清空计数及旧窗口。

列表行显式提供稳定的逻辑条目 ID，用于验证受限的文字刷新规则。真实第三方应用通常不提供这项约定，其动态列表不能据此宣称已经通过验证。PiP 场景存在不代表某设备的 PiP 放行路径已通过；实际完成的设备验收见 [验证记录](../docs/validation.md)。

逻辑条目契约由 provider 在行节点 `AccessibilityNodeInfo.extras["ai.nidhoggfgg.phoneuse.logical_item_id"]` 声明，必须是非空且不超过 256 字符的字符串，并跟随业务条目而非复用的控件或槽位。只在 widget `uniqueId`、唯一不变内容锚、完整文字事件日志和其余结构状态证据同时满足时才考虑文字刷新放行。固定序号、位置或 `uniqueId` 单独均不是业务身份依据。

`keyboard` 和 `animation` 可分别单独启动键盘与局部视觉动画。非矩形平台输入区域目前由纯逻辑测试覆盖，透明像素并不构成此设备场景；无障碍树未暴露的绘制浮层也不能保证检出。窗口区域来自 Android 的 [getRegionInScreen](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getRegionInScreen(android.graphics.Region))，表示输入区域，不能称作已验证的视觉可见区域。

Android 报告输出到 `app/build/reports/androidTests/connected/debug/`。纯逻辑投影基线由 `ConditionalObservationTest.fixedCatalogProjectionBaselineUsesTheSameInternalNodes` 在 JVM 测试 XML 中输出节点数、UTF-8 字节数及 MCP 文本块序列化字节数；这不是实际业务任务耗时或视觉成本指标。
