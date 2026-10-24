package moe.ouom.neriplayer.platform.netease.playmode

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.json.JSONObject

private const val TAG = "NeteasePlaymode"

/** 同一首歌 30 秒内重复触发心动模式时复用上一次结果，避免连点打爆接口。 */
private const val CACHE_TTL_MS = 30_000L

/** 缓存只服务于连续切歌的短窗口，超过这个条数按插入顺序淘汰最旧的。 */
private const val MAX_CACHE_ENTRIES = 8

/**
 * 心动模式数据层。
 *
 * 只负责「拿到下一首推荐歌曲」，不含任何播放器/队列逻辑。
 * 未登录（code=301）、无红心歌单、接口异常等所有情况一律返回 null，
 * 由调用方静默降级到本地推荐，绝不把错误抛给 UI。
 */
class NeteasePlaymodeIntelligenceRepository internal constructor(
    private val api: NeteasePlaymodeIntelligenceApi,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** 生产入口：直接包装真实客户端。 */
    constructor(client: NeteaseClient) : this(NeteaseClientPlaymodeIntelligenceApi(client))

    private data class CacheEntry(val song: SongItem, val fetchedAtMs: Long)

    private val cacheLock = Any()
    private val cache = LinkedHashMap<Long, CacheEntry>()

    /** userId → 红心歌单 id，见 [resolveLikedPlaylistId]。 */
    private val likedPlaylistLock = Any()
    private var likedPlaylistCache: Pair<Long, Long>? = null

    /** 未登录时上层可先判断，避免无谓请求。 */
    fun isLoggedIn(): Boolean = api.hasLogin()

    /**
     * [isLoggedIn] 的别名：上层按「官方通道是否可用」语义调用时用这个名字。
     *
     * 只有登录态（MUSIC_U）就绪才可能拿到红心歌单 id，因此二者等价。
     */
    fun isOfficialAvailable(): Boolean = api.hasLogin()

    /**
     * 返回 null 表示「本次无法给出官方推荐」，调用方静默降级。
     *
     * 不会抛异常（取消除外），也不会把接口错误暴露给用户。
     *
     * [bypassCache] 供「命中不喜欢名单后重试」使用：第一次拿到的结果刚被写进 30 秒缓存，
     * 不绕过缓存的话后面几次「重试」都是原地读缓存，等于没重试。
     */
    suspend fun fetchNextSong(currentSongId: Long, bypassCache: Boolean = false): SongItem? {
        if (currentSongId <= 0L) return null
        if (!bypassCache) cachedSong(currentSongId)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { load(currentSongId) }
                .getOrElse { error ->
                    // 取消必须向上传播，否则切歌时的旧请求会污染新歌状态
                    if (error is CancellationException) throw error
                    NPLogger.w(TAG, "获取心动模式推荐失败: songId=$currentSongId", error)
                    null
                }
        }
    }

    /** [fetchNextSong] 的别名：与 [isOfficialAvailable] 配对，供上层统一命名使用。 */
    suspend fun nextSong(songId: Long): SongItem? = fetchNextSong(songId)

    private suspend fun load(currentSongId: Long): SongItem? {
        val userId = api.currentUserId()
        if (userId <= 0L) {
            // 未登录属于预期情况：静默降级，不打错误日志
            NPLogger.d(TAG, "心动模式跳过: 未登录")
            return null
        }

        val playlistId = resolveLikedPlaylistId(userId)
        if (playlistId == null || playlistId <= 0L) {
            // 没有真实红心歌单 id 时不能用 0 占位去请求，直接降级
            NPLogger.w(TAG, "心动模式跳过: 未找到红心歌单 userId=$userId")
            return null
        }

        val raw = try {
            api.loadIntelligence(
                songId = currentSongId,
                playlistId = playlistId,
                startMusicId = currentSongId,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            NPLogger.w(TAG, "心动模式请求失败: songId=$currentSongId", io)
            return null
        }

        return when (val result = PlaymodeIntelligenceResponseParser.parse(raw)) {
            is PlaymodeIntelligenceResult.Success -> {
                putCache(currentSongId, result.song)
                result.song
            }
            PlaymodeIntelligenceResult.RequiresLogin -> {
                NPLogger.d(TAG, "心动模式不可用: 未登录 (code=301)")
                null
            }
            is PlaymodeIntelligenceResult.Failure -> {
                NPLogger.w(
                    TAG,
                    "心动模式失败: songId=$currentSongId code=${result.code} message=${result.message}"
                )
                // 诊断：2026-10-19 真机实测发现 data[] 元素是包装对象（歌曲字段在 songInfo 里），
                // 已由 PlaymodeIntelligenceResponseParser 兼容。这里再保留一次原始响应：
                // 若将来字段再次改名，日志能直接给出新形状，不需要重新抓包。
                // 只取前 800 字符，避免 logcat 单条上限截断关键信息。
                NPLogger.d(TAG, "心动模式原始响应(Failure): ${raw.take(800)}")
                null
            }
            // 穷尽性由编译器保证；不要依赖编译器自动补的 NoWhenBranchMatchedException 兜底分支，
            // 域依赖白名单不允许本包引用 kotlin.NoWhenBranchMatchedException。
            else -> null
        }
    }

    private fun cachedSong(songId: Long): SongItem? {
        synchronized(cacheLock) {
            val entry = cache[songId] ?: return null
            if (nowMs() - entry.fetchedAtMs > CACHE_TTL_MS) {
                cache.remove(songId)
                return null
            }
            return entry.song
        }
    }

    private fun putCache(songId: Long, song: SongItem) {
        synchronized(cacheLock) {
            cache[songId] = CacheEntry(song = song, fetchedAtMs = nowMs())
            while (cache.size > MAX_CACHE_ENTRIES) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
    }

    /**
     * 红心歌单 id 在同一个账号登录期间不会变，按 userId 记住它。
     *
     * 取它要先把「我创建和收藏的歌单」整页拉下来（生产实现是
     * `getUserPlaylists(limit = 1000)`），而心动模式每切一首歌都会走到这里。
     * 不缓存的话，每首歌都要重复两次「当前账号 + 红心歌单」请求，
     * 既慢又多打一次大响应。账号变了（userId 不同）自然命中不了，不会串号。
     */
    private suspend fun resolveLikedPlaylistId(userId: Long): Long? {
        synchronized(likedPlaylistLock) {
            likedPlaylistCache?.takeIf { it.first == userId }?.let { return it.second }
        }
        val resolved = api.likedPlaylistId(userId)
        if (resolved != null && resolved > 0L) {
            synchronized(likedPlaylistLock) { likedPlaylistCache = userId to resolved }
        }
        return resolved
    }
}

/**
 * 心动模式数据层的测试接缝。
 *
 * [NeteaseClient] 是 final class 且方法为阻塞调用，直接 mock 代价高，
 * 因此把仓库真正需要的四个动作抽成 interface，单测可用假实现替换。
 */
internal interface NeteasePlaymodeIntelligenceApi {

    /** 是否持有可用的登录态（MUSIC_U）。 */
    fun hasLogin(): Boolean

    /** 当前登录用户 id；未登录返回 0，不抛异常。 */
    fun currentUserId(): Long

    /** 「我喜欢的音乐」歌单 id；取不到返回 null。 */
    fun likedPlaylistId(userId: Long): Long?

    /** 调用 `/eapi/playmode/intelligence/list`（eapi 加密），返回原始 JSON。 */
    suspend fun loadIntelligence(songId: Long, playlistId: Long, startMusicId: Long): String
}

/**
 * 生产实现：全部转发给 [NeteaseClient]。
 *
 * 这里刻意不缓存、不做业务判断，只负责把客户端返回的原始结构转成仓库需要的形状。
 */
internal class NeteaseClientPlaymodeIntelligenceApi(
    private val client: NeteaseClient,
) : NeteasePlaymodeIntelligenceApi {

    override fun hasLogin(): Boolean = client.hasLogin()

    override fun currentUserId(): Long {
        if (!client.hasLogin()) return 0L
        // 未登录 / 会话失效时客户端会抛异常，这里统一收敛成 0 交给上层静默降级
        return runCatching { client.getCurrentUserId() }.getOrDefault(0L)
    }

    override fun likedPlaylistId(userId: Long): Long? {
        if (userId <= 0L) return null
        val raw = runCatching { client.getLikedPlaylistId(userId) }.getOrNull() ?: return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) return null
            root.optLong("playlistId", 0L).takeIf { it > 0L }
        }.getOrNull()
    }

    override suspend fun loadIntelligence(
        songId: Long,
        playlistId: Long,
        startMusicId: Long,
    ): String = client.getPlaymodeIntelligenceListCancellable(
        songId = songId,
        playlistId = playlistId,
        startMusicId = startMusicId,
        count = 1,
    )
}
