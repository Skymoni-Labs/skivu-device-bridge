package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.device.*
import org.junit.Assert.*
import org.junit.Test

class ActionSafetyTest {
    @Test fun retainingAnOlderConditionSampleDoesNotRefreshItsLifetime() {
        var now = 20_000L
        val store = SnapshotStore<String>({ now })
        assertTrue(store.put("client", "captured-earlier", 10, "old sample", createdAt = 0))
        now = 30_001
        val found = store.get("client", "captured-earlier")
        assertEquals("expired", found.reason)
        assertEquals(0L, found.created)
    }

    private fun node(id: String, text: String = id, container: String? = null) = ElementReference(
        NodeReference("$id:$text", -1, -1), "layout:$id", "subtree:$id:$text", container, true,
        identity = id, nonTextContent = "semantics:$id", textContent = text, stableId = "stable:$id", contentAnchor = text)

    private fun scene() = mapOf("0" to node("root"), "0.0" to node("list", container = "0.0"),
        "0.0.0" to node("row", container = "0.0.0").copy(collectionRow = true, contentAnchor = null, logicalItemId = "item-one"),
        "0.0.0.0" to node("label", "Price 1", "0.0.0"), "0.0.0.1" to node("title", "Item one", "0.0.0"))

    private fun refresh(before: Map<String, ElementReference>) = before +
        ("0.0" to before.getValue("0.0").copy(subtree = "new descendants")) +
        ("0.0.0" to before.getValue("0.0.0").copy(subtree = "new label")) +
        ("0.0.0.0" to before.getValue("0.0.0.0").copy(reference = NodeReference("label:Price 2", -1, -1), textContent = "Price 2", contentAnchor = "Price 2"))

    private val textEvidence = ScrollEventEvidence(true, listOf(ScrollChange("TYPE_WINDOW_CONTENT_CHANGED", "label", true)))

    @Test fun staticListAndProvenTextRefreshCanScroll() {
        val before = scene()
        assertNull(scrollChangeReason(before, before, "0.0", ScrollEventEvidence(true, emptyList())))
        assertNull(scrollChangeReason(before, refresh(before), "0.0", textEvidence))
        assertEquals("container_changed", targetChangeReason(before, refresh(before), "0.0", false))
    }

    @Test fun textRefreshNeedsCompleteEventHistoryAndExactSource() {
        val before = scene(); val after = refresh(before)
        assertEquals("scroll_event_history_incomplete", scrollChangeReason(before, after, "0.0", textEvidence.copy(complete = false)))
        assertEquals("scroll_text_change_without_event", scrollChangeReason(before, after, "0.0", ScrollEventEvidence(true, emptyList())))
        assertEquals("scroll_change_unverifiable", scrollChangeReason(before, after, "0.0",
            ScrollEventEvidence(true, listOf(ScrollChange("TYPE_VIEW_TEXT_CHANGED", null, true)))))
        assertEquals("scroll_change_outside_container", scrollChangeReason(before, after, "0.0",
            ScrollEventEvidence(true, listOf(ScrollChange("TYPE_VIEW_TEXT_CHANGED", "unrelated", true)))))
    }

    @Test fun providerStableIdentityRequiredForRefreshedLabelAndContainingRow() {
        val before = scene()
        for (path in listOf("0.0.0", "0.0.0.0")) {
            val noStableId = before + (path to before.getValue(path).copy(stableId = null))
            assertEquals("scroll_identity_unverifiable", scrollChangeReason(noStableId, refresh(noStableId), "0.0", textEvidence))
        }
    }

    @Test fun recycledRowNewIdentityAndReorderingAreRejected() {
        val before = scene()
        val changedRow = refresh(before) + ("0.0.0" to before.getValue("0.0.0").copy(identity = "replacement"))
        assertEquals("container_structure_changed", scrollChangeReason(before, changedRow, "0.0", textEvidence))
        val replaced = refresh(before) + ("0.0.0.0" to before.getValue("0.0.0.0").copy(stableId = "different business item"))
        assertEquals("container_structure_changed", scrollChangeReason(before, replaced, "0.0", textEvidence))
        val inserted = before + ("0.0.1" to node("new row"))
        assertEquals("container_structure_changed", scrollChangeReason(before, inserted, "0.0", textEvidence))
    }

