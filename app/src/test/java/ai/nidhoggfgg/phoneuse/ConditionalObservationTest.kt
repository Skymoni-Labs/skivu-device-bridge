package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.*
import ai.nidhoggfgg.phoneuse.device.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConditionalObservationTest {
    private fun node(id: String = "0.0", text: String = "Search") = json("element_id" to id, "text" to text,
        "view_id" to "app:id/query", "editable" to true, "enabled" to true, "visible" to true,
        "bounds" to JSONArray(listOf(0, 0, 100, 50)), "actions" to JSONArray(listOf("set_text")))
    private fun waitFor(match: String = "unique") = json("package_name" to "app", "element" to json("text" to "Search", "editable" to true, "match" to match))

    @Test fun packageAndUniqueElementMustBothMatch() {
        assertEquals("unsatisfied", evaluateCondition(waitFor(), "launcher", JSONArray(listOf(node())), true, false).getString("status"))
        assertEquals("unsatisfied", evaluateCondition(waitFor(), "app", JSONArray(), true, false).getString("status"))
        assertEquals("satisfied", evaluateCondition(waitFor(), "app", JSONArray(listOf(node())), true, false).getString("status"))
        assertEquals("ambiguous", evaluateCondition(waitFor(), "app", JSONArray(listOf(node(), node("0.1"))), true, false).getString("status"))
        assertEquals("satisfied", evaluateCondition(waitFor("exists"), "app", JSONArray(listOf(node(), node("0.1"))), true, false).getString("status"))
    }
    @Test fun unavailableOrTruncatedTreeCannotProveUniqueness() {
        assertEquals("unknown", evaluateCondition(waitFor(), "app", JSONArray(), false, false).getString("status"))
        assertEquals("unknown", evaluateCondition(waitFor(), "app", JSONArray(listOf(node())), true, true).getString("status"))
        assertEquals("unknown", evaluateCondition(waitFor(), "app", JSONArray(listOf(node())), true, false, complete = false).getString("status"))
        assertEquals("satisfied", evaluateCondition(waitFor("exists"), "app", JSONArray(listOf(node())), true, true).getString("status"))
        assertEquals("unsatisfied", evaluateCondition(waitFor(), "app", JSONArray(listOf(node().put("text_truncated", true))), true, false).getString("status"))
    }
    @Test fun requestedStatesAndRealActionAreRequired() {
        val selector = json("element" to json("view_id" to "app:id/query", "enabled" to true, "action" to "set_text"))
        assertEquals("unsatisfied", evaluateCondition(selector, "app", JSONArray(listOf(node().put("enabled", false))), true, false).getString("status"))
        assertEquals("unsatisfied", evaluateCondition(selector, "app", JSONArray(listOf(node().put("actions", JSONArray(listOf("123"))))), true, false).getString("status"))
    }
    @Test fun exactLongLabelsUseInternalEvidenceWithoutExposingTheirHash() {
        val label = "x".repeat(1200)
        val nodes = JSONArray(listOf(node(text = label.take(1024)).put("text_truncated", true).put("_text_hash", conditionTextHash(label))))
        assertEquals("satisfied", evaluateCondition(json("element" to json("text" to label)), "app", nodes, true, false).getString("status"))
        assertEquals("unsatisfied", evaluateCondition(json("element" to json("text" to label.take(1024))), "app", nodes, true, false).getString("status"))
        assertFalse(projectTree(nodes, true, true, false).getJSONArray("elements").getJSONObject(0).has("_text_hash"))
    }
    @Test fun defaultCaptureDoesNotWaitForContentEventsOrQuietness() {
        var now = 0L
        var captures = 0
        val result = ConditionalObserver({ now }, { error("Default observation must not sleep") })
            .run(json(), {}, { error("Default observation must not sample quietness") }) { _, _ ->
                captures++; now += 7
                json("observation_id" to "current")
            }
        assertEquals(1, captures)
        assertEquals(0L, result.getLong("wait_ms"))
        assertEquals(7L, result.getLong("collection_ms"))
    }

    @Test fun inconsistentPagesHaveBoundedImmediateReadRetries() {
        var captures = 0
        val result = ConditionalObserver({ 0L }, { error("No implicit wait") }).run(json(), {}, { 0 }) { _, _ ->
            captures++; fail("OBSERVATION_INCONSISTENT", "window changed")
        }
        assertEquals(3, captures)
        assertEquals("failed", result.getString("observation_status"))
    }

    @Test fun skeletonKeepsWaitingUntilTheRequestedTargetAppears() {
        var now = 0L
        var captures = 0
        val result = ConditionalObserver({ now }, { now += it }).run(json("wait_until" to waitFor(), "timeout_ms" to 1000), {}, { 1 }) { _, _ ->
            val nodes = if (++captures < 3) JSONArray() else JSONArray(listOf(node()))
            json("observation_id" to "s$captures", "condition" to evaluateCondition(waitFor(), "app", nodes, true, false))
        }
        assertEquals(3, captures)
        assertEquals("complete", result.getString("observation_status"))
        assertEquals("satisfied", result.getJSONObject("condition").getString("status"))
    }
    @Test fun timeoutKeepsLastCaptureAndItsConditionWithoutClaimingSuccess() {
        var now = 0L
        val result = ConditionalObserver({ now }, { now += it }).run(json("wait_until" to waitFor(), "stable_wait_ms" to 0, "timeout_ms" to 100), {}, { 1 }) { _, _ ->
            json("observation_id" to "original", "captured_at_ms" to 7, "condition" to evaluateCondition(waitFor(), "app", JSONArray(), true, false))
        }
        assertEquals(100L, now)
        assertEquals("condition_timeout", result.getString("observation_status"))
        assertEquals(7, result.getInt("captured_at_ms"))
        assertEquals("unsatisfied", result.getJSONObject("condition").getString("status"))
        assertTrue(result.getJSONObject("condition").getBoolean("timed_out"))
    }
    @Test fun failedNewCaptureReportsUnknownAndKeepsOldConditionSeparate() {
        var now = 0L
        var reads = 0
        val result = ConditionalObserver({ now }, { now += it }).run(json("wait_until" to waitFor(), "stable_wait_ms" to 0, "timeout_ms" to 500), {}, { 1 }) { _, _ ->
            if (++reads > 1) fail("ACCESSIBILITY_UNAVAILABLE", "disconnected")
            json("observation_id" to "old", "condition" to evaluateCondition(waitFor(), "app", JSONArray(), true, false))
        }
        assertEquals("failed", result.getString("observation_status"))
        assertEquals("unknown", result.getJSONObject("condition").getString("status"))
        assertEquals("unsatisfied", result.getJSONObject("last_observation").getJSONObject("condition").getString("status"))
    }
    @Test fun totalDeadlineAndCancellationApplyInsideCapture() {
        var now = 0L
        val result = ConditionalObserver({ now }, { now += it }).run(json("timeout_ms" to 20, "stable_wait_ms" to 0), {}, { 1 }) { _, check ->
            now += 20; check(); JSONObject()
        }
        assertEquals("OBSERVATION_TIMEOUT", result.getJSONObject("error").getString("code"))
        val cancelled = ConditionalObserver({ now }, { now += it }).run(json("timeout_ms" to 1000, "stable_wait_ms" to 600), { if (now >= 70) fail("CANCELLED", "cancel") }, { 1 }) { _, _ -> fail("TEST", "must not capture") }
        assertEquals("CANCELLED", cancelled.getJSONObject("error").getString("code"))
        assertEquals(70L, now)
    }
    @Test fun inconsistentAnimationConsumesOnlyTotalBudget() {
        var now = 0L
        var captures = 0
        val result = ConditionalObserver({ now }, { now += it }).run(json("wait_until" to waitFor(), "timeout_ms" to 600,
            "stable_wait_ms" to 100, "max_attempts" to 2), {}, { now }) { _, _ -> captures++; fail("OBSERVATION_INCONSISTENT", "changed") }
        assertEquals(600L, now)
        assertTrue(captures <= 4)
        assertTrue(result.getBoolean("inconsistency_detected"))
        assertEquals("unknown", result.getJSONObject("condition").getString("status"))
    }
    @Test fun decisionProjectionPreservesPathsStatesAndOnlyExecutableActions() {
        val input = JSONArray(listOf(
            node("0", "").put("editable", false).put("actions", JSONArray()).put("class", "Container").put("_compact_keep", true),
            node("0.0").put("enabled", false).put("selected", true).put("password", true).put("text", JSONObject.NULL),
            node("0.1", "").put("hint", "Enter query").put("error", "Required").put("actions", JSONArray(listOf("set_text", "999999")))
        ))
        val before = input.toString()
        val tree = projectTree(input, true, true, false)
        val out = tree.getJSONArray("elements")
        assertEquals(before, input.toString())
        assertEquals(2, out.length())
        assertEquals("0.0", out.getJSONObject(0).getString("element_id"))
        assertFalse(out.getJSONObject(0).getBoolean("enabled"))
        assertTrue(out.getJSONObject(0).getBoolean("selected"))
        assertTrue(out.getJSONObject(0).getBoolean("password"))
        assertEquals("Required", out.getJSONObject(1).getString("error"))
        assertEquals("[\"set_text\"]", out.getJSONObject(1).getJSONArray("actions").toString())
        assertTrue(tree.getJSONObject("state_defaults").getBoolean("enabled"))
    }
    @Test fun scopedProjectionDoesNotRewriteReferences() {
        val tree = projectTree(JSONArray(listOf(node("0.1"), node("0.10"), node("0.1.0"))), true, true, false,
            json("left" to 20, "top" to 10, "right" to 30, "bottom" to 30), "0.1")
        val nodes = tree.getJSONArray("elements")
        assertEquals(2, nodes.length())
        assertEquals("0.1", nodes.getJSONObject(1).getString("parent_id"))
        assertTrue(tree.getBoolean("scope_clipped"))
    }
    @Test fun fixedCatalogProjectionBaselineUsesTheSameInternalNodes() {
        val input = JSONArray()
        repeat(100) { i ->
            input.put(json("element_id" to "0.$i", "parent_id" to "0", "class" to "android.widget.LinearLayout",
                "view_id" to "app:id/row_$i", "bounds" to JSONArray(listOf(0, i * 20, 400, i * 20 + 20)),
                "_compact_keep" to true, "actions" to JSONArray(listOf("64", "128"))))
            input.put(json("element_id" to "0.$i.0", "parent_id" to "0.$i", "class" to "android.widget.Button",
                "view_id" to "app:id/item_$i", "text" to "Item $i", "bounds" to JSONArray(listOf(0, i * 20, 400, i * 20 + 20)),
                "_compact_keep" to true, "enabled" to true, "actions" to JSONArray(listOf("tap", "64", "128"))))
        }
        val legacy = projectTree(input, false, true, false)
        val decision = projectTree(input, true, true, false)
        assertEquals(200, legacy.getInt("output_nodes"))
        assertEquals(100, decision.getInt("output_nodes"))
        assertTrue(decision.getInt("output_bytes") < legacy.getInt("output_bytes") * 0.6)
        val legacyMcp = json("type" to "text", "text" to legacy.toString()).toString().toByteArray(Charsets.UTF_8).size
        val decisionMcp = json("type" to "text", "text" to decision.toString()).toString().toByteArray(Charsets.UTF_8).size
        println("PROJECTION_BASELINE legacy_nodes=200 decision_nodes=100 legacy_element_bytes=${legacy.getInt("output_bytes")} decision_element_bytes=${decision.getInt("output_bytes")} legacy_mcp_text_bytes=$legacyMcp decision_mcp_text_bytes=$decisionMcp")
    }
}
