package moe.ouom.neriplayer.ui.viewmodel.tab.bili

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankPeriod
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankSong
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankType
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo

/** 榜单快照：期数与曲目必须来自同一期，避免切换榜单时串期 */
data class BiliMusicRankSnapshot(
    val type: BiliMusicRankType,
    val period: BiliMusicRankPeriod,
    val songs: List<BiliMusicRankSong>,
    val fetchedAtMs: Long
)

/**
 * 首页 B 站音乐模块的内存缓存（不落库、不新增表）。
 *
 * 缓存按榜单类型隔离：热门榜与原创榜各存一份，热门视频榜单独一份。
 */
object BiliMusicHomeRepository {

    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    /** 官方 MV 补全的并发上限：每批同时发起的 view 请求数 */
    private const val MV_ENRICH_PARALLELISM = 4

    /** 热门视频榜的缓存快照，与榜单缓存分开存放，便于各自独立失效 */
    private data class HotSnapshot(
        val videos: List<BiliMusicHotVideo>,
        val fetchedAtMs: Long
    )

    private val rankCache = ConcurrentHashMap<BiliMusicRankType, BiliMusicRankSnapshot>()

    @Volatile
    private var hotCache: HotSnapshot? = null

    private val client get() = AppContainer.biliClient

    /**
     * 命中且未过期时返回榜单缓存，否则返回 null（由调用方决定是否发起请求）。
     *
     * @param nowMs 便于测试注入的当前时间，默认取系统时间
     */
    fun cachedRank(
        type: BiliMusicRankType,
        nowMs: Long = System.currentTimeMillis()
    ): BiliMusicRankSnapshot? {
        val snapshot = rankCache[type] ?: return null
        return if (nowMs - snapshot.fetchedAtMs <= CACHE_TTL_MS) snapshot else null
    }

    /**
     * 命中且未过期时返回热门视频缓存，否则返回 null。
     *
     * @param nowMs 便于测试注入的当前时间，默认取系统时间
     */
    fun cachedHot(nowMs: Long = System.currentTimeMillis()): List<BiliMusicHotVideo>? {
        val snapshot = hotCache ?: return null
        return if (nowMs - snapshot.fetchedAtMs <= CACHE_TTL_MS) snapshot.videos else null
    }

    /**
     * 拉取指定榜单类型的最新一期（期数不写死：按期数发布时间与 ID 取最新）。
     */
    suspend fun loadRank(type: BiliMusicRankType, force: Boolean): BiliMusicRankSnapshot {
        if (!force) {
            cachedRank(type)?.let { return it }
        }
        val periods = client.getCopyrightMusicRankPeriods(type.listType)
        val latest = periods.maxWithOrNull(
            compareBy<BiliMusicRankPeriod> { it.publishTimeSec }.thenBy { it.id }
        ) ?: throw IOException("Bilibili music rank has no period, listType=${type.listType}")
        val songs = resolveOfficialMvIds(client.getCopyrightMusicRankSongs(latest.id))
        val snapshot = BiliMusicRankSnapshot(
            type = type,
            period = latest,
            songs = songs,
            fetchedAtMs = System.currentTimeMillis()
        )
        if (songs.isNotEmpty()) {
            rankCache[type] = snapshot
        }
        return snapshot
    }

    /**
     * 拉取音乐分区热门视频榜。
     *
     * 数据源为移动端分区榜（Web 端 `ranking/v2?rid=3` 已停止更新），
     * 接口只返回 avid，bvid 由 [moe.ouom.neriplayer.platform.bilibili.api.client.BiliVideoIdCodec] 本地换算。
     */
    suspend fun loadHot(force: Boolean): List<BiliMusicHotVideo> {
        if (!force) {
            cachedHot()?.let { return it }
        }
        val videos = client.getMusicHotRankVideos()
        if (videos.isNotEmpty()) {
            hotCache = HotSnapshot(videos = videos, fetchedAtMs = System.currentTimeMillis())
        }
        return videos
    }

    /**
     * 按 mv_bvid 补全官方 MV 的 aid（顺带取回 MV 真实时长、上传账号与封面）。
     *
     * 榜单可能已有 mv_aid，但官方 MV 的时长、账号与封面仍需单独补全
     * 单个曲目补全失败时保留已有身份，缺少有效身份才回退到授权投稿
     *
     * 补全需要逐条调用 WBI view 接口，因此按 [MV_ENRICH_PARALLELISM] 分块串行推进：
     * 与 [moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient] 的其它分页批处理保持一致，
     * 避免一次性打出几十个并发请求触发 B 站风控。
     *
     * 另外，一旦某一批里所有待补全曲目都失败（典型是风控 412 或网络超时），说明后续请求大概率也会失败，
     * 此时立刻停止并把剩余曲目原样返回：用户马上就能看到榜单（回退到榜单授权投稿），
     * 而不是等所有批次依次超时后仍然一无所获。
     */
    internal suspend fun resolveOfficialMvIds(
        songs: List<BiliMusicRankSong>,
        loadVideo: suspend (String) -> VideoBasicInfo = { client.getVideoBasicInfoByBvid(it) }
    ): List<BiliMusicRankSong> {
        if (songs.none { it.needsMvEnrichment }) return songs
        val resolved = ArrayList<BiliMusicRankSong>(songs.size)
        var cursor = 0
        while (cursor < songs.size) {
            val chunkEnd = minOf(cursor + MV_ENRICH_PARALLELISM, songs.size)
            val chunk = songs.subList(cursor, chunkEnd)
            val outcomes = coroutineScope {
                chunk.map { song -> async { song to enrichOfficialMv(song, loadVideo) } }.awaitAll()
            }
            var attempted = 0
            var enriched = 0
            outcomes.forEach { (song, enrichedSong) ->
                resolved += enrichedSong ?: song
                if (song.needsMvEnrichment) {
                    attempted++
                    if (enrichedSong != null) enriched++
                }
            }
            if (attempted > 0 && enriched == 0) {
                resolved += songs.subList(chunkEnd, songs.size)
                break
            }
            cursor = chunkEnd
        }
        return resolved
    }

    /** 已有 aid 仍可能缺少官方 MV 的元信息，不能借用授权投稿的数据 */
    private val BiliMusicRankSong.needsMvEnrichment: Boolean
        get() = mvBvid.isNotBlank() &&
            (mvAid <= 0L || mvDurationSec <= 0 || mvUploader.isBlank() || mvCoverUrl.isBlank())

    /** 返回成功取得的 MV 数据，让批次停发只依据请求失败判断 */
    private suspend fun enrichOfficialMv(
        song: BiliMusicRankSong,
        loadVideo: suspend (String) -> VideoBasicInfo
    ): BiliMusicRankSong? {
        if (!song.needsMvEnrichment) return null
        val info = runCatching { loadVideo(song.mvBvid) }.getOrElse { error ->
            if (error is CancellationException) throw error
            return null
        }
        return if (info.aid > 0L) {
            song.copy(
                mvAid = info.aid,
                mvDurationSec = info.durationSec,
                mvUploader = info.ownerName,
                mvCoverUrl = normalizeVideoCover(info.coverUrl)
            )
        } else {
            null
        }
    }

    /** B 站接口偶尔返回 http 图片地址，统一提升为 https，避免明文图片被拦截 */
    private fun normalizeVideoCover(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        return if (trimmed.startsWith("http://")) {
            "https://" + trimmed.removePrefix("http://")
        } else {
            trimmed
        }
    }
}
