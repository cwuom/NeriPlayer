package moe.ouom.neriplayer.core.player.lan.timing

import kotlin.math.abs

internal class LanDriftController {
    private var previousElapsedMs = -1L
    private var previousErrorMs = 0.0
    private var speed = 1f

    fun reset() {
        previousElapsedMs = -1L
        previousErrorMs = 0.0
        speed = 1f
    }

    fun speedFor(errorMs: Double, sampleElapsedMs: Long): Float {
        if (errorMs !in -MAX_CORRECTABLE_ERROR_MS..MAX_CORRECTABLE_ERROR_MS || sampleElapsedMs < 0) {
            reset()
            return 1f
        }
        if (previousElapsedMs >= 0) {
            val intervalMs = sampleElapsedMs - previousElapsedMs
            val continuous = intervalMs in MIN_CONFIRMATION_MS..MAX_SAMPLE_GAP_MS &&
                abs(errorMs - previousErrorMs) <= MAX_ERROR_STEP_MS
            if (!continuous) {
                reset()
                return 1f
            }
            speed = correctionSpeed(errorMs)
        }
        previousElapsedMs = sampleElapsedMs
        previousErrorMs = errorMs
        return speed
    }

    private fun correctionSpeed(errorMs: Double): Float = when {
        // 两次同向观测才开始调速，进入较小的死区后才结束，减少噪声触发的反复切换
        minOf(errorMs, previousErrorMs) >= START_ERROR_MS -> 1f + SPEED_STEP
        maxOf(errorMs, previousErrorMs) <= -START_ERROR_MS -> 1f - SPEED_STEP
        speed > 1f && errorMs > STOP_ERROR_MS -> speed
        speed < 1f && errorMs < -STOP_ERROR_MS -> speed
        else -> 1f
    }

    private companion object {
        const val MIN_CONFIRMATION_MS = 50L
        const val MAX_SAMPLE_GAP_MS = 1_000L
        const val MAX_CORRECTABLE_ERROR_MS = 250.0
        const val MAX_ERROR_STEP_MS = 12.0
        const val START_ERROR_MS = 5.0
        const val STOP_ERROR_MS = 2.0
        const val SPEED_STEP = 0.002f
    }
}
