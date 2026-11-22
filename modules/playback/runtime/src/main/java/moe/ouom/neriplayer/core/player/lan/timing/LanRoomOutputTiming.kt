package moe.ouom.neriplayer.core.player.lan.timing

import moe.ouom.neriplayer.core.player.lan.LanMedia3StartPlan

internal class LanRoomOutputTiming {
    val hardware = LanHardwareAudioClock()
    private val drift = LanDriftController()
    private var target: LanMedia3StartPlan? = null
    private var initialOffsetMs = 0.0
    private var offsetMs = 0.0
    private var nudgeDeltaMs = 0
    private var clockUpdatedAtMs: Long? = null
    private var lastSampleMs = Long.MIN_VALUE
    private var routeId: Int? = null
    var errorMs: Double? = null
        private set

    fun arm(generation: Long, plan: LanMedia3StartPlan, clockOffsetMs: Double) {
        target = plan
        initialOffsetMs = clockOffsetMs
        nudgeDeltaMs = 0
        resetSamples()
        hardware.arm(generation, plan.deadlineMs)
    }

    fun upcomingPositionMs(nowMs: Long): Long? = target?.takeIf { nowMs < it.deadlineMs }?.positionMs

    fun updateClock(newOffsetMs: Double, elapsedMs: Long) {
        offsetMs = newOffsetMs
        clockUpdatedAtMs = elapsedMs
    }

    fun resetClock() {
        clockUpdatedAtMs = null
        resetSamples()
    }

    fun cancel() {
        target = null
        resetSamples()
        hardware.cancel()
    }

    fun discardSamples() {
        resetSamples()
        hardware.cleared(hardware.plan)
    }

    fun resumeOutput(generation: Long, nowMs: Long) {
        if (target != null && hardware.plan == null) hardware.arm(generation, nowMs)
    }

    fun movedByNudge(deltaMs: Int) {
        nudgeDeltaMs += deltaMs
        resetSamples()
        hardware.cancel()
    }

    fun speed(generation: Long, nowMs: Long, currentSpeed: Float): Float {
        val plan = target ?: return 1f
        val lastClockMs = clockUpdatedAtMs
        val sample = hardware.observation(generation)
        if (sample == null || nowMs - sample.elapsedMs !in -20.0..500.0 ||
            lastClockMs == null || nowMs - lastClockMs !in 0..MAX_CLOCK_AGE_MS) {
            resetSamples()
            return 1f
        }
        if (routeId != sample.routeId) resetSamples()
        routeId = sample.routeId
        val sampleMs = sample.elapsedMs.toLong()
        if (sampleMs == lastSampleMs) return currentSpeed
        lastSampleMs = sampleMs
        val targetMs = plan.positionMs + (sample.elapsedMs - plan.deadlineMs) +
            (offsetMs - initialOffsetMs) + nudgeDeltaMs
        val error = targetMs - sample.mediaPositionMs
        errorMs = error
        return drift.speedFor(error, sampleMs)
    }

    private fun resetSamples() {
        routeId = null
        lastSampleMs = Long.MIN_VALUE
        errorMs = null
        drift.reset()
    }

    private companion object {
        // 稳态探针对间隔为 2.5 秒，连续错过四轮后等待新的房间时钟
        const val MAX_CLOCK_AGE_MS = 10_000L
    }
}
