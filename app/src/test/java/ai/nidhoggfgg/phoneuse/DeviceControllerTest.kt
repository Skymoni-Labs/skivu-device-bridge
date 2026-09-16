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
        var observationFailure: DeviceError? = null
        val observedClients = mutableListOf<String>()
        val actingClients = mutableListOf<String>()
        override fun capabilities() = json("available" to true)
        override fun observe(args: JSONObject) = json("observation_id" to "test")
        override fun observe(args: JSONObject, client: String, check: () -> Unit): JSONObject {
            check(); observedClients.add(client)
            observationFailure?.let { throw it }
            return observe(args)
        }
        override fun listApps() = JSONObject()
        override fun waitForChange(args: JSONObject, check: () -> Unit): JSONObject { check(); return JSONObject() }
        override fun act(name: String, args: JSONObject, check: () -> Unit): JSONObject {
            check(); calls.incrementAndGet(); entered?.countDown(); release?.await(3, TimeUnit.SECONDS)
            failure?.let { throw it }
            return json("confirmation" to "android_accepted")
        }
        override fun act(name: String, args: JSONObject, client: String, check: () -> Unit): JSONObject {
            actingClients.add(client)
            return act(name, args, check)
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
    @Test fun reservationsAreOptionalAndLegacyControlToolsStayRemoved() {
        val catalog = ToolCatalog.all()
        for (i in 0 until catalog.length()) {
            val tool = catalog.getJSONObject(i)
            assertFalse(tool.getString("name") in setOf("acquire_control", "release_control"))
            val required = tool.getJSONObject("inputSchema").getJSONArray("required")
            if (tool.getString("name") != "release_device")
                assertFalse((0 until required.length()).any { required.getString(it) == "lease_id" })
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
    @Test fun observationFailurePreservesExecutedActionAndItsClientWithoutReplay() {
        val backend = Backend().apply {
            observationFailure = DeviceError("OBSERVATION_INCONSISTENT", "Changing", json("reason" to "content_changed", "attempts" to 3))
        }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action("once").put("observe_after", true)
            repeat(2) {
                val result = controller.call("client-a", "home", args)
                assertEquals("executed", result.getString("state"))
                assertTrue(result.getBoolean("action_executed"))
                assertEquals("OBSERVATION_INCONSISTENT", result.getJSONObject("observation").getJSONObject("error").getString("code"))
            }
            assertEquals(1, backend.calls.get())
            assertEquals(listOf("client-a"), backend.observedClients)
            controller.call("client-b", "observe", JSONObject())
            assertEquals(listOf("client-a", "client-b"), backend.observedClients)
            assertEquals(listOf("client-a"), backend.actingClients)
        } finally { controller.stop() }
    }
    @Test fun staleDiagnosticsAndNonExecutionArePreserved() {
        val backend = Backend().apply { failure = DeviceError("STALE_OBSERVATION", "Expired", json(
            "reason" to "expired", "age_ms" to 30_001, "snapshot_ui_version" to 12, "current_ui_version" to 13, "action_executed" to false)) }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val result = controller.call("a", "home", action("stale"))
            assertFalse(result.getBoolean("action_executed"))
            assertEquals("expired", result.getJSONObject("error").getString("reason"))
            assertEquals(30_001, result.getJSONObject("error").getInt("age_ms"))
            assertFalse(result.getJSONObject("error").has("action_executed"))
            val malformed = controller.call("a", "tap", action("coord").put("x", 1).put("y", 2))
            assertFalse(malformed.getBoolean("action_executed"))
            assertEquals("INVALID_ARGUMENT", malformed.getJSONObject("error").getString("code"))
            assertEquals(1, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun reservationsAreBoundToClientAndSequenceAndExpire() {
        var now = 100L
        val backend = Backend()
        val controller = DeviceController(backend, { now }, { true })
        try {
            val lease = controller.call("a", "acquire_device", json("ttl_ms" to 1000)).getString("lease_id")
            fun leased(id: String) = action(id).put("lease_id", lease)
            assertEquals("DEVICE_RESERVED", controller.call("b", "home", leased("other")).getJSONObject("error").getString("code"))
            assertEquals("DEVICE_RESERVED", controller.call("a", "home", action("missing")).getJSONObject("error").getString("code"))
            assertEquals("executed", controller.call("a", "home", leased("valid")).getString("state"))
            assertFalse(controller.call("b", "get_status", JSONObject()).toString().contains(lease))
            assertEquals("DEVICE_RESERVED", controller.call("b", "release_device", json("lease_id" to lease)).getJSONObject("error").getString("code"))
            now += 500
            assertEquals(lease, controller.call("a", "acquire_device", json("ttl_ms" to 1000, "lease_id" to lease)).getString("lease_id"))
            now += 1000
            assertEquals("LEASE_EXPIRED", controller.call("a", "home", leased("expired")).getJSONObject("error").getString("code"))
            assertEquals("executed", controller.call("b", "home", action("free")).getString("state"))
            // Retained results remain queryable after a reservation expires.
            assertEquals("executed", controller.call("a", "home", leased("valid")).getString("state"))
            assertEquals(2, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun localPauseAndRevocationOverrideReservations() {
        val controller = DeviceController(Backend(), { 100L }, { true })
        try {
            controller.call("a", "acquire_device", JSONObject())
            controller.pause(); controller.resume()
            assertEquals("executed", controller.call("b", "home", action("after-pause")).getString("state"))
            controller.call("a", "acquire_device", JSONObject())
            controller.revokeClient("a")
            assertEquals("executed", controller.call("b", "home", action("after-revoke")).getString("state"))
        } finally { controller.stop() }
    }
    @Test fun queuedActionsCannotRunAfterReservationExpires() {
        val now = java.util.concurrent.atomic.AtomicLong(100)
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val controller = DeviceController(backend, now::get, { true })
        val threads = Executors.newFixedThreadPool(2)
        try {
            val lease = controller.call("a", "acquire_device", json("ttl_ms" to 1000)).getString("lease_id")
            val first = threads.submit<JSONObject> { controller.call("a", "home", action("first").put("lease_id", lease)) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val second = threads.submit<JSONObject> { controller.call("a", "home", action("second").put("lease_id", lease)) }
            awaitQueued(controller, "a", "second")
            now.set(1100); backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            val rejected = second.get(2, TimeUnit.SECONDS)
            assertEquals("LEASE_EXPIRED", rejected.getJSONObject("error").getString("code"))
            assertFalse(rejected.getBoolean("action_executed"))
            assertEquals(1, backend.calls.get())
        } finally { backend.release!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
}
