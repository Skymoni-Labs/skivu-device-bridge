package ai.nidhoggfgg.phoneuse.server

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import ai.nidhoggfgg.phoneuse.core.*
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Only token hashes are persisted; pending pairing responses expire in memory. */
@android.annotation.SuppressLint("ApplySharedPref", "UseKtx") // Synchronous persistence is part of credential issuance/revocation.
class IdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("clients", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    val pairing = PairingRequests(SystemClock::elapsedRealtime, ::issue)
    @Synchronized private fun issue(name: String): JSONObject {
        if (preferences.all.size >= 16) fail("CLIENT_LIMIT", "Revoke an existing client first")
        val token = Base64.encodeToString(ByteArray(32).also { random.nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val id = UUID.randomUUID().toString()
        val record = json("id" to id, "name" to name, "token_hash" to hash(token.toByteArray()))
        if (!preferences.edit().putString(id, record.toString()).commit()) fail("STORAGE_ERROR", "Could not save pairing")
        return json("client_id" to id, "token" to token,
            "api_path" to "/api", "mcp_path" to "/mcp", "token_type" to "Bearer")
    }
    @Synchronized fun authenticate(token: String): String? {
        if (token.length !in 32..128) return null
        val digest = hash(token.toByteArray()).toByteArray()
        return preferences.all.values.mapNotNull { it as? String }.map { JSONObject(it) }.firstOrNull {
            MessageDigest.isEqual(it.getString("token_hash").toByteArray(), digest)
        }?.getString("id")
    }
    @Synchronized fun exists(id: String): Boolean = preferences.contains(id)
    @Synchronized fun clients(): List<Pair<String, String>> = preferences.all.values.mapNotNull { it as? String }
        .map { JSONObject(it) }.map { it.getString("id") to it.getString("name") }
    @Synchronized fun revoke(id: String) {
        if (!preferences.edit().remove(id).commit()) fail("STORAGE_ERROR", "Could not revoke client")
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
