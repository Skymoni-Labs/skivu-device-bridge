package ai.nidhoggfgg.phoneuse.core

/** Invalidates pending work across pause/stop, even if the service is resumed immediately. */
class ControlGate {
    @Volatile var paused = false
        private set
    @Volatile var stopped = false
        private set
    @Volatile var generation = 0L
        private set

    @Synchronized fun check(expectedGeneration: Long? = null) {
        if (stopped || paused) fail("CANCELLED", "Control is paused or stopped")
        if (expectedGeneration != null && expectedGeneration != generation)
            fail("CANCELLED", "Request was invalidated")
    }
    @Synchronized fun pause() { paused = true; generation++ }
    @Synchronized fun resume() { if (!stopped) paused = false }
    @Synchronized fun stop() { stopped = true; generation++ }
}
