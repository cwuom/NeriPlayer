package moe.ouom.neriplayer.platform.bilibili.lyrics

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleTrack
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.api.parser.selectBiliSubtitleTrack
import moe.ouom.neriplayer.platform.bilibili.api.parser.selectBiliTranslatedSubtitleTrack
import moe.ouom.neriplayer.platform.bilibili.api.parser.toBiliSubtitleLyricEntries
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliAvidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliCidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.resolveBiliSong

/** B 站 CC 字幕转换出的歌词结果 */
data class BiliSubtitleLyrics(
    val cid: Long? = null,
    val lyrics: List<LyricEntry> = emptyList(),
    val translatedLyrics: List<LyricEntry> = emptyList(),
    val track: BiliSubtitleTrack? = null,
    val translatedTrack: BiliSubtitleTrack? = null
)

/**
 * 用视频分 P 的 CC 字幕充当歌词。
 *
 * B 站的字幕列表接口依赖登录态，未登录时轨道为空，此时返回空结果交由上层决定回退。
 */
object BiliSubtitleLyricsLoader {
    private const val TAG = "NERI-BiliSubtitleLyrics"

    suspend fun load(song: SongItem, client: BiliClient): BiliSubtitleLyrics {
        val avid = song.biliAvidOrNull() ?: return BiliSubtitleLyrics()
        // 未登录时字幕列表为空，提前返回可以省掉整轮元数据请求
        if (!client.hasLoginSession()) return BiliSubtitleLyrics()
        val cid = song.biliCidOrNull() ?: resolveBiliCid(song, client) ?: return BiliSubtitleLyrics()
        val tracks = client.getVideoSubtitleTracks(avid, cid)
        val primary = selectBiliSubtitleTrack(tracks) ?: return BiliSubtitleLyrics(cid = cid)
        val lyrics = toBiliSubtitleLyricEntries(client.getSubtitleCues(primary.url))
        if (lyrics.isEmpty()) return BiliSubtitleLyrics(cid = cid, track = primary)
        val translatedTrack = selectBiliTranslatedSubtitleTrack(primary, tracks)
        val translatedLyrics = translatedTrack?.let { track ->
            loadTranslatedCues(client, track)
        }.orEmpty()
        NPLogger.d(
            TAG,
            "CC 字幕歌词已加载: aid=$avid, cid=$cid, lan=${primary.lan}, " +
                "lines=${lyrics.size}, translated=${translatedLyrics.size}"
        )
        return BiliSubtitleLyrics(
            cid = cid,
            lyrics = lyrics,
            translatedLyrics = translatedLyrics,
            track = primary,
            translatedTrack = translatedTrack?.takeIf { translatedLyrics.isNotEmpty() }
        )
    }

    private suspend fun resolveBiliCid(song: SongItem, client: BiliClient): Long? =
        resolveBiliSong(song, client)?.cid?.takeIf { it > 0L }

    private suspend fun loadTranslatedCues(
        client: BiliClient,
        track: BiliSubtitleTrack
    ): List<LyricEntry> = try {
        toBiliSubtitleLyricEntries(client.getSubtitleCues(track.url))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // 翻译轨失败时保留原文轨，字幕文件按轨道单独下发
        NPLogger.w(TAG, "CC 翻译字幕读取失败: lan=${track.lan}, ${error.message}")
        emptyList()
    }
}
