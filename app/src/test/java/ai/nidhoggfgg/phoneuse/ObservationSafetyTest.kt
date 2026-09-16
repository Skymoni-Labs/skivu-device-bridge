package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.device.ElementReference
import ai.nidhoggfgg.phoneuse.device.NodeReference
import ai.nidhoggfgg.phoneuse.device.NodeSemantics
import ai.nidhoggfgg.phoneuse.device.nodeContentHash
import ai.nidhoggfgg.phoneuse.device.SnapshotStore
import ai.nidhoggfgg.phoneuse.device.targetChangeReason
import ai.nidhoggfgg.phoneuse.device.enteredText
import ai.nidhoggfgg.phoneuse.device.insertionSelection
import org.junit.Assert.*
import org.junit.Test

class ObservationSafetyTest {
    @Test fun diagnosticSemanticsKeepMutableWidgetState() {
        val original = NodeSemantics(checkable = true, range = listOf(0, 0f, 100f, 10f),
            collectionItem = listOf(0, 1, 0, 1, false, false), collection = listOf(3, 1, false, 1))
        val variants = listOf(original.copy(checked = true), original.copy(selected = true),
            original.copy(checkable = false), original.copy(stateDescription = "Expanded"),
            original.copy(range = listOf(0, 0f, 100f, 11f)),
            original.copy(collectionItem = listOf(1, 1, 0, 1, false, false)),
            original.copy(collection = listOf(4, 1, false, 1)),
            original.copy(hint = "Another field"), original.copy(error = "Invalid value"), original.copy(contentInvalid = true))
        val labelAndPosition = listOf("same node", "same label", "same bounds")
        variants.forEach { changed ->
            assertNotEquals("Widget state must participate in the actual content hash", nodeContentHash(labelAndPosition, original),
                nodeContentHash(labelAndPosition, changed))
        }
    }

    @Test fun observingClientCanOnlyEvictItsOwnSnapshots() {
        val store = SnapshotStore<String>({ 0 }, perClientLimit = 2, maxBytes = 100)
        assertTrue(store.put("b", "b1", 10, "other client"))
        repeat(5) { assertTrue(store.put("a", "a$it", 10, "own $it")) }
        assertEquals("other client", store.get("b", "b1").value)
        assertEquals("evicted", store.get("a", "a0").reason)
        assertEquals("own 4", store.get("a", "a4").value)
        assertEquals("client_mismatch", store.get("a", "b1").reason)
    }

    @Test fun deviceBudgetRefusesNewClientWithoutEvictingLiveReferences() {
        val store = SnapshotStore<String>({ 0 }, maxBytes = 100)
        assertTrue(store.put("a", "a1", 60, "retained"))
        assertFalse(store.put("b", "b1", 50, "too large"))
        assertEquals("retained", store.get("a", "a1").value)
        assertTrue(store.put("a", "a2", 60, "replacement"))
        assertEquals("evicted", store.get("a", "a1").reason)
    }

    @Test fun expiredReferenceHasDistinctReasonAndCannotBeRead() {
        var now = 0L
        val store = SnapshotStore<String>({ now })
        store.put("a", "old", 10, "value")
        now = 30_001
        val expired = store.get("a", "old")
        assertNull(expired.value)
        assertEquals("expired", expired.reason)
        assertEquals("value", expired.rejectedValue)
        assertEquals("expired", store.get("a", "old").reason)
        assertEquals("not_found", store.get("a", "missing").reason)
    }

    @Test fun placeholderIsEmptyInputButUnreadableTextStaysUnknown() {
        assertEquals("", enteredText("Search", true, true))
        assertEquals("Search", enteredText("Search", true, false))
        assertEquals("Search", enteredText("Search", false, true))
        assertNull(enteredText(null, true, false))
        assertEquals(0 to 0, insertionSelection("", -1, -1))
        assertEquals(-1 to -1, insertionSelection(null, -1, -1))
        assertEquals(-1 to -1, insertionSelection("entered", -1, -1))
        assertEquals(2 to 4, insertionSelection("entered", 2, 4))
    }

    private fun element(content: String, identity: String = content) =
        ElementReference(NodeReference(content, 0, 0), identity)

    private fun scene() = mapOf("0" to element("root"), "0.0" to element("row"),
        "0.0.0" to element("button"), "0.1" to element("unrelated status"))

    @Test fun unrelatedContentAncestorsAndIncompleteContainersDoNotInvalidateTheTarget() {
        val before = scene()
        val now = before + ("0" to element("new root layout")) +
            ("0.0" to element("new recommendation").copy(complete = false)) + ("0.1" to element("new status"))
        assertNull(targetChangeReason(before, now, "0.0.0", false))
        assertNull(targetChangeReason(before, mapOf("0.0.0" to before.getValue("0.0.0")), "0.0.0", false))
    }

    @Test fun identicalLabelAndPathCannotIdentifyAReplacementNode() {
        val before = scene()
        val now = before + ("0.0.0" to element("button", "replacement provider identity"))
        assertEquals("target_replaced", targetChangeReason(before, now, "0.0.0", false))
        assertEquals("element_missing", targetChangeReason(before, emptyMap(), "0.0.0", false))
        assertEquals("element_not_in_observation", targetChangeReason(before, now, "0.9", false))
    }

    @Test fun targetTextOrSemanticChangesStillRequireReselection() {
        val before = scene()
        val now = before + ("0.0.0" to element("new text or bounds", "button"))
        assertEquals("target_changed", targetChangeReason(before, now, "0.0.0", false))
    }

    @Test fun replaceIgnoresSelectionButInsertRequiresObservedSelectionAndText() {
        val before = scene()
        val selected = before + ("0.0.0" to before.getValue("0.0.0").copy(reference = NodeReference("button", 2, 4)))
        assertNull(targetChangeReason(before, selected, "0.0.0", false))
        assertEquals("text_selection_changed", targetChangeReason(before, selected, "0.0.0", true))
        val changedText = before + ("0.0.0" to before.getValue("0.0.0").copy(reference = NodeReference("new text", 0, 0)))
        assertEquals("target_changed", targetChangeReason(before, changedText, "0.0.0", true))
    }
}
