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
import android.graphics.Region
import android.graphics.RegionIterator
import androidx.core.graphics.scale
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
        "observation_timeout_max_ms" to 60_000, "condition_matching" to "exact fields; AND; unique by default, explicit exists supported",
        "tree_output_modes" to JSONArray(listOf("legacy", "decision")),
        "observation_scope" to "output projection only; subtree references revalidated",
        "coordinate_observation_required" to true, "service_instance_id" to serviceInstanceId,
        "deduplication_ttl_ms" to 600_000, "deduplication_scope" to "client + request_id, current server process",
        "mcp_protocol_versions" to JSONArray(listOf("2025-03-26", "2025-06-18", "2025-11-25"))
    )
    override fun observe(args: JSONObject): JSONObject = observe(args, "legacy") {}

    override fun observe(args: JSONObject, client: String, check: () -> Unit): JSONObject {
        val mode = args.optString("mode", "both")
        if (mode !in setOf("tree", "screenshot", "both")) fail("INVALID_ARGUMENT", "mode must be tree, screenshot or both")
        val compact = args.optBoolean("compact", true)
        val decision = args.optString("output", "legacy") == "decision"
        val maxDimension = args.integer("max_dimension", 1280, 320, 2048)
        val service = ready()
        val started = System.currentTimeMillis()
        val captures = JSONArray()
        val candidates = LinkedHashMap<String, Snapshot>()
        val checked = { check(); if (ready().instanceId != service.instanceId) fail("ACCESSIBILITY_UNAVAILABLE", "Accessibility service changed during observation") }
        var result = ConditionalObserver({ SystemClock.elapsedRealtime() }, { Thread.sleep(it) }).run(args, checked, {
            val root = service.rootInActiveWindow
            try { service.relevantVersion(root?.windowId ?: -1) } finally { root?.recycle() }
        }) { budget, captureCheck ->
            val captureStart = System.currentTimeMillis()
            val captureDiagnostic = json("started_at_ms" to captureStart)
            if (args.optBoolean("diagnostics") && captures.length() < 64) captures.put(captureDiagnostic)
            try {
                val before = PhoneAccessibilityService.version.get()
                val (width, height, rotation) = geometry()
                val scope = args.optJSONObject("subtree")
                val source = scope?.let { validateContext(service, it, client) }
                val root = service.rootInActiveWindow
                val window = root?.windowId ?: -1
                val relevant = service.relevantVersion(window)
                val app = root?.packageName?.toString() ?: ""
                captureDiagnostic.put("before_window_id", window).put("before_package_name", app).put("before_ui_version", before)
                val readableTree = root != null && app != context.packageName
                val windowState: String
                val tree: Tree
                try {
                    windowState = windowSignature(service, window, observing = true)
                    tree = if (root != null && readableTree && (mode != "screenshot" || args.has("wait_until") || scope != null))
                        collect(root, width, height, false, captureCheck) else Tree(JSONArray(), emptyMap(), emptySet(), false)
                } finally { root?.recycle() }
                captureCheck()
                if (scope != null) {
                    val path = scope.getString("element_id")
                    if (source == null || path !in source.exported) stale(service, source, "element_not_in_observation", "STALE_ELEMENT")
                    targetChangeReason(source.nodes, tree.nodes, path, false)?.let { stale(service, source, it, "STALE_ELEMENT") }
                }
                val screenshot = if (mode != "tree") screenshot(service, maxDimension, budget, captureCheck) else null
                captureCheck()
                val afterRoot = service.rootInActiveWindow
                val stableWindow = afterRoot?.windowId ?: -1
                val stableApp = afterRoot?.packageName?.toString() ?: ""
                afterRoot?.recycle()
                val reason = when {
                    relevant > before -> "content_changed"
                    ready().instanceId != service.instanceId -> "service_instance_changed"
                    window != stableWindow || app != stableApp -> "window_changed"
                    geometry() != Triple(width, height, rotation) -> "display_changed"
                    windowSignature(service, stableWindow, observing = true) != windowState -> "window_occlusion_changed"
                    relevant != service.relevantVersion(window) -> "content_changed"
                    else -> ""
                }
                captureDiagnostic.put("finished_at_ms", System.currentTimeMillis()).put("after_window_id", stableWindow)
                    .put("after_package_name", stableApp).put("after_ui_version", PhoneAccessibilityService.version.get())
                    .put("inconsistency_reason", reason.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
                if (reason.isNotEmpty()) fail("OBSERVATION_INCONSISTENT", "UI changed during observation collection", json(
                    "reason" to reason, "snapshot_ui_version" to before, "current_ui_version" to PhoneAccessibilityService.version.get(),
                    "events" to service.eventsSince(before, window)))
                val projected = projectTree(tree.elements, decision, compact, args.optBoolean("include_details"),
                    args.optJSONObject("region"), scope?.getString("element_id"))
                val outputElements = projected.getJSONArray("elements")
                val exported = if (mode == "screenshot") emptySet() else (0 until outputElements.length()).map {
                    outputElements.getJSONObject(it).getString("element_id") }.toSet()
                val id = "$serviceInstanceId:${UUID.randomUUID()}"
                val snapshot = Snapshot(id, before, relevant, service.instanceId, window, app, width, height,
                    rotation, SystemClock.elapsedRealtime(), windowState, tree.nodes, exported)
                candidates[id] = snapshot
                while (candidates.size > 2) candidates.remove(candidates.keys.first())
                json("observation_id" to id, "service_instance_id" to serviceInstanceId,
                    "ui_version" to before, "captured_at_ms" to System.currentTimeMillis(),
                    "window_id" to window, "package_name" to app,
                    "display" to json("id" to 0, "width" to width, "height" to height, "rotation" to rotation * 90,
                        "coordinates" to "physical_pixels", "origin" to "top_left", "includes_system_bars_and_ime" to true),
                    "tree" to if (mode == "screenshot") null else projected.put("available", readableTree).put("compact", compact)
                        .put("reason", if (app == context.packageName) "LOCAL_CONTROL_UI" else if (window == -1) "NO_ACTIVE_WINDOW" else JSONObject.NULL)
                        .put("truncated", tree.truncated).put("collection_truncated", tree.truncated)
                        .put("collection_complete", tree.nodes["0"]?.complete == true),
                    "screenshot" to screenshot).apply {
                    args.optJSONObject("wait_until")?.let { put("condition", evaluateCondition(it, app, tree.elements, readableTree,
                        tree.truncated, tree.nodes["0"]?.complete == true)) }
                }
            } catch (error: Exception) {
                captureDiagnostic.put("capture_error", (error as? DeviceError)?.code ?: "INTERNAL_ERROR")
                (error as? DeviceError)?.details?.optString("reason")?.takeIf { it.isNotEmpty() }?.let {
                    captureDiagnostic.put("inconsistency_reason", it)
                }
                throw error
            } finally {
                if (!captureDiagnostic.has("finished_at_ms")) captureDiagnostic.put("finished_at_ms", System.currentTimeMillis()).put("aborted", true)
            }
        }
        val retained = if (result.has("observation_id")) result else result.optJSONObject("last_observation")
        retained?.optString("observation_id")?.let { id -> candidates[id]?.let { snapshot ->
            // Include map overhead, all fingerprints and provider IDs in the retained budget.
            val bytes = 1024L + snapshot.nodes.entries.sumOf { (path, node) ->
                768L + 2L * (path.length + node.reference.content.length + node.layout.length + node.subtree.length +
                    (node.container?.length ?: 0) + (node.identity?.length ?: 0) + (node.nonTextContent?.length ?: 0) +
                    (node.textContent?.length ?: 0) + (node.stableId?.length ?: 0) + (node.contentAnchor?.length ?: 0) + (node.logicalItemId?.length ?: 0))
            } + snapshot.exported.sumOf { 64L + it.length * 2L }
            if (!snapshots.put(client, id, bytes, snapshot, snapshot.version, createdAt = snapshot.created)) {
                val previous = result
                result = errorResult(DeviceError("BUSY", "Device snapshot memory budget is full", json("reason" to "snapshot_budget_exceeded")))
                    .put("observation_status", "failed").put("failure_stage", "snapshot_retention")
                    .put("collection_attempts", previous.optInt("collection_attempts"))
                    .put("inconsistency_detected", previous.optBoolean("inconsistency_detected"))
                if (args.has("wait_until")) result.put("condition", json("status" to "unknown", "timed_out" to false))
                previous.optJSONObject("error")?.let { result.put("previous_observation_error", it) }
            }
        } }
        if (args.optBoolean("diagnostics")) {
            for (i in 0 until captures.length()) captures.getJSONObject(i).let { capture ->
                if (!capture.has("finished_at_ms")) capture.put("finished_at_ms", System.currentTimeMillis())
                    .put("aborted", true).put("failure_stage", result.optString("failure_stage"))
            }
            result.put("diagnostics", json("started_at_ms" to started,
            "finished_at_ms" to System.currentTimeMillis(), "captures" to captures,
            "capture_diagnostics_truncated" to (result.optInt("collection_attempts") > captures.length()),
                "consistency_scope" to "accessibility events, active window, package, display and window regions; not atomic tree/screenshot frames"))
        }
        return result
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
            node.actionList.map { it.id }.sorted(), logicalItemId(node))
        return NodeReference(nodeContentHash(value, semantics(node)), node.textSelectionStart, node.textSelectionEnd)
    }

    private data class WindowEvidence(val id: Int, val type: Int, val layer: Int, val bounds: Rect,
        val region: List<ScreenRect>, val active: Boolean, val focused: Boolean, val pictureInPicture: Boolean)

    private fun regionRects(region: Region): List<ScreenRect> {
        val iterator = RegionIterator(region)
        val rect = Rect()
        val result = mutableListOf<ScreenRect>()
        while (iterator.next(rect)) {
            if (result.size >= 256) fail("STALE_OBSERVATION", "Window input region is too complex to verify", json(
                "reason" to "window_region_unverifiable", "action_executed" to false))
            result.add(ScreenRect(rect.left, rect.top, rect.right, rect.bottom))
        }
        return result
    }

    private fun windowEvidence(service: PhoneAccessibilityService, activeWindow: Int): List<WindowEvidence> {
        val windows = service.windows
        try {
            if (windows.any { it.id == activeWindow && it.displayId != Display.DEFAULT_DISPLAY })
                fail("DISPLAY_UNSUPPORTED", "Only the default display is supported")
            if (windows.size > 64) fail("STALE_OBSERVATION", "Too many windows to verify", json("reason" to "window_region_unverifiable"))
            return windows.filter { it.displayId == Display.DEFAULT_DISPLAY }.sortedBy { it.id }.map {
                val bounds = Rect(); it.getBoundsInScreen(bounds)
                val region = Region(); it.getRegionInScreen(region)
                WindowEvidence(it.id, it.type, it.layer, bounds, regionRects(region), it.isActive, it.isFocused, it.isInPictureInPictureMode)
            }
        } finally { windows.forEach { it.recycle() } }
    }

    private fun windowSignature(service: PhoneAccessibilityService, activeWindow: Int, observing: Boolean = false): String {
        val windows = windowEvidence(service, activeWindow)
        if (observing && activeWindow != -1 && windows.none { it.id == activeWindow })
            fail("OBSERVATION_INCONSISTENT", "The active root is not yet represented in the window inventory", json("reason" to "window_inventory_incomplete"))
        return hash(windows.map {
            listOf(it.id, it.type, it.layer, it.active, it.focused, it.bounds.flattenToString(), it.region, it.pictureInPicture)
        })
    }

    private fun Rect.screenRect() = ScreenRect(left, top, right, bottom)
    private fun ScreenRect.json() = JSONArray(listOf(left, top, right, bottom))
    private fun windowDetail(window: WindowEvidence, overlap: List<ScreenRect>) = json(
        "window_id" to window.id, "type" to window.type, "layer" to window.layer,
        "bounds" to window.bounds.screenRect().json(), "touchable_region" to JSONArray(window.region.map { it.json() }),
        "overlap" to JSONArray(overlap.map { it.json() }), "active" to window.active, "focused" to window.focused,
        "picture_in_picture" to window.pictureInPicture, "region_basis" to "platform_touchable_region",
        "visual_visibility_verified" to false)

    private fun validateNodeOcclusion(service: PhoneAccessibilityService, snapshot: Snapshot, bounds: Rect, semanticScroll: Boolean): JSONArray {
        val windows = windowEvidence(service, snapshot.window)
        val active = windows.find { it.id == snapshot.window } ?: stale(service, snapshot, "window_region_unverifiable")
        val area = bounds.screenRect()
        val hits = JSONArray()
        val exceptions = JSONArray()
        for (window in windows.filter { it.id != snapshot.window && it.layer > active.layer }) {
            val overlap = window.region.filter { it.intersects(area) }.map {
                ScreenRect(maxOf(it.left, area.left), maxOf(it.top, area.top), minOf(it.right, area.right), minOf(it.bottom, area.bottom))
            }
            if (overlap.isEmpty()) continue
            if (semanticScroll && permitsCornerPipScroll(area, overlap,
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION, window.pictureInPicture, window.active, window.focused,
                    active.active, active.focused)) {
                exceptions.put(windowDetail(window, overlap).put("basis", "platform_pip_in_small_corner_without_focus_or_activation"))
                continue
            }
            hits.put(windowDetail(window, overlap).put("blocking_reason", if (semanticScroll && !window.pictureInPicture)
                "modality_unverifiable" else "target_intersects_touchable_region"))
        }
        if (hits.length() > 0) fail("STALE_ELEMENT", "A higher window blocks this target", diagnostics(service, snapshot, "target_occluded")
            .put("occlusion", json("source" to "higher_window", "target_bounds" to area.json(), "windows" to hits,
                "action" to if (semanticScroll) "semantic_scroll" else "node_action", "visible_region_verified" to false)))
        return exceptions
    }

    /** Accessibility window lists cannot reveal siblings within the same window. */
    private fun validateSameWindowOcclusion(service: PhoneAccessibilityService, snapshot: Snapshot, target: AccessibilityNodeInfo,
        bounds: Rect, check: () -> Unit) {
        var branch = AccessibilityNodeInfo.obtain(target)
        var examined = 0
        try {
            repeat(40) {
                check()
                val parent = branch.parent ?: return
                try {
                    for (index in 0 until parent.childCount) {
                        if (++examined > 1500) stale(service, snapshot, "same_window_occlusion_unverifiable")
                        if (examined % 16 == 1) check()
                        val sibling = parent.getChild(index) ?: stale(service, snapshot, "same_window_occlusion_unverifiable")
                        try {
                            if (sibling == branch || !sibling.isVisibleToUser || sibling.drawingOrder < branch.drawingOrder) continue
                            val siblingBounds = Rect(); sibling.getBoundsInScreen(siblingBounds)
                            if (!Rect.intersects(bounds, siblingBounds)) continue
                            fail("STALE_ELEMENT", "An overlapping sibling may block this target", diagnostics(service, snapshot, "target_occluded")
                                .put("occlusion", json("source" to "same_window_sibling", "window_id" to snapshot.window,
                                    "target_bounds" to bounds.screenRect().json(), "sibling_bounds" to siblingBounds.screenRect().json(),
                                    "sibling_identity" to accessibilityIdentityHash(sibling), "basis" to "bounds_and_drawing_order",
                                    "blocking_reason" to "same_window_occlusion_unverifiable", "visible_region_verified" to false)))
                        } finally { sibling.recycle() }
                    }
                    branch.recycle(); branch = AccessibilityNodeInfo.obtain(parent)
                } finally { parent.recycle() }
            }
            stale(service, snapshot, "same_window_occlusion_unverifiable")
        } finally { branch.recycle() }
    }

    private fun validateGestureOcclusion(service: PhoneAccessibilityService, snapshot: Snapshot, points: List<Pair<Float, Float>>) {
        val windows = windowEvidence(service, snapshot.window)
        val active = windows.find { it.id == snapshot.window } ?: stale(service, snapshot, "window_region_unverifiable")
        val hits = windows.filter { it.id != active.id && it.layer > active.layer && pathIntersectsRegion(points, it.region) }
        if (hits.isNotEmpty()) fail("STALE_OBSERVATION", "The gesture path intersects a higher window", diagnostics(service, snapshot, "target_occluded")
            .put("occlusion", json("source" to "higher_window", "action" to "coordinate_gesture",
                "path" to JSONArray(points.map { JSONArray(listOf(it.first, it.second)) }),
                "windows" to JSONArray(hits.map { windowDetail(it, it.region) }), "visible_region_verified" to false)))
        // Require the whole path to remain within the platform-reported active input region.
        val outside = Region(0, 0, snapshot.width, snapshot.height)
        val input = Region()
        active.region.forEach { input.op(Rect(it.left, it.top, it.right, it.bottom), Region.Op.UNION) }
        outside.op(input, Region.Op.DIFFERENCE)
        if (pathIntersectsRegion(points, regionRects(outside))) stale(service, snapshot, "gesture_outside_active_window")
    }

    /** Detect exposed overlays inside a custom scroll host as well as overlays beside it. */
    private fun validateDescendantOcclusion(service: PhoneAccessibilityService, snapshot: Snapshot,
        target: AccessibilityNodeInfo, bounds: Rect, check: () -> Unit) {
        var examined = 0
        var comparisons = 0
        fun visit(parent: AccessibilityNodeInfo, depth: Int) {
            check()
            if (depth > 40) stale(service, snapshot, "same_window_occlusion_unverifiable")
            val visibleChildren = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
            try {
                for (index in 0 until parent.childCount) {
                    if (++examined > 1500) stale(service, snapshot, "same_window_occlusion_unverifiable")
                    if (examined % 16 == 1) check()
                    val child = parent.getChild(index) ?: stale(service, snapshot, "same_window_occlusion_unverifiable")
                    val area = Rect(); child.getBoundsInScreen(area)
                    if (child.isVisibleToUser && area.intersect(bounds)) visibleChildren.add(child to area) else child.recycle()
                }
                for (i in visibleChildren.indices) for (j in i + 1 until visibleChildren.size) {
                    if (++comparisons > 5000) stale(service, snapshot, "same_window_occlusion_unverifiable")
                    if (comparisons % 32 == 1) check()
                    val (first, a) = visibleChildren[i]; val (second, b) = visibleChildren[j]
                    if (Rect.intersects(a, b)) fail("STALE_ELEMENT", "Overlapping child branches may obscure this scrolling region",
                        diagnostics(service, snapshot, "target_occluded").put("occlusion", json(
                            "source" to "same_window_descendant_overlap", "window_id" to snapshot.window,
                            "target_bounds" to bounds.screenRect().json(),
                            "branch_bounds" to JSONArray(listOf(a.screenRect().json(), b.screenRect().json())),
                            "branch_identities" to JSONArray(listOf(accessibilityIdentityHash(first), accessibilityIdentityHash(second))),
                            "basis" to "overlapping_exposed_sibling_branches", "visible_region_verified" to false)))
                }
                visibleChildren.forEach { (child, _) -> visit(child, depth + 1) }
            } finally { visibleChildren.forEach { it.first.recycle() } }
        }
        visit(target, 0)
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
            val nonText = nodeContentHash(identity(node) + listOf(node.contentDescription?.toString(), bounds.flattenToString(),
                node.isEnabled, node.isVisibleToUser, node.isEditable, node.isPassword, node.actionList.map { it.id }.sorted(),
                node.textSelectionStart, node.textSelectionEnd, node.isFocused, node.isAccessibilityFocused, logicalItemId(node)), semantics(node))
            states[path] = ElementReference(reference, layout, subtree, container, complete,
                identity = accessibilityIdentityHash(node), nonTextContent = nonText, textContent = hash(node.text?.toString()),
                stableId = if (android.os.Build.VERSION.SDK_INT >= 33) node.uniqueId else null,
                contentAnchor = if (!node.isPassword && (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()))
                    hash(listOf(node.text?.toString(), node.contentDescription?.toString())) else null,
                collectionRow = node.collectionItemInfo != null, logicalItemId = logicalItemId(node))
            val meaningful = !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty() ||
                !node.viewIdResourceName.isNullOrEmpty() || node.isEditable || node.isClickable || node.isLongClickable || node.isScrollable ||
                !node.hintText.isNullOrEmpty() || !node.error.isNullOrEmpty() || !node.stateDescription.isNullOrEmpty() || node.actionList.isNotEmpty() ||
                retained.any { it.substringBeforeLast('.', "") == path }
            val compactKeep = node.isVisibleToUser && !bounds.isEmpty && Rect.intersects(bounds, screen) && meaningful
            if (compactKeep) retained.add(path)
            run {
                val element = json("element_id" to path, "class" to node.className?.toString(),
                    "_compact_keep" to compactKeep,
                    "_on_screen" to Rect.intersects(bounds, screen),
                    "bounds" to JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                fun optional(key: String, value: Any?, include: Boolean) { element.put(key, value ?: JSONObject.NULL) }
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
                optional("content_invalid", node.isContentInvalid, node.isContentInvalid)
                if (!node.isPassword && (node.text?.length ?: 0) > 1024) element.put("text_truncated", true)
                    .put("_text_hash", conditionTextHash(node.text.toString()))
                if ((node.contentDescription?.length ?: 0) > 1024) element.put("description_truncated", true)
                    .put("_description_hash", conditionTextHash(node.contentDescription.toString()))
                val semanticState = semantics(node)
                optional("range", semanticState.range?.let { JSONArray(it) }, semanticState.range != null)
                optional("collection_item", semanticState.collectionItem?.let { JSONArray(it) }, semanticState.collectionItem != null)
                optional("collection", semanticState.collection?.let { JSONArray(it) }, semanticState.collection != null)
                element.put("actions", JSONArray(node.actionList.map { actionName(it.id) }))
                if (node.isEditable && !node.isPassword) element.put("selection", JSONArray(listOf(node.textSelectionStart, node.textSelectionEnd)))
                elements[path] = element
            }
            return subtree
        }
        visit(root, "0", 0, null)
        val out = JSONArray()
        order.forEach { path ->
            var parent = path.substringBeforeLast('.', "")
            while (parent.isNotEmpty() && parent !in retained) parent = parent.substringBeforeLast('.', "")
            out.put(elements.getValue(path).put("parent_id", parent.takeIf { it.isNotEmpty() } ?: JSONObject.NULL))
        }
        return Tree(out, states, order.toSet(), truncated)
    }
    private fun actionName(id: Int): String = when (id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "tap"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_press"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        else -> id.toString()
    }
    private fun screenshot(service: PhoneAccessibilityService, maxDimension: Int, budget: ObservationBudget, check: () -> Unit): JSONObject {
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
        val screenshotDeadline = SystemClock.elapsedRealtime() + minOf(5000L, budget.remaining())
        while (true) {
            check()
            val remaining = screenshotDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return json("available" to false, "reason" to "SCREENSHOT_TIMEOUT")
            try { return pending.get(minOf(50L, remaining), TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* bounded cancellation polling */ }
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
        "event_history_complete" to snapshot?.let { service.scrollEvidenceSince(it.version, it.window).complete },
        "event_evidence_scope" to "delivered_accessibility_events",
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
    private data class ValidatedTarget(val node: AccessibilityNodeInfo, val version: Long, val validation: JSONObject)

    private fun target(service: PhoneAccessibilityService, args: JSONObject, snapshot: Snapshot?, name: String, check: () -> Unit): ValidatedTarget {
        if (snapshot == null) fail("INVALID_ARGUMENT", "element_id requires observation_id")
        val path = args.string("element_id")
        if (path !in snapshot.exported) stale(service, snapshot, "element_not_in_observation", "STALE_ELEMENT")
        val expected = snapshot.nodes.getValue(path)
        val insert = name == "input_text" && args.optString("mode") == "insert"
        val semanticScroll = name == "scroll"
        val version = service.relevantVersion(snapshot.window)
        var node = service.rootInActiveWindow ?: stale(service, snapshot, "window_changed")
        try {
            val current = collect(node, snapshot.width, snapshot.height, false, check)
            val evidence = if (semanticScroll) service.scrollEvidenceSince(snapshot.version, snapshot.window) else null
            val reason = if (evidence != null) scrollChangeReason(snapshot.nodes, current.nodes, path, evidence)
                else targetChangeReason(snapshot.nodes, current.nodes, path, insert)
            reason?.let {
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
            if (semanticScroll && !node.isScrollable) stale(service, snapshot, "target_not_scrollable", "ACTION_UNSUPPORTED")
            val occlusionExceptions = validateNodeOcclusion(service, snapshot, bounds, semanticScroll)
            validateSameWindowOcclusion(service, snapshot, node, bounds, check)
            if (semanticScroll) validateDescendantOcclusion(service, snapshot, node, bounds, check)
            if (windowSignature(service, snapshot.window) != snapshot.windows) stale(service, snapshot, "window_occlusion_changed")
            if (version != service.relevantVersion(snapshot.window)) stale(service, snapshot, "changed_during_validation")
            return ValidatedTarget(node, version, json("strategy" to if (semanticScroll) "semantic_scroll" else "target_and_container",
                "allowed_change" to if (semanticScroll && expected.subtree != current.nodes[path]?.subtree) "verified_descendant_text_refresh" else "none",
                "event_history_complete" to evidence?.complete, "occlusion_exceptions" to occlusionExceptions))
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
                validateContext(service, args, client)
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
                    "cursor_restored" to restored, "validation" to validated.validation)
            } finally { node.recycle() }
        }
        when (name) {
            "tap", "long_press", "swipe", "scroll" -> return gesture(service, name, args) { points ->
                check()
                validateContext(service, args, client)
                validateGestureOcclusion(service, snapshot!!, points)
                // Region acquisition is also a read and may race a window/event change.
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
    private fun gesture(service: PhoneAccessibilityService, name: String, args: JSONObject,
        check: (List<Pair<Float, Float>>) -> Unit): JSONObject {
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
        check(points)
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
