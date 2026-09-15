package ai.nidhoggfgg.phoneuse.core

import java.util.UUID

/** Pure control state. All deadlines use a monotonic clock; leases never survive a restart. */
class ControlGate(private val clock: () -> Long) {
    data class Lease(val id: String, val client: String, var deadline: Long)
    private var lease: Lease? = null
    @Volatile var paused = false
        private set
    @Volatile var stopped = false
        private set
    @Volatile var generation = 0L
        private set

    @Synchronized fun current(): Lease? {
        if (lease?.let { it.deadline <= clock() } == true) { lease = null; generation++ }
        return lease?.copy()
    }
    @Synchronized fun acquire(client: String, seconds: Int, renewId: String?): Lease {
        if (stopped || paused) fail("PAUSED", "Resume the service on the phone first")
        val old = current()
        if (old != null) {
            if (old.client != client || old.id != renewId) fail("CONTROL_BUSY", "An active lease already owns control")
            lease!!.deadline = clock() + seconds * 1000L
        } else {
            if (renewId != null) fail("LEASE_EXPIRED", "Expired leases cannot be renewed")
            lease = Lease(UUID.randomUUID().toString(), client, clock() + seconds * 1000L)
        }
        return lease!!.copy()
    }
    @Synchronized fun check(client: String, id: String, expectedGeneration: Long? = null) {
        val active = current()
        if (stopped || paused) fail("CANCELLED", "Control is paused or stopped")
        if (active?.id != id || active.client != client) fail("LEASE_EXPIRED", "Acquire a new control lease")
        if (expectedGeneration != null && expectedGeneration != generation) fail("CANCELLED", "Request was invalidated")
    }
    @Synchronized fun release(client: String, id: String) { check(client, id); revoke() }
    @Synchronized fun revoke() { lease = null; generation++ }
    @Synchronized fun pause() { paused = true; revoke() }
    @Synchronized fun resume() { if (!stopped) paused = false }
    @Synchronized fun stop() { stopped = true; revoke() }
    @Synchronized fun remaining(): Long = current()?.let { (it.deadline - clock()).coerceAtLeast(0) } ?: 0
}
