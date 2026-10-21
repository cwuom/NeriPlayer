package moe.ouom.neriplayer.platform.netease.encyclopedia

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaArtistInfo
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaBasicInfo
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaCreditRole
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaCredits
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaMusicInfo
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia
import moe.ouom.neriplayer.data.model.encyclopedia.encyclopediaSongKey
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient

private const val TAG = "SongEncyclopedia"
private const val CREDIT_ROLE_LYRICIST = "作词"
private const val CREDIT_ROLE_COMPOSER = "作曲"
private const val CREDIT_ROLE_ARRANGER = "编曲"
private const val CREDIT_ROLE_PRODUCER = "制作人"
private const val NETEASE_SOURCE_TAG = "Netease"

private val STANDARD_CREDIT_ROLES = setOf(
    CREDIT_ROLE_LYRICIST,
    CREDIT_ROLE_COMPOSER,
    CREDIT_ROLE_ARRANGER,
    CREDIT_ROLE_PRODUCER
)

/**
 * 百科接口的最小接缝。
 *
 * [NeteaseClient] 是 final class，且这两个接口都是 suspend 函数，无法在单测里 mock；
 * 因此仓库只依赖这个可替换的接口，生产环境由 [NeteaseClientEncyclopediaApi] 包装真实客户端。
 */
internal interface NeteaseEncyclopediaApi {
    suspend fun loadSummary(songId: Long): String

    suspend fun loadCreators(songId: Long): String

    suspend fun loadSongDetail(songId: Long): String

    suspend fun loadArtist(artistId: Long): String
}

/** 生产实现：把接缝调用原样转发给真实的网易云客户端。 */
internal class NeteaseClientEncyclopediaApi(
    private val client: NeteaseClient
) : NeteaseEncyclopediaApi {
    override suspend fun loadSummary(songId: Long): String =
        client.getSongEncyclopediaSummaryCancellable(songId)

    override suspend fun loadCreators(songId: Long): String =
        client.getSongCreatorsCancellable(songId)

    override suspend fun loadSongDetail(songId: Long): String =
        client.getSongDetailByIdsCancellable(listOf(songId))

    override suspend fun loadArtist(artistId: Long): String =
        client.getArtistInfoCancellable(artistId)
}

/**
 * 网易云音乐百科数据源。
 *
 * 只使用匿名可访问的 `/api/song/play/about/block/page` 与 `/api/song/creators`；
 * 所有属性都来自接口原文，接口未给出的一律留空，不做任何推断。
 */
