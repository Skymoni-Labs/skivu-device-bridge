package ai.nidhoggfgg.phoneuse.core

import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.UUID

class DeviceController(
    private val backend: DeviceBackend,
    private val clock: () -> Long,
    private val authorized: (String) -> Boolean,
    private val changed: () -> Unit = {}
) {
    val gate = ControlGate()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue(32))
    private data class Request(val client: String, val id: String, val signature: String, val created: Long,
        val result: CompletableFuture<JSONObject> = CompletableFuture(),
        @Volatile var phase: String = "accepted", @Volatile var cancelled: Boolean = false,
        @Volatile var retainedChars: Int = 0)
    private val requests = LinkedHashMap<String, Request>()
    private data class Reservation(val client: String, val id: String, val expires: Long)
    private var reservation: Reservation? = null // Guarded by requests, including queue admission.
    @Volatile private var active: Request? = null
    val actionNames = setOf("tap", "long_press", "swipe", "scroll", "input_text", "back", "home", "recents", "launch_app")

    fun call(client: String, name: String, args: JSONObject): JSONObject {
        try {
            checkAccess(client)
            ToolCatalog.validate(name, args)
            return when (name) {
                "get_capabilities" -> backend.capabilities()
                "get_status" -> status(client, args.optString("request_id"))
                "cancel" -> cancel(client, args)
                "acquire_device" -> acquireDevice(client, args)
                "release_device" -> releaseDevice(client, args)
                "observe", "list_apps" -> serialRead(client, name, args)
                "wait_for_change" -> {
                    val generation = gate.generation
                    backend.waitForChange(args) { checkAccess(client); gate.check(generation) }
                }
                in actionNames -> action(client, name, args)
                else -> fail("UNKNOWN_TOOL", "Unknown tool: $name")
            }
        } catch (e: DeviceError) {
            return errorResult(e).apply {
                if (name in actionNames) {
                    put("action_executed", false)
                    if (args.has("request_id")) put("request_id", args.opt("request_id"))
                }
            }
        }
    }
    private fun checkAccess(client: String) {
        if (gate.stopped) fail("STOPPED", "Service is stopped")
        if (!authorized(client)) fail("UNAUTHORIZED", "Client credential was revoked")
    }
    private fun serialRead(client: String, name: String, args: JSONObject): JSONObject {
        if (gate.paused) fail("PAUSED", "Resume the service on the phone first")
        val generation = gate.generation
        val result = CompletableFuture<JSONObject>()
        try { executor.execute {
            try {
                checkAccess(client)
                if (gate.paused || gate.generation != generation || result.isDone) fail("CANCELLED", "Read invalidated")
                val check = {
                    checkAccess(client)
                    gate.check(generation)
                    if (result.isDone) fail("CANCELLED", "Read invalidated")
                }
                val value = if (name == "observe") backend.observe(args, client, check) else backend.listApps()
                checkAccess(client)
                if (gate.paused || gate.generation != generation) fail("CANCELLED", "Read invalidated")
                result.complete(value)
            } catch (e: Exception) { result.complete(errorResult(e)) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { fail("BUSY", "Device queue is full") }
        return try { result.get(15, TimeUnit.SECONDS) } catch (_: TimeoutException) {
            result.complete(errorResult(DeviceError("TIMEOUT", "Observation timed out")))
            result.get()
        }
    }
    private fun action(client: String, name: String, args: JSONObject): JSONObject {
        val id = args.string("request_id", 128)
        val key = "$client:$id"
        val signature = name + canonical(args)
        val request: Request
        synchronized(requests) {
            requests.entries.removeAll { it.value.result.isDone && clock() - it.value.created > 600_000 }
            val old = requests[key]
            if (old != null) {
                if (old.signature != signature) fail("REQUEST_ID_CONFLICT", "Request ID was used with different arguments")
                request = old
            } else {
                checkReservation(client, args)
                if (requests.size >= 256 || requests.values.sumOf { it.retainedChars.toLong() } >= 8_000_000)
                    fail("BUSY", "Deduplication cache full; retry later")
                val generation = gate.generation
                gate.check(generation)
                request = Request(client, id, signature, clock())
                requests[key] = request
                try { executor.execute { execute(request, name, args, generation) } }
                catch (_: java.util.concurrent.RejectedExecutionException) {
                    request.result.complete(errorResult(DeviceError("BUSY", "Device queue is full"))
                        .put("request_id", id).put("action_executed", false))
                }
            }
        }
        return try { request.result.get(15, TimeUnit.SECONDS) } catch (_: TimeoutException) {
            json("state" to request.phase, "request_id" to id, "action_executed" to if (request.phase == "accepted") false else null,
                "message" to "Still pending; query get_status with this request_id. Do not replay with a new ID.")
        }
    }
    private fun execute(request: Request, name: String, args: JSONObject, generation: Long) {
        if (request.result.isDone) return
        val check = {
            checkAccess(request.client)
            gate.check(generation)
            if (request.cancelled) fail("CANCELLED", "Request cancelled")
            checkReservation(request.client, args)
        }
        var dispatched = false
        try {
            check()
            synchronized(requests) {
                if (requests.values.sumOf { it.retainedChars.toLong() } >= 8_000_000) fail("BUSY", "Result retention budget is full")
            }
            active = request
            request.phase = "executing"
            dispatched = true
            val value = backend.act(name, args, request.client, check)
            val result = json("state" to "executed", "request_id" to request.id, "action_executed" to true, "execution" to value)
            if (args.optBoolean("observe_after", false)) {
                try { check(); result.put("observation", backend.observe(JSONObject(), request.client, check)); check() }
                catch (e: Exception) { result.put("observation", errorResult(e)) }
            }
            request.retainedChars = result.toString().length
            request.result.complete(result)
        } catch (e: Exception) {
            val state = when ((e as? DeviceError)?.code) {
                "CANCELLED", "UNAUTHORIZED", "STOPPED" -> "cancelled"
                "RESULT_UNKNOWN" -> "unknown"
                else -> if (e is DeviceError) "failed" else "unknown"
            }
            val result = errorResult(e, state).put("request_id", request.id)
            if (!result.has("action_executed")) result.put("action_executed", if (!dispatched) false else JSONObject.NULL)
            request.retainedChars = result.toString().length
            request.result.complete(result)
        } finally { active = null }
    }
    private fun cancel(client: String, args: JSONObject): JSONObject {
        val id = args.optString("request_id")
        cancelPending(client, id.takeIf { it.isNotEmpty() })
        return json("state" to "cancel_requested", "in_flight_may_complete" to (active?.client == client))
    }
    private fun cancelPending(client: String? = null, id: String? = null) { synchronized(requests) {
        requests.values.filter { !it.result.isDone && (client == null || it.client == client) && (id == null || it.id == id) }.forEach {
            it.cancelled = true
            if (it.phase == "accepted") it.result.complete(errorResult(DeviceError("CANCELLED", "Request cancelled before execution"), "cancelled")
                .put("request_id", it.id).put("action_executed", false))
        }
    } }
    private fun currentReservation(): Reservation? {
        if (reservation?.let { clock() >= it.expires } == true) reservation = null
        return reservation
    }
    private fun checkReservation(client: String, args: JSONObject) = synchronized(requests) {
        val current = currentReservation()
        val supplied = args.optString("lease_id")
        if (current == null) {
            if (supplied.isNotEmpty()) fail("LEASE_EXPIRED", "Reservation expired or was released", json("reason" to "RESERVATION_INACTIVE"))
        } else if (current.client != client || current.id != supplied) {
            fail("DEVICE_RESERVED", "Device is reserved for another test sequence", json("expires_in_ms" to (current.expires - clock()).coerceAtLeast(0)))
        }
    }
    private fun acquireDevice(client: String, args: JSONObject): JSONObject = synchronized(requests) {
        checkAccess(client)
        gate.check()
        val ttl = args.integer("ttl_ms", 60_000, 1_000, 300_000)
        val current = currentReservation()
        if (current != null) {
            if (current.client != client || args.optString("lease_id") != current.id)
                fail("DEVICE_RESERVED", "Device already has a test reservation", json("expires_in_ms" to (current.expires - clock()).coerceAtLeast(0)))
        } else {
            if (args.has("lease_id")) fail("LEASE_EXPIRED", "Cannot renew an expired or released reservation")
            if (requests.values.any { !it.result.isDone }) fail("BUSY", "Wait for outstanding device actions before reserving")
        }
        val updated = Reservation(client, current?.id ?: UUID.randomUUID().toString(), clock() + ttl)
        reservation = updated
        json("state" to "reserved", "lease_id" to updated.id, "expires_in_ms" to ttl,
            "lease_scope" to "device_service", "local_controls_override" to true)
    }
    private fun releaseDevice(client: String, args: JSONObject): JSONObject = synchronized(requests) {
        val id = args.string("lease_id", 128)
        val current = currentReservation() ?: fail("LEASE_EXPIRED", "Reservation expired or was released")
        if (current.client != client || current.id != id) fail("DEVICE_RESERVED", "Only the reservation owner can release it")
        reservation = null
        // Queued reserved actions fail their lease check; an injected action may still complete.
        json("state" to "released", "in_flight_may_complete" to (active?.client == client))
    }
    fun pause() { gate.pause(); synchronized(requests) { reservation = null }; cancelPending(); changed() }
    fun resume() { gate.resume(); changed() }
    fun revokeClient(client: String) { synchronized(requests) { if (reservation?.client == client) reservation = null }; cancelPending(client); changed() }
    fun stop() { gate.stop(); synchronized(requests) { reservation = null }; cancelPending(); executor.shutdown(); changed() }
    fun status(client: String, id: String = ""): JSONObject {
        val result = json("paused" to gate.paused, "stopped" to gate.stopped,
            "executing_request_id" to active?.takeIf { it.client == client }?.id)
        synchronized(requests) {
            val lease = currentReservation()
            result.put("reservation", lease?.let { json("reserved" to true, "owned_by_client" to (it.client == client),
                "expires_in_ms" to (it.expires - clock()).coerceAtLeast(0)) } ?: json("reserved" to false))
        }
        if (id.isNotEmpty()) synchronized(requests) {
            val request = requests["$client:$id"] ?: fail("REQUEST_NOT_FOUND", "No retained request with this ID")
            result.put("request", request.result.getNow(json("state" to request.phase, "request_id" to id)))
        }
        return result
    }
}
