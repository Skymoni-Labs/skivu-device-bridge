package ai.nidhoggfgg.phoneuse.core

import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
                "observe", "list_apps" -> serialRead(client, name, args)
                "wait_for_change" -> {
                    val generation = gate.generation
                    backend.waitForChange(args) { checkAccess(client); gate.check(generation) }
                }
                in actionNames -> action(client, name, args)
                else -> fail("UNKNOWN_TOOL", "Unknown tool: $name")
            }
        } catch (e: DeviceError) { return errorResult(e) }
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
                val value = if (name == "observe") backend.observe(args) else backend.listApps()
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
                if (requests.size >= 256 || requests.values.sumOf { it.retainedChars.toLong() } >= 8_000_000)
                    fail("BUSY", "Deduplication cache full; retry later")
                val generation = gate.generation
                gate.check(generation)
                request = Request(client, id, signature, clock())
                requests[key] = request
                try { executor.execute { execute(request, name, args, generation) } }
                catch (_: java.util.concurrent.RejectedExecutionException) {
                    request.result.complete(errorResult(DeviceError("BUSY", "Device queue is full")))
                }
            }
        }
        return try { request.result.get(15, TimeUnit.SECONDS) } catch (_: TimeoutException) {
            json("state" to request.phase, "request_id" to id, "message" to "Still pending; query get_status with this request_id. Do not replay with a new ID.")
        }
    }
    private fun execute(request: Request, name: String, args: JSONObject, generation: Long) {
        if (request.result.isDone) return
        val check = {
            checkAccess(request.client)
            gate.check(generation)
            if (request.cancelled) fail("CANCELLED", "Request cancelled")
        }
        try {
            check()
            synchronized(requests) {
                if (requests.values.sumOf { it.retainedChars.toLong() } >= 8_000_000) fail("BUSY", "Result retention budget is full")
            }
            active = request
            request.phase = "executing"
            val value = backend.act(name, args, check)
            val result = json("state" to "executed", "request_id" to request.id, "execution" to value)
            if (args.optBoolean("observe_after", false)) {
                try { check(); result.put("observation", backend.observe(JSONObject())); check() }
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
            request.result.complete(errorResult(e, state).put("request_id", request.id))
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
            if (it.phase == "accepted") it.result.complete(errorResult(DeviceError("CANCELLED", "Request cancelled before execution"), "cancelled"))
        }
    } }
    fun pause() { gate.pause(); cancelPending(); changed() }
    fun resume() { gate.resume(); changed() }
    fun revokeClient(client: String) { cancelPending(client); changed() }
    fun stop() { gate.stop(); cancelPending(); executor.shutdown(); changed() }
    fun status(client: String, id: String = ""): JSONObject {
        val result = json("paused" to gate.paused, "stopped" to gate.stopped,
            "executing_request_id" to active?.takeIf { it.client == client }?.id)
        if (id.isNotEmpty()) synchronized(requests) {
            val request = requests["$client:$id"] ?: fail("REQUEST_NOT_FOUND", "No retained request with this ID")
            result.put("request", request.result.getNow(json("state" to request.phase, "request_id" to id)))
        }
        return result
    }
}
