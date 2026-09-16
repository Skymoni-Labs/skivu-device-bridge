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
class AndroidBackend(private val context: Context, private val serviceInstanceId: String = UUID.randomUUID().toString()) : DeviceBackend {
    private data class Tree(val elements: JSONArray, val nodes: Map<String, ElementReference>, val exported: Set<String>, val truncated: Boolean)
    private data class Snapshot(val id: String, val version: Long, val relevantVersion: Long,
        val accessibilityInstance: String, val window: Int, val app: String,
        val width: Int, val height: Int, val rotation: Int, val created: Long,
        val windows: String, val nodes: Map<String, ElementReference>, val exported: Set<String>)
    private val snapshots = SnapshotStore<Snapshot>({ SystemClock.elapsedRealtime() })
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
        "max_nodes" to 1500, "snapshot_ttl_ms" to 30_000, "compact_tree_default" to true,
        "snapshot_limit_per_client" to 8, "snapshot_budget_bytes" to 4 * 1024 * 1024,
        "observation_max_attempts" to 3, "observation_quiet_ms" to 120,
        "coordinate_observation_required" to true, "service_instance_id" to serviceInstanceId,
        "deduplication_ttl_ms" to 600_000, "deduplication_scope" to "client + request_id, current server process",
        "mcp_protocol_versions" to JSONArray(listOf("2025-03-26", "2025-06-18", "2025-11-25"))
    )
    override fun observe(args: JSONObject): JSONObject = observe(args, "legacy") {}

    override fun observe(args: JSONObject, client: String, check: () -> Unit): JSONObject {
        val mode = if (args.has("mode")) args.string("mode") else "both"
        if (mode !in setOf("tree", "screenshot", "both")) fail("INVALID_ARGUMENT", "mode must be tree, screenshot or both")
        val compact = args.optBoolean("compact", true)
        val waitMs = args.integer("stable_wait_ms", 600, 0, 2000)
        val maxAttempts = args.integer("max_attempts", 3, 1, 3)
        val maxDimension = args.integer("max_dimension", 1280, 320, 2048)
        val service = ready()
        var firstVersion = PhoneAccessibilityService.version.get()
        var lastWindow = -1
        var lastReason = "content_changed"
        val sampled = StableSampler({ SystemClock.elapsedRealtime() }, { Thread.sleep(it) }).sample(
            waitMs, maxAttempts, check, {
                val root = service.rootInActiveWindow
                try { service.relevantVersion(root?.windowId ?: -1) } finally { root?.recycle() }
            }) {
            val before = PhoneAccessibilityService.version.get()
            firstVersion = before
            val (width, height, rotation) = geometry()
            val root = service.rootInActiveWindow
            val window = root?.windowId ?: -1
            lastWindow = window
            val relevant = service.relevantVersion(window)
            val app = root?.packageName?.toString() ?: ""
            val readableTree = root != null && app != context.packageName
            val windowState: String
            val tree: Tree
            try {
                windowState = windowSignature(service, window)
                tree = if (root != null && readableTree && mode != "screenshot") collect(root, width, height, compact, check)
                    else Tree(JSONArray(), emptyMap(), emptySet(), false)
            } finally { root?.recycle() }
            check()
            val screenshot = if (mode != "tree") screenshot(service, maxDimension) else null
            val afterRoot = service.rootInActiveWindow
            val stableWindow = afterRoot?.windowId ?: -1
            val stableApp = afterRoot?.packageName?.toString() ?: ""
            afterRoot?.recycle()
            lastReason = when {
                relevant > before -> "content_changed"
                ready().instanceId != service.instanceId -> "service_instance_changed"
                window != stableWindow || app != stableApp -> "window_changed"
                geometry() != Triple(width, height, rotation) -> "display_changed"
                windowSignature(service, stableWindow) != windowState -> "window_occlusion_changed"
                relevant != service.relevantVersion(window) -> "content_changed"
                else -> ""
            }
            if (lastReason.isNotEmpty()) null else {
                val id = "$serviceInstanceId:${UUID.randomUUID()}"
                val snapshot = Snapshot(id, before, relevant, service.instanceId, window, app, width, height,
                    rotation, SystemClock.elapsedRealtime(), windowState, tree.nodes, tree.exported)
                val result = json("observation_id" to id, "service_instance_id" to serviceInstanceId,
                    "ui_version" to before, "captured_at_ms" to System.currentTimeMillis(),
                    "window_id" to window, "package_name" to app,
                    "display" to json("id" to 0, "width" to width, "height" to height, "rotation" to rotation * 90,
                "coordinates" to "physical_pixels", "origin" to "top_left", "includes_system_bars_and_ime" to true),
                    "tree" to if (mode == "screenshot") null else json("available" to readableTree, "compact" to compact,
                "reason" to if (app == context.packageName) "LOCAL_CONTROL_UI" else if (window == -1) "NO_ACTIVE_WINDOW" else null,
                        "truncated" to tree.truncated, "elements" to tree.elements), "screenshot" to screenshot)
                snapshot to result
            }
        } ?: fail("OBSERVATION_INCONSISTENT", "UI kept changing during bounded observation retries", json(
            "reason" to lastReason, "collection_attempts" to maxAttempts, "snapshot_ui_version" to firstVersion,
            "current_ui_version" to PhoneAccessibilityService.version.get(), "events" to service.eventsSince(firstVersion, lastWindow)))
        val (snapshot, result) = sampled.value
        // Conservative retained-memory estimate includes object/map overhead and UTF-16 strings.
        val bytes = 1024L + snapshot.nodes.entries.sumOf { (path, node) ->
            640L + 2L * (path.length + node.reference.content.length + node.layout.length + node.subtree.length + (node.container?.length ?: 0))
        } + snapshot.exported.sumOf { 64L + it.length * 2L }
        if (!snapshots.put(client, snapshot.id, bytes, snapshot, snapshot.version)) fail("BUSY", "Device snapshot memory budget is full", json("reason" to "snapshot_budget_exceeded"))
        return result.put("collection_attempts", sampled.attempts)
    }

    private fun hash(value: Any?): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun identity(node: AccessibilityNodeInfo): List<Any?> = listOf(node.windowId, node.hashCode(),
        if (android.os.Build.VERSION.SDK_INT >= 33) node.uniqueId else null, node.viewIdResourceName, node.className?.toString())

    private fun semantics(node: AccessibilityNodeInfo) = NodeSemantics(
        checkable = node.isCheckable, checked = node.isChecked, selected = node.isSelected,
        stateDescription = node.stateDescription?.toString(),
        range = node.rangeInfo?.let { listOf(it.type, it.min.takeIf { value -> value.isFinite() },
            it.max.takeIf { value -> value.isFinite() }, it.current.takeIf { value -> value.isFinite() }) },
        collectionItem = node.collectionItemInfo?.let { listOf(it.rowIndex, it.rowSpan, it.columnIndex, it.columnSpan, it.isHeading, it.isSelected) },
        collection = node.collectionInfo?.let { listOf(it.rowCount, it.columnCount, it.isHierarchical, it.selectionMode) },
        hint = node.hintText?.toString(), error = node.error?.toString(), contentInvalid = node.isContentInvalid)

    private fun fingerprint(node: AccessibilityNodeInfo): NodeReference {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val value = identity(node) + listOf(node.text?.toString(), node.contentDescription?.toString(),
            bounds.flattenToString(), node.isEnabled, node.isVisibleToUser, node.isEditable, node.isPassword,
            node.actionList.map { it.id }.sorted())
        return NodeReference(nodeContentHash(value, semantics(node)), node.textSelectionStart, node.textSelectionEnd)
    }

    private fun windowSignature(service: PhoneAccessibilityService, activeWindow: Int): String {
        val windows = service.windows
        try {
            if (windows.any { it.id == activeWindow && it.displayId != Display.DEFAULT_DISPLAY })
                fail("DISPLAY_UNSUPPORTED", "Only the default display is supported")
            return hash(windows.filter { it.displayId == Display.DEFAULT_DISPLAY }.sortedBy { it.id }.map {
                val bounds = Rect(); it.getBoundsInScreen(bounds)
                listOf(it.id, it.type, it.layer, it.isActive, it.isFocused, bounds.flattenToString())
            })
        } finally { windows.forEach { it.recycle() } }
    }

    private fun collect(root: AccessibilityNodeInfo, width: Int, height: Int, compact: Boolean, check: () -> Unit): Tree {
        val states = LinkedHashMap<String, ElementReference>()
        val elements = LinkedHashMap<String, JSONObject>()
        val order = mutableListOf<String>()
        val retained = HashSet<String>()
        var truncated = false
        val screen = Rect(0, 0, width, height)
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int, row: String?): String {
            if (order.size >= 1500 || depth > 40) { truncated = true; return "TRUNCATED" }
            if (order.size % 32 == 0) check()
            order.add(path)
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val reference = fingerprint(node)
            val childLayouts = mutableListOf<Any?>()
            val childHashes = mutableListOf<String>()
            val currentRow = if (node.collectionItemInfo != null) path else row
            var complete = true
            for (i in 0 until node.childCount) {
                if (order.size >= 1500) { truncated = true; complete = false; break }
                val child = node.getChild(i)
                if (child == null) { complete = false; childHashes.add("MISSING:$i"); childLayouts.add("MISSING:$i"); continue }
                try {
                    val childBounds = Rect(); child.getBoundsInScreen(childBounds)
                    childLayouts.add(listOf(i, identity(child), childBounds.flattenToString()))
                    val childPath = "$path.$i"
                    childHashes.add(visit(child, childPath, depth + 1, currentRow ?: if (node.isScrollable) childPath else null))
                    if (states[childPath]?.complete != true) complete = false
                } finally { child.recycle() }
            }
            val parent = path.substringBeforeLast('.', "")
            val container = currentRow ?: if (node.isScrollable) path else parent.takeUnless { it.isEmpty() || it == "0" }
            val layout = hash(listOf(identity(node), bounds.flattenToString(), node.childCount,
                node.isVisibleToUser, node.isEnabled, node.isScrollable, childLayouts))
            val subtree = hash(listOf(reference.content, childHashes))
            states[path] = ElementReference(reference, layout, subtree, container, complete)
            val meaningful = !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty() ||
                !node.viewIdResourceName.isNullOrEmpty() || node.isEditable || node.isClickable || node.isLongClickable || node.isScrollable ||
                retained.any { it.substringBeforeLast('.', "") == path }
            if (!compact || (node.isVisibleToUser && !bounds.isEmpty && Rect.intersects(bounds, screen) && meaningful)) {
                retained.add(path)
                val element = json("element_id" to path, "class" to node.className?.toString(),
                    "bounds" to JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                fun optional(key: String, value: Any?, include: Boolean) { if (!compact || include) element.put(key, value ?: JSONObject.NULL) }
                optional("text", if (node.isPassword) null else node.text?.toString()?.take(1024), !node.isPassword && !node.text.isNullOrEmpty())
                optional("description", node.contentDescription?.toString()?.take(1024), !node.contentDescription.isNullOrEmpty())
                optional("view_id", node.viewIdResourceName, !node.viewIdResourceName.isNullOrEmpty())
                optional("enabled", node.isEnabled, !node.isEnabled)
                optional("visible", node.isVisibleToUser, !node.isVisibleToUser)
                optional("editable", node.isEditable, node.isEditable)
                optional("password", node.isPassword, node.isPassword)
                optional("focused", node.isFocused, node.isFocused)
                optional("checkable", node.isCheckable, node.isCheckable)
                optional("checked", node.isChecked, node.isCheckable)
                optional("selected", node.isSelected, node.isSelected)
                optional("state_description", node.stateDescription?.toString()?.take(1024), !node.stateDescription.isNullOrEmpty())
                optional("hint", node.hintText?.toString()?.take(1024), !node.hintText.isNullOrEmpty())
                optional("error", node.error?.toString()?.take(1024), !node.error.isNullOrEmpty())
                val semanticState = semantics(node)
                optional("range", semanticState.range?.let { JSONArray(it) }, semanticState.range != null)
                optional("collection_item", semanticState.collectionItem?.let { JSONArray(it) }, semanticState.collectionItem != null)
                optional("collection", semanticState.collection?.let { JSONArray(it) }, semanticState.collection != null)
                if (!compact || node.actionList.isNotEmpty()) element.put("actions", JSONArray(node.actionList.map { actionName(it.id) }))
                if (node.isEditable && !node.isPassword) element.put("selection", JSONArray(listOf(node.textSelectionStart, node.textSelectionEnd)))
                elements[path] = element
            }
            return subtree
        }
        visit(root, "0", 0, null)
        val out = JSONArray()
        order.filter { it in retained }.forEach { path ->
            var parent = path.substringBeforeLast('.', "")
            while (parent.isNotEmpty() && parent !in retained) parent = parent.substringBeforeLast('.', "")
            out.put(elements.getValue(path).put("parent_id", parent.takeIf { it.isNotEmpty() } ?: JSONObject.NULL))
        }
        return Tree(out, states, retained, truncated)
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
    private fun diagnostics(service: PhoneAccessibilityService, snapshot: Snapshot?, reason: String): JSONObject = json(
        "reason" to reason, "action_executed" to false,
        "snapshot_age_ms" to snapshot?.let { SystemClock.elapsedRealtime() - it.created },
        "snapshot_ui_version" to snapshot?.version, "current_ui_version" to PhoneAccessibilityService.version.get(),
        "events" to if (snapshot != null) service.eventsSince(snapshot.version, snapshot.window) else JSONArray())

    private fun stale(service: PhoneAccessibilityService, snapshot: Snapshot?, reason: String,
                      code: String = "STALE_OBSERVATION"): Nothing =
        fail(code, "Observation cannot safely identify this target; observe again", diagnostics(service, snapshot, reason))

    private fun validateContext(service: PhoneAccessibilityService, args: JSONObject, client: String): Snapshot? {
        val snapshot = if (args.has("observation_id")) {
            val id = args.string("observation_id")
            if (id.substringBefore(':', "") != serviceInstanceId) stale(service, null, "service_instance_changed")
            val found = snapshots.get(client, id)
            found.value ?: run {
                val detail = diagnostics(service, found.rejectedValue, found.reason ?: "not_found")
                found.created?.let { detail.put("snapshot_age_ms", SystemClock.elapsedRealtime() - it) }
                found.version?.let { detail.put("snapshot_ui_version", it) }
                fail("STALE_OBSERVATION", "Snapshot is no longer available to this client; observe again", detail)
            }
        } else null
        if (ready().instanceId != service.instanceId) stale(service, snapshot, "service_instance_changed")
        val root = service.rootInActiveWindow
        try {
            if (args.has("expected_window_id") && root?.windowId != args.integer("expected_window_id", -1, -1, Int.MAX_VALUE))
                stale(service, snapshot, "expected_window_mismatch", "WINDOW_MISMATCH")
            if (snapshot != null) {
                if (snapshot.accessibilityInstance != service.instanceId) stale(service, snapshot, "service_instance_changed")
                if (SystemClock.elapsedRealtime() - snapshot.created > 30_000) stale(service, snapshot, "expired")
                if (root?.windowId != snapshot.window || root?.packageName?.toString() != snapshot.app) stale(service, snapshot, "window_changed")
                if (geometry() != Triple(snapshot.width, snapshot.height, snapshot.rotation)) stale(service, snapshot, "display_changed")
                if (windowSignature(service, snapshot.window) != snapshot.windows) stale(service, snapshot, "window_occlusion_changed")
                // Coordinates have no target/container evidence with which to approve a changed scene.
                if (!args.has("element_id") && snapshot.relevantVersion != service.relevantVersion(snapshot.window))
                    stale(service, snapshot, "content_changed")
            }
        } finally { root?.recycle() }
        return snapshot
    }
    private data class ValidatedTarget(val node: AccessibilityNodeInfo, val version: Long)

    private fun target(service: PhoneAccessibilityService, args: JSONObject, snapshot: Snapshot?, name: String, check: () -> Unit): ValidatedTarget {
        if (snapshot == null) fail("INVALID_ARGUMENT", "element_id requires observation_id")
        val path = args.string("element_id")
        if (path !in snapshot.exported) stale(service, snapshot, "element_not_in_observation", "STALE_ELEMENT")
        val expected = snapshot.nodes.getValue(path)
        val insert = name == "input_text" && args.optString("mode") == "insert"
        val version = service.relevantVersion(snapshot.window)
        var node = service.rootInActiveWindow ?: stale(service, snapshot, "window_changed")
        try {
            val current = collect(node, snapshot.width, snapshot.height, false, check)
            targetChangeReason(snapshot.nodes, current.nodes, path, insert)?.let {
                stale(service, snapshot, it, "STALE_ELEMENT")
            }
            if (version != service.relevantVersion(snapshot.window)) stale(service, snapshot, "changed_during_validation")
            for (part in path.split('.').drop(1)) {
                val child = node.getChild(part.toInt()) ?: stale(service, snapshot, "element_missing", "STALE_ELEMENT")
                node.recycle(); node = child
            }
            if (!node.refresh() || !expected.reference.matches(fingerprint(node), insert)) stale(service, snapshot, "target_changed", "STALE_ELEMENT")
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            if (!node.isEnabled || !node.isVisibleToUser || bounds.isEmpty || !Rect.intersects(bounds, Rect(0, 0, snapshot.width, snapshot.height)))
                stale(service, snapshot, "target_unavailable", "ELEMENT_UNAVAILABLE")
            val windows = service.windows
            try {
                val layer = windows.find { it.id == snapshot.window }?.layer
                if (layer != null && windows.any {
                    val area = Rect(); it.getBoundsInScreen(area)
                    it.displayId == Display.DEFAULT_DISPLAY && it.id != snapshot.window && it.layer > layer && Rect.intersects(area, bounds)
                }) stale(service, snapshot, "target_occluded", "STALE_ELEMENT")
            } finally { windows.forEach { it.recycle() } }
            if (windowSignature(service, snapshot.window) != snapshot.windows) stale(service, snapshot, "window_occlusion_changed")
            if (version != service.relevantVersion(snapshot.window)) stale(service, snapshot, "changed_during_validation")
            return ValidatedTarget(node, version)
        } catch (e: Exception) { node.recycle(); throw e }
    }
    override fun act(name: String, args: JSONObject, check: () -> Unit): JSONObject = act(name, args, "legacy", check)

    override fun act(name: String, args: JSONObject, client: String, check: () -> Unit): JSONObject = try {
        actChecked(name, args, client, check)
    } catch (error: DeviceError) {
        if (error.code != "RESULT_UNKNOWN") error.details.put("action_executed", false)
        throw error
    }

    private fun actChecked(name: String, args: JSONObject, client: String, check: () -> Unit): JSONObject {
        val service = ready()
        if (name in setOf("tap", "long_press", "swipe", "scroll") && !args.has("element_id") && !args.has("observation_id"))
            fail("INVALID_ARGUMENT", "Coordinate actions require observation_id", json("reason" to "observation_required"))
        val snapshot = validateContext(service, args, client)
        val activeRoot = service.rootInActiveWindow
        try {
            if (activeRoot?.packageName?.toString() == context.packageName && name !in setOf("home", "back", "recents", "launch_app"))
                fail("LOCAL_CONTROL_UI", "Phone Use authorization controls can only be operated locally")
        } finally { activeRoot?.recycle() }
        if (name == "input_text" || (name in setOf("tap", "long_press", "scroll") && args.has("element_id"))) {
            val validated = target(service, args, snapshot, name, check)
            val node = validated.node
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
                if (validated.version != service.relevantVersion(snapshot!!.window)) stale(service, snapshot, "changed_before_dispatch")
                if (ready().instanceId != service.instanceId) stale(service, snapshot, "service_instance_changed")
                if (geometry() != Triple(snapshot.width, snapshot.height, snapshot.rotation)) stale(service, snapshot, "display_changed")
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
            "tap", "long_press", "swipe", "scroll" -> return gesture(service, name, args) {
                check()
                validateContext(service, args, client)
            }
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
