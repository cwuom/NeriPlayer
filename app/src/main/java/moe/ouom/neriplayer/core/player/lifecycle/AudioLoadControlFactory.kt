@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import moe.ouom.neriplayer.core.logging.NPLogger

internal data class AudioLoadControlPolicy(
    val minBufferMs: Int = 15_000,
    val maxBufferMs: Int = 30_000,
    val bufferForPlaybackMs: Int = 800,
    val bufferForPlaybackAfterRebufferMs: Int = 1_500
)

internal fun buildAudioLoadControl(
    policy: AudioLoadControlPolicy = AudioLoadControlPolicy()
): LoadControl {
    return try {
        assert(policy.bufferForPlaybackMs >= 0) {
            "bufferForPlaybackMs must be >= 0"
        }
        assert(policy.bufferForPlaybackAfterRebufferMs >= 0) {
            "bufferForPlaybackAfterRebufferMs must be >= 0"
        }
        assert(policy.minBufferMs >= policy.bufferForPlaybackMs) {
            "minBufferMs must be >= bufferForPlaybackMs"
        }
        assert(policy.minBufferMs >= policy.bufferForPlaybackAfterRebufferMs) {
            "minBufferMs must be >= bufferForPlaybackAfterRebufferMs"
        }
        assert(policy.maxBufferMs >= policy.minBufferMs) {
            "maxBufferMs must be >= minBufferMs"
        }

        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                policy.minBufferMs,
                policy.maxBufferMs,
                policy.bufferForPlaybackMs,
                policy.bufferForPlaybackAfterRebufferMs
            )
            // 纯音频按缓冲时长判断，避免字节阈值提前打断加载
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    } catch (error: IllegalArgumentException) {
        buildDefaultLoadControlAfterFailure(error)
    } catch (error: AssertionError) {
        buildDefaultLoadControlAfterFailure(error)
    }
}

private fun buildDefaultLoadControlAfterFailure(error: Throwable): LoadControl {
    NPLogger.e(
        "NERI-PlayerManager",
        "Invalid audio LoadControl policy, falling back to Media3 defaults",
        error
    )
    return DefaultLoadControl.Builder().build()
}
