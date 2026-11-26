package moe.ouom.neriplayer.core.player.report

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.runtime.report.PlatformPlayReportEvent
import moe.ouom.neriplayer.core.player.runtime.report.PlatformPlayReportTracker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliPlayHistoryTarget
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "NERI-PlayHistorySync"
private const val BILI_TARGET_CACHE_SIZE = 32
private const val REPORT_QUEUE_CAPACITY = 64

internal interface PlatformPlayHistoryPort {
    fun neteaseSignedIn(): Boolean
    suspend fun biliSignedIn(): Boolean
    suspend fun scrobbleNetease(songId: Long, listenedSec: Long)
    suspend fun resolveBiliTarget(song: SongItem): BiliPlayHistoryTarget?
    suspend fun reportBiliProgress(target: BiliPlayHistoryTarget, playedSec: Long)
}

/**
 * 把播放器状态转成平台收听记录。
 *
 * 上报全部是尽力而为：失败只记日志不重试，不能反过来影响播放；
 * 请求经单个队列串行发出，暂停心跳不会晚于"已看完"到达而把进度写回去。
 */
internal class PlatformPlayHistoryReporter(
    private val scope: CoroutineScope,
    private val port: PlatformPlayHistoryPort,
    private val songKey: (SongItem) -> String,
    private val tracker: PlatformPlayReportTracker = PlatformPlayReportTracker(songKey)
) {
    @Volatile var flags = PlayHistorySyncFlags()

    private val queue = Channel<Pair<String, suspend () -> Unit>>(
        capacity = REPORT_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var lastSong: SongItem? = null
    private var lastPlaying = false
    init {
        scope.launch {
            for ((platform, block) in queue) runReport(platform, block)
        }
    }

    private val biliTargets = object : LinkedHashMap<String, BiliPlayHistoryTarget>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BiliPlayHistoryTarget>?): Boolean =
            size > BILI_TARGET_CACHE_SIZE
    }

    /** 同一时刻只由一个收集协程调用，三个输入按"歌曲 -> 播放状态 -> 进度"的顺序喂给会话 */
    fun onPlayerState(song: SongItem?, playing: Boolean, positionMs: Long) {
        val events = buildList {
            if (song !== lastSong) addAll(tracker.onSongChanged(song))
            lastSong = song
            if (playing != lastPlaying) addAll(tracker.onPlayingChanged(playing))
            lastPlaying = playing
            addAll(tracker.onProgress(positionMs))
        }
        events.forEach(::dispatch)
    }

    private fun dispatch(event: PlatformPlayReportEvent) {
        val currentFlags = flags
        when (val target = platformPlayHistoryTarget(event.song)) {
            is PlatformPlayHistoryTarget.Netease -> if (currentFlags.netease && shouldScrobbleNetease(event)) {
                report("netease") {
                    if (!port.neteaseSignedIn()) return@report
                    port.scrobbleNetease(songId = target.songId, listenedSec = event.listenedMs / 1000L)
                }
            }
            PlatformPlayHistoryTarget.Bili -> if (currentFlags.bili) {
                report("bili") { reportBili(event) }
            }
            null -> Unit
        }
    }

    private suspend fun reportBili(event: PlatformPlayReportEvent) {
        if (!port.biliSignedIn()) return
        val key = songKey(event.song)
        val target = synchronized(biliTargets) { biliTargets[key] }
            ?: port.resolveBiliTarget(event.song)?.also { resolved ->
                synchronized(biliTargets) { biliTargets[key] = resolved }
            }
            ?: return
        port.reportBiliProgress(target = target, playedSec = biliPlayedSeconds(event))
    }

    /** 开关全部关闭后丢弃尚未发出的请求 */
    fun close() {
        queue.close()
    }

    private fun report(platform: String, block: suspend () -> Unit) {
        queue.trySend(platform to block)
    }

    private suspend fun runReport(platform: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "$platform play history report failed: ${error.message.orEmpty()}")
        }
    }
}

/** 播放完毕按 B 站约定上报 -1，下次打开视频不会停在片尾 */
internal fun biliPlayedSeconds(event: PlatformPlayReportEvent): Long =
    if (event is PlatformPlayReportEvent.Finished && event.completed) -1L else event.positionMs / 1000L
