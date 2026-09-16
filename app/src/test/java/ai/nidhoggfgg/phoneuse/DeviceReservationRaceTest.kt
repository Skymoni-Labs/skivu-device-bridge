package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.DeviceBackend
import ai.nidhoggfgg.phoneuse.core.DeviceController
import ai.nidhoggfgg.phoneuse.core.json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DeviceReservationRaceTest {
    @Test fun revocationBetweenAdmissionAndReservationCannotLeaveAnOrphanedLease() {
        val permitted = AtomicBoolean(true)
        val firstCheck = AtomicBoolean(true)
        val checked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val backend = object : DeviceBackend {
            override fun capabilities() = JSONObject()
            override fun observe(args: JSONObject) = JSONObject()
            override fun listApps() = JSONObject()
            override fun waitForChange(args: JSONObject, check: () -> Unit) = JSONObject()
            override fun act(name: String, args: JSONObject, check: () -> Unit) = JSONObject()
        }
        val controller = DeviceController(backend, { 100L }, { client ->
            if (client != "revoked") true else {
                val captured = permitted.get()
                if (firstCheck.compareAndSet(true, false)) {
                    checked.countDown()
                    check(proceed.await(3, TimeUnit.SECONDS)) { "Reservation authorization was not resumed" }
                }
                captured
            }
        })
        val thread = Executors.newSingleThreadExecutor()
        try {
            val pending = thread.submit<JSONObject> { controller.call("revoked", "acquire_device", json("ttl_ms" to 300_000)) }
            assertTrue("Initial authorization did not run", checked.await(2, TimeUnit.SECONDS))
            permitted.set(false)
            controller.revokeClient("revoked")
            proceed.countDown()
            assertEquals("UNAUTHORIZED", pending.get(2, TimeUnit.SECONDS).getJSONObject("error").getString("code"))
            val status = controller.call("other", "get_status", JSONObject())
            assertFalse(status.getJSONObject("reservation").getBoolean("reserved"))
            assertEquals("reserved", controller.call("other", "acquire_device", JSONObject()).getString("state"))
        } finally {
            proceed.countDown()
            controller.stop()
            thread.shutdownNow()
        }
    }
}
