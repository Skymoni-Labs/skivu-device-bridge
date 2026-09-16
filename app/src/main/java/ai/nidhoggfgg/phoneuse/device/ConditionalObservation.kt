package ai.nidhoggfgg.phoneuse.device

import ai.nidhoggfgg.phoneuse.core.*
import org.json.JSONArray
import org.json.JSONObject

/** One deadline includes quiet waits, retries, tree walks and screenshot waits. */
internal class ObservationBudget(private val clock: () -> Long, timeoutMs: Int) {
    private val end = clock() + timeoutMs
    fun remaining(): Long = (end - clock()).coerceAtLeast(0)
    fun check() { if (remaining() == 0L) fail("OBSERVATION_TIMEOUT", "Observation total budget exhausted") }
}

internal fun conditionTextHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun evaluateCondition(wait: JSONObject, app: String, elements: JSONArray,
                               readable: Boolean, truncated: Boolean, complete: Boolean = !truncated): JSONObject {
    val packageMatched = !wait.has("package_name") || wait.getString("package_name") == app
    val selector = wait.optJSONObject("element")
    val selectorHashes = listOf("text", "description").filter { selector?.has(it) == true }
        .associateWith { conditionTextHash(selector!!.getString(it)) }
    val matches = mutableListOf<String>()
    if (selector != null && readable) for (i in 0 until elements.length()) {
        val node = elements.getJSONObject(i)
        val labelsMatch = listOf("view_id", "text", "description").all { key ->
            !selector.has(key) || if (node.optBoolean("${key}_truncated"))
                selectorHashes[key] != null && selectorHashes[key] == node.optString("_${key}_hash")
            else !node.isNull(key) && selector.getString(key) == node.optString(key)
        }
        val statesMatch = listOf("visible", "enabled", "editable").all { key ->
            !selector.has(key) || selector.getBoolean(key) == node.optBoolean(key, key != "editable")
        }
        val actions = node.optJSONArray("actions") ?: JSONArray()
        val actionMatch = !selector.has("action") || (0 until actions.length()).any { actions.getString(it) == selector.getString("action") }
        if (labelsMatch && statesMatch && actionMatch) matches.add(node.getString("element_id"))
    }
    val existence = selector?.optString("match", "unique") == "exists"
    val status = when {
        !packageMatched -> "unsatisfied"
        selector == null -> "satisfied"
        !readable -> "unknown"
        !existence && matches.size > 1 -> "ambiguous"
        !complete && (!existence || matches.isEmpty()) -> "unknown"
        matches.isEmpty() -> "unsatisfied"
        else -> "satisfied"
    }
    return json("status" to status, "timed_out" to false, "package_matched" to packageMatched,
        "match_count" to matches.size, "match" to if (selector != null) if (existence) "exists" else "unique" else null,
        "element_ids" to JSONArray(matches), "collection_truncated" to truncated, "collection_complete" to complete)
}

/** Retries reads only; the caller has already executed (or rejected) the action. */
internal class ConditionalObserver(private val clock: () -> Long, private val sleep: (Long) -> Unit) {
    fun run(args: JSONObject, check: () -> Unit, stamp: () -> Long,
            capture: (ObservationBudget, () -> Unit) -> JSONObject): JSONObject {
        val conditional = args.has("wait_until")
        val budget = ObservationBudget(clock, args.integer("timeout_ms", if (conditional) 10_000 else 20_000, 1, 60_000))
        val quietWait = args.integer("stable_wait_ms", 600, 0, 2000)
        val maxAttempts = args.integer("max_attempts", 3, 1, 3)
        val checked = { check(); budget.check() }
        var attempts = 0
        var inconsistent = 0
        var last: JSONObject? = null
        var lastError: DeviceError? = null
        var phase = "quiet_wait"
        fun failure(error: Exception): JSONObject {
            val timeout = (error as? DeviceError)?.code == "OBSERVATION_TIMEOUT"
            // A deadline after a successful condition check is an unmet wait; a later failed
            // collection means the current condition is unknown, even with a retained older frame.
            if (timeout && last != null && lastError == null && conditional && phase != "collection") return last!!.apply {
                put("observation_status", "condition_timeout")
                getJSONObject("condition").put("timed_out", true)
                put("failure_stage", phase)
                put("collection_attempts", attempts)
                put("inconsistency_detected", inconsistent > 0)
            }
            return errorResult(error).put("observation_status", "failed").put("failure_stage", phase)
                .put("collection_attempts", attempts).put("inconsistency_detected", inconsistent > 0)
                .apply {
                    if (lastError != null) put("last_collection_error", errorResult(lastError!!).getJSONObject("error"))
                    if (conditional) put("condition", json("status" to "unknown", "timed_out" to timeout))
                    if (last != null) put("last_observation", last)
                }
        }
        try {
            while (true) {
                var collected = false
                for (attempt in 0 until maxAttempts) {
                    phase = "quiet_wait"
                    checked()
                    val quietDeadline = clock() + minOf(quietWait.toLong(), budget.remaining())
                    var version = stamp()
                    var quietSince = clock()
                    while (clock() < quietDeadline && clock() - quietSince < 120) {
                        checked()
                        sleep(minOf(25L, quietDeadline - clock(), budget.remaining()).coerceAtLeast(1))
                        val next = stamp()
                        if (next != version) { version = next; quietSince = clock() }
                    }
                    checked()
                    phase = "collection"
                    attempts++
                    try {
                        val value = capture(budget, checked)
                        checked()
                        last = value
                        lastError = null
                        collected = true
                        value.put("collection_attempts", attempts).put("inconsistency_detected", inconsistent > 0)
                        if (!conditional || value.optJSONObject("condition")?.optString("status") == "satisfied")
                            return value.put("observation_status", "complete")
                        break
                    } catch (e: DeviceError) {
                        if (e.code != "OBSERVATION_INCONSISTENT") throw e
                        lastError = e
                        inconsistent++
                    }
                }
                if (!conditional && !collected) throw lastError!!
                phase = "condition_wait"
                checked()
                sleep(minOf(100L, budget.remaining()))
            }
        } catch (e: Exception) { return failure(e) }
    }
}

