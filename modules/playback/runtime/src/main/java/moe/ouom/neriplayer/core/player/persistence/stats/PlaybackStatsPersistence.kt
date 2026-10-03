package moe.ouom.neriplayer.core.player.persistence.stats

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.playback.AppPlaybackStatsWritePort
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsPendingStore
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot

internal object AppPlaybackStatsPendingWrites {
    private val lock = Any()
    private var application: Context? = null
    private var openedStore: FilePlaybackStatsPendingStore? = null
    private val store = object : PlaybackStatsPendingStore {
        override fun append(snapshot: PlaybackStatsSnapshot) = delegate().append(snapshot)
        override fun first(): PlaybackStatsSnapshot? = delegate().first()
        override fun acknowledge(eventId: String) = delegate().acknowledge(eventId)
    }
    // 播放器重建或释放自己的 scope 时不能取消共享的磁盘重放
    val queue = PlaybackStatsPendingWrites(store, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    fun bind(context: Context) = synchronized(lock) {
        val resolved = context.applicationContext ?: context
        check(openedStore == null || application === resolved) { "An open playback journal cannot switch application contexts" }
        application = resolved
        AppPlaybackStatsWritePort.bind(requireNotNull(application))
    }

    private fun delegate(): FilePlaybackStatsPendingStore = synchronized(lock) {
        openedStore ?: FilePlaybackStatsPendingStore(File(
            (application ?: throw IOException("Playback statistics journal has no application context")).filesDir,
            "playback_stats_pending_v1"
        )).also { openedStore = it }
    }
}

suspend fun flushPlaybackStatsPendingWrites(context: Context) = withContext(Dispatchers.IO) {
    AppPlaybackStatsPendingWrites.bind(context)
    AppPlaybackStatsPendingWrites.queue.activate(AppPlaybackStatsWritePort)
    AppPlaybackStatsPendingWrites.queue.flush(AppPlaybackStatsWritePort)
}

suspend fun withPlaybackStatsRestore(context: Context, block: suspend () -> Unit) = withContext(Dispatchers.IO) {
    AppPlaybackStatsPendingWrites.bind(context)
    AppPlaybackStatsPendingWrites.queue.withStatisticsRestore(AppPlaybackStatsWritePort, block)
}
