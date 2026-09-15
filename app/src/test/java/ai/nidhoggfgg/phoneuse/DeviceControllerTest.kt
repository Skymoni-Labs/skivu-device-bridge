package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeviceControllerTest {
    private class Backend : DeviceBackend {
        val calls = AtomicInteger()
        var entered: CountDownLatch? = null
        var release: CountDownLatch? = null
        var failure: DeviceError? = null
        override fun capabilities() = json("available" to true)
        override fun observe(args: JSONObject) = json("observation_id" to "test")
        override fun listApps() = JSONObject()
        override fun waitForChange(args: JSONObject, check: () -> Unit): JSONObject { check(); return JSONObject() }
        override fun act(name: String, args: JSONObject, check: () -> Unit): JSONObject {
            check(); calls.incrementAndGet(); entered?.countDown(); release?.await(3, TimeUnit.SECONDS)
            failure?.let { throw it }
            return json("confirmation" to "android_accepted")
        }
    }
    private fun lease(controller: DeviceController, client: String = "a") = controller.call(client, "acquire_control", JSONObject()).getString("lease_id")
    private fun action(lease: String, id: String) = json("lease_id" to lease, "request_id" to id)

    @Test fun leaseIsExclusiveAndExpiresMonotonically() {
        var now = 100L
        val gate = ControlGate { now }
        val first = gate.acquire("a", 5, null)
        assertThrows(DeviceError::class.java) { gate.acquire("b", 5, null) }
        assertEquals(first.id, gate.acquire("a", 5, first.id).id)
        now += 5001
        assertThrows(DeviceError::class.java) { gate.check("a", first.id) }
        assertEquals("b", gate.acquire("b", 5, null).client)
    }
    @Test fun duplicateActionsAreNotReplayedAndConflictingPayloadsFail() {
        val backend = Backend()
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val lease = lease(controller)
            val args = action(lease, "one")
            assertEquals("executed", controller.call("a", "home", args).getString("state"))
            assertEquals("executed", controller.call("a", "home", JSONObject(args.toString())).getString("state"))
            assertEquals("REQUEST_ID_CONFLICT", controller.call("a", "back", args).getJSONObject("error").getString("code"))
            assertEquals(1, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun unknownResultsAreRetainedWithoutRetry() {
        val backend = Backend().apply { failure = DeviceError("RESULT_UNKNOWN", "Callback lost") }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action(lease(controller), "one")
            repeat(2) { assertEquals("unknown", controller.call("a", "home", args).getString("state")) }
            assertEquals(1, backend.calls.get())
            assertEquals("unknown", controller.call("a", "get_status", json("request_id" to "one")).getJSONObject("request").getString("state"))
        } finally { controller.stop() }
    }
    @Test fun pauseCancelsQueuedActionButReportsCompletedInFlightAction() {
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val controller = DeviceController(backend, { 100L }, { true })
        val threads = Executors.newFixedThreadPool(2)
        try {
            val lease = lease(controller)
            val first = threads.submit<JSONObject> { controller.call("a", "home", action(lease, "one")) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val second = threads.submit<JSONObject> { controller.call("a", "back", action(lease, "two")) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (controller.call("a", "get_status", json("request_id" to "two")).has("error") && System.nanoTime() < deadline) Thread.yield()
            controller.pause()
            backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            assertEquals("cancelled", second.get(2, TimeUnit.SECONDS).getString("state"))
            controller.resume()
            assertTrue(controller.call("a", "back", action(lease, "late")).has("error"))
            assertEquals(1, backend.calls.get())
        } finally { backend.release!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
    @Test fun revokedCredentialCannotReadOrAct() {
        var allowed = true
        val controller = DeviceController(Backend(), { 100L }, { allowed })
        try {
            val lease = lease(controller)
            allowed = false
            assertEquals("UNAUTHORIZED", controller.call("a", "observe", JSONObject()).getJSONObject("error").getString("code"))
            assertEquals("UNAUTHORIZED", controller.call("a", "home", action(lease, "one")).getJSONObject("error").getString("code"))
        } finally { controller.stop() }
    }
    @Test fun malformedActionsAreRejectedBeforeDispatch() {
        val backend = Backend()
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action(lease(controller), "bad").put("x", "12").put("y", 2)
            assertEquals("INVALID_ARGUMENT", controller.call("a", "tap", args).getJSONObject("error").getString("code"))
            assertEquals(0, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun stopRejectsLateRequestsAndLeaseCannotBeResumed() {
        val controller = DeviceController(Backend(), { 100L }, { true })
        val lease = lease(controller)
        controller.stop(); controller.resume()
        assertEquals("STOPPED", controller.call("a", "home", action(lease, "late")).getJSONObject("error").getString("code"))
    }
    @Test fun canonicalPayloadIgnoresObjectKeyOrder() {
        assertEquals(canonical(json("a" to 1, "b" to json("x" to "中文"))), canonical(json("b" to json("x" to "中文"), "a" to 1)))
    }
}
