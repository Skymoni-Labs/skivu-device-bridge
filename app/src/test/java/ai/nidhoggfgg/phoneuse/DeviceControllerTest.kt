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
    private fun action(id: String) = json("request_id" to id)

    @Test fun authorizedClientsCanActWithoutAcquiringControl() {
        var now = 100L
        val controller = DeviceController(Backend(), { now }, { true })
        try {
            assertEquals("executed", controller.call("a", "home", action("one")).getString("state"))
            now += TimeUnit.DAYS.toMillis(30)
            assertEquals("executed", controller.call("a", "home", action("later")).getString("state"))
            assertEquals("executed", controller.call("b", "back", action("one")).getString("state"))
            assertFalse(controller.call("a", "get_status", JSONObject()).has("control_owner"))
        } finally { controller.stop() }
    }
    @Test fun pauseInvalidatesGenerationEvenAfterResume() {
        val gate = ControlGate()
        val generation = gate.generation
        gate.pause()
        assertThrows(DeviceError::class.java) { gate.check() }
        gate.resume()
        assertThrows(DeviceError::class.java) { gate.check(generation) }
        gate.check()
        gate.stop(); gate.resume()
        assertThrows(DeviceError::class.java) { gate.check() }
    }
    @Test fun catalogHasNoControlAcquisitionOrLeaseArguments() {
        val catalog = ToolCatalog.all()
        for (i in 0 until catalog.length()) {
            val tool = catalog.getJSONObject(i)
            assertFalse(tool.getString("name") in setOf("acquire_control", "release_control"))
            assertFalse(tool.getJSONObject("inputSchema").getJSONObject("properties").has("lease_id"))
        }
    }
    @Test fun duplicateActionsAreNotReplayedAndConflictingPayloadsFail() {
        val backend = Backend()
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action("one")
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
            val args = action("one")
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
            val first = threads.submit<JSONObject> { controller.call("a", "home", action("one")) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val second = threads.submit<JSONObject> { controller.call("a", "back", action("two")) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (controller.call("a", "get_status", json("request_id" to "two")).has("error") && System.nanoTime() < deadline) Thread.yield()
            controller.pause()
            backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            assertEquals("cancelled", second.get(2, TimeUnit.SECONDS).getString("state"))
            controller.resume()
            assertEquals("executed", controller.call("a", "back", action("late")).getString("state"))
            assertEquals(2, backend.calls.get())
        } finally { backend.release!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
    @Test fun revokedCredentialCannotReadOrAct() {
        var allowed = true
        val controller = DeviceController(Backend(), { 100L }, { allowed })
        try {
            allowed = false
            assertEquals("UNAUTHORIZED", controller.call("a", "observe", JSONObject()).getJSONObject("error").getString("code"))
            assertEquals("UNAUTHORIZED", controller.call("a", "home", action("one")).getJSONObject("error").getString("code"))
        } finally { controller.stop() }
    }
    @Test fun malformedActionsAreRejectedBeforeDispatch() {
        val backend = Backend()
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action("bad").put("x", "12").put("y", 2)
            assertEquals("INVALID_ARGUMENT", controller.call("a", "tap", args).getJSONObject("error").getString("code"))
            assertEquals(0, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun stopRejectsLateRequestsAndCannotBeResumed() {
        val controller = DeviceController(Backend(), { 100L }, { true })
        controller.stop(); controller.resume()
        assertEquals("STOPPED", controller.call("a", "home", action("late")).getJSONObject("error").getString("code"))
    }
    private fun awaitQueued(controller: DeviceController, client: String, id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (controller.call(client, "get_status", json("request_id" to id)).has("request")) return
            Thread.yield()
        }
        fail("Request was not queued: $client/$id")
    }
    private fun verifyClientCancellation(revoke: Boolean) {
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val allowed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf("a", "b")) }
        val controller = DeviceController(backend, { 100L }, { it in allowed })
        val threads = Executors.newFixedThreadPool(3)
        try {
            val first = threads.submit<JSONObject> { controller.call("b", "home", action("first")) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val own = threads.submit<JSONObject> { controller.call("a", "home", action("same-id")) }
            val other = threads.submit<JSONObject> { controller.call("b", "back", action("same-id")) }
            awaitQueued(controller, "a", "same-id"); awaitQueued(controller, "b", "same-id")
            assertEquals(1, backend.calls.get()) // Other clients share the same serial device queue.
            if (revoke) { allowed.remove("a"); controller.revokeClient("a") }
            else controller.call("a", "cancel", JSONObject())
            assertEquals("cancelled", own.get(2, TimeUnit.SECONDS).getString("state"))
            assertFalse(other.isDone)
            backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            assertEquals("executed", other.get(2, TimeUnit.SECONDS).getString("state"))
            assertEquals(2, backend.calls.get())
        } finally { backend.release!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
    @Test fun cancelOnlyAffectsRequestsFromTheCallingClient() { verifyClientCancellation(false) }
    @Test fun credentialRevocationOnlyCancelsThatClientsPendingActions() { verifyClientCancellation(true) }
    @Test fun canonicalPayloadIgnoresObjectKeyOrder() {
        assertEquals(canonical(json("a" to 1, "b" to json("x" to "中文"))), canonical(json("b" to json("x" to "中文"), "a" to 1)))
    }
}
