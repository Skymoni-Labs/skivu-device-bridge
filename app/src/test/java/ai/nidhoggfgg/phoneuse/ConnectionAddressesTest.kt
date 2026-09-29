package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.server.ConnectionAddresses
import org.junit.Assert.*
import org.junit.Test

class ConnectionAddressesTest {
    @Test fun addressesHaveExplicitSchemesAndSharedPort() {
        assertEquals(listOf("https://192.168.1.2:8443", "https://phone.example:8443"),
            ConnectionAddresses.urls(listOf("192.168.1.2"), "phone.example", false))
        assertEquals(listOf("https://[2001:db8::1]:8443", "http://[2001:db8::1]:8443"),
            ConnectionAddresses.urls(listOf("2001:db8::1"), "", true))
        assertEquals("http://127.0.0.1:8443/", ConnectionAddresses.LOCAL_CONSOLE)
        assertTrue(ConnectionAddresses.urls(emptyList(), "", false).isEmpty())
    }
    @Test fun onlyExplicitNonLoopbackHostsCanBeConfigured() {
        for (host in listOf("phone.example", "203.0.113.7", "2001:db8::7", ""))
            assertEquals(host, ConnectionAddresses.publicHost(host))
        assertEquals("phone.example", ConnectionAddresses.publicHost(" Phone.Example "))
        for (host in listOf("127.0.0.1", "127.0.0.2", "::1", "::ffff:127.0.0.1", "localhost", "x.localhost", "0.0.0.0", "::", "127.1",
            "https://phone.example", "phone.example:8443", "phone.example/", "phone.example?x", "user@phone.example", "*.example", "phone.example#x", "999.2.3.4"))
            assertThrows(host, Exception::class.java) { ConnectionAddresses.publicHost(host) }
    }
}
