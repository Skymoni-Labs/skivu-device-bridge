package ai.nidhoggfgg.phoneuse.server

import ai.nidhoggfgg.phoneuse.core.*
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Pending secrets and issued credentials live only until the request deadline. */
class PairingRequests(private val clock: () -> Long, private val issue: (String) -> JSONObject) {
    data class Request(val id: String, val name: String, val source: String, var verificationCode: String,
        val deadline: Long, internal val secret: String, var status: String = "pending",
        internal var credential: JSONObject? = null, internal val commitment: String? = null,
        internal val pin: String? = null, internal val serverNonce: String? = null)
    private val requests = LinkedHashMap<String, Request>()
    private val random = SecureRandom()
    private var nextRequestAt = 0L
    private fun prune() { requests.entries.removeAll { clock() >= it.value.deadline } }

    @Synchronized fun create(name: String, source: String, commitment: String? = null, pin: String? = null): JSONObject {
        prune()
        if (name.isBlank() || name.length > 80 || name.any { it.isISOControl() }) fail("INVALID_ARGUMENT", "client_name must contain 1–80 printable characters")
        if (clock() < nextRequestAt || requests.size >= 16) fail("PAIRING_BUSY", "Wait before requesting pairing again")
        if (commitment != null) { PairingCrypto.checked(commitment); PairingCrypto.checked(pin.orEmpty()) }
        nextRequestAt = clock() + 5_000
        val request = Request(UUID.randomUUID().toString(), name, source,
            (100_000 + random.nextInt(900_000)).toString(), clock() + 120_000,
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes)),
            status = if (commitment == null) "pending" else "committed", commitment = commitment, pin = pin,
            serverNonce = if (commitment == null) null else ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) })
        requests[request.id] = request
        return json("pairing_id" to request.id, "pairing_secret" to request.secret, "status" to request.status,
            "expires_in" to 120, "poll_interval" to 2).apply {
            if (commitment == null) put("verification_code", request.verificationCode)
            else { put("protocol", PairingCrypto.PROTOCOL); put("server_nonce", request.serverNonce) }
        }
    }
    @Synchronized fun reveal(id: String, secret: String, nonce: String): JSONObject {
        prune()
        val request = requests[id] ?: fail("PAIRING_EXPIRED", "Pairing request expired or service restarted")
        if (!MessageDigest.isEqual(request.secret.toByteArray(), secret.toByteArray())) fail("PAIRING_DENIED", "Invalid pairing secret")
        if (request.commitment == null || request.status !in setOf("committed", "pending")) fail("PAIRING_DENIED", "Pairing cannot be revealed")
        val expected = PairingCrypto.commitment(request.pin!!, PairingCrypto.checked(nonce))
        if (!MessageDigest.isEqual(expected.toByteArray(), request.commitment.toByteArray())) {
            request.status = "denied"
            fail("PAIRING_DENIED", "Pairing commitment mismatch; do not approve")
        }
        request.verificationCode = PairingCrypto.code(request.pin, nonce, request.serverNonce!!)
        request.status = "pending"
        return json("status" to "pending")
    }
    @Synchronized fun pending(): List<Request> { prune(); return requests.values.filter { it.status == "pending" }.map { it.copy(secret = "") } }
    @Synchronized fun decide(id: String, approved: Boolean) {
        prune()
        val request = requests[id] ?: return
        if (request.status != "pending") return
        if (approved) request.credential = issue(request.name)
        request.status = if (approved) "approved" else "denied"
    }
    @Synchronized fun poll(id: String, secret: String): JSONObject {
        prune()
        val request = requests[id] ?: fail("PAIRING_EXPIRED", "Pairing request expired or service restarted")
        if (!MessageDigest.isEqual(request.secret.toByteArray(), secret.toByteArray())) fail("PAIRING_DENIED", "Invalid pairing secret")
        return json("status" to request.status).apply {
            request.credential?.let { credential -> credential.keys().forEach { put(it, credential.get(it)) } }
        }
    }
    @Synchronized fun clear() { requests.clear() }
    @Synchronized fun revoked(clientId: String) {
        requests.values.filter { it.credential?.optString("client_id") == clientId }.forEach {
            it.credential = null; it.status = "denied"
        }
    }
}