/** A projection only: internal fingerprints and bounded collection are never trimmed. */
internal fun projectTree(elements: JSONArray, decision: Boolean, compact: Boolean,
                         details: Boolean, region: JSONObject? = null, subtree: String? = null): JSONObject {
    val nodes = (0 until elements.length()).map { elements.getJSONObject(it) }
    val knownActions = setOf("tap", "long_press", "scroll_forward", "scroll_backward", "set_text")
    fun actions(node: JSONObject): List<String> = node.optJSONArray("actions")?.let { array ->
        (0 until array.length()).map { array.getString(it) }.filter { it in knownActions }
    } ?: emptyList()
    fun useful(node: JSONObject): Boolean = listOf("text", "description", "hint", "error", "state_description").any {
        !node.isNull(it) && node.optString(it).isNotEmpty()
    } || actions(node).isNotEmpty() || (details && (node.optJSONArray("actions")?.length() ?: 0) > 0) || !node.isNull("range") ||
        listOf("editable", "password", "checkable", "checked", "selected", "content_invalid").any { node.optBoolean(it) } || !node.optBoolean("enabled", true)
    val kept = nodes.filter { node ->
        val path = node.getString("element_id")
        val b = node.getJSONArray("bounds")
        val inScope = (subtree == null || path == subtree || path.startsWith("$subtree.")) &&
            (region == null || (b.getInt(0) < region.getInt("right") && b.getInt(2) > region.getInt("left") &&
                b.getInt(1) < region.getInt("bottom") && b.getInt(3) > region.getInt("top")))
        inScope && if (decision) node.optBoolean("visible", true) && node.optBoolean("_on_screen", true) && b.getInt(2) > b.getInt(0) && b.getInt(3) > b.getInt(1) && useful(node)
        else !compact || node.optBoolean("_compact_keep")
    }
    val ids = kept.map { it.getString("element_id") }.toSet()
    val projected = JSONArray()
    kept.forEach { node ->
        val out = JSONObject(node.toString())
        out.remove("_compact_keep")
        out.remove("_on_screen")
        out.remove("_text_hash")
        out.remove("_description_hash")
        var parent = out.getString("element_id").substringBeforeLast('.', "")
        while (parent.isNotEmpty() && parent !in ids) parent = parent.substringBeforeLast('.', "")
        out.put("parent_id", parent.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        if (decision) {
            if (!details) { out.remove("class"); out.remove("view_id") }
            if (details) out.put("raw_actions", out.optJSONArray("actions") ?: JSONArray())
            out.put("actions", JSONArray(actions(node)))
        }
        // Both compact formats declare boolean defaults and emit deviations.
        if (compact || decision) {
            out.keys().asSequence().toList().forEach { key ->
                if ((out.isNull(key) && key != "parent_id") || (key in setOf("enabled", "visible") && out.optBoolean(key)) ||
                    (key in setOf("editable", "password", "focused", "checkable", "checked", "selected", "content_invalid") && !out.optBoolean(key))) out.remove(key)
            }
        }
        projected.put(out)
    }
    return json("elements" to projected, "output" to if (decision) "decision" else "legacy",
        "scope_clipped" to (region != null || subtree != null), "collected_nodes" to nodes.size,
        "output_nodes" to projected.length(), "output_bytes" to projected.toString().toByteArray(Charsets.UTF_8).size,
        "state_defaults" to json("enabled" to true, "visible" to true, "editable" to false, "password" to false,
            "focused" to false, "checkable" to false, "checked" to false, "selected" to false, "content_invalid" to false))
}
