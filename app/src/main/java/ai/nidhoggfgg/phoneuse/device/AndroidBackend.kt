package ai.nidhoggfgg.phoneuse.device

import ai.nidhoggfgg.phoneuse.core.*
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import androidx.core.graphics.scale
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Suppress("DEPRECATION")
class AndroidBackend(private val context: Context) : DeviceBackend {
    private data class Snapshot(val id: String, val version: Long, val window: Int, val app: String,
        val width: Int, val height: Int, val rotation: Int, val created: Long, val nodes: Map<String, String>)
    private val snapshots = LinkedHashMap<String, Snapshot>()
    private fun service() = PhoneAccessibilityService.current ?: fail("ACCESSIBILITY_UNAVAILABLE", "Enable Phone Use accessibility service")
    private fun ready(): PhoneAccessibilityService {
        val service = service()
        if (!context.getSystemService(PowerManager::class.java).isInteractive) fail("SCREEN_OFF", "Turn on the phone screen")
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) fail("DEVICE_LOCKED", "Unlock the phone")
        return service
    }
    private fun geometry(): Triple<Int, Int, Int> {
        val manager = context.getSystemService(WindowManager::class.java)
        val rect = manager.maximumWindowMetrics.bounds
        return Triple(rect.width(), rect.height(), manager.defaultDisplay.rotation)
    }
    override fun capabilities(): JSONObject = json(
        "android_api" to android.os.Build.VERSION.SDK_INT,
        "accessibility" to (PhoneAccessibilityService.current != null),
        "screen_on" to context.getSystemService(PowerManager::class.java).isInteractive,
        "locked" to context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        "unavailable_reason" to if (PhoneAccessibilityService.current == null) "ACCESSIBILITY_UNAVAILABLE" else null,
        "screenshot" to "accessibility_on_demand; protected windows may fail",
        "text_modes" to JSONArray(listOf("replace", "insert")),
        "insert_mechanism" to "read selection + ACTION_SET_TEXT; requires readable non-password text",
        "coordinate_space" to "default display physical pixels, origin top-left, system bars and IME included",
        "max_nodes" to 1500, "snapshot_ttl_ms" to 30_000,
        "deduplication_ttl_ms" to 600_000, "deduplication_scope" to "client + request_id, current server process",
        "mcp_protocol_versions" to JSONArray(listOf("2025-03-26", "2025-06-18", "2025-11-25"))
    )
    override fun observe(args: JSONObject): JSONObject {
        val mode = if (args.has("mode")) args.string("mode") else "both"
        if (mode !in setOf("tree", "screenshot", "both")) fail("INVALID_ARGUMENT", "mode must be tree, screenshot or both")
        val service = ready()
        val before = PhoneAccessibilityService.version.get()
        val (width, height, rotation) = geometry()
        val root = service.rootInActiveWindow
        val window = root?.windowId ?: -1
        val app = root?.packageName?.toString() ?: ""
        val readableTree = root != null && app != context.packageName
        val windows = service.windows
        val wrongDisplay = windows.any { it.id == window && it.displayId != Display.DEFAULT_DISPLAY }
        windows.forEach { it.recycle() }
        if (wrongDisplay) {
            root?.recycle()
            fail("DISPLAY_UNSUPPORTED", "Only the default display is supported")
        }
        val elements = JSONArray()
        val fingerprints = HashMap<String, String>()
        val truncated = booleanArrayOf(false)
        try {
            if (root != null && readableTree && mode != "screenshot") collect(root, "0", elements, fingerprints, 0, truncated)
        } finally { root?.recycle() }
        val screenshot = if (mode != "tree") screenshot(service, args.integer("max_dimension", 1280, 320, 2048)) else null
        val afterRoot = service.rootInActiveWindow
        val stableWindow = afterRoot?.windowId ?: -1
        val stableApp = afterRoot?.packageName?.toString() ?: ""
        afterRoot?.recycle()
        ready()
        if (before != PhoneAccessibilityService.version.get() || window != stableWindow || app != stableApp ||
            geometry() != Triple(width, height, rotation)) fail("OBSERVATION_INCONSISTENT", "UI changed during collection; observe again")
        val id = UUID.randomUUID().toString()
        synchronized(snapshots) {
            snapshots.entries.removeAll { SystemClock.elapsedRealtime() - it.value.created > 30_000 }
            if (snapshots.size >= 8) snapshots.remove(snapshots.keys.first())
            snapshots[id] = Snapshot(id, before, window, app, width, height, rotation, SystemClock.elapsedRealtime(), fingerprints)
        }
        return json("observation_id" to id, "ui_version" to before, "captured_at_ms" to System.currentTimeMillis(),
            "window_id" to window, "package_name" to app,
            "display" to json("id" to 0, "width" to width, "height" to height, "rotation" to rotation * 90,
                "coordinates" to "physical_pixels", "origin" to "top_left", "includes_system_bars_and_ime" to true),
            "tree" to if (mode == "screenshot") null else json("available" to readableTree,
                "reason" to if (app == context.packageName) "LOCAL_CONTROL_UI" else if (window == -1) "NO_ACTIVE_WINDOW" else null,
                "truncated" to truncated[0], "elements" to elements),
            "screenshot" to screenshot)
    }
    private fun fingerprint(node: AccessibilityNodeInfo): String {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val value = listOf(node.windowId, node.viewIdResourceName, node.className, node.text, node.contentDescription,
            bounds.flattenToString(), node.isEnabled, node.isEditable, node.isPassword, node.textSelectionStart,
            node.textSelectionEnd, node.actionList.map { it.id }).joinToString("\u001f")
        return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun collect(node: AccessibilityNodeInfo, path: String, out: JSONArray, refs: MutableMap<String, String>, depth: Int, truncated: BooleanArray) {
        if (out.length() >= 1500 || depth > 40) { truncated[0] = true; return }
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        refs[path] = fingerprint(node)
        out.put(json("element_id" to path, "text" to if (node.isPassword) null else node.text?.toString()?.take(1024),
            "description" to node.contentDescription?.toString()?.take(1024), "view_id" to node.viewIdResourceName,
            "class" to node.className?.toString(), "bounds" to JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)),
            "enabled" to node.isEnabled, "visible" to node.isVisibleToUser, "editable" to node.isEditable,
            "password" to node.isPassword, "focused" to node.isFocused,
            "actions" to JSONArray(node.actionList.map { actionName(it.id) })))
        for (i in 0 until node.childCount) {
            if (out.length() >= 1500) { truncated[0] = true; break }
            val child = node.getChild(i) ?: continue
            try { collect(child, "$path.$i", out, refs, depth + 1, truncated) } finally { child.recycle() }
        }
    }
    private fun actionName(id: Int): String = when (id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "tap"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_press"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        else -> id.toString()
    }
    private fun screenshot(service: PhoneAccessibilityService, maxDimension: Int): JSONObject {
        val pending = CompletableFuture<JSONObject>()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, java.util.concurrent.Executor { it.run() }, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                // Conversion is bounded to a single frame; all buffers close even after a timeout.
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?: throw IllegalStateException("No bitmap")
                    try {
                        val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                            ?: throw IllegalStateException("No software bitmap")
                        try {
                            val ratio = minOf(1.0, maxDimension.toDouble() / maxOf(software.width, software.height))
                            val scaled = software.scale((software.width * ratio).toInt().coerceAtLeast(1),
                                (software.height * ratio).toInt().coerceAtLeast(1), true)
                            try {
                                val bytes = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
                                pending.complete(json("available" to true, "mime_type" to "image/jpeg",
                                    "width" to scaled.width, "height" to scaled.height,
                                    "scale_x" to software.width.toDouble() / scaled.width,
                                    "scale_y" to software.height.toDouble() / scaled.height,
                                    "offset_x" to 0, "offset_y" to 0,
                                    "data_base64" to Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)))
                            } finally { if (scaled !== software) scaled.recycle() }
                        } finally { software.recycle() }
                    } finally { hardware.recycle() }
                } catch (_: Exception) { pending.complete(json("available" to false, "reason" to "SCREENSHOT_CONVERSION_FAILED")) }
                finally { result.hardwareBuffer.close() }
            }
            override fun onFailure(errorCode: Int) {
                pending.complete(json("available" to false, "reason" to when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "SCREENSHOT_RATE_LIMITED"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "ACCESSIBILITY_UNAVAILABLE"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "DISPLAY_UNSUPPORTED"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "NO_ACTIVE_WINDOW"
                    else -> "SCREENSHOT_UNAVAILABLE"
                }, "android_error_code" to errorCode))
            }
        })
        return try { pending.get(5, TimeUnit.SECONDS) } catch (_: TimeoutException) {
            json("available" to false, "reason" to "SCREENSHOT_TIMEOUT")
        }
    }
    override fun listApps(): JSONObject {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(pm).toString() }
        return json("apps" to JSONArray(apps.map { json("package_name" to it.activityInfo.packageName, "label" to it.loadLabel(pm).toString()) }))
    }
    override fun waitForChange(args: JSONObject, check: () -> Unit): JSONObject {
        val timeout = args.integer("timeout_ms", 5000, 1, 10_000)
        val raw = args.opt("ui_version") as? Number ?: fail("INVALID_ARGUMENT", "ui_version is required")
        val baseline = raw.toLong()
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            check(); ready()
            val version = PhoneAccessibilityService.version.get()
            if (version != baseline) return json("changed" to true, "ui_version" to version)
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return json("changed" to false, "ui_version" to PhoneAccessibilityService.version.get(), "timed_out" to true)
    }
    private fun validateContext(service: PhoneAccessibilityService, args: JSONObject): Snapshot? {
        val snapshot = if (args.has("observation_id")) synchronized(snapshots) {
            snapshots[args.string("observation_id")] ?: fail("STALE_OBSERVATION", "Snapshot expired or was evicted")
        } else null
        val root = service.rootInActiveWindow
        try {
            if (args.has("expected_window_id") && root?.windowId != args.integer("expected_window_id", -1, -1, Int.MAX_VALUE))
                fail("WINDOW_MISMATCH", "Active window changed")
            if (snapshot != null && (SystemClock.elapsedRealtime() - snapshot.created > 30_000 ||
                snapshot.version != PhoneAccessibilityService.version.get() || root?.windowId != snapshot.window ||
                root.packageName?.toString() != snapshot.app || geometry() != Triple(snapshot.width, snapshot.height, snapshot.rotation)))
                fail("STALE_OBSERVATION", "Observe again before acting")
        } finally { root?.recycle() }
        return snapshot
    }
    private fun target(service: PhoneAccessibilityService, args: JSONObject, snapshot: Snapshot?): AccessibilityNodeInfo {
        if (snapshot == null) fail("INVALID_ARGUMENT", "element_id requires observation_id")
        val path = args.string("element_id")
        val expected = snapshot.nodes[path] ?: fail("STALE_ELEMENT", "Element is not part of this observation")
        var node = service.rootInActiveWindow ?: fail("NO_ACTIVE_WINDOW", "No active window")
        try {
            for (part in path.split('.').drop(1)) {
                val child = node.getChild(part.toInt()) ?: fail("STALE_ELEMENT", "Element no longer exists")
                node.recycle(); node = child
            }
            if (!node.refresh() || fingerprint(node) != expected) fail("STALE_ELEMENT", "Element changed")
            if (!node.isEnabled || !node.isVisibleToUser) fail("ELEMENT_UNAVAILABLE", "Element is not enabled and visible")
            return node
        } catch (e: Exception) { node.recycle(); throw e }
    }
    override fun act(name: String, args: JSONObject, check: () -> Unit): JSONObject {
        val service = ready()
        val snapshot = validateContext(service, args)
        val activeRoot = service.rootInActiveWindow
        try {
            if (activeRoot?.packageName?.toString() == context.packageName && name !in setOf("home", "back", "recents", "launch_app"))
                fail("LOCAL_CONTROL_UI", "Phone Use authorization controls can only be operated locally")
        } finally { activeRoot?.recycle() }
        if (name == "input_text" || (name in setOf("tap", "long_press", "scroll") && args.has("element_id"))) {
            val node = target(service, args, snapshot)
            try {
                val action = when (name) {
                    "tap" -> AccessibilityNodeInfo.ACTION_CLICK
                    "long_press" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                    "scroll" -> when (args.string("direction")) {
                        "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else -> fail("INVALID_ARGUMENT", "Node scroll direction must be forward or backward")
                    }
                    else -> AccessibilityNodeInfo.ACTION_SET_TEXT
                }
                if (node.actionList.none { it.id == action }) fail("ACTION_UNSUPPORTED", "Node does not support this action; no automatic fallback")
                val bundle = if (name == "input_text") textArguments(node, args) else null
                val cursor = if (name == "input_text" && args.optString("mode") == "insert") {
                    if (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION })
                        fail("ACTION_UNSUPPORTED", "Cannot restore the insertion cursor on this field")
                    node.textSelectionStart + args.getString("text").length
                } else null
                check()
                if (!node.performAction(action, bundle)) fail("ACTION_REJECTED", "Android rejected node action; observe before retrying")
                val restored = if (cursor != null && runCatching { check() }.isSuccess) node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                    }) else null
                return json("mechanism" to "node_action", "confirmation" to "android_accepted", "business_success" to JSONObject.NULL,
                    "cursor_restored" to restored)
            } finally { node.recycle() }
        }
        when (name) {
            "tap", "long_press", "swipe", "scroll" -> return gesture(service, name, args, check)
            "back", "home", "recents" -> {
                check()
                val action = when (name) { "back" -> AccessibilityService.GLOBAL_ACTION_BACK; "home" -> AccessibilityService.GLOBAL_ACTION_HOME; else -> AccessibilityService.GLOBAL_ACTION_RECENTS }
                if (!service.performGlobalAction(action)) fail("ACTION_REJECTED", "Android rejected navigation")
            }
            "launch_app" -> {
                val intent = context.packageManager.getLaunchIntentForPackage(args.string("package_name"))
                    ?: fail("APP_UNAVAILABLE", "Application is not discoverable or launchable")
                check()
                service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return json("confirmation" to "android_accepted", "business_success" to JSONObject.NULL)
    }
    private fun textArguments(node: AccessibilityNodeInfo, args: JSONObject): Bundle {
        val text = args.opt("text") as? String ?: fail("INVALID_ARGUMENT", "text must be a string")
        if (text.length > 10_000) fail("INVALID_ARGUMENT", "text exceeds 10000 characters")
        if (!node.isEditable) fail("ACTION_UNSUPPORTED", "Target is not editable")
        val value = when (args.string("mode")) {
            "replace" -> text
            "insert" -> {
                if (node.isPassword) fail("ACTION_UNSUPPORTED", "Cannot safely insert into a password field")
                val old = node.text?.toString() ?: fail("ACTION_UNSUPPORTED", "Current text is not readable")
                val start = node.textSelectionStart; val end = node.textSelectionEnd
                if (start < 0 || end < start || end > old.length || old.length + text.length > 20_000)
                    fail("ACTION_UNSUPPORTED", "Text selection is not available or content is too large")
                old.substring(0, start) + text + old.substring(end)
            }
            else -> fail("INVALID_ARGUMENT", "mode must be replace or insert")
        }
        return Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
    }
    private fun gesture(service: PhoneAccessibilityService, name: String, args: JSONObject, check: () -> Unit): JSONObject {
        val (width, height, _) = geometry()
        fun point(obj: JSONObject): Pair<Float, Float> {
            val x = (obj.opt("x") as? Number)?.toDouble() ?: fail("INVALID_ARGUMENT", "x is required")
            val y = (obj.opt("y") as? Number)?.toDouble() ?: fail("INVALID_ARGUMENT", "y is required")
            if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height)
                fail("INVALID_ARGUMENT", "Coordinates must be inside the display")
            return x.toFloat() to y.toFloat()
        }
        val points = if (name == "swipe") {
            val path = args.optJSONArray("points") ?: fail("INVALID_ARGUMENT", "points array is required")
            if (path.length() !in 2..64) fail("INVALID_ARGUMENT", "Provide 2..64 points")
            (0 until path.length()).map { point(path.optJSONObject(it) ?: fail("INVALID_ARGUMENT", "Each point must be an object")) }
        } else if (name == "scroll") {
            val region = args.optJSONObject("region") ?: fail("INVALID_ARGUMENT", "Coordinate scroll requires region")
            val left = region.integer("left", -1, 0, width - 1); val right = region.integer("right", -1, 1, width)
            val top = region.integer("top", -1, 0, height - 1); val bottom = region.integer("bottom", -1, 1, height)
            if (left < 0 || top < 0 || right <= left || bottom <= top) fail("INVALID_ARGUMENT", "Invalid region bounds")
            val x = (left + right) / 2f; val y = (top + bottom) / 2f
            val dx = (right - left) * .3f; val dy = (bottom - top) * .3f
            when (args.string("direction")) {
                "forward", "down" -> listOf(x to y + dy, x to y - dy)
                "backward", "up" -> listOf(x to y - dy, x to y + dy)
                "left" -> listOf(x - dx to y, x + dx to y)
                "right" -> listOf(x + dx to y, x - dx to y)
                else -> fail("INVALID_ARGUMENT", "Invalid scroll direction")
            }
        } else listOf(point(args))
        val duration = args.integer("duration_ms", if (name == "tap") 80 else 600, if (name == "long_press") 500 else 1, 5000)
        val path = Path().apply { moveTo(points[0].first, points[0].second); points.drop(1).forEach { lineTo(it.first, it.second) } }
        val result = CompletableFuture<Boolean>()
        check()
        val accepted = service.dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong())).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { result.complete(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { result.complete(false) }
            }, null)
        if (!accepted) fail("ACTION_REJECTED", "Android rejected the gesture")
        val completed = try { result.get(duration + 2000L, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { fail("RESULT_UNKNOWN", "Gesture callback timed out; do not automatically replay") }
        if (!completed) fail("RESULT_UNKNOWN", "Gesture was interrupted and may have partially executed")
        return json("mechanism" to "gesture", "confirmation" to "gesture_completed", "business_success" to JSONObject.NULL)
    }
}
