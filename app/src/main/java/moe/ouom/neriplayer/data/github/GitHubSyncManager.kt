package moe.ouom.neriplayer.data.github

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.FavoritesPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.PlayedEntry
import moe.ouom.neriplayer.data.PlayHistoryRepository
import moe.ouom.neriplayer.data.identity
import moe.ouom.neriplayer.util.LanguageManager
import moe.ouom.neriplayer.util.NPLogger
import java.util.UUID

class GitHubSyncManager(private val context: Context) {
    private val storage = SecureTokenStorage(context)
    private val playlistRepo = LocalPlaylistRepository.getInstance(context)
    private val favoriteRepo = FavoritePlaylistRepository.getInstance(context)
    private val playHistoryRepo = PlayHistoryRepository.getInstance(context)
    private val syncLock = Mutex()

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

    suspend fun performSync(): Result<SyncResult> = withContext(Dispatchers.IO) {
        val localizedContext = LanguageManager.applyLanguage(context)

        if (!syncLock.tryLock()) {
            return@withContext Result.success(
                SyncResult(
                    success = true,
                    message = localizedContext.getString(R.string.github_sync_in_progress)
                )
            )
        }

        try {
            val token = storage.getToken()
            val owner = storage.getRepoOwner()
            val repo = storage.getRepoName()
            if (token == null || owner == null || repo == null) {
                return@withContext Result.failure(
                    IllegalStateException(localizedContext.getString(R.string.github_not_configured))
                )
            }

            val apiClient = GitHubApiClient(context, token)
            val localData = buildLocalSyncData()
            val useDataSaver = storage.isDataSaverMode()
            val preferredFileName = SyncDataSerializer.getFileName(useDataSaver)

            var remoteResult = apiClient.getFileContent(owner, repo, preferredFileName)
            var actualFileName = preferredFileName
            if (remoteResult.isFailure) {
                val alternativeFileName = SyncDataSerializer.getFileName(!useDataSaver)
                val alternativeResult = apiClient.getFileContent(owner, repo, alternativeFileName)
                if (alternativeResult.isSuccess) {
                    remoteResult = alternativeResult
                    actualFileName = alternativeFileName
                }
            }

            if (remoteResult.isFailure) {
                val error = remoteResult.exceptionOrNull()
                if (error is TokenExpiredException) {
                    storage.clearToken()
                    return@withContext Result.failure(error)
                }
                return@withContext handleInitialUpload(
                    apiClient = apiClient,
                    owner = owner,
                    repo = repo,
                    localData = localData,
                    sha = null,
                    fileName = preferredFileName,
                    localizedContext = localizedContext
                )
            }

            val (remoteContent, remoteSha) = remoteResult.getOrThrow()
            if (remoteContent.isEmpty()) {
                return@withContext handleInitialUpload(
                    apiClient = apiClient,
                    owner = owner,
                    repo = repo,
                    localData = localData,
                    sha = remoteSha,
                    fileName = preferredFileName,
                    localizedContext = localizedContext
                )
            }

            val remoteData = try {
                SyncDataSerializer.deserialize(
                    remoteContent,
                    SyncDataSerializer.isBinaryFileName(actualFileName)
                )
            } catch (e: Exception) {
                NPLogger.e(TAG, "Failed to parse remote data", e)
                return@withContext Result.failure(e)
            }

            val lastRemoteSha = storage.getLastRemoteSha()
            val isFirstSync = lastRemoteSha == null
            val remoteHasChanged = lastRemoteSha != null && lastRemoteSha != remoteSha
            val mergeResult = performThreeWayMerge(localData, remoteData, isFirstSync)

            applyMergedDataToLocal(
                mergedData = mergeResult.mergedData,
                remoteHasChanged = isFirstSync || remoteHasChanged
            )

            if (!hasDataChanged(remoteData, mergeResult.mergedData) && !remoteHasChanged) {
                storage.saveLastRemoteSha(remoteSha)
                storage.saveLastSyncTime(System.currentTimeMillis())
                return@withContext Result.success(
                    SyncResult(
                        success = true,
                        message = localizedContext.getString(R.string.github_sync_no_change)
                    )
                )
            }

            val uploadResult = uploadLocalData(
                apiClient = apiClient,
                owner = owner,
                repo = repo,
                data = mergeResult.mergedData,
                sha = remoteSha,
                fileName = preferredFileName
            )

            if (uploadResult.isFailure) {
                val error = uploadResult.exceptionOrNull()
                if (error is TokenExpiredException) {
                    storage.clearToken()
                }
                return@withContext Result.failure(
                    error ?: Exception(localizedContext.getString(R.string.sync_upload_failed))
                )
            }

            uploadResult.getOrNull()?.let { storage.saveLastRemoteSha(it) }
            storage.saveLastSyncTime(System.currentTimeMillis())
            storage.clearDeletedPlaylistIds()
            Result.success(mergeResult.syncResult)
        } catch (e: Exception) {
            NPLogger.e(TAG, "Sync failed", e)
            Result.failure(e)
        } finally {
            syncLock.unlock()
        }
    }

