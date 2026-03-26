package moe.ouom.neriplayer.data.github

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.FavoritesPlaylist
import moe.ouom.neriplayer.data.LocalFilesPlaylist
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.LocalSongSupport
import moe.ouom.neriplayer.data.PlayedEntry
import moe.ouom.neriplayer.data.PlayHistoryRepository
import moe.ouom.neriplayer.data.SystemLocalPlaylists
import moe.ouom.neriplayer.data.identity
import moe.ouom.neriplayer.data.stableKey
import moe.ouom.neriplayer.util.LanguageManager
import moe.ouom.neriplayer.util.NPLogger
import java.io.IOException

class GitHubSyncManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val storage = SecureTokenStorage(appContext)
    private val playlistRepo = LocalPlaylistRepository.getInstance(appContext)
    private val favoriteRepo = FavoritePlaylistRepository.getInstance(appContext)
    private val playHistoryRepo = PlayHistoryRepository.getInstance(appContext)
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
        val localizedContext = LanguageManager.applyLanguage(appContext)

        if (!syncLock.tryLock()) {
            return@withContext Result.failure(
                GitHubSyncInProgressException(
                    localizedContext.getString(R.string.github_sync_in_progress)
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

            val apiClient = GitHubApiClient(appContext, token)
            val startMutationVersion = storage.getSyncMutationVersion()
            val localData = sanitizeSyncData(buildLocalSyncData(localizedContext))
            val uploadedDeletedPlaylistIds = localData.playlists
                .asSequence()
                .filter(SyncPlaylist::isDeleted)
                .map(SyncPlaylist::id)
                .toSet()
            val useDataSaver = storage.isDataSaverMode()
            val preferredFileName = SyncDataSerializer.getFileName(useDataSaver)

            var remoteResult = apiClient.getFileContentStrict(owner, repo, preferredFileName)
            var actualFileName = preferredFileName
            if (remoteResult.exceptionOrNull() is GitHubFileNotFoundException) {
                val alternativeFileName = SyncDataSerializer.getFileName(!useDataSaver)
                val alternativeResult = apiClient.getFileContentStrict(owner, repo, alternativeFileName)
                if (alternativeResult.isSuccess) {
                    remoteResult = alternativeResult
                    actualFileName = alternativeFileName
                } else {
                    val alternativeError = alternativeResult.exceptionOrNull()
                    if (alternativeError !is GitHubFileNotFoundException) {
                        if (alternativeError is TokenExpiredException) {
                            storage.clearToken()
                        }
                        return@withContext Result.failure(
                            alternativeError ?: IOException(localizedContext.getString(R.string.github_sync_failed_message))
                        )
                    }
                }
            }

            if (remoteResult.isFailure) {
                val error = remoteResult.exceptionOrNull()
                if (error is TokenExpiredException) {
                    storage.clearToken()
                    return@withContext Result.failure(error)
                }
                if (error is GitHubFileNotFoundException) {
                    return@withContext handleInitialUpload(
                        apiClient = apiClient,
                        owner = owner,
                        repo = repo,
                        localData = localData,
                        sha = null,
                        fileName = preferredFileName,
                        localizedContext = localizedContext,
                        startMutationVersion = startMutationVersion,
                        uploadedDeletedPlaylistIds = uploadedDeletedPlaylistIds
                    )
                }
                return@withContext Result.failure(
                    error ?: IOException(localizedContext.getString(R.string.github_sync_failed_message))
                )
            }

            val (remoteContent, remoteSha) = remoteResult.getOrThrow()
            var actualRemoteSha = remoteSha
            if (remoteContent.isEmpty()) {
                return@withContext Result.failure(
                    IOException(localizedContext.getString(R.string.github_backup_file_invalid))
                )
            }

            val remoteData = try {
                sanitizeSyncData(
                    SyncDataSerializer.deserialize(
                    remoteContent,
                    SyncDataSerializer.isBinaryFileName(actualFileName)
                    )
                )
            } catch (e: Exception) {
                // 解析失败时尝试另一种格式，再不行才报错
                val alternativeFileName = SyncDataSerializer.getFileName(!useDataSaver)
                val fallbackResult = if (alternativeFileName != actualFileName) {
                    apiClient.getFileContentStrict(owner, repo, alternativeFileName).getOrNull()
                } else null

                val fallbackContent = fallbackResult?.first
                val fallbackSha = fallbackResult?.second
                val parsedFallback = if (!fallbackContent.isNullOrEmpty()) {
                    runCatching {
                        sanitizeSyncData(
                            SyncDataSerializer.deserialize(
                                fallbackContent,
                                SyncDataSerializer.isBinaryFileName(alternativeFileName)
                            )
                        )
                    }.getOrNull()
                } else null

                if (parsedFallback != null) {
                    actualFileName = alternativeFileName
                    if (!fallbackSha.isNullOrBlank()) {
                        actualRemoteSha = fallbackSha
                    }
                    parsedFallback
                } else {
                    NPLogger.e(TAG, "Failed to parse remote data", e)
                    return@withContext Result.failure(e)
                }
            }

            val lastRemoteSha = storage.getLastRemoteSha()
            val isFirstSync = lastRemoteSha == null
            val remoteHasChanged = lastRemoteSha != null && lastRemoteSha != actualRemoteSha
            val lastSyncTime = storage.getLastSyncTime()
            val mergeResult = performThreeWayMerge(localData, remoteData, lastSyncTime)
            val localMutatedDuringSync =
                storage.getSyncMutationVersion() != startMutationVersion

            if (!localMutatedDuringSync) {
                applyMergedDataToLocal(
                    mergedData = mergeResult.mergedData,
                    remoteHasChanged = isFirstSync || remoteHasChanged
                )
            } else {
                NPLogger.w(TAG, "Skip applying merged sync data because local state changed during sync")
            }

            if (!hasDataChanged(remoteData, mergeResult.mergedData) && !remoteHasChanged) {
                storage.saveLastRemoteSha(actualRemoteSha)
                storage.saveLastSyncTime(System.currentTimeMillis())
                if (localMutatedDuringSync) {
                    GitHubSyncWorker.scheduleDelayedSync(
                        appContext,
                        triggerByUserAction = false,
                        markMutation = false
                    )
                }
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
                sha = actualRemoteSha,
                fileName = actualFileName
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
            storage.removeDeletedPlaylistIds(uploadedDeletedPlaylistIds)
            if (localMutatedDuringSync) {
                GitHubSyncWorker.scheduleDelayedSync(
                    appContext,
                    triggerByUserAction = false,
                    markMutation = false
                )
            }
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
        localizedContext: Context,
        startMutationVersion: Long,
        uploadedDeletedPlaylistIds: Set<Long>
    ): Result<SyncResult> {
        val uploadResult = uploadLocalData(apiClient, owner, repo, localData, sha, fileName)
        if (uploadResult.isSuccess) {
            uploadResult.getOrNull()?.let { storage.saveLastRemoteSha(it) }
            storage.saveLastSyncTime(System.currentTimeMillis())
            storage.removeDeletedPlaylistIds(uploadedDeletedPlaylistIds)
            if (storage.getSyncMutationVersion() != startMutationVersion) {
                GitHubSyncWorker.scheduleDelayedSync(
                    appContext,
                    triggerByUserAction = false,
                    markMutation = false
                )
            }
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
        return Result.failure(
            error ?: IOException(localizedContext.getString(R.string.sync_upload_failed))
        )
    }

    private fun buildLocalSyncData(localizedContext: Context): SyncData {
        val playlists = playlistRepo.playlists.value
        val syncPlaylists = playlists.map { playlist ->
            SyncPlaylist.fromLocalPlaylist(playlist, playlist.modifiedAt, localizedContext)
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

        val syncFavoritePlaylists = favoriteRepo.getSyncSnapshots().map {
            SyncFavoritePlaylist.fromFavoritePlaylist(it, localizedContext)
        }

        val syncRecentPlays = playHistoryRepo.historyFlow.value
            .filterNot { LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext) }
            .take(500)
            .map { playedEntry ->
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
                        mediaUri = LocalSongSupport.sanitizeMediaUriForSync(playedEntry.mediaUri),
                        matchedLyric = playedEntry.matchedLyric,
                        matchedTranslatedLyric = playedEntry.matchedTranslatedLyric,
                        customCoverUrl = playedEntry.customCoverUrl,
                        customName = playedEntry.customName,
                        customArtist = playedEntry.customArtist,
                        originalName = playedEntry.originalName,
                        originalArtist = playedEntry.originalArtist,
                        originalCoverUrl = playedEntry.originalCoverUrl,
                        originalLyric = playedEntry.originalLyric,
                        originalTranslatedLyric = playedEntry.originalTranslatedLyric
                    ),
                    playedAt = playedEntry.playedAt,
                    deviceId = getDeviceId()
                )
            }
        val syncRecentPlayDeletions = storage.getRecentPlayDeletions()
            .map {
                it.copy(mediaUri = LocalSongSupport.sanitizeMediaUriForSync(it.mediaUri))
            }

        return SyncData(
            deviceId = getDeviceId(),
            deviceName = getDeviceName(),
            lastModified = System.currentTimeMillis(),
            playlists = syncPlaylists,
            favoritePlaylists = syncFavoritePlaylists,
            recentPlays = syncRecentPlays,
            syncLog = emptyList(),
            recentPlayDeletions = syncRecentPlayDeletions
        )
    }

    private fun performThreeWayMerge(
        local: SyncData,
        remote: SyncData,
        lastSyncTime: Long
    ): MergeResult {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        val conflicts = mutableListOf<SyncConflict>()
        var playlistsAdded = 0
        var playlistsUpdated = 0
        var playlistsDeleted = 0
        var songsAdded = 0
        var songsRemoved = 0

        val mergedPlaylistsById = linkedMapOf<Long, SyncPlaylist>()
        val localPlaylistsMap = local.playlists.associateBy { it.id }
        val remotePlaylistsMap = remote.playlists.associateBy { it.id }
        val allPlaylistIds = (localPlaylistsMap.keys + remotePlaylistsMap.keys).toSet()

        for (playlistId in allPlaylistIds) {
            val localPlaylist = localPlaylistsMap[playlistId]
            val remotePlaylist = remotePlaylistsMap[playlistId]
            when {
                localPlaylist != null && remotePlaylist == null -> {
                    if (!localPlaylist.isDeleted) {
                        mergedPlaylistsById[localPlaylist.id] = localPlaylist
                        playlistsAdded++
                    } else {
                        playlistsDeleted++
                    }
                }

                localPlaylist == null && remotePlaylist != null -> {
                    if (!remotePlaylist.isDeleted) {
                        mergedPlaylistsById[remotePlaylist.id] = remotePlaylist
                        playlistsAdded++
                    } else {
                        playlistsDeleted++
                    }
                }

                localPlaylist != null && remotePlaylist != null -> {
                    if (localPlaylist.isDeleted || remotePlaylist.isDeleted) {
                        playlistsDeleted++
                    } else {
                        val merged = mergePlaylist(localPlaylist, remotePlaylist, lastSyncTime)
                        mergedPlaylistsById[merged.playlist.id] = merged.playlist
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
                snapshots.reduce(::mergeFavoritePlaylist)
            }
            .sortedByDescending { it.sortOrder }

        val mergedRecentPlayDeletions = pruneRecentPlayDeletions(
            mergeRecentPlayDeletions(local.recentPlayDeletions, remote.recentPlayDeletions),
            local.recentPlays + remote.recentPlays
        )
        val mergedPlaylists = orderMergedPlaylists(
            local = local.playlists,
            remote = remote.playlists,
            mergedById = mergedPlaylistsById,
            lastSyncTime = lastSyncTime
        )
        val mergedRecentPlays = mergeRecentPlays(
            local = local.recentPlays,
            remote = remote.recentPlays,
            deletions = mergedRecentPlayDeletions
        )

        val mergedData = SyncData(
            deviceId = local.deviceId,
            deviceName = local.deviceName,
            lastModified = System.currentTimeMillis(),
            playlists = mergedPlaylists,
            favoritePlaylists = mergedFavoritePlaylists,
            recentPlays = mergedRecentPlays,
            syncLog = (local.syncLog + remote.syncLog)
                .distinctBy { it.timestamp }
                .sortedByDescending { it.timestamp }
                .take(100),
            recentPlayDeletions = mergedRecentPlayDeletions
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

    private fun orderMergedPlaylists(
        local: List<SyncPlaylist>,
        remote: List<SyncPlaylist>,
        mergedById: Map<Long, SyncPlaylist>,
        lastSyncTime: Long
    ): List<SyncPlaylist> {
        if (mergedById.isEmpty()) return emptyList()

        val localChangedAfterSync = hasPlaylistCollectionChangedAfterSync(local, lastSyncTime)
        val remoteChangedAfterSync = hasPlaylistCollectionChangedAfterSync(remote, lastSyncTime)
        val primary = if (remoteChangedAfterSync && !localChangedAfterSync) remote else local
        val secondary = if (primary === local) remote else local
        val orderedIds = LinkedHashSet<Long>()

        fun appendPlaylistIds(source: List<SyncPlaylist>) {
            source.asSequence()
                .filterNot(SyncPlaylist::isDeleted)
                .map(SyncPlaylist::id)
                .filter(mergedById::containsKey)
                .forEach(orderedIds::add)
        }

        appendPlaylistIds(primary)
        appendPlaylistIds(secondary)
        mergedById.keys.forEach(orderedIds::add)

        return orderedIds.mapNotNull(mergedById::get)
    }

    private fun hasPlaylistCollectionChangedAfterSync(
        playlists: List<SyncPlaylist>,
        lastSyncTime: Long
    ): Boolean {
        return playlists.any { it.modifiedAt > lastSyncTime }
    }

    private fun mergePlaylist(
        local: SyncPlaylist,
        remote: SyncPlaylist,
        lastSyncTime: Long
    ): PlaylistMergeResult {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        var conflict: SyncConflict? = null
        var hasConflict = false
        var isUpdated = false

        val systemDescriptor = SystemLocalPlaylists.resolve(local.id, local.name, localizedContext)
            ?: SystemLocalPlaylists.resolve(remote.id, remote.name, localizedContext)
        val isFavorites = systemDescriptor?.id == FavoritesPlaylist.SYSTEM_ID
        val localChangedAfterSync = local.modifiedAt > lastSyncTime
        val remoteChangedAfterSync = remote.modifiedAt > lastSyncTime

        val finalName = when {
            systemDescriptor != null -> systemDescriptor.currentName
            local.name == remote.name -> local.name
            remoteChangedAfterSync && !localChangedAfterSync -> {
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
            localChangedAfterSync && !remoteChangedAfterSync -> {
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
                conflict = SyncConflict(
                    type = ConflictType.PLAYLIST_RENAMED_BOTH_SIDES,
                    playlistId = local.id,
                    playlistName = local.name,
                    description = localizedContext.getString(R.string.github_playlist_renamed_local, local.name),
                    resolution = ConflictResolution.MANUAL_REQUIRED
                )
                local.name
            }
        }

        val localSongs = local.songs.map { it.identity() }.toSet()
        val remoteSongs = remote.songs.map { it.identity() }.toSet()
        val preferRemoteFavorites = isFavorites && localSongs.isEmpty() && remoteSongs.isNotEmpty()

        fun mergeSongsPreservingLocal(
            localList: List<SyncSong>,
            remoteList: List<SyncSong>
        ): List<SyncSong> {
            val merged = localList.toMutableList()
            val known = localList.map { it.identity() }.toMutableSet()
            remoteList.forEach { song ->
                if (known.add(song.identity())) {
                    merged += song
                }
            }
            return merged
        }

        val mergedSongs = when {
            remoteSongs.isEmpty() && localSongs.isNotEmpty() -> local.songs
            localSongs.isEmpty() && remoteSongs.isNotEmpty() -> {
                isUpdated = true
                remote.songs
            }
            preferRemoteFavorites && !localChangedAfterSync -> {
                isUpdated = true
                remote.songs
            }
            remoteChangedAfterSync && !localChangedAfterSync -> {
                isUpdated = true
                remote.songs
            }
            localChangedAfterSync && !remoteChangedAfterSync -> local.songs
            else -> {
                val merged = mergeSongsPreservingLocal(local.songs, remote.songs)
                if (merged.size != local.songs.size || merged.size != remote.songs.size) {
                    isUpdated = true
                }
                merged
            }
        }

        val mergedIdentities = mergedSongs.map { it.identity() }.toSet()
        val songsAdded = (mergedIdentities - localSongs).size
        val songsRemoved = (localSongs - mergedIdentities).size

        return PlaylistMergeResult(
            playlist = SyncPlaylist(
                id = systemDescriptor?.id ?: local.id,
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

    private fun mergeRecentPlays(
        local: List<SyncRecentPlay>,
        remote: List<SyncRecentPlay>,
        deletions: List<SyncRecentPlayDeletion>
    ): List<SyncRecentPlay> {
        val deletionByIdentity = deletions.associateBy { it.identity().stableKey() }
        return (local + remote)
            .sortedByDescending { it.playedAt }
            .distinctBy { it.song.identity().stableKey() }
            .filter { recentPlay ->
                val deletion = deletionByIdentity[recentPlay.song.identity().stableKey()]
                deletion == null || recentPlay.playedAt > deletion.deletedAt
            }
            .take(500)
    }

    private fun mergeRecentPlayDeletions(
        local: List<SyncRecentPlayDeletion>,
        remote: List<SyncRecentPlayDeletion>
    ): List<SyncRecentPlayDeletion> {
        return (local + remote)
            .groupBy { it.identity().stableKey() }
            .mapNotNull { (_, snapshots) ->
                snapshots.maxWithOrNull(
                    compareBy<SyncRecentPlayDeletion> { it.deletedAt }
                        .thenBy { it.deviceId }
                )
            }
            .sortedByDescending { it.deletedAt }
            .take(500)
    }

    private fun pruneRecentPlayDeletions(
        deletions: List<SyncRecentPlayDeletion>,
        recentPlays: List<SyncRecentPlay>
    ): List<SyncRecentPlayDeletion> {
        val latestPlayByIdentity = recentPlays
            .groupBy { it.song.identity().stableKey() }
            .mapValues { (_, plays) -> plays.maxOf { it.playedAt } }
        return deletions
            .filter { deletion ->
                val latestPlay = latestPlayByIdentity[deletion.identity().stableKey()]
                latestPlay == null || latestPlay <= deletion.deletedAt
            }
            .sortedByDescending { it.deletedAt }
            .take(500)
    }

    private fun mergeFavoritePlaylist(
        left: SyncFavoritePlaylist,
        right: SyncFavoritePlaylist
    ): SyncFavoritePlaylist {
        val newer = if (right.modifiedAt > left.modifiedAt) right else left
        val older = if (newer === left) right else left

        if (left.isDeleted != right.isDeleted) {
            return if (left.modifiedAt == right.modifiedAt) {
                newer.copy(
                    songs = if (newer.isDeleted) emptyList() else (left.songs + right.songs).distinctBy { it.identity() },
                    trackCount = if (newer.isDeleted) 0 else maxOf(left.trackCount, right.trackCount, left.songs.size, right.songs.size)
                )
            } else {
                if (newer.isDeleted) {
                    newer.copy(
                        songs = emptyList(),
                        trackCount = 0,
                        sortOrder = maxOf(left.sortOrder, right.sortOrder)
                    )
                } else {
                    newer.copy(
                        songs = (left.songs + right.songs).distinctBy { it.identity() },
                        trackCount = maxOf(left.trackCount, right.trackCount, left.songs.size, right.songs.size),
                        sortOrder = newer.sortOrder.takeIf { it > 0L } ?: older.sortOrder
                    )
                }
            }
        }

        if (newer.isDeleted) {
            return newer.copy(
                songs = emptyList(),
                trackCount = 0,
                addedTime = maxOf(left.addedTime, right.addedTime),
                sortOrder = maxOf(left.sortOrder, right.sortOrder)
            )
        }

        val mergedSongs = (left.songs + right.songs).distinctBy { it.identity() }
        return newer.copy(
            coverUrl = newer.coverUrl ?: older.coverUrl,
            songs = mergedSongs,
            trackCount = maxOf(left.trackCount, right.trackCount, mergedSongs.size),
            addedTime = maxOf(left.addedTime, right.addedTime),
            modifiedAt = maxOf(left.modifiedAt, right.modifiedAt),
            sortOrder = newer.sortOrder.takeIf { it > 0L } ?: older.sortOrder,
            isDeleted = false
        )
    }

    private suspend fun applyMergedDataToLocal(mergedData: SyncData, remoteHasChanged: Boolean) {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        val sanitizedMergedData = sanitizeSyncData(mergedData)
        val currentPlaylists = playlistRepo.playlists.value.associateBy { playlist ->
            SystemLocalPlaylists.resolve(playlist.id, playlist.name, localizedContext)?.id ?: playlist.id
        }
        val mergedLocalPlaylists = sanitizedMergedData.playlists.map { syncPlaylist ->
            val systemDescriptor = SystemLocalPlaylists.resolve(
                syncPlaylist.id,
                syncPlaylist.name,
                localizedContext
            )
            val normalizedId = systemDescriptor?.id ?: syncPlaylist.id
            val syncedSongs = syncPlaylist.songs.map { it.toSongItem() }
            val preservedLocalSongs = currentPlaylists[normalizedId]
                ?.songs
                .orEmpty()
                .filter { LocalSongSupport.isLocalSong(it, localizedContext) }

            LocalPlaylist(
                id = normalizedId,
                name = systemDescriptor?.currentName ?: syncPlaylist.name,
                songs = mergeLocalOnlySongs(syncedSongs, preservedLocalSongs),
                modifiedAt = syncPlaylist.modifiedAt,
                customCoverUrl = currentPlaylists[normalizedId]?.customCoverUrl
            )
        }
        playlistRepo.updatePlaylists(mergedLocalPlaylists)

        favoriteRepo.replaceFavoritesFromSync(
            sanitizedMergedData.favoritePlaylists.map { it.toFavoritePlaylist() }
        )
        storage.setRecentPlayDeletions(sanitizedMergedData.recentPlayDeletions)

        val localPlayHistoryEmpty = playHistoryRepo.historyFlow.value.isEmpty()
        val shouldApplyRemoteHistory = remoteHasChanged ||
            (localPlayHistoryEmpty && sanitizedMergedData.recentPlays.isNotEmpty())

        if (shouldApplyRemoteHistory) {
            val syncedHistory = sanitizedMergedData.recentPlays.mapNotNull { syncPlay ->
                if (LocalSongSupport.isLocalSong(syncPlay.song.album, syncPlay.song.mediaUri, syncPlay.song.albumId, localizedContext)) {
                    return@mapNotNull null
                }

                PlayedEntry(
                    id = syncPlay.song.id,
                    name = syncPlay.song.name,
                    artist = syncPlay.song.artist,
                    album = syncPlay.song.album,
                    albumId = syncPlay.song.albumId,
                    durationMs = syncPlay.song.durationMs,
                    coverUrl = syncPlay.song.coverUrl,
                    mediaUri = LocalSongSupport.sanitizeMediaUriForSync(syncPlay.song.mediaUri),
                    matchedLyric = syncPlay.song.matchedLyric,
                    matchedTranslatedLyric = syncPlay.song.matchedTranslatedLyric,
                    customCoverUrl = syncPlay.song.customCoverUrl,
                    customName = syncPlay.song.customName,
                    customArtist = syncPlay.song.customArtist,
                    originalName = syncPlay.song.originalName,
                    originalArtist = syncPlay.song.originalArtist,
                    originalCoverUrl = syncPlay.song.originalCoverUrl,
                    originalLyric = syncPlay.song.originalLyric,
                    originalTranslatedLyric = syncPlay.song.originalTranslatedLyric,
                    playedAt = syncPlay.playedAt
                )
            }
            val localOnlyHistory = playHistoryRepo.historyFlow.value.filter {
                LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext)
            }
            val playHistory = mergeLocalOnlyHistory(syncedHistory, localOnlyHistory)
            playHistoryRepo.updateHistory(playHistory)
        }
    }

    private fun mergeLocalOnlySongs(
        syncedSongs: List<moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem>,
        localOnlySongs: List<moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem>
    ): MutableList<moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem> {
        val merged = syncedSongs.toMutableList()
        val knownIdentities = merged.map { it.identity() }.toMutableSet()
        localOnlySongs.forEach { song ->
            if (knownIdentities.add(song.identity())) {
                merged += song
            }
        }
        return merged
    }

    private fun mergeLocalOnlyHistory(
        syncedHistory: List<PlayedEntry>,
        localOnlyHistory: List<PlayedEntry>
    ): List<PlayedEntry> {
        return (syncedHistory + localOnlyHistory)
            .distinctBy { "${it.id}|${it.album}|${it.mediaUri.orEmpty()}|${it.playedAt}" }
            .sortedByDescending { it.playedAt }
            .take(500)
    }

    private fun sanitizeSyncData(data: SyncData): SyncData {
        return data.copy(
            playlists = data.playlists.mapNotNull { sanitizeSyncPlaylist(it) },
            favoritePlaylists = data.favoritePlaylists.map { sanitizeSyncFavoritePlaylist(it) },
            recentPlays = data.recentPlays.mapNotNull { sanitizeRecentPlay(it) },
            recentPlayDeletions = data.recentPlayDeletions.mapNotNull { sanitizeRecentPlayDeletion(it) }
        )
    }

    private fun sanitizeSyncPlaylist(playlist: SyncPlaylist): SyncPlaylist? {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        val systemDescriptor = SystemLocalPlaylists.resolve(playlist.id, playlist.name, localizedContext)
        if (systemDescriptor?.id == LocalFilesPlaylist.SYSTEM_ID) {
            return null
        }
        return playlist.copy(
            id = systemDescriptor?.id ?: playlist.id,
            name = systemDescriptor?.currentName ?: playlist.name,
            songs = playlist.songs.mapNotNull { sanitizeSyncSong(it) }
        )
    }

    private fun sanitizeSyncFavoritePlaylist(playlist: SyncFavoritePlaylist): SyncFavoritePlaylist {
        return playlist.copy(
            songs = if (playlist.isDeleted) {
                emptyList()
            } else {
                playlist.songs.mapNotNull { sanitizeSyncSong(it) }
            },
            trackCount = if (playlist.isDeleted) 0 else playlist.trackCount
        )
    }

    private fun sanitizeRecentPlay(play: SyncRecentPlay): SyncRecentPlay? {
        val sanitizedSong = sanitizeSyncSong(play.song) ?: return null
        return play.copy(songId = sanitizedSong.id, song = sanitizedSong)
    }

    private fun sanitizeRecentPlayDeletion(
        deletion: SyncRecentPlayDeletion
    ): SyncRecentPlayDeletion? {
        if (LocalSongSupport.isLocalSong(deletion.album, deletion.mediaUri, 0L, appContext)) {
            return null
        }
        return deletion.copy(mediaUri = LocalSongSupport.sanitizeMediaUriForSync(deletion.mediaUri))
    }

    private fun sanitizeSyncSong(song: SyncSong): SyncSong? {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        if (LocalSongSupport.isLocalSong(song.album, song.mediaUri, song.albumId, localizedContext)) {
            return null
        }
        return song.copy(mediaUri = LocalSongSupport.sanitizeMediaUriForSync(song.mediaUri))
    }

    private fun hasDataChanged(remote: SyncData, merged: SyncData): Boolean {
        if (remote.playlists.size != merged.playlists.size) return true
        if (remote.playlists.map(SyncPlaylist::id) != merged.playlists.map(SyncPlaylist::id)) return true

        val remotePlaylistMap = remote.playlists.associateBy { it.id }
        for (mergedPlaylist in merged.playlists) {
            val remotePlaylist = remotePlaylistMap[mergedPlaylist.id] ?: return true
            if (remotePlaylist.name != mergedPlaylist.name) return true
            if (remotePlaylist.songs.size != mergedPlaylist.songs.size) return true
            if (remotePlaylist.songs.map { it.identity() } != mergedPlaylist.songs.map { it.identity() }) return true
            for (i in remotePlaylist.songs.indices) {
                val remoteSong = remotePlaylist.songs[i]
                val mergedSong = mergedPlaylist.songs[i]
                if (!sameSongMetadata(remoteSong, mergedSong)) return true
            }
        }

        if (remote.favoritePlaylists.size != merged.favoritePlaylists.size) return true
        val remoteFavoriteMap = remote.favoritePlaylists.associateBy { "${it.id}_${it.source}" }
        val mergedFavoriteMap = merged.favoritePlaylists.associateBy { "${it.id}_${it.source}" }
        if (remoteFavoriteMap.keys != mergedFavoriteMap.keys) return true
        remoteFavoriteMap.forEach { (key, remoteFavorite) ->
            val mergedFavorite = mergedFavoriteMap[key] ?: return true
            if (remoteFavorite.isDeleted != mergedFavorite.isDeleted) return true
            if (remoteFavorite.modifiedAt != mergedFavorite.modifiedAt) return true
            if (remoteFavorite.sortOrder != mergedFavorite.sortOrder) return true
            if (remoteFavorite.trackCount != mergedFavorite.trackCount) return true
            if (remoteFavorite.songs.map { it.identity() } != mergedFavorite.songs.map { it.identity() }) return true
            for (i in remoteFavorite.songs.indices) {
                val remoteSong = remoteFavorite.songs[i]
                val mergedSong = mergedFavorite.songs[i]
                if (!sameSongMetadata(remoteSong, mergedSong)) return true
            }
        }

        val remoteRecent = remote.recentPlays.take(50)
        val mergedRecent = merged.recentPlays.take(50)
        if (remoteRecent.size != mergedRecent.size) return true
        for (i in remoteRecent.indices) {
            if (remoteRecent[i].song.identity() != mergedRecent[i].song.identity()) return true
            if (!sameSongMetadata(remoteRecent[i].song, mergedRecent[i].song)) return true
            if (remoteRecent[i].playedAt != mergedRecent[i].playedAt) return true
        }

        val remoteRecentDeletions = remote.recentPlayDeletions.take(100)
        val mergedRecentDeletions = merged.recentPlayDeletions.take(100)
        if (remoteRecentDeletions.size != mergedRecentDeletions.size) return true
        for (i in remoteRecentDeletions.indices) {
            if (remoteRecentDeletions[i].identity() != mergedRecentDeletions[i].identity()) return true
            if (remoteRecentDeletions[i].deletedAt != mergedRecentDeletions[i].deletedAt) return true
            if (remoteRecentDeletions[i].deviceId != mergedRecentDeletions[i].deviceId) return true
        }
        return false
    }

    private fun sameSongMetadata(a: SyncSong, b: SyncSong): Boolean {
        return a.name == b.name &&
            a.artist == b.artist &&
            a.album == b.album &&
            a.albumId == b.albumId &&
            a.durationMs == b.durationMs &&
            a.coverUrl == b.coverUrl &&
            a.mediaUri == b.mediaUri &&
            a.matchedLyric == b.matchedLyric &&
            a.matchedTranslatedLyric == b.matchedTranslatedLyric &&
            a.matchedLyricSource == b.matchedLyricSource &&
            a.matchedSongId == b.matchedSongId &&
            a.userLyricOffsetMs == b.userLyricOffsetMs &&
            a.customCoverUrl == b.customCoverUrl &&
            a.customName == b.customName &&
            a.customArtist == b.customArtist &&
            a.originalName == b.originalName &&
            a.originalArtist == b.originalArtist &&
            a.originalCoverUrl == b.originalCoverUrl &&
            a.originalLyric == b.originalLyric &&
            a.originalTranslatedLyric == b.originalTranslatedLyric &&
            a.channelId == b.channelId &&
            a.audioId == b.audioId &&
            a.subAudioId == b.subAudioId &&
            a.playlistContextId == b.playlistContextId
    }

    private suspend fun uploadLocalData(
        apiClient: GitHubApiClient,
        owner: String,
        repo: String,
        data: SyncData,
        sha: String?,
        fileName: String
    ): Result<String> {
        val localizedContext = LanguageManager.applyLanguage(appContext)
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
        return storage.getOrCreateDeviceId()
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
