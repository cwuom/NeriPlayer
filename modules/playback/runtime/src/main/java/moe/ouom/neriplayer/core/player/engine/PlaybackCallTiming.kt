package moe.ouom.neriplayer.core.player.engine

import android.os.SystemClock
import moe.ouom.neriplayer.common.logging.NPLogger

private const val SLOW_CALL_MS = 100L
private const val LONG_GAP_MS = 250L
private const val NO_PREVIOUS_CALL = -1L

internal class PlaybackCallTiming(
    private val operation: String,
    private val enabled: () -> Boolean,
    private val reportGaps: Boolean = true,
    private val details: () -> String = { "" },
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val uptimeMs: () -> Long = SystemClock::uptimeMillis,
    private val report: (String) -> Unit = { NPLogger.d("NERI-PlaybackTiming", it) }
) {
    private var previousEndMs = NO_PREVIOUS_CALL
    private var previousEndUptimeMs = NO_PREVIOUS_CALL

    inline fun <T> measure(call: () -> T): T {
        if (!enabled()) {
            reset()
            return call()
        }
        val startedAtMs = elapsedRealtimeMs()
        val startedAtUptimeMs = uptimeMs()
        val hasPrevious = reportGaps && previousEndMs != NO_PREVIOUS_CALL
        val gapMs = if (hasPrevious) startedAtMs - previousEndMs else 0L
        val gapUptimeMs = if (hasPrevious) startedAtUptimeMs - previousEndUptimeMs else 0L
        try {
            return call()
        } finally {
            previousEndMs = elapsedRealtimeMs()
            previousEndUptimeMs = uptimeMs()
            val durationMs = previousEndMs - startedAtMs
            if (gapMs >= LONG_GAP_MS || durationMs >= SLOW_CALL_MS) {
                val durationUptimeMs = previousEndUptimeMs - startedAtUptimeMs
                report(
                    "operation=$operation atMs=$startedAtMs gapMs=$gapMs " +
                        "gapUptimeMs=$gapUptimeMs durationMs=$durationMs durationUptimeMs=$durationUptimeMs" +
                        details()
                )
            }
        }
    }

    fun reset() {
        previousEndMs = NO_PREVIOUS_CALL
        previousEndUptimeMs = NO_PREVIOUS_CALL
    }
}
