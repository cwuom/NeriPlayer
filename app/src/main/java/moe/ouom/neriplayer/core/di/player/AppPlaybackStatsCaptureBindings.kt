package moe.ouom.neriplayer.core.di.player

import android.content.Context
import moe.ouom.neriplayer.core.player.persistence.stats.flushPlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.player.persistence.stats.withPlaybackStatsRestore
import moe.ouom.neriplayer.data.stats.PlaybackStatsCaptureBarrier

internal fun installPlaybackStatsCaptureBarrier(
    runningInMainProcess: Boolean,
    restore: suspend (Context, suspend () -> Unit) -> Unit = ::withPlaybackStatsRestore,
    flush: suspend (Context) -> Unit = ::flushPlaybackStatsPendingWrites
) {
    if (!runningInMainProcess) return
    PlaybackStatsCaptureBarrier.install(
        flush = { context -> flush(context.applicationContext) },
        restore = { context, block -> restore(context.applicationContext, block) }
    )
}
