@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import moe.ouom.neriplayer.core.logging.NPLogger

internal data class AudioLoadControlPolicy(
    val minBufferMs: Int = 15_000,
    val maxBufferMs: Int = 30_000,
    val bufferForPlaybackMs: Int = 800,
    val bufferForPlaybackAfterRebufferMs: Int = 1_500,
    /**
     * 往回拖进度条时留着已经下过的那段
     *
     * 默认是 0, 意味着播过的音频立刻丢掉, 往回拖一点点都要重新联网拉一遍;
     * 音频码率低, 留一分钟也就一两 MB, 换来的是回拖直接从内存续上
     */
    val backBufferMs: Int = 60_000
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
            // 音频每帧都能独立解码, 不必从关键帧起留;
            // 回退缓冲越界只当成不留, 不该连带把调好的启播时延打回默认值
            .setBackBuffer(policy.backBufferMs.coerceAtLeast(0), false)
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
