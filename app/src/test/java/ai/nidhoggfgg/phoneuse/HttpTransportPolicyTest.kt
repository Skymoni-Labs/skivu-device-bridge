package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.DeviceError
import ai.nidhoggfgg.phoneuse.server.HttpTransportPolicy
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class HttpTransportPolicyTest {
    private class Peer(address: String) : Socket() {
        private val peer = InetAddress.getByName(address)
        var closedByPolicy = false
        override fun getInetAddress() = peer
        override fun close() { closedByPolicy = true }
    }

    @Test fun onlyActualLoopbackPeersBypassTheSwitch() {
        val policy = HttpTransportPolicy()
        for (ip in listOf("127.0.0.1", "127.0.0.2", "::1", "::ffff:127.0.0.1")) {
            val socket = Peer(ip)
            assertNotNull(policy.register(socket, false))
        }
        for (ip in listOf("192.168.1.2", "203.0.113.7", "2001:db8::2")) {
            assertNull(policy.register(Peer(ip), false))
            assertNotNull(policy.register(Peer(ip), true))
        }
    }

    @Test fun disablingClosesOnlyRemoteHttpAndPermanentlyInvalidatesItsWork() {
        val policy = HttpTransportPolicy(true)
        val remote = Peer("192.168.1.2")
        val tls = Peer("192.168.1.2")
        val local = Peer("127.0.0.1")
        val remoteCheck = policy.register(remote, false)!!
        val tlsCheck = policy.register(tls, true)!!
        val localCheck = policy.register(local, false)!!
        remoteCheck()
        policy.update(false)
        assertTrue(remote.closedByPolicy)
        assertFalse(tls.closedByPolicy)
        assertFalse(local.closedByPolicy)
        tlsCheck(); localCheck()
        policy.update(true)
        assertThrows(DeviceError::class.java) { remoteCheck() }
        policy.register(Peer("192.168.1.2"), false)!!()
    }

    @Test fun pendingWorkStaysInvalidatedAfterItsResponseSocketWasRemoved() {
        val policy = HttpTransportPolicy(true)
        val peer = Peer("203.0.113.1")
        val check = policy.register(peer, false)!!
        policy.remove(peer)
        policy.update(false); policy.update(true)
        assertThrows(DeviceError::class.java) { check() }
    }
}
