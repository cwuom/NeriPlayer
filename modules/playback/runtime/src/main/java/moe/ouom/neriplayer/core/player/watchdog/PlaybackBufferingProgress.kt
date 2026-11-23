package moe.ouom.neriplayer.core.player.watchdog

import android.os.SystemClock
import androidx.media3.common.Player
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.policy.progress.PLAYBACK_RUNTIME_STALL_POSITION_TOLERANCE_MS

internal class PlaybackBufferingProgress {
    private var positionGeneration = -1L
    private var highWaterMs: Long? = null
    var lastGrowthAtMs = 0L
        private set

    fun observe(state: Int, bufferedMs: Long, generation: Long): Boolean {
        val previous = highWaterMs.takeIf { generation == positionGeneration }
        positionGeneration = generation
        if (state != Player.STATE_BUFFERING || previous == null) {
            highWaterMs = bufferedMs.takeIf { state == Player.STATE_BUFFERING }
            lastGrowthAtMs = 0L
            return false
        }
        // 只认本轮缓冲的新高，反复回到同一水位不能一直推迟恢复
        if (bufferedMs - previous <= PLAYBACK_RUNTIME_STALL_POSITION_TOLERANCE_MS) return false
        highWaterMs = bufferedMs
        lastGrowthAtMs = SystemClock.elapsedRealtime()
        return true
    }
}

internal fun PlayerManager.watchdogBufferedDurationMs(): Long =
    runCatching { player.totalBufferedDuration.coerceAtLeast(0L) }.getOrDefault(0L)
