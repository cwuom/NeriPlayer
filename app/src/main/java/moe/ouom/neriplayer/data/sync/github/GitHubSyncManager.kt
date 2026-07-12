package moe.ouom.neriplayer.data.sync.github

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
 * File: moe.ouom.neriplayer.data.sync.github/GitHubSyncManager
 * Updated: 2026/3/23
 */


import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.history.PlayedEntry
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.SyncCoordinator
import moe.ouom.neriplayer.util.LanguageManager
import moe.ouom.neriplayer.util.NPLogger
import java.io.IOException

class GitHubSyncManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val storage = SecureTokenStorage(appContext)
    private val playlistRepo = LocalPlaylistRepository.getInstance(appContext)
    private val favoriteRepo = FavoritePlaylistRepository.getInstance(appContext)
    private val playHistoryRepo = PlayHistoryRepository.getInstance(appContext)
    private val playbackStatsRepo = PlaybackStatsRepository.getInstance(appContext)

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

        if (!SyncCoordinator.tryLock()) {
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
            val useDataSaver = storage.isDataSaverMode()
            val preferredFileName = SyncDataSerializer.getFileName(useDataSaver)
            val remoteSnapshotResult = fetchRemoteSnapshot(
                apiClient = apiClient,
                owner = owner,
                repo = repo,
                preferredFileName = preferredFileName,
                useDataSaver = useDataSaver
            )
            if (remoteSnapshotResult.isFailure) {
                val error = remoteSnapshotResult.exceptionOrNull()
                if (error is TokenExpiredException) {
                    storage.clearToken()
                }
                return@withContext Result.failure(
                    error ?: IOException(localizedContext.getString(R.string.github_sync_failed_message))
                )
            }

            val remoteSnapshot = remoteSnapshotResult.getOrThrow()
            val lastRemoteSha = storage.getLastRemoteSha()
            val isFirstSync = lastRemoteSha == null
            val remoteHasChanged = remoteSnapshot != null &&
                lastRemoteSha != null &&
                lastRemoteSha != remoteSnapshot.version.sha
            val lastSyncTime = storage.getLastSyncTime()
            val uploadResolutionResult = SyncUploadRetryExecutor.execute<SyncData?, MergeResult, GitHubRemoteVersion>(
                initialRemote = remoteSnapshot?.data,
                initialVersion = remoteSnapshot?.version ?: GitHubRemoteVersion(
                    sha = null,
                    fileName = preferredFileName
                ),
                initialRemoteChangedDuringSync = remoteHasChanged,
                merge = { remoteData ->
                    if (remoteData == null) {
                        buildInitialMergeResult(localData, localizedContext)
                    } else {
                        performThreeWayMerge(localData, remoteData, lastSyncTime)
                    }
                },
                hasMeaningfulChange = { remoteData, mergeResult ->
                    remoteData == null || hasDataChanged(remoteData, mergeResult.mergedData)
                },
                upload = { mergeResult, version ->
                    if (storage.getSyncMutationVersion() != startMutationVersion) {
                        return@execute Result.failure(
                            LocalSyncMutationConflictException("Local state changed during GitHub sync")
                        )
                    }
                    uploadLocalData(
                        apiClient = apiClient,
                        owner = owner,
                        repo = repo,
                        data = mergeResult.mergedData,
                        sha = version.sha,
                        fileName = version.fileName
                    ).map { newSha ->
                        GitHubRemoteVersion(
                            sha = newSha,
                            fileName = version.fileName
                        )
                    }
                },
                refetch = { version ->
                    fetchRemoteSnapshot(
                        apiClient = apiClient,
                        owner = owner,
                        repo = repo,
                        preferredFileName = version.fileName,
                        useDataSaver = SyncDataSerializer.isBinaryFileName(version.fileName)
                    ).map { snapshot ->
                        val resolvedSnapshot = snapshot ?: GitHubRemoteSnapshot(
                            data = null,
                            version = GitHubRemoteVersion(
                                sha = null,
                                fileName = version.fileName
                            )
                        )
                        resolvedSnapshot.data to resolvedSnapshot.version
                    }
                },
                isConflict = { error ->
                    error is GitHubContentConflictException
                }
            )
            if (uploadResolutionResult.isFailure) {
                val error = uploadResolutionResult.exceptionOrNull()
                if (error is LocalSyncMutationConflictException) {
                    GitHubSyncWorker.scheduleDelayedSync(
                        appContext,
                        triggerByUserAction = false,
                        markMutation = false
                    )
                    return@withContext Result.success(
                        SyncResult(
                            success = true,
                            message = localizedContext.getString(R.string.github_sync_no_change)
                        )
                    )
                }
                if (error is TokenExpiredException) {
                    storage.clearToken()
                }
                return@withContext Result.failure(
                    error ?: Exception(localizedContext.getString(R.string.sync_upload_failed))
                )
            }

            val uploadResolution = uploadResolutionResult.getOrThrow()
            val localMutatedDuringSync = storage.getSyncMutationVersion() != startMutationVersion

            if (!localMutatedDuringSync) {
                applyMergedDataToLocal(
                    mergedData = uploadResolution.merged.mergedData,
                    remoteHasChanged = isFirstSync || uploadResolution.remoteChangedDuringSync
                )
            } else {
                NPLogger.w(TAG, "Skip applying merged sync data because local state changed during sync")
            }

            uploadResolution.remoteVersion.sha?.let(storage::saveLastRemoteSha)
            storage.saveLastSyncTime(System.currentTimeMillis())
            if (localMutatedDuringSync) {
                GitHubSyncWorker.scheduleDelayedSync(
                    appContext,
                    triggerByUserAction = false,
                    markMutation = false
                )
            }
            val syncResult = when {
                !uploadResolution.uploadPerformed &&
                    !uploadResolution.remoteChangedDuringSync &&
                    !isFirstSync -> {
                    SyncResult(
                        success = true,
                        message = localizedContext.getString(R.string.github_sync_no_change)
                    )
                }

                remoteSnapshot == null &&
                    uploadResolution.uploadPerformed &&
                    !uploadResolution.remoteChangedDuringSync -> {
                    SyncResult(
                        success = true,
                        message = localizedContext.getString(R.string.sync_initial_uploaded)
                    )
                }

                else -> uploadResolution.merged.syncResult
            }
            Result.success(syncResult)
        } catch (e: Exception) {
            NPLogger.e(TAG, "Sync failed", e)
            Result.failure(e)
        } finally {
            SyncCoordinator.unlock()
        }
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
            .filterNot {
                !it.localFilePath.isNullOrBlank() ||
                    LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext)
            }
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
        val syncPlaylistSongDeletions = storage.getPlaylistSongDeletions()
            .map {
                it.copy(mediaUri = LocalSongSupport.sanitizeMediaUriForSync(it.mediaUri))
            }

        val syncPlaybackStats = playbackStatsRepo.statsFlow.value
            .filter { SyncPlaybackStatMapper.shouldSync(it, localizedContext) }
            .map(SyncPlaybackStatMapper::fromTrackStat)
        val syncPlaybackStatBuckets = playbackStatsRepo.dailyStatsFlow.value
            .filter { SyncPlaybackStatMapper.shouldSync(it, localizedContext) }
            .map(SyncPlaybackStatMapper::fromPlaybackStatBucket)

        return SyncData(
            deviceId = getDeviceId(),
            deviceName = getDeviceName(),
            lastModified = System.currentTimeMillis(),
            playlists = syncPlaylists,
            favoritePlaylists = syncFavoritePlaylists,
            recentPlays = syncRecentPlays,
            syncLog = emptyList(),
            recentPlayDeletions = syncRecentPlayDeletions,
            playbackStats = syncPlaybackStats,
            playbackStatsClearedAt = playbackStatsRepo.statsClearedAtFlow.value,
            playbackStatBuckets = syncPlaybackStatBuckets,
            playlistSongDeletions = syncPlaylistSongDeletions
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
        val mergedPlaylistSongDeletions = SyncPlaylistDeletionPolicy.mergeDeletions(
            local = local.playlistSongDeletions,
            remote = remote.playlistSongDeletions
        )

        val mergedPlaylistsById = linkedMapOf<Long, SyncPlaylist>()
        val localPlaylistsMap = local.playlists.associateBy { it.id }
        val remotePlaylistsMap = remote.playlists.associateBy { it.id }
        val allPlaylistIds = (localPlaylistsMap.keys + remotePlaylistsMap.keys).toSet()

        for (playlistId in allPlaylistIds) {
            val localPlaylist = localPlaylistsMap[playlistId]
            val remotePlaylist = remotePlaylistsMap[playlistId]
            when {
                localPlaylist != null && remotePlaylist == null -> {
                    mergedPlaylistsById[localPlaylist.id] = localPlaylist
                    if (!localPlaylist.isDeleted) {
                        playlistsAdded++
                    } else {
                        playlistsDeleted++
                    }
                }

                localPlaylist == null && remotePlaylist != null -> {
                    mergedPlaylistsById[remotePlaylist.id] = remotePlaylist
                    if (!remotePlaylist.isDeleted) {
                        playlistsAdded++
                    } else {
                        playlistsDeleted++
                    }
                }

                localPlaylist != null && remotePlaylist != null -> {
                    if (localPlaylist.isDeleted || remotePlaylist.isDeleted) {
                        mergedPlaylistsById[playlistId] = mergeDeletedPlaylist(localPlaylist, remotePlaylist)
                        playlistsDeleted++
                    } else {
                        val merged = mergePlaylist(
                            local = localPlaylist,
                            remote = remotePlaylist,
                            lastSyncTime = lastSyncTime,
                            playlistSongDeletions = mergedPlaylistSongDeletions
                        )
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
        val prunedPlaylistSongDeletions = SyncPlaylistDeletionPolicy.pruneResolvedDeletions(
            deletions = mergedPlaylistSongDeletions,
            playlists = mergedPlaylists
        )
        val mergedRecentPlays = mergeRecentPlays(
            local = local.recentPlays,
            remote = remote.recentPlays,
            deletions = mergedRecentPlayDeletions
        )
        val mergedPlaybackStats = mergePlaybackStats(
            local = local.playbackStats,
            remote = remote.playbackStats,
            playbackStatsClearedAt = maxOf(local.playbackStatsClearedAt, remote.playbackStatsClearedAt)
        )
        val mergedPlaybackStatBuckets = mergePlaybackStatBuckets(
            local = local.playbackStatBuckets,
            remote = remote.playbackStatBuckets,
            playbackStatsClearedAt = maxOf(local.playbackStatsClearedAt, remote.playbackStatsClearedAt)
        )
        val playbackStatsClearedAt = maxOf(local.playbackStatsClearedAt, remote.playbackStatsClearedAt)

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
            recentPlayDeletions = mergedRecentPlayDeletions,
            playbackStats = mergedPlaybackStats,
            playbackStatsClearedAt = playbackStatsClearedAt,
            playbackStatBuckets = mergedPlaybackStatBuckets,
            playlistSongDeletions = prunedPlaylistSongDeletions
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
        lastSyncTime: Long,
        playlistSongDeletions: List<SyncPlaylistSongDeletion>
    ): PlaylistMergeResult {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        var conflict: SyncConflict? = null
        var hasConflict = false
        var isUpdated = false

        val systemDescriptor = SystemLocalPlaylists.resolve(local.id, local.name, localizedContext)
            ?: SystemLocalPlaylists.resolve(remote.id, remote.name, localizedContext)
        val resolvedPlaylistId = systemDescriptor?.id ?: local.id
        val isFavorites = resolvedPlaylistId == FavoritesPlaylist.SYSTEM_ID
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
        val songMergeResult = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = local.songs,
            remoteSongs = remote.songs,
            localModifiedAt = local.modifiedAt,
            remoteModifiedAt = remote.modifiedAt,
            localChangedAfterSync = localChangedAfterSync,
            remoteChangedAfterSync = remoteChangedAfterSync,
            lastSyncTime = lastSyncTime,
            isFavorites = isFavorites
        )
        val mergedSongs = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = resolvedPlaylistId,
            songs = songMergeResult.songs,
            deletions = playlistSongDeletions
        )
        if (songMergeResult.isUpdated || mergedSongs.size != songMergeResult.songs.size) {
            isUpdated = true
        }

        val mergedIdentities = mergedSongs.map { it.identity() }.toSet()
        val songsAdded = (mergedIdentities - localSongs).size
        val songsRemoved = (localSongs - mergedIdentities).size

        return PlaylistMergeResult(
            playlist = SyncPlaylist(
                id = resolvedPlaylistId,
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

    private fun mergePlaybackStats(
        local: List<SyncTrackStat>,
        remote: List<SyncTrackStat>,
        playbackStatsClearedAt: Long
    ): List<SyncTrackStat> {
        return SyncPlaybackStatsMergePolicy.merge(local, remote, playbackStatsClearedAt)
    }

    private fun mergePlaybackStatBuckets(
        local: List<SyncPlaybackStatBucket>,
        remote: List<SyncPlaybackStatBucket>,
        playbackStatsClearedAt: Long
    ): List<SyncPlaybackStatBucket> {
        return SyncPlaybackStatsMergePolicy.mergeBuckets(local, remote, playbackStatsClearedAt)
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

    private fun mergeDeletedPlaylist(
        local: SyncPlaylist,
        remote: SyncPlaylist
    ): SyncPlaylist {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        val systemDescriptor = SystemLocalPlaylists.resolve(local.id, local.name, localizedContext)
            ?: SystemLocalPlaylists.resolve(remote.id, remote.name, localizedContext)
        val resolvedName = systemDescriptor?.currentName
            ?: local.name.takeIf { it.isNotBlank() }
            ?: remote.name
        return SyncPlaylist(
            id = systemDescriptor?.id ?: local.id,
            name = resolvedName,
            songs = emptyList(),
            createdAt = minOf(local.createdAt, remote.createdAt),
            modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt),
            isDeleted = true
        )
    }

    private suspend fun applyMergedDataToLocal(mergedData: SyncData, remoteHasChanged: Boolean) {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        val sanitizedMergedData = sanitizeSyncData(mergedData)
        val currentPlaylists = playlistRepo.playlists.value.associateBy { playlist ->
            SystemLocalPlaylists.resolve(playlist.id, playlist.name, localizedContext)?.id ?: playlist.id
        }
        val mergedLocalPlaylists = sanitizedMergedData.playlists
            .filterNot(SyncPlaylist::isDeleted)
            .map { syncPlaylist ->
                val systemDescriptor = SystemLocalPlaylists.resolve(
                    syncPlaylist.id,
                    syncPlaylist.name,
                    localizedContext
                )
                val normalizedId = systemDescriptor?.id ?: syncPlaylist.id
                val syncedSongs = syncPlaylist.songs
                    .map { it.toSongItem() }
                    .distinctBy { it.identity() }
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
        storage.setPlaylistSongDeletions(sanitizedMergedData.playlistSongDeletions)

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

        playbackStatsRepo.applyMergedStats(
            syncStats = sanitizedMergedData.playbackStats,
            playbackStatsClearedAt = sanitizedMergedData.playbackStatsClearedAt,
            syncDailyStats = sanitizedMergedData.playbackStatBuckets
        )
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
            recentPlayDeletions = data.recentPlayDeletions.mapNotNull { sanitizeRecentPlayDeletion(it) },
            playlistSongDeletions = data.playlistSongDeletions.mapNotNull {
                sanitizePlaylistSongDeletion(it)
            },
            playbackStats = data.playbackStats.mapNotNull {
                SyncPlaybackStatMapper.sanitize(it, appContext)
            },
            playbackStatsClearedAt = data.playbackStatsClearedAt.coerceAtLeast(0L),
            playbackStatBuckets = data.playbackStatBuckets.mapNotNull {
                SyncPlaybackStatMapper.sanitize(it, appContext)
            }
        )
    }

    private fun sanitizeSyncPlaylist(playlist: SyncPlaylist): SyncPlaylist? {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        val systemDescriptor = SystemLocalPlaylists.resolve(playlist.id, playlist.name, localizedContext)
        if (systemDescriptor?.id == LocalFilesPlaylist.SYSTEM_ID) {
            return null
        }
        if (playlist.isDeleted) {
            return playlist.copy(
                id = systemDescriptor?.id ?: playlist.id,
                name = systemDescriptor?.currentName ?: playlist.name,
                songs = emptyList()
            )
        }
        return playlist.copy(
            id = systemDescriptor?.id ?: playlist.id,
            name = systemDescriptor?.currentName ?: playlist.name,
            songs = SyncPlaylistSongMergePolicy.deduplicateSongs(
                playlist.songs.mapNotNull { sanitizeSyncSong(it) }
            )
        )
    }

    private fun sanitizeSyncFavoritePlaylist(playlist: SyncFavoritePlaylist): SyncFavoritePlaylist {
        val sanitizedSongs = if (playlist.isDeleted) {
            emptyList()
        } else {
            SyncPlaylistSongMergePolicy.deduplicateSongs(
                playlist.songs.mapNotNull { sanitizeSyncSong(it) }
            )
        }
        return playlist.copy(
            songs = sanitizedSongs,
            trackCount = if (playlist.isDeleted) 0 else maxOf(playlist.trackCount, sanitizedSongs.size)
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

    private fun sanitizePlaylistSongDeletion(
        deletion: SyncPlaylistSongDeletion
    ): SyncPlaylistSongDeletion? {
        if (deletion.playlistId == LocalFilesPlaylist.SYSTEM_ID) {
            return null
        }
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
        return SyncDataChangeDetector.hasDataChanged(remote, merged)
    }

    private suspend fun fetchRemoteSnapshot(
        apiClient: GitHubApiClient,
        owner: String,
        repo: String,
        preferredFileName: String,
        useDataSaver: Boolean
    ): Result<GitHubRemoteSnapshot?> {
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
                    return Result.failure(alternativeError ?: IOException("Failed to fetch remote data"))
                }
            }
        }

        if (remoteResult.isFailure) {
            val error = remoteResult.exceptionOrNull()
            return if (error is GitHubFileNotFoundException) {
                Result.success(null)
            } else {
                Result.failure(error ?: IOException("Failed to fetch remote data"))
            }
        }

        val (remoteContent, remoteSha) = remoteResult.getOrThrow()
        if (remoteContent.isEmpty()) {
            return Result.failure(
                IOException(LanguageManager.applyLanguage(appContext).getString(R.string.github_backup_file_invalid))
            )
        }

        var actualRemoteSha = remoteSha
        val remoteData = try {
            SyncDataSerializer.ensureRemoteContentSize(
                remoteContent,
                SyncDataSerializer.isBinaryFileName(actualFileName)
            )
            sanitizeSyncData(
                SyncDataSerializer.deserialize(
                    remoteContent,
                    SyncDataSerializer.isBinaryFileName(actualFileName)
                )
            )
        } catch (e: Exception) {
            val alternativeFileName = SyncDataSerializer.getFileName(!SyncDataSerializer.isBinaryFileName(actualFileName))
            val fallbackResult = if (alternativeFileName != actualFileName) {
                apiClient.getFileContentStrict(owner, repo, alternativeFileName).getOrNull()
            } else null
            val fallbackContent = fallbackResult?.first
            val fallbackSha = fallbackResult?.second
            val parsedFallback = if (!fallbackContent.isNullOrEmpty()) {
                runCatching {
                    SyncDataSerializer.ensureRemoteContentSize(
                        fallbackContent,
                        SyncDataSerializer.isBinaryFileName(alternativeFileName)
                    )
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
                return Result.failure(e)
            }
        }

        return Result.success(
            GitHubRemoteSnapshot(
                data = remoteData,
                version = GitHubRemoteVersion(
                    sha = actualRemoteSha,
                    fileName = actualFileName
                )
            )
        )
    }

    private fun buildInitialMergeResult(
        localData: SyncData,
        localizedContext: Context
    ): MergeResult {
        val playlistsAdded = localData.playlists.count { !it.isDeleted }
        val playlistsDeleted = localData.playlists.count(SyncPlaylist::isDeleted)
        val songsAdded = localData.playlists.sumOf { playlist -> playlist.songs.size }
        return MergeResult(
            mergedData = localData.copy(lastModified = System.currentTimeMillis()),
            syncResult = SyncResult(
                success = true,
                message = localizedContext.getString(R.string.sync_initial_uploaded),
                playlistsAdded = playlistsAdded,
                playlistsDeleted = playlistsDeleted,
                songsAdded = songsAdded
            )
        )
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
        val useDataSaver = SyncDataSerializer.isBinaryFileName(fileName)
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

    private data class GitHubRemoteVersion(
        val sha: String?,
        val fileName: String
    )

    private data class GitHubRemoteSnapshot(
        val data: SyncData?,
        val version: GitHubRemoteVersion
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
