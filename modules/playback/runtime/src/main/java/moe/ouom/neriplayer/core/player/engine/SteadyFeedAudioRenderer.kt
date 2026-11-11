@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine

import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import kotlin.math.min

/**
 * 动态调度会让播放线程按音频缓冲余量休眠，PCM 会成批送达
 * 可视化和高采样率系统输出需要更频繁地补数，调度间隔最多 10 ms
 */
internal class SteadyFeedAudioRenderer(
    renderer: Renderer,
    private val steadyFeedRequired: () -> Boolean,
) : ForwardingRenderer(renderer) {
    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        steadyFeedDurationToProgressUs(
            rendererDurationUs = super.getDurationToProgressUs(positionUs, elapsedRealtimeUs),
            steadyFeedRequired = steadyFeedRequired(),
        )
}

internal fun steadyFeedDurationToProgressUs(rendererDurationUs: Long, steadyFeedRequired: Boolean): Long =
    if (steadyFeedRequired) min(rendererDurationUs, Renderer.DEFAULT_DURATION_TO_PROGRESS_US) else rendererDurationUs
