package moe.ouom.neriplayer.core.player.report

import moe.ouom.neriplayer.core.player.runtime.report.PlatformPlayReportEvent
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.platform.comments.resolveCommentSource

private const val NETEASE_SCROBBLE_MIN_LISTEN_MS = 30_000L

internal data class PlayHistorySyncFlags(
    val netease: Boolean = false,
    val bili: Boolean = false
) {
    val any: Boolean get() = netease || bili
}

internal sealed interface PlatformPlayHistoryTarget {
    data class Netease(val songId: Long) : PlatformPlayHistoryTarget
    /** B 站歌曲可能带历史打包 id，真实 aid/cid 交给播放侧同一套解析逻辑确认 */
    data object Bili : PlatformPlayHistoryTarget
}

/**
 * 复用评论来源的平台判定：网易云歌曲即使音频回退到其他来源，也只记到网易云这首歌上。
 */
internal fun platformPlayHistoryTarget(song: SongItem): PlatformPlayHistoryTarget? {
    val source = resolveCommentSource(song) ?: return null
    return when (source.platform) {
        CommentPlatform.NETEASE -> PlatformPlayHistoryTarget.Netease(source.resourceId)
        CommentPlatform.BILIBILI -> PlatformPlayHistoryTarget.Bili
    }
}

/**
 * 网易云只认一次像样的收听：至少听 30 秒；短歌播完且听过一半也算，
 * 来回切歌产生的几秒试听不上报，避免污染听歌排行。
 */
internal fun shouldScrobbleNetease(event: PlatformPlayReportEvent): Boolean {
    if (event !is PlatformPlayReportEvent.Finished) return false
    if (event.listenedMs >= NETEASE_SCROBBLE_MIN_LISTEN_MS) return true
    val durationMs = event.song.durationMs
    return event.completed && durationMs > 0L && event.listenedMs * 2 >= durationMs
}
