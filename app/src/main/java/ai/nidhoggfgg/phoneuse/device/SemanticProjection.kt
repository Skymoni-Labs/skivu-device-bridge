package ai.nidhoggfgg.phoneuse.device

import org.json.JSONArray
import org.json.JSONObject

/** Labels are presentation evidence only; IDs, actions and raw snapshot fingerprints stay intact. */
internal fun semanticObjects(all: List<JSONObject>, visible: List<JSONObject>, details: Boolean): List<JSONObject> {
    val originals = all.associateBy { it.getString("element_id") }
    val output = visible.associate { it.getString("element_id") to JSONObject(it.toString()) }
    val folded = mutableSetOf<String>()
    val labels = mutableMapOf<String, LinkedHashSet<String>>()
    val sources = mutableMapOf<String, MutableList<String>>()
    fun values(node: JSONObject) = listOf("description", "text").mapNotNull {
        if (node.isNull(it)) null else node.optString(it).takeIf(String::isNotBlank)
    }.distinct()
    fun actions(node: JSONObject): List<String> = node.optJSONArray("actions")?.let { array ->
        (0 until array.length()).map { array.getString(it) }
    } ?: emptyList()
    fun stateful(node: JSONObject) = listOf("editable", "password", "focused", "checkable", "checked",
        "selected", "content_invalid", "text_truncated", "description_truncated").any { node.optBoolean(it) } ||
        !node.optBoolean("enabled", true) || listOf("hint", "error", "state_description", "range", "collection", "collection_item")
        .any { !node.isNull(it) }
    fun contains(parent: JSONObject, child: JSONObject): Boolean {
        val p = parent.getJSONArray("bounds")
        val c = child.getJSONArray("bounds")
        return p.getInt(0) <= c.getInt(0) && p.getInt(1) <= c.getInt(1) &&
            p.getInt(2) >= c.getInt(2) && p.getInt(3) >= c.getInt(3)
    }
    // Even unexported descendants make a node non-leaf: never fold an independent subtree.
    val parents = all.map { it.getString("element_id").substringBeforeLast('.', "") }.toSet()
    for (node in visible) {
        val id = node.getString("element_id")
        val text = values(node)
        if (id in parents || text.isEmpty() || stateful(node) ||
            actions(node).any { it !in setOf("64", "128") }) continue
        var path = id.substringBeforeLast('.', "")
        while (path.isNotEmpty()) {
            val ancestor = originals[path] ?: break
            if (!ancestor.optBoolean("visible", true) || !ancestor.optBoolean("_on_screen", true) ||
                !contains(ancestor, node)) break
            val supported = actions(ancestor)
            val target = output[path]
            if (supported.any { it == "tap" || it == "long_press" }) {
                // Do not relabel editors, password fields, scrolling regions, or out-of-scope targets.
                if (target == null || ancestor.optBoolean("editable") || ancestor.optBoolean("password") ||
                    supported.any { it == "set_text" || it.startsWith("scroll_") } || !ancestor.isNull("collection")) break
                val existing = labels[path] ?: LinkedHashSet(values(ancestor))
                val combined = LinkedHashSet(existing).apply { addAll(text) }
                val origin = sources[path] ?: mutableListOf()
                // Budget overflow keeps the original text node instead of silently losing content.
                if (origin.size >= 8 || combined.joinToString(" · ").length > 1024) break
                labels[path] = combined
                origin.add(id)
                sources[path] = origin
                if (!details) folded.add(id)
                break
            }
            // Only cross semantically empty layout wrappers. Never borrow a sibling's label.
            if (stateful(ancestor) || values(ancestor).isNotEmpty() || supported.any { it !in setOf("64", "128") }) break
            path = path.substringBeforeLast('.', "")
        }
    }
    labels.forEach { (id, text) ->
        output.getValue(id).put("label", text.joinToString(" · "))
            .put("label_sources", JSONArray(sources.getValue(id)))
    }
    return visible.mapNotNull { node -> node.getString("element_id").let { if (it in folded) null else output.getValue(it) } }
}
