package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.server.BrowserAccess
import org.junit.Assert.*
import org.junit.Test

class BrowserAccessTest {
    private val host = "192.168.1.20:8443"
    private fun allowed(vararg extra: Pair<String, String>) = BrowserAccess.allowed(mapOf("host" to host, *extra), "192.168.1.20", 8443)
    @Test fun sameOriginAndNativeClientsAreAllowed() {
        assertTrue(allowed())
        assertTrue(allowed("origin" to "http://$host", "sec-fetch-site" to "same-origin"))
    }
    @Test fun foreignNullPlaintextAndWrongPortOriginsAreDenied() {
        for (origin in listOf("null", "http://example.com", "https://$host", "http://192.168.1.20:443"))
            assertFalse(allowed("origin" to origin))
        assertFalse(allowed("sec-fetch-site" to "cross-site"))
    }
    @Test fun domainHostCannotRebindToLocalAddress() {
        assertFalse(allowed("host" to "attacker.example:8443", "origin" to "http://attacker.example:8443"))
        assertFalse(BrowserAccess.allowed(emptyMap(), "192.168.1.20", 8443))
    }
    @Test fun androidAppCanOpenPublicPageWithoutAllowingCrossSiteApiAccess() {
        val navigation = mapOf("host" to host, "sec-fetch-site" to "cross-site")
        assertTrue(BrowserAccess.allowed(navigation, "192.168.1.20", 8443, publicPage = true))
        assertFalse(BrowserAccess.allowed(navigation, "192.168.1.20", 8443))
        assertFalse(BrowserAccess.allowed(navigation + ("host" to "attacker.example:8443"), "192.168.1.20", 8443, publicPage = true))
    }
}