    @Test fun reusedWidgetIdsDoNotAllowRowsToSwapTheirText() {
        val before = scene() + ("0.0.1" to node("row2", container = "0.0.1").copy(collectionRow = true, contentAnchor = null)) +
            ("0.0.1.0" to node("price2", "Price 2", "0.0.1")) + ("0.0.1.1" to node("title2", "Item two", "0.0.1"))
        val after = before.toMutableMap()
        for ((one, two) in listOf("0.0.0.0" to "0.0.1.0", "0.0.0.1" to "0.0.1.1")) {
            after[one] = before.getValue(one).copy(reference = NodeReference("swapped:$one", -1, -1),
                textContent = before.getValue(two).textContent, contentAnchor = before.getValue(two).contentAnchor)
            after[two] = before.getValue(two).copy(reference = NodeReference("swapped:$two", -1, -1),
                textContent = before.getValue(one).textContent, contentAnchor = before.getValue(one).contentAnchor)
        }
        val evidence = ScrollEventEvidence(true, listOf("label", "title", "price2", "title2").map { ScrollChange("TYPE_VIEW_TEXT_CHANGED", it, true) })
        assertEquals("scroll_row_identity_unverifiable", scrollChangeReason(before, after, "0.0", evidence))
    }

    @Test fun EmptyOrDuplicateRowAnchorsCannotProveIdentity() {
        for (anchor in listOf<String?>(null, "Price 1")) {
            val before = scene() + ("0.0.0.1" to scene().getValue("0.0.0.1").copy(contentAnchor = anchor))
            assertEquals("scroll_row_identity_unverifiable", scrollChangeReason(before, refresh(before), "0.0", textEvidence))
        }
    }

    @Test fun fixedRankLabelsAndWidgetIdsCannotApproveReboundBusinessRows() {
        val before = scene() + ("0.0.0" to scene().getValue("0.0.0").copy(logicalItemId = null)) +
            ("0.0.0.2" to node("rank", "Slot 1", "0.0.0"))
        val after = refresh(before) + ("0.0.0.1" to before.getValue("0.0.0.1").copy(reference = NodeReference("rebound title", -1, -1),
            textContent = "Item two", contentAnchor = "Item two"))
        assertEquals("scroll_row_identity_unverifiable", scrollChangeReason(before, after, "0.0",
            ScrollEventEvidence(true, listOf("label", "title").map { ScrollChange("TYPE_VIEW_TEXT_CHANGED", it, true) })))
    }

    @Test fun changedOrDuplicatedDeclaredLogicalItemKeyIsRejected() {
        val before = scene()
        val rebound = refresh(before) + ("0.0.0" to before.getValue("0.0.0").copy(logicalItemId = "item-two"))
        assertEquals("container_structure_changed", scrollChangeReason(before, rebound, "0.0", textEvidence))
        val duplicate = before + ("0.0.1" to node("row2", container = "0.0.1").copy(collectionRow = true, logicalItemId = "item-one"))
        assertEquals("scroll_row_identity_unverifiable", scrollChangeReason(duplicate, refresh(duplicate), "0.0", textEvidence))
    }

    @Test fun logicalItemChangeIsRejectedEvenWhenEveryLabelIsIdentical() {
        val before = scene()
        val rebound = before + ("0.0.0" to before.getValue("0.0.0").copy(logicalItemId = "different item same title"))
        assertEquals("container_structure_changed", scrollChangeReason(before, rebound, "0.0", ScrollEventEvidence(true, emptyList())))
    }

    @Test fun restoredTreeStillRejectsUserScrollSubtreeChangeAndModalEvents() {
        val before = scene()
        for (kind in listOf("TYPE_VIEW_SCROLLED", "TYPE_WINDOWS_CHANGED", "TYPE_WINDOW_CONTENT_CHANGED", "TYPE_VIEW_FOCUSED")) {
            assertEquals("scroll_change_unverifiable", scrollChangeReason(before, before, "0.0",
                ScrollEventEvidence(true, listOf(ScrollChange(kind, "list", false)))))
        }
    }

