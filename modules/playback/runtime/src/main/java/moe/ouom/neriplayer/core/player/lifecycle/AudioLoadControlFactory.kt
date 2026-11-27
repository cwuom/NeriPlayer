@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.lanltw.core.LanSchedule

internal data class AudioLoadControlPolicy(
    val localMinBufferMs: Int = 15_000,
    // 网络请求恢复供数可能需要几秒，提早开始下一轮加载
    val streamingMinBufferMs: Int = 30_000,
    /**
     * 超过低水位后，时长上限和 Media3 的音频字节目标都能结束本轮加载，
     * 让网络和存储在两轮读取之间保持空闲
     */
    val maxBufferMs: Int = 300_000,
    val bufferForPlaybackMs: Int = 1_000,
    val localBufferForPlaybackAfterRebufferMs: Int = 3_000,
    val streamingBufferForPlaybackAfterRebufferMs: Int = 5_000,
    /**
     * 往回拖进度条时留着已经下过的那段
     *
     * 默认是 0, 意味着播过的音频立刻丢掉, 往回拖一点点都要重新联网拉一遍;
     * 音频码率低, 留一分钟也就一两 MB, 换来的是回拖直接从内存续上
     */
    val backBufferMs: Int = 60_000
)

internal fun buildLanAudioLoadControl(): LoadControl = buildAudioLoadControl(
    AudioLoadControlPolicy(
        maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
        bufferForPlaybackMs = LanSchedule.START_BUFFER_MS,
        backBufferMs = 0
    )
)

internal fun buildAudioLoadControl(
    policy: AudioLoadControlPolicy = AudioLoadControlPolicy()
): LoadControl {
    return try {
        DefaultLoadControl.Builder()
            .setBufferDurationsMsForLocalPlayback(
                policy.localMinBufferMs,
                policy.maxBufferMs,
                policy.bufferForPlaybackMs,
                policy.localBufferForPlaybackAfterRebufferMs
            )
            .setBufferDurationsMsForStreaming(
                policy.streamingMinBufferMs,
                policy.maxBufferMs,
                policy.bufferForPlaybackMs,
                policy.streamingBufferForPlaybackAfterRebufferMs
            )
            // 纯音频按缓冲时长判断，避免字节阈值提前打断加载
            .setPrioritizeTimeOverSizeThresholds(true)
            // 音频每帧都能独立解码, 不必从关键帧起留;
            // 回退缓冲越界只当成不留, 不该连带把调好的启播时延打回默认值
            .setBackBuffer(policy.backBufferMs.coerceAtLeast(0), false)
            .build()
    } catch (error: IllegalArgumentException) {
        NPLogger.e(
            "NERI-PlayerManager",
            "Invalid audio LoadControl policy, falling back to Media3 defaults",
            error
        )
        DefaultLoadControl.Builder().build()
    }
}
