package ai.nidhoggfgg.phoneuse.server

import ai.nidhoggfgg.phoneuse.core.fail
import java.net.Socket

/** Registration and disabling share a lock so a concurrent admission cannot escape shutdown. */
class HttpTransportPolicy(allowInsecureHttp: Boolean = false) {
    private val lock = Any()
    private var enabled = allowInsecureHttp
    private var generation = 0L
    private val remoteHttp = mutableSetOf<Socket>()

    fun update(allow: Boolean) {
        val closing = synchronized(lock) {
            if (enabled == allow) return
            enabled = allow
            if (allow) emptyList() else {
                generation++
                remoteHttp.toList().also { remoteHttp.clear() }
            }
        }
        closing.forEach { runCatching { it.close() } }
    }

    /** The peer is the socket's actual source, never Host, Origin or forwarding headers. */
    fun register(socket: Socket, secure: Boolean): (() -> Unit)? = synchronized(lock) {
        if (secure || socket.inetAddress.isLoopbackAddress) return@synchronized {}
        if (!enabled) return@synchronized null
        remoteHttp.add(socket)
        val admittedGeneration = generation
        val check: () -> Unit = {
            synchronized(lock) {
                if (!enabled || generation != admittedGeneration)
                    fail("TRANSPORT_DISABLED", "Non-loopback HTTP connections are disabled")
            }
        }
        check
    }

    fun remove(socket: Socket) { synchronized(lock) { remoteHttp.remove(socket) } }
}