class NeteaseSongEncyclopediaRepository internal constructor(
    private val api: NeteaseEncyclopediaApi,
    private val cache: SongEncyclopediaMemoryCache = SongEncyclopediaMemoryCache(),
    private val nowMs: () -> Long = System::currentTimeMillis
) : SongEncyclopediaRepository {
    /** 生产入口：直接包装真实客户端，保持 `NeteaseSongEncyclopediaRepository(neteaseClient)` 可用。 */
    constructor(client: NeteaseClient) : this(NeteaseClientEncyclopediaApi(client))

    override suspend fun getEncyclopedia(song: SongItem): SongEncyclopediaResult {
        val songId = resolveNeteaseSongId(song) ?: return SongEncyclopediaResult.NotSupported
        val songKey = song.encyclopediaSongKey()
        cache.get(songKey)?.let { return SongEncyclopediaResult.Success(it) }
        return withContext(Dispatchers.IO) {
            runCatching { load(song, songKey, songId) }
                .getOrElse { error ->
                    // 取消必须向上传播，否则切歌时的旧请求会污染新歌状态
                    if (error is CancellationException) throw error
                    NPLogger.w(TAG, "加载百科失败: songId=$songId", error)
                    SongEncyclopediaResult.Failed(error)
                }
        }
    }

    private suspend fun load(
        song: SongItem,
        songKey: String,
        songId: Long
    ): SongEncyclopediaResult {
        val summaryRaw = api.loadSummary(songId)
        // 歌曲不存在时接口同样返回 200，只是不带 SONG_PLAY_ABOUT_SONG_BASIC 板块
        val summary = parseNeteaseEncyclopediaSummary(summaryRaw)
            ?: return SongEncyclopediaResult.NotAvailable
        // 相似歌曲与相关歌单与 summary 同源，复用同一份原始响应，不再重复请求
        val similarSongs = parseSafely { parseNeteaseSimilarSongs(summaryRaw) }.orEmpty()
        val relatedPlaylists = parseSafely { parseNeteaseRelatedPlaylists(summaryRaw) }.orEmpty()
        val detail = loadSongDetail(songId)
        val artist = detail?.artistId?.let { loadArtist(it) }
        val data = SongEncyclopedia(
            songKey = songKey,
            source = MusicPlatform.CLOUD_MUSIC,
            basicInfo = EncyclopediaBasicInfo(
                name = song.name,
                artists = song.artist.split("/").map(String::trim).filter(String::isNotEmpty),
                album = detail?.albumName ?: song.encyclopediaAlbumName()
            ),
            musicInfo = EncyclopediaMusicInfo(
                genres = summary.genres,
                bpm = summary.bpm,
                language = summary.language,
                tags = summary.tags
            ),
            credits = loadCredits(songId),
            awards = summary.awards,
            appearances = summary.appearances,
            publishTimeMs = detail?.publishTimeMs,
            albumCompany = detail?.albumCompany,
            albumId = detail?.albumId,
            albumPicUrl = detail?.albumPicUrl,
            artistId = detail?.artistId,
            artist = artist,
            similarSongs = similarSongs,
            relatedPlaylists = relatedPlaylists,
            fetchedAtMs = nowMs()
        )
        cache.put(data)
        return SongEncyclopediaResult.Success(data)
    }

    /**
     * 专辑/发行信息是加分项：整个接口失败只丢这一组字段，绝不影响已到手的曲风/BPM 等主数据。
     */
    private suspend fun loadSongDetail(songId: Long): EncyclopediaSongDetail? {
        val raw = runCatching { api.loadSongDetail(songId) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                NPLogger.w(TAG, "获取歌曲详情失败: songId=$songId", error)
                return null
            }
        return runCatching { parseNeteaseSongDetail(raw) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                NPLogger.w(TAG, "解析歌曲详情失败: songId=$songId", error)
                null
            }
    }

    /** 艺人资料同样是加分项，失败只让模块 5 留空。 */
    private suspend fun loadArtist(artistId: Long): EncyclopediaArtistInfo? {
        val raw = runCatching { api.loadArtist(artistId) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                NPLogger.w(TAG, "获取艺人资料失败: artistId=$artistId", error)
                return null
            }
        return runCatching { parseNeteaseArtistInfo(raw) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                NPLogger.w(TAG, "解析艺人资料失败: artistId=$artistId", error)
                null
            }
    }

    /**
     * 解析环节的兜底：解析器本身已对「无板块」返回空列表，
     * 这里只防御非法 JSON 等意外，失败同样降级为空列表。
     */
    private inline fun <T> parseSafely(parse: () -> T): T? = try {
        parse()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.w(TAG, "解析百科附加板块失败", error)
        null
    }

    /**
     * 创作团队是加分项：单独失败只降级为「没有创作团队」，不能让整页变成错误。
     */
    private suspend fun loadCredits(songId: Long): EncyclopediaCredits? {
        val raw = runCatching { api.loadCreators(songId) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                NPLogger.w(TAG, "获取创作团队失败: songId=$songId", error)
                return null
            }
        val roles = parseNeteaseEncyclopediaCredits(raw)
        if (roles.isEmpty()) return null
        return EncyclopediaCredits(
            lyricists = roles.namesOf(CREDIT_ROLE_LYRICIST),
            composers = roles.namesOf(CREDIT_ROLE_COMPOSER),
            arrangers = roles.namesOf(CREDIT_ROLE_ARRANGER),
            producers = roles.namesOf(CREDIT_ROLE_PRODUCER),
            otherRoles = roles.filterNot { it.role in STANDARD_CREDIT_ROLES }
        )
    }
}

private fun List<EncyclopediaCreditRole>.namesOf(role: String): List<String> =
    firstOrNull { it.role == role }?.names.orEmpty()

/**
 * 判定歌曲是否属于网易云，并取出可用于百科接口的 songId。
 *
 * 与专辑导航使用同一套来源判定，避免对 B 站/YouTube/本地歌曲发起网易云请求。
 */
internal fun resolveNeteaseSongId(song: SongItem): Long? {
    val isNetease = song.channelId.equals("netease", ignoreCase = true) ||
        song.album.startsWith(NETEASE_SOURCE_TAG) ||
        song.mediaUri?.contains("music.163.com", ignoreCase = true) == true
    if (!isNetease) return null
    return song.audioId.toPositiveLongOrNull()
        ?: song.id.takeIf { it > 0L }
        ?: song.matchedSongId.toPositiveLongOrNull()
}

private fun String?.toPositiveLongOrNull(): Long? = this?.toLongOrNull()?.takeIf { it > 0L }

private fun SongItem.encyclopediaAlbumName(): String? {
    val name = if (album.startsWith(NETEASE_SOURCE_TAG)) {
        album.removePrefix(NETEASE_SOURCE_TAG).trim().trimStart('-', ':', '_', '|')
    } else {
        album
    }
    return name.trim().takeIf { it.isNotEmpty() }
}
