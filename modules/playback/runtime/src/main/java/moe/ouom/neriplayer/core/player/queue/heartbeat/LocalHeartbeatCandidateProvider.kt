package moe.ouom.neriplayer.core.player.queue.heartbeat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry

/**
 * 心动模式本地降级候选池的数据来源实现。
 *
 * 数据全部来自 `modules/local`：
 * - 收藏（层级 1）= 本地「我喜欢的音乐」系统歌单；
 * - 历史（层级 2）= [PlayHistoryRepository] 的最近播放。
 *
 * `playback/logic` 不认识 `local`，因此由本类在 `playback/runtime` 侧实现
 * [HeartbeatCandidateProvider] 接口注入，域依赖方向保持 `runtime → local`。
 */
class LocalHeartbeatCandidateProvider(
    private val playHistoryRepository: PlayHistoryRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val context: Context,
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
            .map(PlayedEntry::toSongItem)
            .toList()
    }
}
