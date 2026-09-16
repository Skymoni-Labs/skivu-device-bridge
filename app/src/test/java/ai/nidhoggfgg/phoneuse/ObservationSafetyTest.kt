package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.device.ElementReference
import ai.nidhoggfgg.phoneuse.device.NodeReference
import ai.nidhoggfgg.phoneuse.device.NodeSemantics
import ai.nidhoggfgg.phoneuse.device.nodeContentHash
import ai.nidhoggfgg.phoneuse.device.SnapshotStore
import ai.nidhoggfgg.phoneuse.device.StableSampler
import ai.nidhoggfgg.phoneuse.device.targetChangeReason
import org.junit.Assert.*
import org.junit.Test

class ObservationSafetyTest {
    @Test fun labelStableWidgetsStillInvalidateOnTheirMutableState() {
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

    private fun element(content: String, container: String? = null) =
        ElementReference(NodeReference(content, 0, 0), "layout:$content", "subtree:$content", container, true)

    private fun scene() = mapOf(
        "0" to element("root"),
        "0.0" to element("row"),
        "0.0.0" to element("button", "0.0"),
        "0.1" to element("unrelated status")
    )

    @Test fun unrelatedBranchContentRefreshDoesNotInvalidateStableTarget() {
        val before = scene()
        val now = before + ("0.1" to before.getValue("0.1").copy(subtree = "new status")) +
            ("0" to before.getValue("0").copy(subtree = "updated full tree"))
        assertNull(targetChangeReason(before, now, "0.0.0", false))
    }

    @Test fun recycledListItemWithSameButtonButDifferentRowContentIsRejected() {
        val before = scene()
        val now = before + ("0.0" to before.getValue("0.0").copy(subtree = "different item title and price"))
        assertEquals("container_changed", targetChangeReason(before, now, "0.0.0", false))
    }

    @Test fun targetAndAncestorLayoutChangesRemainUnsafe() {
        val before = scene()
        val moved = before + ("0" to before.getValue("0").copy(layout = "new overlay or bounds"))
        assertEquals("layout_changed", targetChangeReason(before, moved, "0.0.0", false))
        val replaced = before + ("0.0.0" to before.getValue("0.0.0").copy(reference = NodeReference("another node", 0, 0)))
        assertEquals("target_changed", targetChangeReason(before, replaced, "0.0.0", false))
    }

    @Test fun replaceIgnoresSelectionButInsertRequiresObservedSelectionAndText() {
        val before = scene()
        val selected = before + ("0.0.0" to before.getValue("0.0.0").copy(reference = NodeReference("button", 2, 4)))
        assertNull(targetChangeReason(before, selected, "0.0.0", false))
        assertEquals("text_selection_changed", targetChangeReason(before, selected, "0.0.0", true))
        val changedText = before + ("0.0.0" to before.getValue("0.0.0").copy(reference = NodeReference("new text", 0, 0)))
        assertEquals("target_changed", targetChangeReason(before, changedText, "0.0.0", true))
    }

    @Test fun truncatedContainerCannotApproveAReusedTarget() {
        val before = scene()
        val truncated = before + ("0.0" to before.getValue("0.0").copy(complete = false))
        assertEquals("container_unverifiable", targetChangeReason(before, truncated, "0.0.0", false))
    }

    @Test fun observationRetriesItsReadAndReportsAcceptedAttempt() {
        var now = 0L
        var reads = 0
        val sampler = StableSampler({ now }, { now += it })
        val result = sampler.sample(600, 3, {}, { 1L }) { if (++reads < 3) null else "stable" }
        assertEquals(3, result!!.attempts)
        assertEquals("stable", result.value)
        assertEquals(3, reads)
        assertTrue(now <= 1800)
    }

    @Test fun continuousAnimationHasBoundedWaitAndRetryCount() {
        var now = 0L
        var reads = 0
        val sampler = StableSampler({ now }, { now += it })
        val result = sampler.sample<String>(200, 3, {}, { now }) { reads++; null }
        assertNull(result)
        assertEquals(3, reads)
        assertEquals(600L, now)
    }

    @Test fun localCancellationInterruptsStableWaitBeforeCollection() {
        var now = 0L
        var reads = 0
        val sampler = StableSampler({ now }, { now += it })
        assertThrows(IllegalStateException::class.java) {
            sampler.sample(600, 3, { if (now >= 50) throw IllegalStateException("paused") }, { now }) { reads++; "frame" }
        }
        assertEquals(0, reads)
        assertEquals(50L, now)
    }
}