    private suspend fun handleInitialUpload(
        apiClient: GitHubApiClient,
        owner: String,
        repo: String,
        localData: SyncData,
        sha: String?,
        fileName: String,
        localizedContext: Context
    ): Result<SyncResult> {
        val uploadResult = uploadLocalData(apiClient, owner, repo, localData, sha, fileName)
        if (uploadResult.isSuccess) {
            uploadResult.getOrNull()?.let { storage.saveLastRemoteSha(it) }
            storage.saveLastSyncTime(System.currentTimeMillis())
            return Result.success(
                SyncResult(
                    success = true,
                    message = localizedContext.getString(R.string.sync_initial_uploaded)
                )
            )
        }

        val error = uploadResult.exceptionOrNull()
        if (error is TokenExpiredException) {
            storage.clearToken()
            return Result.failure(error)
        }
        return Result.success(
            SyncResult(
                success = false,
                message = localizedContext.getString(R.string.sync_upload_failed)
            )
        )
    }

    private fun buildLocalSyncData(): SyncData {
        val playlists = playlistRepo.playlists.value
        val syncPlaylists = playlists.map { playlist ->
            SyncPlaylist.fromLocalPlaylist(playlist, playlist.modifiedAt, context)
        }.toMutableList()

        storage.getDeletedPlaylistIds().forEach { deletedId ->
            if (playlists.none { it.id == deletedId }) {
                syncPlaylists += SyncPlaylist(
                    id = deletedId,
                    name = "",
                    songs = emptyList(),
                    createdAt = 0L,
                    modifiedAt = System.currentTimeMillis(),
                    isDeleted = true
                )
            }
        }

        val syncFavoritePlaylists = favoriteRepo.favorites.value.map {
            SyncFavoritePlaylist.fromFavoritePlaylist(it, context)
        }

        val syncRecentPlays = playHistoryRepo.historyFlow.value.take(500).map { playedEntry ->
            SyncRecentPlay(
                songId = playedEntry.id,
                song = SyncSong(
                    id = playedEntry.id,
                    name = playedEntry.name,
                    artist = playedEntry.artist,
                    album = playedEntry.album,
                    albumId = playedEntry.albumId,
                    durationMs = playedEntry.durationMs,
                    coverUrl = playedEntry.coverUrl,
                    mediaUri = playedEntry.mediaUri
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

    private fun performThreeWayMerge(
        local: SyncData,
        remote: SyncData,
        isFirstSync: Boolean
    ): MergeResult {
        val localizedContext = LanguageManager.applyLanguage(context)
        val conflicts = mutableListOf<SyncConflict>()
        var playlistsAdded = 0
        var playlistsUpdated = 0
        var playlistsDeleted = 0
        var songsAdded = 0
        var songsRemoved = 0

        val mergedPlaylists = mutableListOf<SyncPlaylist>()
        val localPlaylistsMap = local.playlists.associateBy { it.id }
        val remotePlaylistsMap = remote.playlists.associateBy { it.id }
        val allPlaylistIds = (localPlaylistsMap.keys + remotePlaylistsMap.keys).toSet()

        for (playlistId in allPlaylistIds) {
            val localPlaylist = localPlaylistsMap[playlistId]
            val remotePlaylist = remotePlaylistsMap[playlistId]
            when {
                localPlaylist != null && remotePlaylist == null -> {
                    if (!localPlaylist.isDeleted) {
                        mergedPlaylists += localPlaylist
                        playlistsAdded++
                    } else {
                        playlistsDeleted++
                    }
                }

                localPlaylist == null && remotePlaylist != null -> {
                    if (!remotePlaylist.isDeleted) {
                        mergedPlaylists += remotePlaylist
                        playlistsAdded++
                    } else {
                        playlistsDeleted++
                    }
                }

                localPlaylist != null && remotePlaylist != null -> {
                    if (localPlaylist.isDeleted || remotePlaylist.isDeleted) {
                        playlistsDeleted++
                    } else {
                        val merged = mergePlaylist(localPlaylist, remotePlaylist, isFirstSync)
                        mergedPlaylists += merged.playlist
                        merged.conflict?.let { conflicts += it }
                        songsAdded += merged.songsAdded
                        songsRemoved += merged.songsRemoved
                        if (merged.isUpdated) {
                            playlistsUpdated++
                        }
                    }
                }
            }
        }

        val mergedFavoritePlaylists = (local.favoritePlaylists + remote.favoritePlaylists)
            .groupBy { "${it.id}_${it.source}" }
            .map { (_, snapshots) ->
                snapshots.reduce { acc, item ->
                    val mergedSongs = (acc.songs + item.songs).distinctBy { it.identity() }
                    val preferred = when {
                        item.songs.isNotEmpty() && acc.songs.isEmpty() -> item
                        acc.songs.isNotEmpty() && item.songs.isEmpty() -> acc
                        item.addedTime >= acc.addedTime -> item
                        else -> acc
                    }
                    preferred.copy(
                        trackCount = maxOf(acc.trackCount, item.trackCount, mergedSongs.size),
                        songs = mergedSongs
                    )
                }
            }

        val mergedData = SyncData(
            deviceId = local.deviceId,
            deviceName = local.deviceName,
            lastModified = System.currentTimeMillis(),
            playlists = mergedPlaylists,
            favoritePlaylists = mergedFavoritePlaylists,
            recentPlays = mergeRecentPlays(local.recentPlays, remote.recentPlays),
            syncLog = (local.syncLog + remote.syncLog)
                .distinctBy { it.timestamp }
                .sortedByDescending { it.timestamp }
                .take(100)
        )

        return MergeResult(
            mergedData = mergedData,
            syncResult = SyncResult(
                success = true,
                message = localizedContext.getString(R.string.github_sync_success_detail),
                playlistsAdded = playlistsAdded,
                playlistsUpdated = playlistsUpdated,
                playlistsDeleted = playlistsDeleted,
                songsAdded = songsAdded,
                songsRemoved = songsRemoved,
                conflicts = conflicts
            )
        )
    }

    private fun mergePlaylist(
        local: SyncPlaylist,
        remote: SyncPlaylist,
        isFirstSync: Boolean
    ): PlaylistMergeResult {
        val localizedContext = LanguageManager.applyLanguage(context)
        var conflict: SyncConflict? = null
        var hasConflict = false
        var isUpdated = false

        val isFavorites = local.id == FavoritesPlaylist.SYSTEM_ID || remote.id == FavoritesPlaylist.SYSTEM_ID
        val finalName = when {
            isFavorites -> FavoritesPlaylist.currentName(localizedContext)
            local.name == remote.name -> local.name
            local.modifiedAt >= remote.modifiedAt -> {
                hasConflict = true
                conflict = SyncConflict(
                    type = ConflictType.PLAYLIST_RENAMED_BOTH_SIDES,
                    playlistId = local.id,
                    playlistName = local.name,
                    description = localizedContext.getString(R.string.github_playlist_renamed_local, local.name),
                    resolution = ConflictResolution.LOCAL_WINS
                )
                local.name
            }
            else -> {
                hasConflict = true
                isUpdated = true
                conflict = SyncConflict(
                    type = ConflictType.PLAYLIST_RENAMED_BOTH_SIDES,
                    playlistId = remote.id,
                    playlistName = remote.name,
                    description = localizedContext.getString(R.string.github_playlist_renamed_remote, remote.name),
                    resolution = ConflictResolution.REMOTE_WINS
                )
                remote.name
            }
        }

        val localSongs = local.songs.map { it.identity() }.toSet()
        val remoteSongs = remote.songs.map { it.identity() }.toSet()
        val preferRemoteFavorites = isFavorites && localSongs.isEmpty() && remoteSongs.isNotEmpty()

        val mergedSongs = when {
            remoteSongs.isEmpty() && localSongs.isNotEmpty() -> local.songs
            localSongs.isEmpty() && remoteSongs.isNotEmpty() -> {
                isUpdated = true
                remote.songs
            }
            preferRemoteFavorites || (isFirstSync && remoteSongs.isNotEmpty()) -> {
                isUpdated = true
                remote.songs
            }
            remote.modifiedAt > local.modifiedAt -> {
                isUpdated = true
                remote.songs
            }
            else -> local.songs
        }

        val songsAdded = (remoteSongs - localSongs).size
        val songsRemoved = (localSongs - remoteSongs).size

        return PlaylistMergeResult(
            playlist = SyncPlaylist(
                id = if (isFavorites) FavoritesPlaylist.SYSTEM_ID else local.id,
                name = finalName,
                songs = mergedSongs,
                createdAt = minOf(local.createdAt, remote.createdAt),
                modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt)
            ),
            hasConflict = hasConflict,
            conflict = conflict,
            songsAdded = songsAdded,
            songsRemoved = songsRemoved,
            isUpdated = isUpdated
        )
    }

    private fun mergeRecentPlays(local: List<SyncRecentPlay>, remote: List<SyncRecentPlay>): List<SyncRecentPlay> {
        return (local + remote)
            .distinctBy { "${it.song.identity()}|${it.playedAt}|${it.deviceId}" }
            .sortedByDescending { it.playedAt }
            .take(500)
    }

    private suspend fun applyMergedDataToLocal(mergedData: SyncData, remoteHasChanged: Boolean) {
        val localizedContext = LanguageManager.applyLanguage(context)
        val mergedLocalPlaylists = mergedData.playlists.map { syncPlaylist ->
            val isFavorites = syncPlaylist.id == FavoritesPlaylist.SYSTEM_ID ||
                FavoritesPlaylist.matches(syncPlaylist.name, localizedContext)
            syncPlaylist.toLocalPlaylist().copy(
                id = if (isFavorites) FavoritesPlaylist.SYSTEM_ID else syncPlaylist.id,
                name = if (isFavorites) FavoritesPlaylist.currentName(localizedContext) else syncPlaylist.name,
                modifiedAt = syncPlaylist.modifiedAt
            )
        }
        playlistRepo.updatePlaylists(mergedLocalPlaylists)

        for (favorite in mergedData.favoritePlaylists.map { it.toFavoritePlaylist() }) {
            favoriteRepo.addFavorite(
                id = favorite.id,
                name = favorite.name,
                coverUrl = favorite.coverUrl,
                trackCount = favorite.trackCount,
                source = favorite.source,
                songs = favorite.songs
            )
        }

        val localPlayHistoryEmpty = playHistoryRepo.historyFlow.value.isEmpty()
        val shouldApplyRemoteHistory = remoteHasChanged ||
            (localPlayHistoryEmpty && mergedData.recentPlays.isNotEmpty())

        if (shouldApplyRemoteHistory) {
            val playHistory = mergedData.recentPlays.map { syncPlay ->
                PlayedEntry(
                    id = syncPlay.song.id,
                    name = syncPlay.song.name,
                    artist = syncPlay.song.artist,
                    album = syncPlay.song.album,
                    albumId = syncPlay.song.albumId,
                    durationMs = syncPlay.song.durationMs,
                    coverUrl = syncPlay.song.coverUrl,
                    mediaUri = syncPlay.song.mediaUri,
                    playedAt = syncPlay.playedAt
                )
            }
            playHistoryRepo.updateHistory(playHistory)
        }
    }

    private fun hasDataChanged(remote: SyncData, merged: SyncData): Boolean {
        if (remote.playlists.size != merged.playlists.size) return true

        val remotePlaylistMap = remote.playlists.associateBy { it.id }
        for (mergedPlaylist in merged.playlists) {
            val remotePlaylist = remotePlaylistMap[mergedPlaylist.id] ?: return true
            if (remotePlaylist.name != mergedPlaylist.name) return true
            if (remotePlaylist.songs.size != mergedPlaylist.songs.size) return true
            if (remotePlaylist.songs.map { it.identity() } != mergedPlaylist.songs.map { it.identity() }) {
                return true
            }
        }

        if (remote.favoritePlaylists.size != merged.favoritePlaylists.size) return true
        val remoteFavoriteKeys = remote.favoritePlaylists.map { "${it.id}_${it.source}" }.toSet()
        val mergedFavoriteKeys = merged.favoritePlaylists.map { "${it.id}_${it.source}" }.toSet()
        if (remoteFavoriteKeys != mergedFavoriteKeys) return true

        val remoteRecent = remote.recentPlays.take(50)
        val mergedRecent = merged.recentPlays.take(50)
        if (remoteRecent.size != mergedRecent.size) return true
        for (i in remoteRecent.indices) {
            if (remoteRecent[i].song.identity() != mergedRecent[i].song.identity()) return true
            if (remoteRecent[i].playedAt != mergedRecent[i].playedAt) return true
        }
        return false
    }

    private suspend fun uploadLocalData(
        apiClient: GitHubApiClient,
        owner: String,
        repo: String,
        data: SyncData,
        sha: String?,
        fileName: String
    ): Result<String> {
        val localizedContext = LanguageManager.applyLanguage(context)
        val useDataSaver = storage.isDataSaverMode()
        val content = SyncDataSerializer.serialize(data, useDataSaver)
        NPLogger.d(
            TAG,
            "Upload data size: ${SyncDataSerializer.getDataSize(data, useDataSaver)} bytes (DataSaver: $useDataSaver, File: $fileName)"
        )

        val uploadResult = apiClient.updateFileContent(owner, repo, content, sha, fileName)
        return if (uploadResult.isSuccess) {
            Result.success(uploadResult.getOrNull().orEmpty())
        } else {
            Result.failure(
                uploadResult.exceptionOrNull()
                    ?: Exception(localizedContext.getString(R.string.sync_upload_failed))
            )
        }
    }

    private fun getDeviceId(): String {
        var deviceId = storage.getDeviceId()
        if (deviceId == null) {
            deviceId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: UUID.randomUUID().toString()
            } catch (_: Exception) {
                UUID.randomUUID().toString()
            }
            storage.saveDeviceId(deviceId)
        }
        return deviceId
    }

    private fun getDeviceName(): String {
        return try {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (_: Exception) {
            "Unknown Device"
        }
    }

    private data class MergeResult(
        val mergedData: SyncData,
        val syncResult: SyncResult
    )

    private data class PlaylistMergeResult(
        val playlist: SyncPlaylist,
        val hasConflict: Boolean,
        val conflict: SyncConflict?,
        val songsAdded: Int,
        val songsRemoved: Int,
        val isUpdated: Boolean
    )
}
