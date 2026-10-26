// SPDX-License-Identifier: MIT
package ai.nidhoggfgg.phoneuse.bridge

import android.app.Service
import android.content.*
import android.os.*
import android.content.pm.PackageManager
import java.security.MessageDigest
import java.util.concurrent.Executors
import ai.nidhoggfgg.phoneuse.core.*
import ai.nidhoggfgg.phoneuse.device.*
import org.json.JSONObject
import org.json.JSONArray

object BridgeRegistry {
    data class Client(val key: String, val uid: Int, val packageName: String, val fingerprint: String)
    data class Confirmation(val owner: String, val task: String, val request: String, val signature: String, val text: String, val expires: Long, var approved: Boolean = false)
    val pending = java.util.concurrent.ConcurrentHashMap<String, Client>()
    val confirmations = java.util.concurrent.ConcurrentHashMap<String, Confirmation>()
    @Volatile var service: BridgeService? = null
    fun approve(context: Context, key: String) {
        val client = pending.remove(key) ?: return
        context.getSharedPreferences("skivu-client-grants-v1", 0).edit().putBoolean(client.key, true).apply()
    }
    fun revokeAll(context: Context) {
        context.getSharedPreferences("skivu-client-grants-v1", 0).edit().clear().apply()
        pending.clear(); confirmations.clear(); service?.revokeAll()
    }
    fun approveAction(value: Confirmation) { value.approved = true; service?.notifyApproved(value.owner,value.task) }
}

