@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine

import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import kotlin.math.min

/**
 * 动态调度会让播放线程按音频缓冲余量休眠，省电但 PCM 会成批送达；
 * 可视化需要连续的数据，开启时退回固定 10 ms 节奏
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
