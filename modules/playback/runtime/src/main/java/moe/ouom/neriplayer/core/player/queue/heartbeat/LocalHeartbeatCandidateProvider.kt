package moe.ouom.neriplayer.core.player.queue.heartbeat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.heartbeat.DislikedSongRepository
import moe.ouom.neriplayer.data.heartbeat.HeartbeatSkipRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.history.toSongItem as playedEntryToSongItem

/**
 * 心动模式本地降级候选池的数据来源实现。
 *
 * 数据全部来自 `modules/local`：
 * - 收藏（层级 1）= 本地「我喜欢的音乐」系统歌单；
 * - 历史（层级 2）= [PlayHistoryRepository] 的最近播放；
 * - 层级 3/4 的原料 = 收藏 + 历史 + 统计库里听过的曲，合并去重；
 * - 播放次数 = [PlaybackStatsRepository] 的 `TrackStat.playCount`。
 *
 * `playback/logic` 不认识 `local`，因此由本类在 `playback/runtime` 侧实现
 * [HeartbeatCandidateProvider] 接口注入，域依赖方向保持 `runtime → local`。
 */
class LocalHeartbeatCandidateProvider(
    private val playHistoryRepository: PlayHistoryRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val context: Context,
    private val playbackStatsRepository: PlaybackStatsRepository? = null,
    private val dislikedSongRepository: DislikedSongRepository? = null,
    private val heartbeatSkipRepository: HeartbeatSkipRepository? = null,
) : HeartbeatCandidateProvider {

    override suspend fun favorites(): List<SongItem> = withContext(Dispatchers.IO) {
        val playlists = runCatching {
            localPlaylistRepository.awaitInitialized()
            localPlaylistRepository.playlists.value
        }.getOrNull().orEmpty()
        FavoritesPlaylist.firstOrNull(playlists, context)?.songs.orEmpty()
    }

    override suspend fun recentHistory(limit: Int): List<SongItem> = withContext(Dispatchers.IO) {
        runCatching {
            playHistoryRepository.awaitHistoryLoaded()
        }
        playHistoryRepository.syncSnapshot()
            .asSequence()
            .take(limit)
            .map(PlayedEntry::playedEntryToSongItem)
            .toList()
    }

    /**
     * 层级 3/4 的原料：把「收藏 + 历史 + 统计库里听过的曲」合并去重。
     *
     * 为什么还要拉统计库：只靠收藏和历史，同歌手/同专辑的候选会很少，
     * 层级 3 基本命中不了。统计库里是「听过但没收藏」的曲目，
     * 正是同歌手推荐最有价值的来源。统计库不可用时静默退回收藏+历史。
     */
    override suspend fun librarySamples(limit: Int): List<SongItem> = withContext(Dispatchers.IO) {
        val favorites = runCatching { favorites() }.getOrDefault(emptyList())
        val history = runCatching {
            recentHistory(HeartbeatCandidateProvider.DEFAULT_HISTORY_LIMIT)
        }.getOrDefault(emptyList())
        val statsSongs = runCatching { statsSongs(limit) }.getOrDefault(emptyList())

        val seen = HashSet<String>()
        val result = ArrayList<SongItem>(favorites.size + history.size + statsSongs.size)
        for (song in favorites + history + statsSongs) {
            if (seen.add(HeartbeatScorer.identityKey(song))) {
                result.add(song)
                if (result.size >= limit) break
            }
        }
        result
    }

    /**
     * 各曲累计播放次数，key 与 [HeartbeatScorer.identityKey] 对齐。
     *
     * 统计库的 identityKey 由 `TrackStat.identityKey` 提供（形如 `netease:123`），
     * 与 [HeartbeatScorer.identityKey] 的生成规则一致；不一致的键不会被命中，
     * 只是少一个惩罚信号，不影响正确性。
     */
    override suspend fun recentPlayCounts(limit: Int): Map<String, Int> = withContext(Dispatchers.IO) {
        val repository = playbackStatsRepository ?: return@withContext emptyMap()
        runCatching {
            repository.readPage(
                query = PlaybackStatsQuery(sort = PlaybackStatsSort.RECENT),
                pageSize = limit.coerceIn(1, 256),
            ).tracks.associate { it.identityKey to it.playCount }
        }.getOrDefault(emptyMap())
    }

    /**
     * 用户显式标记「不喜欢」的曲目身份键集合。
     *
     * 名单存在 `modules/local` 的 DataStore 里（网易云没有「打不喜欢」的通用接口，
     * 只能本地记录），本类只负责把它转成 [HeartbeatCandidateProvider] 的接口形状。
     * 读取失败按「没有标记」处理：名单读不出来不该让心动模式整体失效。
     */
    override suspend fun dislikedIdentities(): Set<String> = withContext(Dispatchers.IO) {
        val repository = dislikedSongRepository ?: return@withContext emptySet()
        runCatching { repository.snapshot() }.getOrDefault(emptySet())
    }

    /**
     * 用户在心动模式下主动跳过各曲的累计次数（任务书 §七 第三阶段）。
     *
     * 与 [dislikedIdentities] 一样是本地 DataStore 数据，读取失败按「没有跳过记录」处理。
     */
    override suspend fun skipCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        val repository = heartbeatSkipRepository ?: return@withContext emptyMap()
        runCatching { repository.snapshot() }.getOrDefault(emptyMap())
    }

    /** 从统计库读「听过的曲」，用于扩充层级 3/4 的原料。 */
    private suspend fun statsSongs(limit: Int): List<SongItem> {
        val repository = playbackStatsRepository ?: return emptyList()
        return repository.readPage(
            query = PlaybackStatsQuery(sort = PlaybackStatsSort.RECENT),
            pageSize = limit.coerceIn(1, 256),
        ).tracks.map { it.toHeartbeatSongItem() }
    }
}

/** 把统计条目还原成可播放的 [SongItem]。 */
private fun TrackStat.toHeartbeatSongItem(): SongItem = SongItem(
    id = id,
    name = name,
    artist = artist,
    album = album,
    albumId = albumId,
    durationMs = durationMs,
    coverUrl = coverUrl,
    mediaUri = mediaUri,
    localFilePath = localFilePath,
    localFileName = localFileName,
)
