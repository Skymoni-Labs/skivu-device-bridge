package ai.nidhoggfgg.phoneuse
import ai.nidhoggfgg.phoneuse.bridge.BridgePolicy
import ai.nidhoggfgg.phoneuse.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class BridgePolicyTest {
    private fun policy(now: () -> Long = { 0 }) = BridgePolicy("client", JSONObject("""{"allowedPackages":["org.example.notes"],"maxActions":1,"timeoutMs":1000}"""), now)
    @Test fun unknownClientCannotUseTask() { assertThrows(DeviceError::class.java) { policy().check("other") } }
    @Test fun expirationAndRevocationAreEnforced() { var now=0L;val p=policy { now };p.check("client");now=1000;assertThrows(DeviceError::class.java){p.check("client")} }
    @Test fun duplicateIdsDoNotConsumeExtraBudget() { val p=policy();p.reserve("one");p.reserve("one");assertEquals(1,p.actions);assertThrows(DeviceError::class.java){p.reserve("two")} }
    @Test fun packageScopeIsExact() { val p=policy();p.checkApp("org.example.notes");assertThrows(DeviceError::class.java){p.checkApp("org.example.notes.other")} }
    @Test fun unknownCoordinatesAndCommitLabelsNeedLocalApproval() {
        assertTrue(BridgePolicy.requiresConfirmation("tap",json("x" to 10,"y" to 10),null))
        assertTrue(BridgePolicy.requiresConfirmation("tap",json("element_id" to "a"),json("text" to "发送")))
        assertFalse(BridgePolicy.requiresConfirmation("tap",json("element_id" to "a"),json("text" to "搜索")))
    }
}
