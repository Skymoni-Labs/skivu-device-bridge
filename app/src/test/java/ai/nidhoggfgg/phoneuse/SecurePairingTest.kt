package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.DeviceError
import ai.nidhoggfgg.phoneuse.core.json
import ai.nidhoggfgg.phoneuse.server.PairingCrypto
import ai.nidhoggfgg.phoneuse.server.PairingRequests
import org.junit.Assert.*
import org.junit.Test

class SecurePairingTest {
    private val pin = "00".repeat(32)
    private val nonce = "11".repeat(32)
    private var now = 1000L
    private var issued = 0
    private val pairing = PairingRequests({ now }) { issued++; json("token" to "secret", "client_id" to "client") }

    @Test fun sharedCrossLanguageTranscriptVector() {
        assertEquals("9752824c0f5f6e1fb8abbea0ef2960d94e93db4125093766cdcb87bdf8b589e1", PairingCrypto.commitment(pin, nonce))
        assertEquals("17242944", PairingCrypto.code(pin, nonce, "22".repeat(32)))
    }

    @Test fun commitmentMustBeRevealedBeforeItCanBeSeenOrApproved() {
        val start = pairing.create("laptop", "source", PairingCrypto.commitment(pin, nonce), pin)
        val id = start.getString("pairing_id")
        val secret = start.getString("pairing_secret")
        assertEquals("committed", start.getString("status"))
        assertFalse(start.has("verification_code"))
        assertTrue(pairing.pending().isEmpty())
        pairing.decide(id, true)
        assertEquals(0, issued)
        assertFalse(pairing.poll(id, secret).has("token"))
        val reveal = pairing.reveal(id, secret, nonce)
        assertFalse(reveal.has("verification_code"))
        assertEquals(PairingCrypto.code(pin, nonce, start.getString("server_nonce")), pairing.pending().single().verificationCode)
        pairing.reveal(id, secret, nonce) // Identical reveals may be retried before approval.
        pairing.decide(id, true)
        assertEquals("secret", pairing.poll(id, secret).getString("token"))
        assertEquals(1, issued)
        assertThrows(DeviceError::class.java) { pairing.reveal(id, secret, nonce) }
    }

    @Test fun relayedCommitmentForAnAttackersCertificateIsRejectedPermanently() {
        val start = pairing.create("laptop", "source", PairingCrypto.commitment("ff".repeat(32), nonce), pin)
        val id = start.getString("pairing_id")
        val secret = start.getString("pairing_secret")
        assertThrows(DeviceError::class.java) { pairing.reveal(id, secret, nonce) }
        assertEquals("denied", pairing.poll(id, secret).getString("status"))
        pairing.decide(id, true)
        assertEquals(0, issued)
        assertTrue(pairing.pending().isEmpty())
        assertThrows(DeviceError::class.java) { pairing.reveal(id, secret, nonce) }
    }

    @Test fun changedNonceCannotReplaceAnAlreadyDisplayedCode() {
        val start = pairing.create("laptop", "source", PairingCrypto.commitment(pin, nonce), pin)
        val id = start.getString("pairing_id")
        val secret = start.getString("pairing_secret")
        pairing.reveal(id, secret, nonce)
        assertThrows(DeviceError::class.java) { pairing.reveal(id, secret, "ff".repeat(32)) }
        assertTrue(pairing.pending().isEmpty())
        pairing.decide(id, true)
        assertEquals(0, issued)
    }

    @Test fun secretsDeadlinesAndRateLimitsAlsoApplyBeforeReveal() {
        val start = pairing.create("laptop", "source", PairingCrypto.commitment(pin, nonce), pin)
        assertThrows(DeviceError::class.java) { pairing.create("laptop", "source", PairingCrypto.commitment(pin, nonce), pin) }
        assertThrows(DeviceError::class.java) { pairing.reveal(start.getString("pairing_id"), "wrong", nonce) }
        now += 120_000
        assertThrows(DeviceError::class.java) { pairing.reveal(start.getString("pairing_id"), start.getString("pairing_secret"), nonce) }
        assertEquals(0, issued)
    }
}
