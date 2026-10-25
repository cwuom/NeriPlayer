package moe.ouom.neriplayer.platform.bilibili.lyrics

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleCue
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleTrack
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliAvidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliBvidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliCidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.resolveBiliSong

/** B 站 CC 字幕转换出的歌词结果 */
data class BiliSubtitleLyrics(
    val cid: Long? = null,
    val lyrics: List<LyricEntry> = emptyList(),
    val translatedLyrics: List<LyricEntry> = emptyList(),
    val track: BiliSubtitleTrack? = null,
    val translatedTrack: BiliSubtitleTrack? = null,
    /** 首选轨道是否命中；未命中时结果是后备轨道，调用方不应长期缓存 */
    val preferredTrackMatched: Boolean = true,
    /** 翻译轨是否读取完整；读取失败时不应缓存缺翻译的结果 */
    val translationComplete: Boolean = true
)

/** 视频提供的 CC 字幕轨道列表，供播放页的选择面板展示 */
data class BiliSubtitleTracks(
    val tracks: List<BiliSubtitleTrack> = emptyList(),
    val loginRequired: Boolean = false
)

/**
 * 用视频分 P 的 CC 字幕充当歌词。
 *
 * B 站的字幕列表接口依赖登录态，未登录时轨道为空，此时返回空结果交由上层决定回退。
 */
object BiliSubtitleLyricsLoader {
    private const val TAG = "NERI-BiliSubtitleLyrics"

    // AI 字幕地址带 auth_key 签名，缓存时间过长的轨道会在播放中途失效
    private const val TRACK_CACHE_TTL_MS = 10 * 60 * 1000L

    private data class CachedTracks(
        val tracks: List<BiliSubtitleTrack>,
        val fetchedAtMs: Long
    )

    // 同一分 P 的字幕轨道在一次播放期间很少变化，缓存起来避免播放页与歌词加载重复请求
    private val trackCache = androidx.collection.LruCache<String, CachedTracks>(16)

    fun clearCachedTracks() {
        trackCache.evictAll()
    }

    suspend fun load(
        song: SongItem,
        client: BiliClient,
        preferredLanguage: String = "",
        aiSubtitleAllowed: Boolean = true
    ): BiliSubtitleLyrics {
        val avid = song.biliAvidOrNull() ?: return BiliSubtitleLyrics()
        // 未登录时字幕列表为空，提前返回可以省掉整轮元数据请求
        if (!client.hasLoginSession()) return BiliSubtitleLyrics()
        // 旧版收藏/下载的 id 是 avid * 10000 + page，真实 avid 只能通过解析拿到
        val legacyEncoded = song.audioId.isNullOrBlank() && song.id / 10_000L > 0L
        val resolved = if (song.biliCidOrNull() == null || legacyEncoded) {
            resolveBiliSong(song, client)
        } else {
            null
        }
        val resolvedAvid = resolved?.avid ?: avid
        val cid = resolved?.cid ?: song.biliCidOrNull() ?: return BiliSubtitleLyrics()
        val tracks = tracksFor(resolvedAvid, cid, client)
        val referer = resolved?.videoInfo?.bvid
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://www.bilibili.com/video/$it" }
            ?: song.subtitleReferer()
            ?: "https://www.bilibili.com/video/av$resolvedAvid"
        val candidates = biliSubtitleTrackCandidates(
            tracks = tracks,
            preferredLanguage = preferredLanguage,
            aiSubtitleAllowed = aiSubtitleAllowed
        )
        // 逐条轨道尝试，人工字幕读取失败时仍有机会使用后备轨道
        val firstCandidate = candidates.firstOrNull()
        var primary: BiliSubtitleTrack? = null
        var lyrics: List<LyricEntry> = emptyList()
        for (candidate in candidates) {
            val cues = loadSubtitleCues(client, candidate, referer) ?: continue
            val entries = toBiliSubtitleLyricEntries(cues)
            if (entries.isEmpty()) continue
            primary = candidate
            lyrics = entries
            break
        }
        primary ?: run {
            // 全部候选失败时丢弃轨道缓存, 下次重新拉取签名地址
            evictCachedTracks(resolvedAvid, cid)
            return BiliSubtitleLyrics(cid = cid)
        }
        val translatedTrack = selectBiliTranslatedSubtitleTrack(
            primary = primary,
            tracks = tracks,
            aiSubtitleAllowed = aiSubtitleAllowed
        )
        val translatedLyrics = translatedTrack?.let { track ->
            loadTranslatedCues(client, track, referer)
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
            translatedTrack = translatedTrack?.takeIf { translatedLyrics.isNotEmpty() },
            preferredTrackMatched = if (preferredLanguage.isBlank()) {
                primary == firstCandidate
            } else {
                matchesBiliSubtitleLanguage(primary.lan, preferredLanguage)
            },
            translationComplete = translatedTrack == null || translatedLyrics.isNotEmpty()
        )
    }

