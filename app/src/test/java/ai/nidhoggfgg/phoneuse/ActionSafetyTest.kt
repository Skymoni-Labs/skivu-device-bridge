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
        assertEquals("expired", store.get("client", "captured-earlier").reason)
    }

    @Test fun localControlProtectionCoversTheWholeGesturePath() {
        val control = listOf(ScreenRect(40, 40, 60, 60))
        assertTrue(pathIntersectsRegion(listOf(0f to 50f, 100f to 50f), control))
        assertTrue(pathIntersectsRegion(listOf(50f to 50f), control))
        assertFalse(pathIntersectsRegion(listOf(0f to 0f, 100f to 20f), control))
        assertFalse(pathIntersectsRegion(listOf(10f to 10f), control))
    }
}
