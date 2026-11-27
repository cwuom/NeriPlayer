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
 * 每个平台各用一条计时线，某个平台关闭期间的收听不会在它开启后补报。
 */
internal class PlatformPlayHistoryReporter(
    private val scope: CoroutineScope,
    private val port: PlatformPlayHistoryPort,
    private val songKey: (SongItem) -> String,
    private val newTracker: () -> PlatformPlayReportTracker = { PlatformPlayReportTracker(songKey) }
) {
    @Volatile private var flags = PlayHistorySyncFlags()
    private var neteaseLane = Lane()
    private var biliLane = Lane()

    private val queue = Channel<Pair<PlayHistoryPlatform, suspend () -> Unit>>(
        capacity = REPORT_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 只取消自己的消费者，播放器共享的 scope 不受影响
    private val consumer = scope.launch {
        for ((platform, block) in queue) runReport(platform, block)
    }

    private val biliTargets = object : LinkedHashMap<String, BiliPlayHistoryTarget>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BiliPlayHistoryTarget>?): Boolean =
            size > BILI_TARGET_CACHE_SIZE
    }

    /** 平台从关闭切到开启时重建它的计时线，开启前已经听过的时长不计入 */
    fun updateFlags(newFlags: PlayHistorySyncFlags) = synchronized(this) {
        if (newFlags.netease && !flags.netease) neteaseLane = Lane()
        if (newFlags.bili && !flags.bili) biliLane = Lane()
        flags = newFlags
    }

    /** 同一时刻只由一个收集协程调用；开关的更新可能来自另一个协程，所以和计时线一起加锁 */
    fun onPlayerState(song: SongItem?, playing: Boolean, positionMs: Long) {
        val (neteaseEvents, biliEvents) = synchronized(this) {
            val current = flags
            val netease = if (current.netease) neteaseLane.feed(song, playing, positionMs) else emptyList()
            val bili = if (current.bili) biliLane.feed(song, playing, positionMs) else emptyList()
            netease to bili
        }
        // 每条计时线只处理自己平台的歌曲，两个平台都开启时同一首歌不会重复上报
        neteaseEvents.forEach(::dispatchNetease)
        biliEvents.forEach(::dispatchBili)
    }

    private fun dispatchNetease(event: PlatformPlayReportEvent) {
        val target = platformPlayHistoryTarget(event.song) as? PlatformPlayHistoryTarget.Netease ?: return
        if (!shouldScrobbleNetease(event)) return
        report(PlayHistoryPlatform.NETEASE) {
            if (!port.neteaseSignedIn()) return@report
            port.scrobbleNetease(songId = target.songId, listenedSec = event.listenedMs / 1000L)
        }
    }

    private fun dispatchBili(event: PlatformPlayReportEvent) {
        if (platformPlayHistoryTarget(event.song) != PlatformPlayHistoryTarget.Bili) return
        report(PlayHistoryPlatform.BILI) { reportBili(event) }
    }

    private suspend fun reportBili(event: PlatformPlayReportEvent) {
        if (!port.biliSignedIn()) return
        val key = songKey(event.song)
        val target = synchronized(biliTargets) { biliTargets[key] }
            ?: port.resolveBiliTarget(event.song)?.also { resolved ->
                synchronized(biliTargets) { biliTargets[key] = resolved }
            }
            ?: return
        // 目标解析可能要等网络，期间用户关闭了 B 站同步就不再发送
        if (!flags.bili) return
        port.reportBiliProgress(target = target, playedSec = biliPlayedSeconds(event))
    }

    /** 开关全部关闭时调用：正在等待的请求和排队中的请求都直接放弃 */
    fun close() {
        updateFlags(PlayHistorySyncFlags())
        queue.cancel()
        consumer.cancel()
    }

    private fun report(platform: PlayHistoryPlatform, block: suspend () -> Unit) {
        queue.trySend(platform to block)
    }

    private suspend fun runReport(platform: PlayHistoryPlatform, block: suspend () -> Unit) {
        // 排队期间用户可能已经关闭该平台，发送前按当前开关再确认一次
        if (!platform.enabledIn(flags)) return
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "${platform.logName} play history report failed: ${error.message.orEmpty()}")
        }
    }

    private inner class Lane {
        private val tracker = newTracker()
        private var lastSongKey: String? = null
        private var lastPlaying = false
        private var started = false

        fun feed(song: SongItem?, playing: Boolean, positionMs: Long): List<PlatformPlayReportEvent> = buildList {
            val key = song?.let(songKey)
            // 元数据刷新会换成新对象，按身份判断是否真的切歌
            val songChanged = !started || key != lastSongKey
            addAll(tracker.onSongChanged(song))
            lastSongKey = key
            if (playing != lastPlaying) addAll(tracker.onPlayingChanged(playing))
            lastPlaying = playing
            started = true
            // 切歌时新歌会先和上一首的进度一起到达，这个进度不能作为新会话的回绕基准
            if (!songChanged) addAll(tracker.onProgress(positionMs))
        }
    }
}

private enum class PlayHistoryPlatform(val logName: String) {
    NETEASE("netease"),
    BILI("bili");

    fun enabledIn(flags: PlayHistorySyncFlags): Boolean = when (this) {
        NETEASE -> flags.netease
        BILI -> flags.bili
    }
}

/** 播放完毕按 B 站约定上报 -1，下次打开视频不会停在片尾 */
internal fun biliPlayedSeconds(event: PlatformPlayReportEvent): Long =
    if (event is PlatformPlayReportEvent.Finished && event.completed) -1L else event.positionMs / 1000L
