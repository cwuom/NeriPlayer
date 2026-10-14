@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import moe.ouom.neriplayer.common.logging.NPLogger

internal data class AudioLoadControlPolicy(
    val minBufferMs: Int = 15_000,
    /**
     * 缓冲降到下限才重新加载，一直加载到上限再停；上下限差得越大，
     * 两轮加载之间网卡和存储空闲越久，蜂窝尾流与 Wi-Fi 唤醒次数越少
     *
     * 字节上限仍是 Media3 的音频默认值，高码率无损会先触到字节上限
     */
    val maxBufferMs: Int = 120_000,
    val bufferForPlaybackMs: Int = 1_000,
    val bufferForPlaybackAfterRebufferMs: Int = 3_000,
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
        NPLogger.e(
            "NERI-PlayerManager",
            "Invalid audio LoadControl policy, falling back to Media3 defaults",
            error
        )
        DefaultLoadControl.Builder().build()
    }
}
