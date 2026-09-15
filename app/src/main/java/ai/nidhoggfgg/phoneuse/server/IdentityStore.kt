package ai.nidhoggfgg.phoneuse.server

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.nidhoggfgg.phoneuse.core.*
import org.json.JSONObject
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** Tokens are only returned once; only their SHA-256 hashes are persisted. */
@android.annotation.SuppressLint("ApplySharedPref", "UseKtx") // Synchronous persistence is part of credential issuance/revocation.
class IdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("clients", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private var code: String? = null
    private var codeDeadline = 0L
    private var attempts = 0
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "phone-use-tls-v1"
    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=Phone Use"))
                    .setCertificateSerialNumber(BigInteger(128, random))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 86_400_000))
                    .build())
                generateKeyPair()
            }
        }
    }
    val certificate: X509Certificate get() = store.getCertificate(alias) as X509Certificate
    val fingerprint: String get() = hash(certificate.encoded).chunked(2).joinToString(":")
    val certificatePem: String get() = "-----BEGIN CERTIFICATE-----\n" +
        Base64.encodeToString(certificate.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
    fun sslContext(): SSLContext {
        val key = store.getKey(alias, null) as PrivateKey
        val cert = certificate
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = if (keyType == "RSA") arrayOf(alias) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = if (keyType == "RSA") alias else null
            override fun getCertificateChain(requested: String?) = if (requested == alias) arrayOf(cert) else null
            override fun getPrivateKey(requested: String?) = if (requested == alias) key else null
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, random) }
    }
    @Synchronized fun openPairing(): String {
        code = (100_000 + random.nextInt(900_000)).toString()
        codeDeadline = SystemClock.elapsedRealtime() + 120_000
        attempts = 0
        return code!!
    }
    @Synchronized fun pairingCode(): String? = code?.takeIf { SystemClock.elapsedRealtime() < codeDeadline }
    @Synchronized fun closePairing() { code = null }
    @Synchronized fun pair(args: JSONObject): JSONObject {
        val submitted = args.string("code", 6)
        val name = args.string("client_name", 80)
        if (pairingCode() == null || attempts >= 5) fail("PAIRING_CLOSED", "Open pairing on the phone")
        attempts++
        if (!MessageDigest.isEqual(submitted.toByteArray(), code!!.toByteArray())) fail("PAIRING_DENIED", "Pairing code is invalid")
        if (preferences.all.size >= 16) fail("CLIENT_LIMIT", "Revoke an existing client first")
        val token = Base64.encodeToString(ByteArray(32).also { random.nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val id = UUID.randomUUID().toString()
        val record = json("id" to id, "name" to name, "token_hash" to hash(token.toByteArray()))
        if (!preferences.edit().putString(id, record.toString()).commit()) fail("STORAGE_ERROR", "Could not save pairing")
        closePairing()
        return json("client_id" to id, "token" to token, "certificate_sha256" to fingerprint)
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
    @Synchronized fun revoke(id: String) { preferences.edit().remove(id).commit() }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
