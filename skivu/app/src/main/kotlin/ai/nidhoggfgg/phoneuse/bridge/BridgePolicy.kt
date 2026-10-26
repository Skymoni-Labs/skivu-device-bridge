// SPDX-License-Identifier: MIT
package ai.nidhoggfgg.phoneuse.bridge

import ai.nidhoggfgg.phoneuse.core.*
import org.json.JSONObject

class BridgePolicy(val owner: String, raw: JSONObject, private val now: () -> Long) {
    val id = java.util.UUID.randomUUID().toString()
    val apps: Set<String>
    val expires: Long
    val limit: Int
    var actions = 0
        private set
    var revoked = false
    private val dispatched = mutableSetOf<String>()
    init {
        val list = raw.optJSONArray("allowedPackages") ?: fail("INVALID_ARGUMENT", "Choose target apps")
        if (list.length() !in 1..10) fail("INVALID_ARGUMENT", "Choose 1-10 target apps")
        apps = (0 until list.length()).map { list.getString(it) }.toSet()
        if (apps.any { !Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+").matches(it) }) fail("INVALID_ARGUMENT", "Invalid app identifier")
        limit = raw.integer("maxActions", 20, 1, 50)
        expires = now() + raw.integer("timeoutMs", 300000, 1000, 600000)
    }
    fun check(client: String) {
        if (client != owner || revoked || now() >= expires) fail("TASK_REVOKED", "Task expired or was revoked")
    }
    fun checkApp(packageName: String) { if (packageName !in apps) fail("APP_OUT_OF_SCOPE", "The active app is outside this task") }
    fun reserve(requestId: String) {
        if (requestId in dispatched) return
        if (actions >= limit) fail("BUDGET_EXCEEDED", "Device action limit reached")
        dispatched.add(requestId); actions++
    }
    companion object {
        val sensitive = Regex("发送|发表|发布|支付|付款|购买|下单|删除|清空|转账|授权|允许|同意|安装|卸载|确认交易|send|publish|pay|purchase|buy|delete|remove|allow|authorize|install|uninstall|submit", RegexOption.IGNORE_CASE)
        fun requiresConfirmation(name: String, args: JSONObject, node: JSONObject?): Boolean {
            if (name == "tap" || name == "long_press") {
                if (args.has("x") || node == null) return true
                val label = listOf("text", "content_description", "description", "label").joinToString(" ") { node.optString(it) }
                return label.isBlank() || sensitive.containsMatchIn(label)
            }
            return false
        }
    }
}
