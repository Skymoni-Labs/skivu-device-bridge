package ai.nidhoggfgg.phoneuse.server

import ai.nidhoggfgg.phoneuse.core.fail
import java.security.MessageDigest
import java.util.Locale

/** Commit/reveal SAS transcript. See docs/transport-security.md for wire encoding and threat model. */
object PairingCrypto {
    const val PROTOCOL = "phoneuse-sas-v1"
    fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    fun checked(value: String): String {
        if (!Regex("[0-9a-f]{64}").matches(value)) fail("INVALID_ARGUMENT", "Expected 32 bytes of lowercase hex")
        return value
    }
    private fun digest(purpose: String, vararg fields: String) =
        MessageDigest.getInstance("SHA-256").digest((listOf(PROTOCOL, purpose) + fields).joinToString("\u0000").toByteArray(Charsets.US_ASCII))
    fun commitment(pin: String, nonce: String): String = digest("commit", checked(pin), checked(nonce)).joinToString("") { "%02x".format(it) }
    fun code(pin: String, clientNonce: String, serverNonce: String): String {
        val bytes = digest("sas", checked(pin), checked(clientNonce), checked(serverNonce))
        val number = bytes.take(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 255) }
        return String.format(Locale.ROOT, "%08d", number % 100_000_000)
    }
}
