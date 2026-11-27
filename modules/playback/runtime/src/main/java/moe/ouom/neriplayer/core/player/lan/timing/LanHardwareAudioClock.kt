package moe.ouom.neriplayer.core.player.lan.timing

internal data class LanHardwareObservation(val elapsedMs: Double, val mediaPositionMs: Double, val routeId: Int)

internal data class LanHardwarePlan(val generation: Long, val deadlineElapsedMs: Long)

internal class LanHardwareAudioClock {
    @Volatile var status = "idle"
        private set
    @Volatile var plan: LanHardwarePlan? = null
        private set
    private var latest: LanHardwareObservation? = null
    internal val history = LanStartLatency()

    @Synchronized fun arm(generation: Long, deadlineElapsedMs: Long) {
        latest = null
        status = "armed"
        plan = LanHardwarePlan(generation, deadlineElapsedMs)
    }

    @Synchronized fun cancel() {
        plan = null
        latest = null
        status = "idle"
    }

    @Synchronized fun observation(generation: Long): LanHardwareObservation? =
        latest.takeIf { plan?.generation == generation }

    @Synchronized internal fun cleared(ticket: LanHardwarePlan?) {
        if (ticket != null && ticket === plan) latest = null
    }

    @Synchronized internal fun start(ticket: LanHardwarePlan, mode: String, play: () -> Unit): Boolean {
        if (plan !== ticket) return false
        play()
        status = mode
        return true
    }

    @Synchronized internal fun observed(ticket: LanHardwarePlan, sample: LanHardwareObservation) {
        if (plan !== ticket) return
        latest = sample
        if (!status.startsWith("tracking")) status = "tracking-$status"
    }
}