class BridgeService : Service() {
    private val workers = java.util.concurrent.ThreadPoolExecutor(4,4,0,java.util.concurrent.TimeUnit.MILLISECONDS,java.util.concurrent.ArrayBlockingQueue(32))
    private val serial = Executors.newSingleThreadExecutor()
    private lateinit var backend: AndroidBackend
    private lateinit var controller: DeviceController
    private val tasks = java.util.concurrent.ConcurrentHashMap<String, BridgePolicy>()
    private val observations = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val identities = java.util.concurrent.ConcurrentHashMap<String, BridgeRegistry.Client>()
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, Messenger>()
    private val instance = java.util.UUID.randomUUID().toString()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val reply = message.replyTo
        val uid = message.sendingUid
        val text = message.data.getString("json") ?: "{}"
        val requestId = message.arg1
        try { workers.execute {
            val answer = try { val client = identify(uid); if(reply!=null)callbacks[client.key]=reply; dispatch(uid, JSONObject(text)) } catch (e: Exception) { errorResult(e.cause as? DeviceError ?: e) }
            if (answer.toString().length > 650000) { answer.remove("screenshot"); answer.put("screenshot", json("available" to false, "reason" to "IPC_SIZE_LIMIT")) }
            val safe = if (answer.toString().length > 650000) errorResult(DeviceError("LIMIT_EXCEEDED", "Observation is too large; request a smaller scope")) else answer
            try { reply?.send(Message.obtain(null, 1, requestId, 0).apply { data = Bundle().apply { putString("json", safe.toString()) } }) } catch (_: Exception) {}
        } } catch(_:java.util.concurrent.RejectedExecutionException) {
            try { reply?.send(Message.obtain(null,1,requestId,0).apply { data=Bundle().apply { putString("json",errorResult(DeviceError("BUSY","Companion request queue is full")).toString()) } }) } catch(_:Exception){}
        }; true
    })
    override fun onCreate() {
        super.onCreate(); backend = AndroidBackend(this, instance)
        controller = DeviceController(backend, { SystemClock.elapsedRealtime() }, { key -> approved(key) })
        BridgeRegistry.service = this
    }
    private fun approved(key: String) = getSharedPreferences("skivu-client-grants-v1", 0).getBoolean(key, false)
    @Suppress("DEPRECATION") private fun identify(uid: Int): BridgeRegistry.Client {
        val packages = packageManager.getPackagesForUid(uid) ?: fail("UNAUTHORIZED", "Unknown Android caller")
        if (packages.size != 1) fail("UNAUTHORIZED", "Shared UID clients are not supported")
        val name = packages.single()
        val signatures = packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
        val fingerprint = signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.sorted().joinToString(":")
        val key = "$name:$fingerprint"
        return BridgeRegistry.Client(key, uid, name, fingerprint).also { identities[key] = it }
    }
    private fun dispatch(uid: Int, request: JSONObject): JSONObject {
        if (request.toString().length > 65536) fail("LIMIT_EXCEEDED", "Request too large")
        val client = identify(uid)
        val method = request.string("method", 80)
        val args = request.optJSONObject("params") ?: JSONObject()
        if (method == "hello") return json("protocol" to 1, "serviceInstanceId" to instance, "approved" to approved(client.key), "packageName" to packageName)
        if (method == "pair") {
            if(!approved(client.key) && !BridgeRegistry.pending.containsKey(client.key) && BridgeRegistry.pending.size>=16)fail("BUSY","Too many pending client approvals")
            if (!approved(client.key)) BridgeRegistry.pending[client.key] = client
            return json("approved" to approved(client.key), "requiresLocalApproval" to !approved(client.key))
        }
        if (!approved(client.key)) fail("UNAUTHORIZED", "Approve this client in the device companion")
        if (method == "capabilities") return backend.capabilities().put("protocol", 1)
        if (method == "list_apps") {
            val value=controller.call(client.key, "list_apps", JSONObject())
            val apps=value.optJSONArray("apps") ?: return value
            value.put("apps",JSONArray((0 until apps.length()).map { apps.getJSONObject(it) }.filter { it.optString("package_name") !in setOf(packageName,client.packageName) }))
            return value
        }
        if (method == "begin") return serial.submit<JSONObject> {
            tasks.values.removeIf { it.revoked || SystemClock.elapsedRealtime() >= it.expires }
            observations.keys.removeIf { !tasks.containsKey(it) }
            if (tasks.isNotEmpty()) fail("DEVICE_BUSY", "Another task owns the device")
            val policy = BridgePolicy(client.key, args) { SystemClock.elapsedRealtime() }
            if (packageName in policy.apps || client.packageName in policy.apps) fail("APP_OUT_OF_SCOPE", "The controller and approval UI cannot be automation targets")
            tasks[policy.id] = policy
            json("taskCapability" to policy.id, "serviceInstanceId" to instance, "expiresInMs" to (policy.expires - SystemClock.elapsedRealtime()))
        }.get()
        val policy = tasks[args.string("taskCapability", 128)] ?: fail("TASK_REVOKED", "Task no longer exists")
        policy.check(client.key)
        if (method == "end") { policy.revoked = true; controller.revokeClient(client.key); tasks.remove(policy.id); observations.remove(policy.id); BridgeRegistry.confirmations.entries.removeIf { it.value.task == policy.id }; return json("cancelled" to true, "inFlightMayComplete" to true) }
        if (method != "call") fail("UNKNOWN_METHOD", "Unknown bridge method")
        val name = args.string("name", 80)
        val input = args.optJSONObject("arguments") ?: JSONObject()
        if (name !in setOf("observe", "wait_for_change", "get_status", "cancel", "tap", "long_press", "swipe", "scroll", "input_text", "back", "home", "launch_app")) fail("PERMISSION_DENIED", "Unsupported task operation")
        fun scope() {
            policy.check(client.key)
            if (!approved(client.key)) fail("UNAUTHORIZED", "Client was revoked")
            if (name == "launch_app") policy.checkApp(input.string("package_name"))
            else if (name !in setOf("get_status", "cancel")) {
                val root = PhoneAccessibilityService.current?.rootInActiveWindow
                val current = try { root?.packageName?.toString() ?: "" } finally { root?.recycle() }
                policy.checkApp(current)
            }
        }
        scope()
        if (name in controller.actionNames) {
            val rid = input.string("request_id", 128)
            val old = observations[policy.id]
            if(input.has("observation_id") && old?.optString("observation_id") != input.optString("observation_id")) fail("STALE_OBSERVATION","Use the latest observation for this task",json("action_executed" to false))
            val nodes = old?.optJSONObject("tree")?.optJSONArray("elements")
            var node: JSONObject? = null
            if (nodes != null) for (i in 0 until nodes.length()) {
                val candidate = nodes.optJSONObject(i) ?: continue
                if (candidate.optString("element_id") == input.optString("element_id")) node = candidate
            }
            if(name == "input_text" && node?.optBoolean("password",false) == true) fail("LOCAL_INPUT_REQUIRED","Password fields must be completed locally by the user")
            if (BridgePolicy.requiresConfirmation(name, input, node)) {
                val signature = name + canonical(input)
                val key = "${policy.id}:$rid"
                val confirmation = BridgeRegistry.confirmations[key]
                if (confirmation == null || confirmation.signature != signature || SystemClock.elapsedRealtime() >= confirmation.expires) {
                    BridgeRegistry.confirmations[key] = BridgeRegistry.Confirmation(client.key, policy.id, rid, signature,
                        "${client.packageName}\n$name\n${node?.optString("text") ?: "Coordinate or unlabeled target"}", SystemClock.elapsedRealtime() + 120000)
                    fail("CONFIRM_REQUIRED", "Approve this specific action in the companion")
                }
                if (!confirmation.approved) fail("CONFIRM_REQUIRED", "Waiting for local action confirmation")
                confirmation.approved = false
            }
            synchronized(policy) { policy.reserve(rid) }
        }
        val result = controller.call(client.key, name, input, ::scope)
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && result.has("error")) {
            val error=result.optJSONObject("error")
            android.util.Log.i("SkivuBridge","Rejected $name code=${error?.optString("code")} reason=${error?.optString("reason")} executed=${result.opt("action_executed")}")
        }
        if (name == "observe" && !result.has("error")) {
            val app = result.optString("package_name")
            policy.checkApp(app); observations[policy.id] = result
        }
        return result
    }
    fun revokeAll() {
        tasks.values.forEach { policy ->
            policy.revoked = true
            try { callbacks[policy.owner]?.send(Message.obtain(null,2).apply { data=Bundle().apply {
                putString("json",json("event" to "revoked","taskCapability" to policy.id).toString())
            } }) } catch(_:Exception){}
        }
        identities.keys.forEach { controller.revokeClient(it) }; tasks.clear(); observations.clear()
    }
    fun targetFor(task: String): String? = observations[task]?.optString("package_name")?.takeIf { it in (tasks[task]?.apps ?: emptySet()) }
    fun notifyApproved(owner: String, task: String) { workers.execute {
        val deadline=SystemClock.elapsedRealtime()+5000
        var targetSince=0L
        while(SystemClock.elapsedRealtime()<deadline) {
            val policy=tasks[task] ?: return@execute
            if(policy.revoked)return@execute
            val root=PhoneAccessibilityService.current?.rootInActiveWindow
            val active=try { root?.packageName?.toString() } finally { root?.recycle() }
            if(active in policy.apps) {
                // Wait for the return transition before retrying the exact approved
                // request. The controller still validates its original target.
                if(targetSince==0L)targetSince=SystemClock.elapsedRealtime()
                if(SystemClock.elapsedRealtime()-targetSince<600) { Thread.sleep(100);continue }
                try { callbacks[owner]?.send(Message.obtain(null,2).apply { data=Bundle().apply { putString("json",json("taskCapability" to task).toString()) } }) } catch(_:Exception){}
                return@execute
            } else targetSince=0L
            Thread.sleep(100)
        }
    } }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { revokeAll(); controller.stop(); workers.shutdownNow(); serial.shutdownNow(); BridgeRegistry.service = null; super.onDestroy() }
}
