package moe.ouom.neriplayer.data.sync.webdav

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
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.history.PlayedEntry
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipRepository
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncDataChangeDetector
import moe.ouom.neriplayer.data.sync.github.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatsMergePolicy
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistUsageStatsMergePolicy
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistDeletionPolicy
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistSongMergePolicy
import moe.ouom.neriplayer.data.sync.github.LocalSyncMutationConflictException
import moe.ouom.neriplayer.data.sync.github.SyncUploadRetryExecutor
import moe.ouom.neriplayer.data.sync.model.ConflictResolution
import moe.ouom.neriplayer.data.sync.model.ConflictType
import moe.ouom.neriplayer.data.sync.model.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.sync.model.SyncConflict
import moe.ouom.neriplayer.data.sync.model.SyncData
import moe.ouom.neriplayer.data.sync.model.SyncBiliVideoSkipMergePolicy
import moe.ouom.neriplayer.data.sync.model.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.model.SyncPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlay
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.model.SyncResult
import moe.ouom.neriplayer.data.sync.model.SyncSong
import moe.ouom.neriplayer.data.sync.model.SyncTrackStat
import moe.ouom.neriplayer.data.sync.model.copyWithNormalizedMembershipTokens
import moe.ouom.neriplayer.data.sync.model.hasResolvableSyncIdentity
import moe.ouom.neriplayer.data.sync.model.mergePositiveTimestamp
import moe.ouom.neriplayer.data.sync.model.toBiliVideoSkipRuleOrNull
import moe.ouom.neriplayer.data.sync.model.toSyncBiliVideoSkipRule
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.SyncCoordinator
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.IOException

class WebDavSyncManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val storage = SecureTokenStorage(appContext)
    private val webDavStorage = WebDavStorage(appContext)
    private val playlistRepo = LocalPlaylistRepository.getInstance(appContext)
    private val favoriteRepo = FavoritePlaylistRepository.getInstance(appContext)
    private val playHistoryRepo = PlayHistoryRepository.getInstance(appContext)
    private val playbackStatsRepo = PlaybackStatsRepository.getInstance(appContext)
    private val playlistUsageRepo = PlaylistUsageRepository.getInstance(appContext)
    private val localPlaylistPlaybackStatsRepo =
        LocalPlaylistPlaybackStatsRepository.getInstance(appContext)
    private val biliVideoSkipRepo = BiliVideoSkipRepository.getInstance(appContext)

    companion object {
        private const val TAG = "WebDavSyncManager"

        @Volatile
        private var instance: WebDavSyncManager? = null

        fun getInstance(context: Context): WebDavSyncManager {
            return instance ?: synchronized(this) {
                instance ?: WebDavSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    suspend fun performSync(): Result<SyncResult> = withContext(Dispatchers.IO) {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        if (!playlistRepo.awaitInitialized()) {
            return@withContext Result.failure(
                IllegalStateException("Local playlist initialization failed")
            )
        }

        if (!SyncCoordinator.tryLock()) {
            return@withContext Result.failure(
                WebDavSyncInProgressException(
                    localizedContext.getString(R.string.webdav_sync_in_progress)
                )
            )
        }

        try {
            val remoteUrl = webDavStorage.getRemoteFileUrl()
            val username = webDavStorage.getUsername()
            val password = webDavStorage.getPassword()
            if (remoteUrl == null || username == null || password == null) {
                return@withContext Result.failure(
                    IllegalStateException(localizedContext.getString(R.string.webdav_not_configured))
                )
            }

            val apiClient = WebDavApiClient(appContext, username, password)
            val startMutationVersion = storage.getSyncMutationVersion()
            val localData = sanitizeSyncData(buildLocalSyncData(localizedContext))
            val remoteSnapshotResult = fetchRemoteSnapshot(
                apiClient = apiClient,
                remoteUrl = remoteUrl
            )
            if (remoteSnapshotResult.isFailure) {
                return@withContext Result.failure(
                    remoteSnapshotResult.exceptionOrNull()
                        ?: IOException(localizedContext.getString(R.string.webdav_sync_failed_message))
                )
            }

            val remoteSnapshot = remoteSnapshotResult.getOrThrow()
            val lastRemoteFingerprint = webDavStorage.getLastRemoteFingerprint()
            val isFirstSync = lastRemoteFingerprint == null
            val remoteHasChanged = remoteSnapshot != null &&
                lastRemoteFingerprint != null &&
                lastRemoteFingerprint != remoteSnapshot.fingerprint
            val lastSyncTime = webDavStorage.getLastSyncTime()
            val uploadResolutionResult = SyncUploadRetryExecutor.execute<SyncData?, MergeResult, WebDavRemoteVersion>(
                initialRemote = remoteSnapshot?.data,
                initialVersion = remoteSnapshot?.version ?: WebDavRemoteVersion(
                    token = null,
                    createOnly = true
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
                            LocalSyncMutationConflictException("Local state changed during WebDAV sync")
                        )
                    }
                    uploadLocalData(
                        apiClient = apiClient,
                        remoteUrl = remoteUrl,
                        data = mergeResult.mergedData,
                        version = version
                    )
                },
                refetch = {
                    fetchRemoteSnapshot(
                        apiClient = apiClient,
                        remoteUrl = remoteUrl
                    ).map { snapshot ->
                        val resolvedSnapshot = snapshot ?: WebDavRemoteSnapshot(
                            data = null,
                            fingerprint = "",
                            version = WebDavRemoteVersion(
                                token = null,
                                createOnly = true
                            )
                        )
                        resolvedSnapshot.data to resolvedSnapshot.version
                    }
                },
                isConflict = { error ->
                    error is WebDavContentConflictException
                }
            )
            if (uploadResolutionResult.isFailure) {
                val error = uploadResolutionResult.exceptionOrNull()
                if (error is LocalSyncMutationConflictException) {
                    WebDavSyncWorker.scheduleDelayedSync(
                        appContext,
                        triggerByUserAction = false,
                        markMutation = false
                    )
                    return@withContext Result.success(
                        SyncResult(
                            success = true,
                            message = localizedContext.getString(R.string.webdav_sync_no_change)
                        )
                    )
                }
                return@withContext Result.failure(
                    error
                        ?: Exception(localizedContext.getString(R.string.sync_upload_failed))
                )
            }

            val uploadResolution = uploadResolutionResult.getOrThrow()
            var localMutatedDuringSync = storage.getSyncMutationVersion() != startMutationVersion

            if (!localMutatedDuringSync) {
                val applied = applyMergedDataToLocal(
                    mergedData = uploadResolution.merged.mergedData,
                    remoteHasChanged = isFirstSync || uploadResolution.remoteChangedDuringSync,
                    expectedMutationVersion = startMutationVersion
                )
                localMutatedDuringSync = !applied ||
                    storage.getSyncMutationVersion() != startMutationVersion
            } else {
                NPLogger.w(TAG, "Skip applying merged sync data because local state changed during sync")
            }

            uploadResolution.remoteVersion.lastKnownFingerprint
                ?.let(webDavStorage::saveLastRemoteFingerprint)
            if (localMutatedDuringSync) {
                WebDavSyncWorker.scheduleDelayedSync(
                    appContext,
                    triggerByUserAction = false,
                    markMutation = false
                )
            } else {
                webDavStorage.saveLastSyncTime(System.currentTimeMillis())
            }
            val syncResult = when {
                !uploadResolution.uploadPerformed &&
                    !uploadResolution.remoteChangedDuringSync &&
                    !isFirstSync -> {
                    SyncResult(
                        success = true,
                        message = localizedContext.getString(R.string.webdav_sync_no_change)
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

        storage.getDeletedPlaylistTimestamps().forEach { (deletedId, deletedAt) ->
            if (playlists.none { it.id == deletedId }) {
                syncPlaylists += SyncPlaylist(
                    id = deletedId,
                    name = "",
                    songs = emptyList(),
                    createdAt = 0L,
                    modifiedAt = deletedAt,
                    isDeleted = true,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
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
                        originalTranslatedLyric = playedEntry.originalTranslatedLyric,
                        syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
                    ),
                    playedAt = playedEntry.playedAt,
                    deviceId = getDeviceId(),
                    resumePositionMs = playedEntry.resumePositionMs
                )
            }
        val syncRecentPlayDeletions = storage.getRecentPlayDeletions()
            .map {
                it.copy(mediaUri = LocalSongSupport.sanitizeMediaUriForSync(it.mediaUri))
            }
        val syncPlaylistSongDeletions = storage.getPlaylistSongDeletions()
            .map {
                it.copyWithNormalizedMembershipTokens(
                    mediaUri = LocalSongSupport.sanitizeMediaUriForSync(it.mediaUri)
                )
            }

        val playbackCounterSnapshot = playbackStatsRepo.syncCounterSnapshot()
        val syncPlaybackStats = playbackStatsRepo.statsFlow.value
            .filter { SyncPlaybackStatMapper.shouldSync(it, localizedContext) }
            .map { stat ->
                SyncPlaybackStatMapper.fromTrackStat(
                    stat = stat,
                    counterShards = playbackCounterSnapshot.trackShards(stat.identityKey)
                )
            }
        val syncPlaybackStatBuckets = playbackStatsRepo.dailyStatsFlow.value
            .filter { SyncPlaybackStatMapper.shouldSync(it, localizedContext) }
            .map { bucket ->
                SyncPlaybackStatMapper.fromPlaybackStatBucket(
                    bucket = bucket,
                    counterShards = playbackCounterSnapshot.dailyShards(
                        dayStartAt = bucket.dayStartAt,
                        identityKey = bucket.identityKey
                    )
                )
            }
        val syncPlaylistUsageStats = playlistUsageRepo.syncStats()
        val localPlaylistPlaybackSnapshot = localPlaylistPlaybackStatsRepo.syncSnapshot()
        val syncBiliVideoSkipRules = SyncBiliVideoSkipMergePolicy.sanitize(
            biliVideoSkipRepo.snapshot().map { rule -> rule.toSyncBiliVideoSkipRule() }
        )

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
            playlistSongDeletions = syncPlaylistSongDeletions,
            playlistUsageStats = syncPlaylistUsageStats,
            localPlaylistPlaybackStats = localPlaylistPlaybackSnapshot.stats,
            localPlaylistPlaybackBuckets = localPlaylistPlaybackSnapshot.buckets,
            biliVideoSkipRules = syncBiliVideoSkipRules
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
                        if (SyncPlaylistDeletionPolicy.shouldKeepPlaylistDeleted(
                                localPlaylist,
                                remotePlaylist
                            )
                        ) {
                            mergedPlaylistsById[playlistId] = mergeDeletedPlaylist(
                                localPlaylist,
                                remotePlaylist
                            )
                            playlistsDeleted++
                        } else {
                            val activePlaylist = if (localPlaylist.isDeleted) {
                                remotePlaylist
                            } else {
                                localPlaylist
                            }
                            val merged = mergePlaylist(
                                local = activePlaylist,
                                remote = activePlaylist,
                                lastSyncTime = lastSyncTime,
                                playlistSongDeletions = mergedPlaylistSongDeletions
                            )
                            mergedPlaylistsById[merged.playlist.id] = merged.playlist
                            songsAdded += merged.songsAdded
                            songsRemoved += merged.songsRemoved
                            playlistsUpdated++
                        }
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
        val playbackStatsClearedAt = maxOf(local.playbackStatsClearedAt, remote.playbackStatsClearedAt)
        // 收尾顺序与桌面 three_way_merge / GitHub 路径逐字一致: 先用"未裁剪"的合并日桶抬升 (消除"年 > 总") , 再分别裁剪
        val finalizedPlaybackStats = SyncPlaybackStatsMergePolicy.finalizeMergedStats(
            mergedStats = mergePlaybackStats(
                local = local.playbackStats,
                remote = remote.playbackStats,
                playbackStatsClearedAt = playbackStatsClearedAt
            ),
            mergedBuckets = mergePlaybackStatBuckets(
                local = local.playbackStatBuckets,
                remote = remote.playbackStatBuckets,
                playbackStatsClearedAt = playbackStatsClearedAt
            )
        )
        val mergedPlaybackStats = finalizedPlaybackStats.stats
        val mergedPlaybackStatBuckets = finalizedPlaybackStats.buckets
        val mergedPlaylistUsageStats = SyncPlaylistUsageStatsMergePolicy
            .mergePlaylistUsageStats(
                local = local.playlistUsageStats,
                remote = remote.playlistUsageStats
            )
        val finalizedLocalPlaylistPlaybackStats =
            SyncPlaylistUsageStatsMergePolicy.finalizeLocalPlaylistPlaybackStats(
                stats = SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackStats(
                    local = local.localPlaylistPlaybackStats,
                    remote = remote.localPlaylistPlaybackStats
                ),
                buckets = SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackBuckets(
                    local = local.localPlaylistPlaybackBuckets,
                    remote = remote.localPlaylistPlaybackBuckets
                )
            )
        val mergedBiliVideoSkipRules = SyncBiliVideoSkipMergePolicy.merge(
            local = local.biliVideoSkipRules,
            remote = remote.biliVideoSkipRules
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
            recentPlayDeletions = mergedRecentPlayDeletions,
            playbackStats = mergedPlaybackStats,
            playbackStatsClearedAt = playbackStatsClearedAt,
            playbackStatBuckets = mergedPlaybackStatBuckets,
            playlistSongDeletions = prunedPlaylistSongDeletions,
            playlistUsageStats = mergedPlaylistUsageStats,
            localPlaylistPlaybackStats = finalizedLocalPlaylistPlaybackStats.stats,
            localPlaylistPlaybackBuckets = finalizedLocalPlaylistPlaybackStats.buckets,
            biliVideoSkipRules = mergedBiliVideoSkipRules
        )

        return MergeResult(
            mergedData = mergedData,
            syncResult = SyncResult(
                success = true,
                message = localizedContext.getString(R.string.webdav_sync_success_detail),
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
        return playlists.any { !it.isDeleted && it.modifiedAt > lastSyncTime }
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
                createdAt = mergePositiveTimestamp(local.createdAt, remote.createdAt),
                modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt),
                songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
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
            .sortedWith(
                compareByDescending<SyncRecentPlay> { it.playedAt }
                    .thenByDescending { it.resumePositionMs }
                    .thenByDescending { it.deviceId }
            )
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
        return SyncPlaylistDeletionPolicy.mergeFavoritePlaylists(left, right)
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
            createdAt = mergePositiveTimestamp(local.createdAt, remote.createdAt),
            modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt),
            isDeleted = true,
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
        )
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

    private suspend fun applyMergedDataToLocal(
        mergedData: SyncData,
        remoteHasChanged: Boolean,
        expectedMutationVersion: Long
    ): Boolean {
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
                    customCoverUrl = currentPlaylists[normalizedId]?.customCoverUrl,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            }
        val playlistsApplied = playlistRepo.applySyncedPlaylistsIfUnchanged(
            playlists = mergedLocalPlaylists,
            expectedMutationVersion = expectedMutationVersion
        )
        if (!playlistsApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local playlist epoch changed")
            return false
        }

        val deletionStateApplied = storage.setDeletionStateIfMutationVersion(
            expectedMutationVersion = expectedMutationVersion,
            recentPlayDeletions = sanitizedMergedData.recentPlayDeletions,
            playlistSongDeletions = sanitizedMergedData.playlistSongDeletions
        )
        if (!deletionStateApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local deletion epoch changed")
            return false
        }

        val favoritesApplied = favoriteRepo.replaceFavoritesFromSyncIfUnchanged(
            favorites = sanitizedMergedData.favoritePlaylists.map { it.toFavoritePlaylist() },
            expectedMutationVersion = expectedMutationVersion
        )
        if (!favoritesApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local favorites epoch changed")
            return false
        }

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
                    resumePositionMs = syncPlay.resumePositionMs,
                    playedAt = syncPlay.playedAt
                )
            }
            val localOnlyHistory = playHistoryRepo.historyFlow.value.filter {
                LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext)
            }
            val playHistory = mergeLocalOnlyHistory(syncedHistory, localOnlyHistory)
            val historyApplied = playHistoryRepo.updateHistoryIfUnchanged(
                entries = playHistory,
                expectedMutationVersion = expectedMutationVersion
            )
            if (!historyApplied) {
                NPLogger.w(TAG, "Skip applying merged sync data because local history epoch changed")
                return false
            }
        }

        playbackStatsRepo.applyMergedStats(
            syncStats = sanitizedMergedData.playbackStats,
            playbackStatsClearedAt = sanitizedMergedData.playbackStatsClearedAt,
            syncDailyStats = sanitizedMergedData.playbackStatBuckets
        )
        playlistUsageRepo.applyMergedStats(sanitizedMergedData.playlistUsageStats)
        localPlaylistPlaybackStatsRepo.applyMergedStats(
            stats = sanitizedMergedData.localPlaylistPlaybackStats,
            buckets = sanitizedMergedData.localPlaylistPlaybackBuckets
        )
        val videoSkipRulesApplied = biliVideoSkipRepo.replaceFromSyncIfUnchanged(
            rules = sanitizedMergedData.biliVideoSkipRules.mapNotNull { rule ->
                rule.toBiliVideoSkipRuleOrNull()
            },
            expectedMutationVersion = expectedMutationVersion
        )
        if (!videoSkipRulesApplied) {
            NPLogger.w(TAG, "Skip applying Bili video skip rules because local state changed")
            return false
        }
        return true
    }

    private fun mergeLocalOnlySongs(
        syncedSongs: List<moe.ouom.neriplayer.data.model.SongItem>,
        localOnlySongs: List<moe.ouom.neriplayer.data.model.SongItem>
    ): MutableList<moe.ouom.neriplayer.data.model.SongItem> {
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
            },
            playlistUsageStats = data.playlistUsageStats.mapNotNull { stat ->
                SyncPlaylistUsageStatsMergePolicy.sanitize(stat)
            },
            localPlaylistPlaybackStats = data.localPlaylistPlaybackStats.mapNotNull { stat ->
                SyncPlaylistUsageStatsMergePolicy.sanitize(stat)
            },
            localPlaylistPlaybackBuckets = data.localPlaylistPlaybackBuckets.mapNotNull { bucket ->
                SyncPlaylistUsageStatsMergePolicy.sanitize(bucket)
            },
            biliVideoSkipRules = SyncBiliVideoSkipMergePolicy.sanitize(data.biliVideoSkipRules)
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
        ).normalizedForDisplayOrder()
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
        if (!deletion.hasResolvableSyncIdentity() || deletion.deletedAt <= 0L) {
            return null
        }
        if (LocalSongSupport.isLocalSong(deletion.album, deletion.mediaUri, 0L, appContext)) {
            return null
        }
        return deletion.copy(mediaUri = LocalSongSupport.sanitizeMediaUriForSync(deletion.mediaUri))
    }

    private fun sanitizePlaylistSongDeletion(
        deletion: SyncPlaylistSongDeletion
    ): SyncPlaylistSongDeletion? {
        if (
            deletion.playlistId == 0L ||
            deletion.playlistId == LocalFilesPlaylist.SYSTEM_ID ||
            !deletion.hasResolvableSyncIdentity() ||
            deletion.deletedAt <= 0L
        ) {
            return null
        }
        if (LocalSongSupport.isLocalSong(deletion.album, deletion.mediaUri, 0L, appContext)) {
            return null
        }
        return deletion.copyWithNormalizedMembershipTokens(
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(deletion.mediaUri)
        )
    }

    private fun sanitizeSyncSong(song: SyncSong): SyncSong? {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        if (!song.hasResolvableSyncIdentity()) {
            return null
        }
        if (LocalSongSupport.isLocalSong(song.album, song.mediaUri, song.albumId, localizedContext)) {
            return null
        }
        return song.copyWithNormalizedMembershipTokens(
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(song.mediaUri)
        )
    }

    private fun hasDataChanged(remote: SyncData, merged: SyncData): Boolean {
        return SyncDataChangeDetector.hasDataChanged(remote, merged)
    }

    private suspend fun fetchRemoteSnapshot(
        apiClient: WebDavApiClient,
        remoteUrl: String
    ): Result<WebDavRemoteSnapshot?> {
        val remoteResult = apiClient.getFileContentStrict(remoteUrl)
        if (remoteResult.isFailure) {
            val error = remoteResult.exceptionOrNull()
            return if (error is WebDavFileNotFoundException) {
                Result.success(null)
            } else {
                Result.failure(error ?: IOException("Failed to fetch remote data"))
            }
        }

        val snapshot = remoteResult.getOrThrow()
        if (snapshot.content.isEmpty()) {
            return Result.failure(
                IOException(LanguageManager.applyLanguage(appContext).getString(R.string.webdav_backup_file_invalid))
            )
        }

        val remoteData = try {
            SyncDataSerializer.ensureRemoteContentSize(snapshot.content)
            sanitizeSyncData(SyncDataSerializer.deserialize(snapshot.content))
        } catch (e: Exception) {
            NPLogger.e(TAG, "Failed to parse remote data", e)
            return Result.failure(e)
        }

        return Result.success(
            WebDavRemoteSnapshot(
                data = remoteData,
                fingerprint = snapshot.fingerprint,
                version = WebDavRemoteVersion(
                    token = snapshot.version,
                    createOnly = false,
                    lastKnownFingerprint = snapshot.fingerprint
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
        // 首次同步同样走收尾 (与 GitHub / 桌面一致: 先用"未裁剪"桶抬升, 再裁剪) , 避免首个备份文件无界增长
        val finalizedInitialStats = SyncPlaybackStatsMergePolicy.finalizeMergedStats(
            mergedStats = localData.playbackStats,
            mergedBuckets = localData.playbackStatBuckets
        )
        val finalizedInitialLocalPlaylistStats =
            SyncPlaylistUsageStatsMergePolicy.finalizeLocalPlaylistPlaybackStats(
                stats = localData.localPlaylistPlaybackStats,
                buckets = localData.localPlaylistPlaybackBuckets
            )
        return MergeResult(
            mergedData = localData.copy(
                lastModified = System.currentTimeMillis(),
                playbackStats = finalizedInitialStats.stats,
                playbackStatBuckets = finalizedInitialStats.buckets,
                localPlaylistPlaybackStats = finalizedInitialLocalPlaylistStats.stats,
                localPlaylistPlaybackBuckets = finalizedInitialLocalPlaylistStats.buckets
            ),
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
        apiClient: WebDavApiClient,
        remoteUrl: String,
        data: SyncData,
        version: WebDavRemoteVersion
    ): Result<WebDavRemoteVersion> {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        val useDataSaver = storage.isDataSaverMode()
        val content = SyncDataSerializer.serialize(data, useDataSaver)
        // 省流传原始 GZIP(ProtoBuf) 字节; 非省流传 UTF-8 JSON 字节
        val mediaType = if (useDataSaver) {
            "application/octet-stream"
        } else {
            "application/json; charset=utf-8"
        }
        NPLogger.d(
            TAG,
            "Upload data size: ${SyncDataSerializer.getDataSize(data, useDataSaver)} bytes (DataSaver: $useDataSaver, WebDAV)"
        )

        val uploadResult = uploadFileWithConcurrencyFallback(
            apiClient = apiClient,
            remoteUrl = remoteUrl,
            content = content,
            mediaType = mediaType,
            version = version
        )
        return if (uploadResult.isSuccess) {
            val writeResult = uploadResult.getOrThrow()
            Result.success(
                WebDavRemoteVersion(
                    token = writeResult.version,
                    createOnly = false,
                    lastKnownFingerprint = writeResult.fingerprint
                )
            )
        } else {
            Result.failure(
                uploadResult.exceptionOrNull()
                    ?: Exception(localizedContext.getString(R.string.sync_upload_failed))
            )
        }
    }

    private fun uploadFileWithConcurrencyFallback(
        apiClient: WebDavApiClient,
        remoteUrl: String,
        content: ByteArray,
        mediaType: String,
        version: WebDavRemoteVersion
    ): Result<WebDavApiClient.WriteResult> {
        val directResult = apiClient.updateFileContent(
            remoteUrl = remoteUrl,
            content = content,
            mediaType = mediaType,
            expectedVersion = version.token,
            createOnly = version.createOnly
        )
        if (version.createOnly || directResult.exceptionOrNull() !is WebDavMissingConcurrencyTokenException) {
            return directResult
        }

        val expectedFingerprint = version.lastKnownFingerprint
        if (expectedFingerprint.isNullOrBlank()) {
            return directResult
        }
        val currentSnapshot = apiClient.getFileContentStrict(remoteUrl)
        if (currentSnapshot.isFailure) {
            return Result.failure(
                currentSnapshot.exceptionOrNull()
                    ?: IOException("无法重新读取 WebDAV 文件以确认并发状态")
            )
        }
        val currentFingerprint = currentSnapshot.getOrThrow().fingerprint
        if (!shouldAllowUnconditionalWebDavWrite(expectedFingerprint, currentFingerprint)) {
            return Result.failure(
                WebDavContentConflictException(
                    statusCode = 412,
                    message = "WebDAV remote content changed while conditional tokens were unavailable"
                )
            )
        }

        NPLogger.w(
            TAG,
            "WebDAV server has no conditional token; writing after fingerprint revalidation"
        )
        return apiClient.updateFileContent(
            remoteUrl = remoteUrl,
            content = content,
            mediaType = mediaType,
            expectedVersion = null,
            createOnly = false,
            allowUnconditionalWrite = true
        )
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

    private data class WebDavRemoteVersion(
        val token: WebDavApiClient.ConcurrencyToken?,
        val createOnly: Boolean,
        val lastKnownFingerprint: String? = null
    )

    private data class WebDavRemoteSnapshot(
        val data: SyncData?,
        val fingerprint: String,
        val version: WebDavRemoteVersion
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
