package moe.ouom.neriplayer.platform.bilibili.history

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliPlayHistoryTarget
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliBvidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliCidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.resolveBiliSong

/**
 * 新版歌曲同时保存了 aid 与 cid，可以直接上报；
 * 旧数据的 id 可能是打包键，必须走播放侧同一套解析，避免把历史记到别的视频上。
 */
suspend fun resolveBiliPlayHistoryTarget(song: SongItem, client: BiliClient): BiliPlayHistoryTarget? {
    directBiliPlayHistoryTarget(song)?.let { return it }
    val resolved = resolveBiliSong(song, client) ?: return null
    if (resolved.avid <= 0L || resolved.cid <= 0L) return null
    return BiliPlayHistoryTarget(
        aid = resolved.avid,
        cid = resolved.cid,
        bvid = resolved.videoInfo.bvid.takeIf { it.isNotBlank() }
    )
}

internal fun directBiliPlayHistoryTarget(song: SongItem): BiliPlayHistoryTarget? {
    if (!song.channelId.equals("bilibili", ignoreCase = true)) return null
    val aid = song.audioId?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val cid = song.biliCidOrNull() ?: return null
    return BiliPlayHistoryTarget(aid = aid, cid = cid, bvid = song.biliBvidOrNull())
}
