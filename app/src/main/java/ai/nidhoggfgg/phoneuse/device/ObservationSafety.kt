package ai.nidhoggfgg.phoneuse.device

import ai.nidhoggfgg.phoneuse.core.canonical
import ai.nidhoggfgg.phoneuse.core.json
import org.json.JSONArray

/** Mutable widget state matters even when the node's label and position have not changed. */
internal data class NodeSemantics(
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val stateDescription: String? = null,
    val range: List<Any?>? = null,
    val collectionItem: List<Any?>? = null,
    val collection: List<Any?>? = null,
    val hint: String? = null,
    val error: String? = null,
    val contentInvalid: Boolean = false
) {
    fun json() = json("checkable" to checkable, "checked" to checked, "selected" to selected,
        "state_description" to stateDescription, "range" to range?.let { JSONArray(it) },
        "collection_item" to collectionItem?.let { JSONArray(it) }, "collection" to collection?.let { JSONArray(it) },
        "hint" to hint, "error" to error, "content_invalid" to contentInvalid)
}

internal fun nodeContentHash(fields: List<Any?>, semantics: NodeSemantics): String = java.security.MessageDigest
    .getInstance("SHA-256").digest(canonical(JSONArray(fields + semantics.json())).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** Bounded, client-owned storage. A noisy client never evicts another client's live reference. */
internal class SnapshotStore<T>(
    private val clock: () -> Long,
    private val perClientLimit: Int = 8,
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val ttlMs: Long = 30_000
) {
    private data class Entry<T>(val client: String, val created: Long, val bytes: Long, val value: T, val version: Long)
    private data class Removed(val client: String, val reason: String, val created: Long, val version: Long)
    data class Lookup<T>(val value: T? = null, val reason: String? = null, val rejectedValue: T? = null,
                         val created: Long? = null, val version: Long? = null)
    private val entries = LinkedHashMap<String, Entry<T>>()
    private val removed = LinkedHashMap<String, Removed>()

    private fun remove(id: String, reason: String) {
        val old = entries.remove(id) ?: return
        removed[id] = Removed(old.client, reason, old.created, old.version)
        while (removed.size > 128) removed.remove(removed.keys.first())
    }

    @Synchronized fun put(client: String, id: String, bytes: Long, value: T, version: Long = 0, createdAt: Long = clock()): Boolean {
        entries.filterValues { clock() - it.created > ttlMs }.keys.toList().forEach { remove(it, "expired") }
        if (bytes > maxBytes) return false
        val own = entries.filterValues { it.client == client }.keys.toMutableList()
        // Refuse before evicting anything if the other clients already consume the remaining budget.
        if (entries.values.filter { it.client != client }.sumOf { it.bytes } + bytes > maxBytes) return false
        while (own.size >= perClientLimit || entries.values.sumOf { it.bytes } + bytes > maxBytes) {
            remove(own.removeAt(0), "evicted")
        }
        entries[id] = Entry(client, createdAt, bytes, value, version)
        removed.remove(id)
        return true
    }

    @Synchronized fun get(client: String, id: String): Lookup<T> {
        val entry = entries[id]
        if (entry == null) {
            val old = removed[id]
            if (old == null) return Lookup(reason = "not_found")
            if (old.client != client) return Lookup(reason = "client_mismatch")
            return Lookup(reason = old.reason, created = old.created, version = old.version)
        }
        if (entry.client != client) return Lookup(reason = "client_mismatch")
        if (clock() - entry.created > ttlMs) {
            remove(id, "expired")
            return Lookup(reason = "expired", rejectedValue = entry.value, created = entry.created, version = entry.version)
        }
        return Lookup(value = entry.value)
    }
}

/** Target text always participates; selection only participates when insertion uses it. */
internal data class NodeReference(val content: String, val selectionStart: Int, val selectionEnd: Int) {
    fun matches(other: NodeReference, insert: Boolean): Boolean = content == other.content &&
        (!insert || (selectionStart == other.selectionStart && selectionEnd == other.selectionEnd))
}

internal data class ElementReference(val reference: NodeReference, val layout: String, val subtree: String,
    val container: String?, val complete: Boolean, val identity: String? = null,
    val nonTextContent: String? = null, val textContent: String? = null, val stableId: String? = null,
    val contentAnchor: String? = null, val collectionRow: Boolean = false, val logicalItemId: String? = null)

/** Only the delivered journal is evidence. Missing/evicted events cannot approve a changed list. */
internal data class ScrollEventEvidence(val complete: Boolean, val events: List<ScrollChange>)
internal data class ScrollChange(val kind: String, val sourceIdentity: String?, val textOnly: Boolean)

/**
 * Semantic scroll can tolerate a leaf label refresh, but not a row rebind inferred from position.
 * Changed labels require provider-assigned stable IDs and exact text events; subtree notifications,
 * user scroll, focus changes, missing sources and a journal gap are all deliberately inconclusive.
 */
internal fun scrollChangeReason(previous: Map<String, ElementReference>, current: Map<String, ElementReference>,
                                path: String, evidence: ScrollEventEvidence): String? {
    val expected = previous[path] ?: return "element_not_in_observation"
    val actual = current[path] ?: return "element_missing"
    if (!expected.reference.matches(actual.reference, false)) return "target_changed"
    var ancestor = path
    while (ancestor.isNotEmpty()) {
        val old = previous[ancestor] ?: return "layout_unverifiable"
        val now = current[ancestor] ?: return "layout_changed"
        if (old.layout != now.layout) return "layout_changed"
        if (old.nonTextContent != null && old.nonTextContent != now.nonTextContent) return "interaction_context_changed"
        ancestor = ancestor.substringBeforeLast('.', "")
    }
    if (!expected.complete || !actual.complete) return "container_unverifiable"
    if (expected.container != actual.container) return "container_changed"
    if (!evidence.complete) return "scroll_event_history_incomplete"
    // Even a restored tree cannot hide intervening user scrolling, reordering or modal transitions.
    if (evidence.events.any { !it.textOnly || it.sourceIdentity == null ||
            it.kind !in setOf("TYPE_VIEW_TEXT_CHANGED", "TYPE_WINDOW_CONTENT_CHANGED") }) return "scroll_change_unverifiable"
    val oldNodes = previous.filterKeys { it == path || it.startsWith("$path.") }
    val newNodes = current.filterKeys { it == path || it.startsWith("$path.") }
    if (oldNodes.keys != newNodes.keys) return "container_structure_changed"
    val identities = oldNodes.values.mapNotNull { it.identity }
    if (identities.toSet().size != oldNodes.size) return "scroll_identity_unverifiable"
    val oldAnchorCounts = oldNodes.values.mapNotNull { it.contentAnchor }.groupingBy { it }.eachCount()
    val newAnchorCounts = newNodes.values.mapNotNull { it.contentAnchor }.groupingBy { it }.eachCount()
    val changed = mutableSetOf<String>()
    for ((nodePath, old) in oldNodes) {
        val now = newNodes.getValue(nodePath)
        if (!old.complete || !now.complete) return "container_unverifiable"
        if (old.identity == null || old.identity != now.identity || old.layout != now.layout || old.stableId != now.stableId ||
            old.logicalItemId != now.logicalItemId || old.collectionRow != now.collectionRow)
            return "container_structure_changed"
        if (old.nonTextContent == null || old.nonTextContent != now.nonTextContent) return "container_semantics_changed"
        if (old.reference.content != now.reference.content) {
            if (old.textContent == null || old.textContent == now.textContent) return "container_semantics_changed"
            if (old.stableId.isNullOrEmpty()) return "scroll_identity_unverifiable"
            val rowPath = old.container ?: return "scroll_row_identity_unverifiable"
            val rowNode = previous[rowPath] ?: return "scroll_row_identity_unverifiable"
            if (rowPath == path || !rowNode.collectionRow || !rowPath.startsWith("$path.")) return "scroll_row_identity_unverifiable"
            val logicalId = rowNode.logicalItemId ?: return "scroll_row_identity_unverifiable"
            // Neither widget IDs nor fixed rank/slot labels prove data-item identity. Only a provider
            // that explicitly declares our logical-item contract can approve text-refresh scrolling.
            if (logicalId != newNodes[rowPath]?.logicalItemId ||
                oldNodes.values.count { it.collectionRow && it.logicalItemId == logicalId } != 1 ||
                newNodes.values.count { it.collectionRow && it.logicalItemId == logicalId } != 1) return "scroll_row_identity_unverifiable"
            // Widget unique IDs survive adapter rebinding. Require independent, unchanged row content
            // that is unique in both scenes; two rows swapping all labels cannot pass this check.
            val anchored = oldNodes.any { (candidatePath, candidate) ->
                val anchor = candidate.contentAnchor
                (candidatePath == rowPath || candidatePath.startsWith("$rowPath.")) && anchor != null &&
                    anchor == newNodes[candidatePath]?.contentAnchor && oldAnchorCounts[anchor] == 1 && newAnchorCounts[anchor] == 1 &&
                    candidate.identity == newNodes[candidatePath]?.identity
            }
            if (!anchored) return "scroll_row_identity_unverifiable"
            // A changed collection item's identity must also be supplied by its provider.
            var row = old.container
            while (row != null && row != path && row.startsWith("$path.")) {
                val item = previous[row] ?: return "scroll_identity_unverifiable"
                if (item.stableId.isNullOrEmpty()) return "scroll_identity_unverifiable"
                row = row.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
            }
            changed.add(old.identity)
        }
    }
    if (evidence.events.any { it.sourceIdentity !in identities }) return "scroll_change_outside_container"
    val sources = evidence.events.mapNotNull { it.sourceIdentity }.toSet()
    if (!sources.containsAll(changed)) return "scroll_text_change_without_event"
    return null
}

internal data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun intersects(other: ScreenRect): Boolean = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun contains(x: Double, y: Double): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** Check every line segment (including endpoints), not just sampled vertices or its bounding box. */
internal fun pathIntersectsRegion(points: List<Pair<Float, Float>>, regions: List<ScreenRect>): Boolean {
    if (points.isEmpty()) return false
    return regions.any { rect ->
        if (points.size == 1) rect.contains(points[0].first.toDouble(), points[0].second.toDouble()) else
            points.zipWithNext().any { (from, to) ->
                var low = 0.0; var high = 1.0
                val x = from.first.toDouble(); val y = from.second.toDouble()
                val dx = to.first - x; val dy = to.second - y
                fun clip(p: Double, q: Double): Boolean {
                    if (p == 0.0) return q >= 0
                    val t = q / p
                    if (p < 0) low = maxOf(low, t) else high = minOf(high, t)
                    return low <= high
                }
                // Boundary contact is conservatively considered an intersection for a moving path.
                clip(-dx, x - rect.left) && clip(dx, rect.right - x) &&
                    clip(-dy, y - rect.top) && clip(dy, rect.bottom - y)
            }
    }
}

/** PiP is a platform-declared non-modal mode. Other unfocused windows may still be modal. */
internal fun permitsCornerPipScroll(target: ScreenRect, overlap: List<ScreenRect>, applicationWindow: Boolean,
    pictureInPicture: Boolean, blockerActive: Boolean, blockerFocused: Boolean,
    targetActive: Boolean, targetFocused: Boolean): Boolean {
    if (!applicationWindow || !pictureInPicture || blockerActive || blockerFocused || !targetActive || !targetFocused || overlap.isEmpty()) return false
    val left = overlap.minOf { it.left }; val top = overlap.minOf { it.top }
    val right = overlap.maxOf { it.right }; val bottom = overlap.maxOf { it.bottom }
    val width = target.right - target.left; val height = target.bottom - target.top
    if (width <= 0 || height <= 0 || right - left > width / 3 || bottom - top > height / 3) return false
    return (right <= target.left + width / 3 || left >= target.right - width / 3) &&
        (bottom <= target.top + height / 3 || top >= target.bottom - height / 3)
}

/** Checks only structural/field evidence: sibling branches may refresh, the containing row may not. */
internal fun targetChangeReason(previous: Map<String, ElementReference>, current: Map<String, ElementReference>,
                                path: String, insert: Boolean): String? {
    val expected = previous[path] ?: return "element_not_in_observation"
    val actual = current[path] ?: return "element_missing"
    if (!expected.reference.matches(actual.reference, insert)) return if (
        expected.reference.content == actual.reference.content && insert) "text_selection_changed" else "target_changed"
    var ancestor = path
    while (ancestor.isNotEmpty()) {
        val old = previous[ancestor] ?: return "layout_unverifiable"
        val now = current[ancestor] ?: return "layout_changed"
        if (old.layout != now.layout) return "layout_changed"
        ancestor = ancestor.substringBeforeLast('.', "")
    }
    if (expected.container != actual.container) return "container_changed"
    expected.container?.let { container ->
        val old = previous[container] ?: return "container_unverifiable"
        val now = current[container] ?: return "container_changed"
        if (!old.complete || !now.complete) return "container_unverifiable"
        if (old.subtree != now.subtree) return "container_changed"
    }
    return null
}

/** Retry only a read callback. The caller performs an action once, before invoking this sampler. */
internal class StableSampler(
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
    private val quietMs: Long = 120
) {
    data class Result<T>(val value: T, val attempts: Int)

    fun <T : Any> sample(waitMs: Int, maxAttempts: Int, check: () -> Unit,
                         stamp: () -> Long, capture: () -> T?): Result<T>? {
        repeat(maxAttempts) { attempt ->
            val deadline = clock() + waitMs
            var version = stamp()
            var quietSince = clock()
            while (clock() < deadline && clock() - quietSince < quietMs) {
                check()
                sleep(minOf(25L, deadline - clock()).coerceAtLeast(1))
                val next = stamp()
                if (next != version) { version = next; quietSince = clock() }
            }
            check()
            val value = capture()
            check()
            if (value != null) return Result(value, attempt + 1)
        }
        return null
    }
}
