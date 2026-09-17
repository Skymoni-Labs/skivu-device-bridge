package ai.nidhoggfgg.phoneuse.device

import ai.nidhoggfgg.phoneuse.core.canonical
import ai.nidhoggfgg.phoneuse.core.json
import org.json.JSONArray

/** Widget state exported for observation and condition diagnostics. */
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

/** An editable node may expose its placeholder through getText(); it is not entered text. */
internal fun enteredText(text: String?, editable: Boolean, showingHint: Boolean): String? =
    if (editable && showingHint) "" else text

/** Empty text has exactly one insertion position, even if Android omits its selection. */
internal fun insertionSelection(text: String?, start: Int, end: Int): Pair<Int, Int> =
    if (text == "" && start == -1 && end == -1) 0 to 0 else start to end

/** Target text always participates; selection only participates when insertion uses it. */
internal data class NodeReference(val content: String, val selectionStart: Int, val selectionEnd: Int) {
    fun matches(other: NodeReference, insert: Boolean): Boolean = content == other.content &&
        (!insert || (selectionStart == other.selectionStart && selectionEnd == other.selectionEnd))
}

internal data class ElementReference(val reference: NodeReference, val identity: String, val complete: Boolean = true)

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

/** Validate the selected node, not siblings, ancestors, or its changing descendants. */
internal fun targetChangeReason(previous: Map<String, ElementReference>, current: Map<String, ElementReference>,
                                path: String, insert: Boolean): String? {
    val expected = previous[path] ?: return "element_not_in_observation"
    val actual = current[path] ?: return "element_missing"
    if (expected.identity != actual.identity) return "target_replaced"
    if (!expected.reference.matches(actual.reference, insert)) return if (
        expected.reference.content == actual.reference.content && insert) "text_selection_changed" else "target_changed"
    return null
}
