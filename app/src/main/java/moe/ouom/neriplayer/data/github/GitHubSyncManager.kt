package moe.ouom.neriplayer.data.github

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.github/GitHubSyncManager
 * Created: 2025/1/7
 */

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.PlayedEntry
import moe.ouom.neriplayer.data.PlayHistoryRepository
import moe.ouom.neriplayer.util.NPLogger
import java.util.UUID

/**
 * GitHub 同步管理器
 * 实现三路合并算法,自动解决冲突
 */
class GitHubSyncManager(private val context: Context) {

    private val storage = SecureTokenStorage(context)
    private val gson = Gson()
    private val playlistRepo = LocalPlaylistRepository.getInstance(context)
    private val favoriteRepo = FavoritePlaylistRepository.getInstance(context)
    private val playHistoryRepo = PlayHistoryRepository.getInstance(context)

    // 同步锁，防止并发同步
    private val syncLock = kotlinx.coroutines.sync.Mutex()

    companion object {
        private const val TAG = "GitHubSyncManager"

        @Volatile
        private var instance: GitHubSyncManager? = null

        fun getInstance(context: Context): GitHubSyncManager {
            return instance ?: synchronized(this) {
                instance ?: GitHubSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 获取或生成设备ID
     */
    private fun getDeviceId(): String {
        var deviceId = storage.getDeviceId()
        if (deviceId == null) {
            deviceId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: UUID.randomUUID().toString()
            } catch (e: Exception) {
                UUID.randomUUID().toString()
            }
            storage.saveDeviceId(deviceId)
        }
        return deviceId
    }

    /**
     * 获取设备名称
     */
    private fun getDeviceName(): String {
        return try {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (e: Exception) {
            "Unknown Device"
        }
    }

    /**
     * 执行完整同步
     */
    suspend fun performSync(): Result<SyncResult> = withContext(Dispatchers.IO) {
        // 使用锁防止并发同步
        if (!syncLock.tryLock()) {
            NPLogger.d(TAG, "Sync already in progress, skipping")
            return@withContext Result.success(SyncResult(
                success = true,
                message = "同步正在进行中，已跳过"
            ))
        }

        try {
            val token = storage.getToken()
            val owner = storage.getRepoOwner()
            val repo = storage.getRepoName()

            if (token == null || owner == null || repo == null) {
                return@withContext Result.failure(IllegalStateException("GitHub未配置"))
            }

            val apiClient = GitHubApiClient(token)

            // 1. 获取本地数据
            val localData = buildLocalSyncData()

            // 2. 获取远程数据
            val remoteResult = apiClient.getFileContent(owner, repo)
            if (remoteResult.isFailure) {
                val error = remoteResult.exceptionOrNull()
                // 检查是否是Token过期
                if (error is TokenExpiredException) {
                    NPLogger.e(TAG, "Token expired, clearing configuration")
                    // 清除过期的Token，触发MainActivity显示警告
                    storage.clearToken()
                    return@withContext Result.failure(error)
                }
                // 远程文件不存在,直接上传本地数据
                val uploadResult = uploadLocalData(apiClient, owner, repo, localData, null)
                return@withContext if (uploadResult.isSuccess) {
                    val newSha = uploadResult.getOrNull()
                    if (newSha != null) {
                        storage.saveLastRemoteSha(newSha)
                    }
                    storage.saveLastSyncTime(System.currentTimeMillis())
                    Result.success(SyncResult(success = true, message = "初始数据已上传"))
                } else {
                    val error = uploadResult.exceptionOrNull()
                    // 检查是否是Token过期
                    if (error is TokenExpiredException) {
                        NPLogger.e(TAG, "Token expired during initial upload, clearing configuration")
                        storage.clearToken()
                        return@withContext Result.failure(error)
                    }
                    uploadResult.map { SyncResult(success = false, message = "上传失败") }
                }
            }

            val (remoteContent, remoteSha) = remoteResult.getOrThrow()
            if (remoteContent.isEmpty()) {
                // 远程文件为空,直接上传
                val uploadResult = uploadLocalData(apiClient, owner, repo, localData, null)
                return@withContext if (uploadResult.isSuccess) {
                    val newSha = uploadResult.getOrNull()
                    if (newSha != null) {
                        storage.saveLastRemoteSha(newSha)
                    }
                    storage.saveLastSyncTime(System.currentTimeMillis())
                    Result.success(SyncResult(success = true, message = "初始数据已上传"))
                } else {
                    val error = uploadResult.exceptionOrNull()
                    // 检查是否是Token过期
                    if (error is TokenExpiredException) {
                        NPLogger.e(TAG, "Token expired during empty file upload, clearing configuration")
                        storage.clearToken()
                        return@withContext Result.failure(error)
                    }
                    uploadResult.map { SyncResult(success = false, message = "上传失败") }
                }
            }

            // 3. 解析远程数据
            val remoteData = try {
                gson.fromJson(remoteContent, SyncData::class.java)
            } catch (e: Exception) {
                NPLogger.e(TAG, "Failed to parse remote data", e)
                return@withContext Result.failure(e)
            }

            // 4. 检测远程是否有变化（通过SHA）
            val lastRemoteSha = storage.getLastRemoteSha()
            // 首次同步(lastRemoteSha为null)或SHA变化时都认为远程有变化
            val remoteHasChanged = lastRemoteSha == null || lastRemoteSha != remoteSha
            if (remoteHasChanged) {
                if (lastRemoteSha == null) {
                    NPLogger.d(TAG, "First sync detected, applying remote data")
                } else {
                    NPLogger.d(TAG, "Remote file has changed (SHA mismatch), prioritizing remote data")
                }
            }

            // 5. 三路合并（传递远程变化标记）
            val mergeResult = performThreeWayMerge(localData, remoteData, remoteHasChanged)

            // 6. 应用合并结果到本地（传递远程变化标记）
            applyMergedDataToLocal(mergeResult.mergedData, remoteHasChanged)

            // 7. 检查数据是否有变化（优化流量）
            val dataChanged = hasDataChanged(remoteData, mergeResult.mergedData)
            if (!dataChanged && !remoteHasChanged) {
                NPLogger.d(TAG, "No data changes detected, skipping upload to save bandwidth")
                // 即使不上传，也要保存SHA和时间
                storage.saveLastRemoteSha(remoteSha)
                storage.saveLastSyncTime(System.currentTimeMillis())
                return@withContext Result.success(SyncResult(
                    success = true,
                    message = "同步成功（无变化）"
                ))
            }

            // 8. 上传合并后的数据
            val uploadResult = uploadLocalData(apiClient, owner, repo, mergeResult.mergedData, remoteSha)
            if (uploadResult.isFailure) {
                val error = uploadResult.exceptionOrNull()
                // 检查是否是Token过期
                if (error is TokenExpiredException) {
                    NPLogger.e(TAG, "Token expired during upload, clearing configuration")
                    // 清除过期的Token，触发MainActivity显示警告
                    storage.clearToken()
                    return@withContext Result.failure(error)
                }
                return@withContext Result.failure(uploadResult.exceptionOrNull() ?: Exception("上传失败"))
            }

            // 9. 保存上传后的新SHA（而不是上传前的旧SHA）
            val newSha = uploadResult.getOrNull()
            if (newSha != null) {
                storage.saveLastRemoteSha(newSha)
            }

            // 10. 更新最后同步时间
            storage.saveLastSyncTime(System.currentTimeMillis())

            // 11. 清除已删除的歌单ID列表（同步成功后）
            storage.clearDeletedPlaylistIds()

            Result.success(mergeResult.syncResult)

        } catch (e: Exception) {
            NPLogger.e(TAG, "Sync failed", e)
            Result.failure(e)
        } finally {
            syncLock.unlock()
        }
    }

    /**
     * 构建本地同步数据
     */
    private fun buildLocalSyncData(): SyncData {
        val playlists = playlistRepo.playlists.value
        val syncPlaylists = playlists.map { playlist ->
            SyncPlaylist.fromLocalPlaylist(playlist, playlist.modifiedAt)
        }.toMutableList()

        // 添加已删除的歌单（标记为已删除）
        val deletedIds = storage.getDeletedPlaylistIds()
        deletedIds.forEach { deletedId ->
            // 只添加不在当前歌单列表中的删除记录
            if (playlists.none { it.id == deletedId }) {
                syncPlaylists.add(
                    SyncPlaylist(
                        id = deletedId,
                        name = "",
                        songs = emptyList(),
                        createdAt = 0L,
                        modifiedAt = System.currentTimeMillis(),
                        isDeleted = true
                    )
                )
            }
        }

        val favoritePlaylists = favoriteRepo.favorites.value
        val syncFavoritePlaylists = favoritePlaylists.map { playlist ->
            SyncFavoritePlaylist.fromFavoritePlaylist(playlist)
        }

        // 获取播放历史（限制最多500条）
        val playHistory = playHistoryRepo.historyFlow.value.take(500)
        NPLogger.d(TAG, "Building local sync data: play history count=${playHistory.size}")
        if (playHistory.isNotEmpty()) {
            NPLogger.d(TAG, "Latest play: songId=${playHistory[0].id}, playedAt=${playHistory[0].playedAt}, name=${playHistory[0].name}")
        }
        val syncRecentPlays = playHistory.map { playedEntry ->
            SyncRecentPlay(
                songId = playedEntry.id,
                song = SyncSong(
                    id = playedEntry.id,
                    name = playedEntry.name,
                    artist = playedEntry.artist,
                    album = playedEntry.album,
                    albumId = playedEntry.albumId,
                    durationMs = playedEntry.durationMs,
                    coverUrl = playedEntry.coverUrl
                ),
                playedAt = playedEntry.playedAt,
                deviceId = getDeviceId()
            )
        }

        return SyncData(
            deviceId = getDeviceId(),
            deviceName = getDeviceName(),
            lastModified = System.currentTimeMillis(),
            playlists = syncPlaylists,
            favoritePlaylists = syncFavoritePlaylists,
            recentPlays = syncRecentPlays,
            syncLog = emptyList()
        )
    }

    /**
     * 三路合并算法
     * 自动解决冲突
     * @param remoteHasChanged 远程文件是否有变化（通过SHA检测）
     */
    private fun performThreeWayMerge(local: SyncData, remote: SyncData, remoteHasChanged: Boolean = false): MergeResult {
        val conflicts = mutableListOf<SyncConflict>()
        var playlistsAdded = 0
        var playlistsUpdated = 0
        var playlistsDeleted = 0
        var songsAdded = 0
        var songsRemoved = 0

        // 合并歌单
        val mergedPlaylists = mutableListOf<SyncPlaylist>()
        val localPlaylistsMap = local.playlists.associateBy { it.id }
        val remotePlaylistsMap = remote.playlists.associateBy { it.id }

        // 处理所有歌单ID
        val allPlaylistIds = (localPlaylistsMap.keys + remotePlaylistsMap.keys).toSet()

        for (playlistId in allPlaylistIds) {
            val localPlaylist = localPlaylistsMap[playlistId]
            val remotePlaylist = remotePlaylistsMap[playlistId]

            when {
                // 只在本地存在 -> 新增（除非标记为已删除）
                localPlaylist != null && remotePlaylist == null -> {
                    if (!localPlaylist.isDeleted) {
                        mergedPlaylists.add(localPlaylist)
                        playlistsAdded++
                    } else {
                        playlistsDeleted++
                    }
                }

                // 只在远程存在 -> 新增（除非标记为已删除）
                localPlaylist == null && remotePlaylist != null -> {
                    if (!remotePlaylist.isDeleted) {
                        mergedPlaylists.add(remotePlaylist)
                        playlistsAdded++
                    } else {
                        playlistsDeleted++
                    }
                }

                // 两端都存在 -> 合并
                localPlaylist != null && remotePlaylist != null -> {
                    // 如果任一端标记为已删除，则不包含在合并结果中
                    if (localPlaylist.isDeleted || remotePlaylist.isDeleted) {
                        playlistsDeleted++
                    } else {
                        val merged = mergePlaylist(localPlaylist, remotePlaylist, remoteHasChanged)
                        mergedPlaylists.add(merged.playlist)

                        if (merged.hasConflict) {
                            conflicts.add(merged.conflict!!)
                        }

                        if (merged.songsAdded > 0) songsAdded += merged.songsAdded
                        if (merged.songsRemoved > 0) songsRemoved += merged.songsRemoved
                        if (merged.isUpdated) playlistsUpdated++
                    }
                }
            }
        }

        // 合并收藏歌单（简单合并，按addedTime去重）
        val mergedFavoritePlaylists = (local.favoritePlaylists + (remote.favoritePlaylists ?: emptyList()))
            .groupBy { "${it.id}_${it.source}" }
            .map { (_, playlists) -> playlists.maxByOrNull { it.addedTime }!! }

        val mergedData = SyncData(
            deviceId = local.deviceId,
            deviceName = local.deviceName,
            lastModified = System.currentTimeMillis(),
            playlists = mergedPlaylists,
            favoritePlaylists = mergedFavoritePlaylists,
            recentPlays = mergeRecentPlays(local.recentPlays, remote.recentPlays),
            syncLog = ((local.syncLog ?: emptyList()) + (remote.syncLog ?: emptyList())).distinctBy { it.timestamp }.sortedByDescending { it.timestamp }.take(100)
        )

        val syncResult = SyncResult(
            success = true,
            message = "同步成功",
            playlistsAdded = playlistsAdded,
            playlistsUpdated = playlistsUpdated,
            playlistsDeleted = playlistsDeleted,
            songsAdded = songsAdded,
            songsRemoved = songsRemoved,
            conflicts = conflicts
        )

        return MergeResult(mergedData, syncResult)
    }

    /**
     * 合并单个歌单
     * 策略：远程变化优先 > 最后修改时间优先
     * - 如果远程文件有变化（SHA不同），优先使用远程数据
     * - 否则使用修改时间更新的一端
     */
    private fun mergePlaylist(local: SyncPlaylist, remote: SyncPlaylist, remoteHasChanged: Boolean = false): PlaylistMergeResult {
        var hasConflict = false
        var conflict: SyncConflict? = null
        var songsAdded = 0
        var songsRemoved = 0
        var isUpdated = false

        // 检查名称冲突
        val finalName = if (local.name != remote.name) {
            hasConflict = true
            // 使用最新修改时间的名称
            if (local.modifiedAt > remote.modifiedAt) {
                conflict = SyncConflict(
                    type = ConflictType.PLAYLIST_RENAMED_BOTH_SIDES,
                    playlistId = local.id,
                    playlistName = local.name,
                    description = "歌单在两端都被重命名,使用本地名称: ${local.name}",
                    resolution = ConflictResolution.LOCAL_WINS
                )
                local.name
            } else {
                conflict = SyncConflict(
                    type = ConflictType.PLAYLIST_RENAMED_BOTH_SIDES,
                    playlistId = local.id,
                    playlistName = remote.name,
                    description = "歌单在两端都被重命名,使用远程名称: ${remote.name}",
                    resolution = ConflictResolution.REMOTE_WINS
                )
                isUpdated = true
                remote.name
            }
        } else {
            local.name
        }

        // 合并歌曲列表 - 远程变化优先 > 最后修改时间优先
        val localSongIds = local.songs.map { it.id }.toSet()
        val remoteSongIds = remote.songs.map { it.id }.toSet()

        // 智能合并策略：
        // 1. 如果远程为空而本地有数据 -> 保留本地数据（防止初始化时数据丢失）
        // 2. 如果本地为空而远程有数据 -> 使用远程数据
        // 3. 如果远程文件有变化（SHA不同）-> 优先使用远程数据（尊重手动编辑）
        // 4. 如果两端都有数据 -> 使用最后修改时间更新的一端
        val mergedSongs = when {
            remoteSongIds.isEmpty() && localSongIds.isNotEmpty() -> {
                // 远程为空，本地有数据 -> 保留本地（防止数据丢失）
                NPLogger.d(TAG, "Remote playlist '${local.name}' is empty, keeping local songs (${localSongIds.size} songs)")
                local.songs
            }
            localSongIds.isEmpty() && remoteSongIds.isNotEmpty() -> {
                // 本地为空，远程有数据 -> 使用远程
                isUpdated = true
                songsAdded = remoteSongIds.size
                remote.songs
            }
            remoteHasChanged -> {
                // 远程文件有变化（用户手动编辑了GitHub文件）-> 优先使用远程数据
                NPLogger.d(TAG, "Remote file has changed, using remote playlist '${local.name}' (${remoteSongIds.size} songs)")
                isUpdated = true
                songsAdded = (remoteSongIds - localSongIds).size
                songsRemoved = (localSongIds - remoteSongIds).size
                remote.songs
            }
            else -> {
                // 两端都有数据且远程无变化 -> 使用最后修改时间更新的一端
                if (remote.modifiedAt > local.modifiedAt) {
                    // 远程更新，使用远程数据
                    NPLogger.d(TAG, "Remote playlist '${local.name}' is newer, using remote songs")
                    isUpdated = true
                    songsAdded = (remoteSongIds - localSongIds).size
                    songsRemoved = (localSongIds - remoteSongIds).size
                    remote.songs
                } else {
                    // 本地更新或相同，使用本地数据
                    NPLogger.d(TAG, "Local playlist '${local.name}' is newer or same, using local songs")
                    songsAdded = (remoteSongIds - localSongIds).size
                    songsRemoved = (localSongIds - remoteSongIds).size
                    if (songsAdded > 0 || songsRemoved > 0) {
                        isUpdated = true
                    }
                    local.songs
                }
            }
        }

        val mergedPlaylist = SyncPlaylist(
            id = local.id,
            name = finalName,
            songs = mergedSongs,
            createdAt = local.createdAt,
            modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt)
        )

        return PlaylistMergeResult(
            playlist = mergedPlaylist,
            hasConflict = hasConflict,
            conflict = conflict,
            songsAdded = songsAdded,
            songsRemoved = songsRemoved,
            isUpdated = isUpdated
        )
    }

    /**
     * 合并最近播放
     */
    private fun mergeRecentPlays(local: List<SyncRecentPlay>, remote: List<SyncRecentPlay>): List<SyncRecentPlay> {
        NPLogger.d(TAG, "Merging play history: local=${local.size}, remote=${remote.size}")
        val merged = (local + remote)
            .distinctBy { it.songId to it.playedAt }
            .sortedByDescending { it.playedAt }
            .take(500)
        NPLogger.d(TAG, "Merged play history: ${merged.size} entries")
        if (merged.isNotEmpty()) {
            NPLogger.d(TAG, "Latest merged play: songId=${merged[0].songId}, playedAt=${merged[0].playedAt}")
        }
        return merged
    }

    /**
     * 应用合并后的数据到本地
     * 使用智能合并策略，避免重复歌单
     * 特殊处理："我喜欢的音乐"始终置顶且按名称匹配
     * @param remoteHasChanged 远程是否有变化，只有远程有变化时才应用播放历史
     */
    private suspend fun applyMergedDataToLocal(mergedData: SyncData, remoteHasChanged: Boolean) {
        // 应用本地歌单 - 只更新存在于mergedData中的歌单，不删除本地独有的歌单
        val currentPlaylists = playlistRepo.playlists.value.toMutableList()
        val currentPlaylistsById = currentPlaylists.associateBy { it.id }.toMutableMap()
        val currentPlaylistsByName = currentPlaylists.associateBy { it.name }.toMutableMap()

        // 更新或添加来自mergedData的歌单
        for (syncPlaylist in mergedData.playlists) {
            // 优先按名称匹配系统歌单（"我喜欢的音乐"），然后按ID匹配
            val existingPlaylist = if (syncPlaylist.name == LocalPlaylistRepository.FAVORITES_NAME) {
                currentPlaylistsByName[LocalPlaylistRepository.FAVORITES_NAME]
            } else {
                currentPlaylistsById[syncPlaylist.id] ?: currentPlaylistsByName[syncPlaylist.name]
            }

            if (existingPlaylist != null) {
                // 歌单已存在，更新它（保留本地ID，使用远程modifiedAt）
                val updatedPlaylist = existingPlaylist.copy(
                    name = syncPlaylist.name,
                    songs = syncPlaylist.songs.map { it.toSongItem() }.toMutableList(),
                    modifiedAt = syncPlaylist.modifiedAt
                )
                // 从两个map中移除旧的，添加新的
                currentPlaylistsById.remove(existingPlaylist.id)
                currentPlaylistsByName.remove(existingPlaylist.name)
                currentPlaylistsById[updatedPlaylist.id] = updatedPlaylist
                currentPlaylistsByName[updatedPlaylist.name] = updatedPlaylist
            } else {
                // 新歌单，添加它
                val newPlaylist = syncPlaylist.toLocalPlaylist()
                currentPlaylistsById[newPlaylist.id] = newPlaylist
                currentPlaylistsByName[newPlaylist.name] = newPlaylist
            }
        }

        // 确保"我喜欢的音乐"存在且置顶
        val allPlaylists = currentPlaylistsById.values.toMutableList()
        val favoritesPlaylist = allPlaylists.firstOrNull { it.name == LocalPlaylistRepository.FAVORITES_NAME }

        if (favoritesPlaylist != null) {
            // 移除"我喜欢的音乐"，然后添加到第一位
            allPlaylists.remove(favoritesPlaylist)
            allPlaylists.add(0, favoritesPlaylist)
        } else {
            // 如果不存在，创建一个新的"我喜欢的音乐"并置顶
            val newFavorites = LocalPlaylist(
                id = System.currentTimeMillis(),
                name = LocalPlaylistRepository.FAVORITES_NAME,
                songs = mutableListOf()
            )
            allPlaylists.add(0, newFavorites)
        }

        // 更新仓库（保留所有歌单，"我喜欢的音乐"置顶）
        playlistRepo.updatePlaylists(allPlaylists)

        // 应用收藏歌单（使用FavoritePlaylistRepository的addFavorite方法，自动去重）
        for (syncFavorite in mergedData.favoritePlaylists) {
            val favorite = syncFavorite.toFavoritePlaylist()
            favoriteRepo.addFavorite(
                id = favorite.id,
                name = favorite.name,
                coverUrl = favorite.coverUrl,
                trackCount = favorite.trackCount,
                source = favorite.source,
                songs = favorite.songs
            )
        }

        // 应用播放历史
        // 条件：1) 远程有变化，或 2) 本地为空但远程有数据（恢复场景）
        val localPlayHistoryEmpty = playHistoryRepo.historyFlow.value.isEmpty()
        val shouldApplyRemoteHistory = remoteHasChanged ||
            (localPlayHistoryEmpty && mergedData.recentPlays.isNotEmpty())

        if (shouldApplyRemoteHistory) {
            val reason = when {
                remoteHasChanged -> "remote changed"
                localPlayHistoryEmpty -> "local empty, restoring from remote"
                else -> "unknown"
            }
            NPLogger.d(TAG, "Applying remote play history to local (${mergedData.recentPlays.size} entries, reason: $reason)")
            val playHistory = mergedData.recentPlays.map { syncPlay ->
                PlayedEntry(
                    id = syncPlay.song.id,
                    name = syncPlay.song.name,
                    artist = syncPlay.song.artist,
                    album = syncPlay.song.album,
                    albumId = syncPlay.song.albumId,
                    durationMs = syncPlay.song.durationMs,
                    coverUrl = syncPlay.song.coverUrl,
                    playedAt = syncPlay.playedAt
                )
            }
            playHistoryRepo.updateHistory(playHistory)
        } else {
            NPLogger.d(TAG, "Skipping play history update (no remote changes)")
        }
    }

    /**
     * 检查数据是否有变化（用于优化流量）
     * 比较歌单、收藏和播放历史的数量和内容
     */
    private fun hasDataChanged(remote: SyncData, merged: SyncData): Boolean {
        // 比较歌单数量
        if (remote.playlists.size != merged.playlists.size) return true

        // 比较每个歌单的详细信息
        val remotePlaylistMap = remote.playlists.associateBy { it.id }
        for (mergedPlaylist in merged.playlists) {
            val remotePlaylist = remotePlaylistMap[mergedPlaylist.id]
            if (remotePlaylist == null) return true

            // 检查歌单名称
            if (remotePlaylist.name != mergedPlaylist.name) return true

            // 检查歌曲数量
            if (remotePlaylist.songs.size != mergedPlaylist.songs.size) return true

            // 检查歌曲顺序（比较歌曲ID序列）
            val remoteSongIds = remotePlaylist.songs.map { it.id }
            val mergedSongIds = mergedPlaylist.songs.map { it.id }
            if (remoteSongIds != mergedSongIds) return true
        }

        // 比较收藏歌单数量
        if (remote.favoritePlaylists.size != merged.favoritePlaylists.size) return true

        // 比较收藏歌单内容（检查ID和来源）
        val remoteFavoriteKeys = remote.favoritePlaylists.map { "${it.id}_${it.source}" }.toSet()
        val mergedFavoriteKeys = merged.favoritePlaylists.map { "${it.id}_${it.source}" }.toSet()
        if (remoteFavoriteKeys != mergedFavoriteKeys) return true

        // 比较播放历史内容（比较前50条，平衡检测准确性和上传频率）
        val remoteRecent = remote.recentPlays.take(50)
        val mergedRecent = merged.recentPlays.take(50)

        // 先比较数量
        if (remoteRecent.size != mergedRecent.size) {
            NPLogger.d(TAG, "Play history count changed: remote=${remoteRecent.size}, merged=${mergedRecent.size}")
            return true
        }

        // 比较每条播放记录的ID和播放时间
        for (i in remoteRecent.indices) {
            val remotePlay = remoteRecent[i]
            val mergedPlay = mergedRecent[i]
            if (remotePlay.songId != mergedPlay.songId || remotePlay.playedAt != mergedPlay.playedAt) {
                NPLogger.d(TAG, "Play history content changed at index $i: remote=(${remotePlay.songId},${remotePlay.playedAt}), merged=(${mergedPlay.songId},${mergedPlay.playedAt})")
                return true
            }
        }

        NPLogger.d(TAG, "No play history changes detected (checked ${remoteRecent.size} entries)")
        return false
    }

    /**
     * 上传本地数据到GitHub
     */
    private suspend fun uploadLocalData(
        apiClient: GitHubApiClient,
        owner: String,
        repo: String,
        data: SyncData,
        sha: String?
    ): Result<String> {
        val json = gson.toJson(data)
        val uploadResult = apiClient.updateFileContent(owner, repo, json, sha)

        return if (uploadResult.isSuccess) {
            val newSha = uploadResult.getOrNull() ?: ""
            Result.success(newSha)
        } else {
            Result.failure(uploadResult.exceptionOrNull() ?: Exception("上传失败"))
        }
    }

    /**
     * 合并结果
     */
    private data class MergeResult(
        val mergedData: SyncData,
        val syncResult: SyncResult
    )

    /**
     * 歌单合并结果
     */
    private data class PlaylistMergeResult(
        val playlist: SyncPlaylist,
        val hasConflict: Boolean,
        val conflict: SyncConflict?,
        val songsAdded: Int,
        val songsRemoved: Int,
        val isUpdated: Boolean
    )
}
