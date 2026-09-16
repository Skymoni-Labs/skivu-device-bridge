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

    @Synchronized fun put(client: String, id: String, bytes: Long, value: T, version: Long = 0): Boolean {
        entries.filterValues { clock() - it.created > ttlMs }.keys.toList().forEach { remove(it, "expired") }
        if (bytes > maxBytes) return false
        val own = entries.filterValues { it.client == client }.keys.toMutableList()
        // Refuse before evicting anything if the other clients already consume the remaining budget.
        if (entries.values.filter { it.client != client }.sumOf { it.bytes } + bytes > maxBytes) return false
        while (own.size >= perClientLimit || entries.values.sumOf { it.bytes } + bytes > maxBytes) {
            remove(own.removeAt(0), "evicted")
        }
        entries[id] = Entry(client, clock(), bytes, value, version)
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
    val container: String?, val complete: Boolean)

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
