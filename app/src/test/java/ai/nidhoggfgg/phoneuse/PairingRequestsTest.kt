package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.*
import ai.nidhoggfgg.phoneuse.server.PairingRequests
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PairingRequestsTest {
    private var now = 1_000L
    private var issued = 0
    private val pairing = PairingRequests({ now }) { name ->
        issued++
        json("client_id" to "client-$issued", "token" to "token-$issued", "name" to name)
    }
    private fun poll(request: JSONObject) = pairing.poll(request.getString("pairing_id"), request.getString("pairing_secret"))
    @Test fun approvalRequiredAndDeliveryCanBeRetriedWithoutIssuingAgain() {
        val request = pairing.create("laptop", "192.168.1.2")
        assertEquals("pending", poll(request).getString("status"))
        assertFalse(poll(request).has("token"))
        assertEquals(0, issued)
        pairing.decide(request.getString("pairing_id"), true)
        assertEquals("token-1", poll(request).getString("token"))
        pairing.decide(request.getString("pairing_id"), true)
        assertEquals("token-1", poll(request).getString("token"))
        assertEquals(1, issued)
    }
    @Test fun secretIsRequiredAndDenialIsTerminal() {
        val request = pairing.create("laptop", "source")
        assertThrows(DeviceError::class.java) { pairing.poll(request.getString("pairing_id"), "wrong") }
        pairing.decide(request.getString("pairing_id"), false)
        pairing.decide(request.getString("pairing_id"), true)
        assertEquals("denied", poll(request).getString("status"))
        assertFalse(poll(request).has("token"))
        assertEquals(0, issued)
    }
    @Test fun expirationPreventsLateApprovalAndClearsApprovedResponse() {
        val request = pairing.create("laptop", "source")
        now += 120_000
        pairing.decide(request.getString("pairing_id"), true)
        assertEquals(0, issued)
        assertTrue(pairing.pending().isEmpty())
        assertThrows(DeviceError::class.java) { poll(request) }
        val next = pairing.create("next", "source")
        pairing.decide(next.getString("pairing_id"), true)
        now += 120_000
        assertThrows(DeviceError::class.java) { poll(next) }
    }
    @Test fun requestFloodIsBoundedAndNamesValidated() {
        assertThrows(DeviceError::class.java) { pairing.create("\n", "source") }
        pairing.create("laptop", "source")
        assertThrows(DeviceError::class.java) { pairing.create("other", "source") }
        repeat(15) { now += 5_000; pairing.create("client-$it", "source") }
        now += 5_000
        assertThrows(DeviceError::class.java) { pairing.create("overflow", "source") }
    }
    @Test fun revocationAndServiceStopRemoveRetrievableCredentials() {
        val request = pairing.create("laptop", "source")
        pairing.decide(request.getString("pairing_id"), true)
        pairing.revoked("client-1")
        assertFalse(poll(request).has("token"))
        assertEquals("denied", poll(request).getString("status"))
        pairing.clear()
        assertThrows(DeviceError::class.java) { poll(request) }
    }
    @Test fun failedIssuanceDoesNotApproveRequest() {
        val broken = PairingRequests({ now }) { fail("STORAGE_ERROR", "Cannot persist") }
        val request = broken.create("laptop", "source")
        assertThrows(DeviceError::class.java) { broken.decide(request.getString("pairing_id"), true) }
        assertEquals("pending", broken.poll(request.getString("pairing_id"), request.getString("pairing_secret")).getString("status"))
    }
}