    suspend fun loadTracksFor(avid: Long, cid: Long, client: BiliClient): List<BiliSubtitleTrack> =
        parseBiliSubtitleTracks(client.getPlayerSubtitlePayload(avid, cid))

    suspend fun loadCuesFor(
        url: String,
        referer: String?,
        client: BiliClient
    ): List<BiliSubtitleCue> = parseBiliSubtitleCues(client.getSubtitlePayload(url, referer))

    /**
     * 读取当前视频的全部 CC 字幕轨道，包含自动生成的字幕。
     */
    suspend fun loadTracks(song: SongItem, client: BiliClient): BiliSubtitleTracks {
        val avid = song.biliAvidOrNull() ?: return BiliSubtitleTracks()
        if (!client.hasLoginSession()) return BiliSubtitleTracks(loginRequired = true)
        val legacyEncoded = song.audioId.isNullOrBlank() && song.id / 10_000L > 0L
        val resolved = if (song.biliCidOrNull() == null || legacyEncoded) {
            resolveBiliSong(song, client)
        } else {
            null
        }
        val resolvedAvid = resolved?.avid ?: avid
        val cid = resolved?.cid ?: song.biliCidOrNull() ?: return BiliSubtitleTracks()
        return BiliSubtitleTracks(tracks = tracksFor(resolvedAvid, cid, client))
    }

    private suspend fun tracksFor(
        avid: Long,
        cid: Long,
        client: BiliClient
    ): List<BiliSubtitleTrack> {
        val key = "$avid:$cid"
        val now = System.currentTimeMillis()
        trackCache.get(key)
            ?.takeIf { now - it.fetchedAtMs < TRACK_CACHE_TTL_MS }
            ?.let { return it.tracks }
        val tracks = parseBiliSubtitleTracks(client.getPlayerSubtitlePayload(avid, cid))
        if (tracks.isNotEmpty()) {
            trackCache.put(key, CachedTracks(tracks, now))
        }
        return tracks
    }

    private fun evictCachedTracks(avid: Long, cid: Long) {
        trackCache.remove("$avid:$cid")
    }

    /**
     * 人工上传的 CC 字幕托管在 `i0.hdslb.com`，防盗链按视频页判断来源，
     * 因此优先使用当前视频的播放页作为 Referer。
     */
    private fun SongItem.subtitleReferer(): String? =
        biliBvidOrNull()?.let { "https://www.bilibili.com/video/$it" }

    private suspend fun loadSubtitleCues(
        client: BiliClient,
        track: BiliSubtitleTrack,
        referer: String?
    ): List<BiliSubtitleCue>? = try {
        parseBiliSubtitleCues(client.getSubtitlePayload(track.url, referer)).takeIf { it.isNotEmpty() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // 单条轨道读取失败时继续尝试后备轨道
        NPLogger.w(TAG, "CC 字幕轨道读取失败: lan=${track.lan}, ${error.message}")
        null
    }

    private suspend fun loadTranslatedCues(
        client: BiliClient,
        track: BiliSubtitleTrack,
        referer: String?
    ): List<LyricEntry> = try {
        toBiliSubtitleLyricEntries(parseBiliSubtitleCues(client.getSubtitlePayload(track.url, referer)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // 翻译轨失败时保留原文轨，字幕文件按轨道单独下发
        NPLogger.w(TAG, "CC 翻译字幕读取失败: lan=${track.lan}, ${error.message}")
        emptyList()
    }
}
