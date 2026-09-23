package ai.nidhoggfgg.phoneuse.server

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Principal
import java.security.cert.X509Certificate
import java.net.Socket
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** Per-installation, non-exportable server key. No shared certificate/private key in the APK. */
class TlsIdentity {
    val context: SSLContext
    val pin: String

    init {
        synchronized(lock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        // Conscrypt hashes TLS transcripts itself and uses NONEwithECDSA
                        // for the Keystore operation; the wire signature still uses SHA-256.
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setCertificateSubject(X500Principal("CN=Phone Use"))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(Date(0))
                        .setCertificateNotAfter(Date(4_102_444_800_000L))
                        .build())
                    generateKeyPair()
                }
            }
            pin = PairingCrypto.hash(store.getCertificate(ALIAS).publicKey.encoded)
            val certificate = store.getCertificate(ALIAS) as X509Certificate
            val key = store.getKey(ALIAS, null) as PrivateKey
            // Do not let a provider select another, unrelated key in this app's Keystore.
            val manager = object : X509ExtendedKeyManager() {
                override fun getPrivateKey(alias: String?) = key.takeIf { alias == ALIAS }
                override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(certificate) else null
                override fun getServerAliases(type: String?, issuers: Array<out Principal>?) =
                    if (type?.startsWith("EC") == true) arrayOf(ALIAS) else null
                override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = getServerAliases(type, issuers)?.first()
                override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = getServerAliases(type, issuers)?.first()
                override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = null
                override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            }
            context = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
        }
    }

    companion object {
        private const val ALIAS = "phone-use-tls-spki-v1"
        private val lock = Any()
    }
}
