package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.json
import ai.nidhoggfgg.phoneuse.device.projectTree
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SemanticProjectionTest {
    private fun node(id: String, text: String = "", vararg actions: String) = json(
        "element_id" to id, "text" to text, "bounds" to JSONArray(listOf(0, 0, 400, 100)),
        "actions" to JSONArray(actions.toList()), "visible" to true, "_compact_keep" to true)
    private fun project(nodes: List<JSONObject>, details: Boolean = false, subtree: String? = null) =
        projectTree(JSONArray(nodes), true, true, details, subtree = subtree)
    private fun elements(tree: JSONObject): List<JSONObject> = tree.getJSONArray("elements").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Test fun cardOwnsStaticLabelsButFavoriteKeepsItsOwnTargetAndLabel() {
        val input = listOf(node("0", "", "tap"), node("0.0"), node("0.0.0", "Headphones"),
            node("0.0.1", "$199"), node("0.1", "", "tap").put("checkable", true), node("0.1.0", "Favorite"))
        val before = JSONArray(input).toString()
        val result = project(input)
        val out = elements(result)
        assertEquals(listOf("0", "0.1"), out.map { it.getString("element_id") })
        assertEquals("Headphones · $199", out[0].getString("label"))
        assertEquals("Favorite", out[1].getString("label"))
        assertTrue(out[1].getBoolean("checkable"))
        assertEquals("0", out[1].getString("parent_id"))
        assertEquals("[\"tap\"]", out[0].getJSONArray("actions").toString())
        assertEquals(3, result.getInt("merged_nodes"))
        assertEquals(before, JSONArray(input).toString())
    }

    @Test fun detailsUnfoldSourcesAndLegacyDoesNotMerge() {
        val input = listOf(node("0", "", "tap"), node("0.0", "Open"))
        val detailed = project(input, details = true)
        assertEquals(2, elements(detailed).size)
        assertEquals(0, detailed.getInt("merged_nodes"))
        assertEquals("[\"0.0\"]", elements(detailed)[0].getJSONArray("label_sources").toString())
        val legacy = elements(projectTree(JSONArray(input), false, true, false))
        assertEquals(2, legacy.size)
        assertFalse(legacy[0].has("label"))
    }

    @Test fun independentActionsAndStatesAreNeverFolded() {
        val protected = listOf(node("0.0", "Edit", "set_text").put("editable", true),
            node("0.1", "More", "long_press"), node("0.2", "Custom", "999"),
            node("0.3", "Selected").put("selected", true), node("0.4", "Focused").put("focused", true),
            node("0.5", "Error").put("error", "Required"), node("0.6", "Disabled").put("enabled", false),
            node("0.7", "Secret").put("password", true), node("0.8", "Partial").put("text_truncated", true))
        val out = elements(project(listOf(node("0", "", "tap")) + protected))
        assertEquals(10, out.size)
        assertFalse(out[0].has("label"))
    }

    @Test fun scrollRegionsAndEditorsDoNotAbsorbTheirContents() {
        for (action in listOf("scroll_forward", "set_text")) {
            val out = elements(project(listOf(node("0", "", "tap", action), node("0.0", "Content"))))
            assertEquals(2, out.size)
            assertFalse(out[0].has("label"))
        }
    }

    @Test fun collectionRowsKeepGroupingAndDoNotLeakLabelsIntoOuterActions() {
        val input = listOf(node("0", "", "tap"),
            node("0.0").put("collection", JSONArray(listOf(2, 1))),
            node("0.0.0").put("collection_item", JSONArray(listOf(0, 1, 0, 1))),
            node("0.0.0.0", "First"), node("0.0.0.1", "Open", "tap"),
            node("0.0.1").put("collection_item", JSONArray(listOf(1, 1, 0, 1))),
            node("0.0.1.0", "Second"), node("0.0.1.1", "Open", "tap"))
        val out = elements(project(input)).associateBy { it.getString("element_id") }
        assertEquals(8, out.size)
        assertEquals("0.0.0", out.getValue("0.0.0.1").getString("parent_id"))
        assertEquals("0.0.1", out.getValue("0.0.1.1").getString("parent_id"))
        assertFalse(out.getValue("0").has("label"))
    }

    @Test fun scopeAndGeometryDoNotInventLabelOwnership() {
        val input = listOf(node("0", "", "tap"), node("0.0", "Inside"),
            node("0.1", "Outside").put("bounds", JSONArray(listOf(0, 110, 400, 150))))
        val scoped = elements(project(input, subtree = "0.0"))
        assertEquals(1, scoped.size)
        assertEquals("Inside", scoped[0].getString("text"))
        val out = elements(project(input))
        assertEquals(2, out.size)
        assertEquals("Inside", out[0].getString("label"))
        assertEquals("Outside", out[1].getString("text"))
    }

    @Test fun duplicateLabelsUseExactDeduplicationAndOverflowStaysReadable() {
        val input = listOf(node("0", "Open", "tap").put("description", "Open"), node("0.0", "Open")) +
            (1..9).map { node("0.$it", "Text $it") } + node("0.10", "x".repeat(1024))
        val result = project(input)
        val out = elements(result)
        assertEquals(8, result.getInt("merged_nodes"))
        assertEquals("Open · Text 1 · Text 2 · Text 3 · Text 4 · Text 5 · Text 6 · Text 7", out[0].getString("label"))
        assertEquals(listOf("0", "0.8", "0.9", "0.10"), out.map { it.getString("element_id") })
        assertEquals(1024, out.last().getString("text").length)
    }

    @Test fun siblingsAndHiddenWrappersDoNotSupplyLabelsToAnUnrelatedButton() {
        val input = listOf(node("0"), node("0.0", "Title"), node("0.1", "", "tap"),
            node("0.1.0").put("visible", false), node("0.1.0.0", "Hidden wrapper child"))
        val out = elements(project(input))
        assertEquals(3, out.size)
        assertFalse(out.single { it.getString("element_id") == "0.1" }.has("label"))
        assertEquals(listOf("Title", "", "Hidden wrapper child"), out.map { it.getString("text") })
    }

    @Test fun regionDoesNotPullOutsideTextIntoAnIntersectingTarget() {
        val input = listOf(node("0", "", "tap"),
            node("0.0", "Outside region").put("bounds", JSONArray(listOf(200, 0, 400, 100))))
        val result = projectTree(JSONArray(input), true, true, false,
            region = json("left" to 0, "top" to 0, "right" to 100, "bottom" to 100))
        assertEquals(1, elements(result).size)
        assertFalse(elements(result)[0].has("label"))
    }

    @Test fun characterBudgetAndFocusOnlyNodesPreserveEvidence() {
        val result = project(listOf(node("0", "", "tap"), node("0.0", "x".repeat(1024)),
            node("0.1", "Overflow"), node("0.2").put("focused", true)))
        val out = elements(result)
        assertEquals(1, result.getInt("merged_nodes"))
        assertEquals(1024, out[0].getString("label").length)
        assertEquals("Overflow", out[1].getString("text"))
        assertTrue(out[2].getBoolean("focused"))
    }
}
