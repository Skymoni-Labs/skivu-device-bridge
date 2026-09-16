# Phone Use 可控设备测试页

独立包 `ai.nidhoggfgg.phoneuse.fixture`，只生成 debug 变体，不包含真实账号、业务数据或网络请求。测试通过真实无障碍服务观察和操作控件，并通过只读 CounterProvider 核对接收目标、动作次数、scrollY 及最终文本。

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

场景包括 delayed、skeleton、duplicate、static、text_refresh、unidentified_refresh、rebind、reorder、replace、move、user_scroll、modal、popup、full_popup、same_window、descendant_overlay、focus、keyboard、selection、carousel、target_replace、secure 和 pip。启动新场景前应 force-stop 测试包，确保清空计数及旧窗口。

本轮验证目标：普通文字刷新、列表后代变化、装饰层和几何重叠不阻断选定容器的语义滚动；所选节点替换、目标移动和节点所属窗口变化仍拒绝。`target_replace` 用相同文本和路径的新控件验证节点身份保护；`carousel` 每 75 ms 更新覆盖整个页面的装饰文字，验证即时观察及连续目标操作。

`keyboard`、`focus` 和 `selection` 覆盖中文替换、插入、仅选区变化、显式附带观察及独立读回。测试同时检查观察中的最终文本和 CounterProvider 的实际文本值，不能只检查请求计数。键盘退格测试从真实 IME 键边界确定截图位置，经 screenshot.scale_x/scale_y 映射后执行一次坐标点击，断言实际只删除一个字符。

列表中的逻辑条目 ID 保留为测试数据；普通第三方应用不需要实现该约定。滚动不再依赖逻辑条目、内容锚或事件日志的整页证明。浮窗与 PiP 由截图供调用方判断，测试不将几何重叠当作已确认遮挡。

Android 报告输出到 `app/build/reports/androidTests/connected/debug/`。纯逻辑投影基线由 `ConditionalObservationTest.fixedCatalogProjectionBaselineUsesTheSameInternalNodes` 在 JVM 测试 XML 中输出节点数、UTF-8 字节数及 MCP 文本块序列化字节数；这不是实际业务任务耗时或视觉成本指标。
