# Phone Use device test fixture

**English** | [简体中文](README.zh-CN.md)

The separate package `ai.nidhoggfgg.phoneuse.fixture` has debug variants only and contains no real accounts, business data, or network requests. Tests observe and operate controls through the real accessibility service. A read-only CounterProvider verifies targets, action counts, scrollY, and final text.

Run on a dedicated emulator:

```powershell
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat :fixture:installDebug :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=ai.nidhoggfgg.phoneuse.ImprovementFixtureTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

Tests save and temporarily change accessibility settings, then restore them on completion. A forcibly terminated test may need manual restoration. Do not target a phone in active use.

Open a scenario manually (`delay_ms` controls delayed changes):

```powershell
adb -s emulator-5554 shell am start -n ai.nidhoggfgg.phoneuse.fixture/.ScenarioActivity --es scenario text_refresh --ei delay_ms 2500
adb -s emulator-5554 shell content query --uri content://ai.nidhoggfgg.phoneuse.fixture.counters/state
```

Scenarios: delayed, skeleton, duplicate, static, text_refresh, unidentified_refresh, rebind, reorder, replace, move, user_scroll, modal, popup, full_popup, same_window, descendant_overlay, focus, keyboard, selection, carousel, target_replace, secure, and pip. Force-stop the fixture before starting a new scenario to clear counters and old windows.

Ordinary text refreshes, descendant changes, decoration, and geometric overlap should not block semantic scroll on the selected container. Replaced or moved targets and target-window changes must still be rejected. `target_replace` swaps a node for one with the same text and path; `carousel` refreshes full-page decorative text every 75 ms to test immediate observation and consecutive target actions.

`keyboard`, `focus`, and `selection` cover Chinese replacement/insertion, selection-only changes, attached observation, and independent readback. Tests check both exported text and actual CounterProvider text, not just request counts. The keyboard backspace test maps a real IME key's screenshot bounds through screenshot.scale_x/scale_y, taps once, and verifies exactly one character was deleted.

Logical item IDs remain fixture data, not a requirement for third-party apps. Scrolling does not depend on whole-page proofs from logical IDs, content anchors, or event history. Screenshots let the caller assess overlays/PiP; geometric overlap alone is not confirmed occlusion.

Reports: `app/build/reports/androidTests/connected/debug/`. The JVM test `ConditionalObservationTest.fixedCatalogProjectionBaselineUsesTheSameInternalNodes` writes node counts, UTF-8 bytes, and serialized MCP text-block bytes to test XML. These are projection metrics, not real task latency or visual cost. See the [validation guide](../docs/validation.md) for coverage limits.