    @Test fun ancestorMovementAndStateAndDescendantCapabilitiesRemainStrict() {
        val before = scene()
        assertEquals("layout_changed", scrollChangeReason(before, before + ("0" to before.getValue("0").copy(layout = "moved")), "0.0", textEvidence))
        assertEquals("interaction_context_changed", scrollChangeReason(before,
            before + ("0" to before.getValue("0").copy(nonTextContent = "modal pane")), "0.0", textEvidence))
        assertEquals("container_semantics_changed", scrollChangeReason(before,
            refresh(before) + ("0.0.0.0" to before.getValue("0.0.0.0").copy(nonTextContent = "disabled")), "0.0", textEvidence))
    }

    @Test fun incompleteCollectionAndUnidentifiedNodesCannotApproveScroll() {
        val before = scene()
        assertEquals("container_unverifiable", scrollChangeReason(before,
            before + ("0.0" to before.getValue("0.0").copy(complete = false)), "0.0", textEvidence))
        val missingIdentity = before + ("0.0.0.0" to before.getValue("0.0.0.0").copy(identity = null))
        assertEquals("scroll_identity_unverifiable", scrollChangeReason(missingIdentity, missingIdentity, "0.0", textEvidence))
    }

    @Test fun crossingBetweenUnblockedEndpointsIsDetected() {
        assertTrue(pathIntersectsRegion(listOf(0f to 50f, 100f to 50f), listOf(ScreenRect(40, 40, 60, 60))))
        assertFalse(pathIntersectsRegion(listOf(0f to 20f, 100f to 20f), listOf(ScreenRect(40, 40, 60, 60))))
        assertTrue(pathIntersectsRegion(listOf(50f to 50f), listOf(ScreenRect(40, 40, 60, 60))))
    }

    @Test fun nonRectangularRegionGapIsNotItsBoundingRectangle() {
        val region = listOf(ScreenRect(0, 0, 10, 100), ScreenRect(90, 0, 100, 100))
        assertFalse(pathIntersectsRegion(listOf(30f to 10f, 50f to 90f), region))
        assertTrue(pathIntersectsRegion(listOf(30f to 10f, 99f to 90f), region))
        assertTrue(pathIntersectsRegion(listOf(0f to 0f, 100f to 100f), region))
    }

    @Test fun pathBoundingBoxOverlapAloneDoesNotRejectDiagonalThatMissesRegion() {
        assertFalse(pathIntersectsRegion(listOf(0f to 0f, 100f to 100f), listOf(ScreenRect(0, 80, 20, 100))))
        assertTrue(pathIntersectsRegion(listOf(0f to 0f, 100f to 100f), listOf(ScreenRect(40, 40, 60, 60))))
    }

    @Test fun onlyPlatformPipModeMayApproveSmallNonfocusedCornerOcclusion() {
        val target = ScreenRect(0, 0, 600, 900)
        val corner = listOf(ScreenRect(500, 750, 600, 900))
        assertTrue(permitsCornerPipScroll(target, corner, true, true, false, false, true, true))
        assertFalse(permitsCornerPipScroll(target, corner, true, false, false, false, true, true))
        assertFalse(permitsCornerPipScroll(target, corner, false, true, false, false, true, true))
        assertFalse(permitsCornerPipScroll(target, corner, true, true, true, false, true, true))
        assertFalse(permitsCornerPipScroll(target, corner, true, true, false, true, true, true))
        assertFalse(permitsCornerPipScroll(target, corner, true, true, false, false, false, true))
        assertFalse(permitsCornerPipScroll(target, corner, true, true, false, false, true, false))
    }

    @Test fun fullCoverageCentralAndLargeOverlaysNeverReceiveCornerAllowance() {
        val target = ScreenRect(0, 0, 600, 900)
        for (overlap in listOf(listOf(target), listOf(ScreenRect(250, 350, 350, 450)),
            listOf(ScreenRect(0, 0, 400, 600)), emptyList())) {
            assertFalse(permitsCornerPipScroll(target, overlap, true, true, false, false, true, true))
        }
    }
}
