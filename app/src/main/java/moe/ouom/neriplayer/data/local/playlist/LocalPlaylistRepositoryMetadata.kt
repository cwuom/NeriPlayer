package moe.ouom.neriplayer.data.local.playlist

import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.NeteaseResolvedCandidate
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.NeteaseCandidateValidationResult
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.SongMetadataUpdate
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.NeteaseRemotePlaylistSyncPlan
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.SongMetadataUpdateIndex
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.model.normalizedSyncCausalTokens
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger

internal suspend fun LocalPlaylistRepository.applySongMetadataUpdates(updates: List<SongMetadataUpdate>) {
    if (updates.isEmpty()) {
        return
    }

    commitPlaylistMutation {
        val updateIndex = SongMetadataUpdateIndex(updates)
        var changed = false
        val updated = _playlists.value.map { playlist ->
            var playlistChanged = false
            val refreshedSongs = playlist.songs.map { currentSong ->
                val update = updateIndex.find(currentSong) ?: return@map currentSong
                val mergedSongInfo = mergeSongMetadataForPersistence(
                    currentSong = currentSong,
                    newSongInfo = update.newSongInfo,
                    clearCoverUrl = update.clearCoverUrl,
                    clearOriginalCoverUrl = update.clearOriginalCoverUrl
                )
                if (currentSong == mergedSongInfo) {
                    currentSong
                } else {
                    saveCoverMapping(mergedSongInfo)
                    changed = true
                    playlistChanged = true
                    mergedSongInfo
                }
            }.toMutableList()

            if (playlistChanged) {
                playlist.copy(
                    songs = refreshedSongs,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            } else {
                playlist
            }
        }

        if (changed) {
            publishLocked(
                playlists = updated,
                triggerSync = false,
                markLocalMutation = false
            )
        }
    }
}

internal suspend fun LocalPlaylistRepository.applySongDurationUpdates(updates: List<Pair<SongItem, SongItem>>) {
    if (updates.isEmpty()) return

    commitPlaylistMutation {
        val updateIndex = SongMetadataUpdateIndex(
            updates.map { (originalSong, hydratedSong) ->
                SongMetadataUpdate(
                    originalSong = originalSong,
                    newSongInfo = hydratedSong
                )
            }
        )
        var changed = false
        val updated = _playlists.value.map { playlist ->
            var playlistChanged = false
            val refreshedSongs = playlist.songs.map { currentSong ->
                val hydratedSong = updateIndex.find(currentSong)?.newSongInfo
                    ?: return@map currentSong
                val durationMs = hydratedSong.durationMs
                    .takeIf { it > 0L && currentSong.durationMs <= 0L }
                    ?: return@map currentSong
                changed = true
                playlistChanged = true
                currentSong.copy(durationMs = durationMs)
            }.toMutableList()
            if (playlistChanged) {
                playlist.copy(
                    songs = refreshedSongs,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            } else {
                playlist
            }
        }
        if (changed) {
            publishLocked(
                playlists = updated,
                triggerSync = false,
                markLocalMutation = false
            )
        }
    }
}

internal fun LocalPlaylistRepository.mergeSongMetadataForPersistence(
    currentSong: SongItem,
    newSongInfo: SongItem,
    clearCoverUrl: Boolean = false,
    clearOriginalCoverUrl: Boolean = false
): SongItem {
    val mergedSong = newSongInfo.copy(
        addedAt = currentSong.addedAt,
        logicalCreatedAtMs = currentSong.logicalCreatedAtMs
            ?: newSongInfo.logicalCreatedAtMs,
        createdAtSource = currentSong.createdAtSource
            ?: newSongInfo.createdAtSource,
        createdAtConfidence = currentSong.createdAtConfidence
            ?: newSongInfo.createdAtConfidence,
        membershipAddedAtMs = currentSong.membershipAddedAtMs
            ?: newSongInfo.membershipAddedAtMs,
        coverUrl = if (clearCoverUrl) {
            null
        } else {
            newSongInfo.coverUrl.takeIf { !it.isNullOrBlank() }
                ?: currentSong.coverUrl
        },
        originalCoverUrl = if (clearOriginalCoverUrl) {
            null
        } else {
            newSongInfo.originalCoverUrl.takeIf { !it.isNullOrBlank() }
                ?: currentSong.originalCoverUrl
                ?: currentSong.coverUrl.takeUnless { clearCoverUrl }
        },
        syncMembershipTokens = currentSong.syncMembershipTokens.normalizedSyncCausalTokens()
    )
    if (!shouldPreserveEntryPlaybackSource(currentSong, newSongInfo)) {
        return mergedSong
    }

    // 下载副本可共享远端身份，但文件引用只能由下载副本持有
    return mergedSong.copy(
        id = currentSong.id,
        name = currentSong.name,
        artist = currentSong.artist,
        album = currentSong.album,
        albumId = currentSong.albumId,
        durationMs = currentSong.durationMs,
        coverUrl = currentSong.coverUrl.takeUnless { clearCoverUrl },
        originalName = currentSong.originalName,
        originalArtist = currentSong.originalArtist,
        originalCoverUrl = if (clearOriginalCoverUrl) {
            null
        } else {
            currentSong.originalCoverUrl ?: currentSong.coverUrl.takeUnless { clearCoverUrl }
        },
        mediaUri = currentSong.mediaUri,
        localFileName = currentSong.localFileName,
        localFilePath = currentSong.localFilePath,
        channelId = currentSong.channelId,
        audioId = currentSong.audioId,
        subAudioId = currentSong.subAudioId,
        playlistContextId = currentSong.playlistContextId,
        sourceStableKey = currentSong.sourceStableKey,
        streamUrl = currentSong.streamUrl,
        neteaseArtists = currentSong.neteaseArtists
    )
}

internal fun LocalPlaylistRepository.shouldPreserveEntryPlaybackSource(
    currentSong: SongItem,
    newSongInfo: SongItem
): Boolean {
    val currentIsLocal = LocalSongSupport.isLocalSong(currentSong, null)
    val updatedIsLocal = LocalSongSupport.isLocalSong(newSongInfo, null)
    if (currentIsLocal != updatedIsLocal) {
        return true
    }
    if (!currentIsLocal) {
        return false
    }
    return LocalSongSupport.identityMediaReference(currentSong) !=
        LocalSongSupport.identityMediaReference(newSongInfo)
}

internal fun LocalPlaylistRepository.saveCoverMapping(newSongInfo: SongItem) {
    runCatching {
        val mapper = CoverUrlMapper.getInstance(context)
        if (newSongInfo.coverUrl != null && newSongInfo.originalCoverUrl != null) {
            mapper.saveCoverMapping(newSongInfo.coverUrl, newSongInfo.originalCoverUrl)
        }
        if (newSongInfo.customCoverUrl != null && newSongInfo.originalCoverUrl != null) {
            mapper.saveCoverMapping(newSongInfo.customCoverUrl, newSongInfo.originalCoverUrl)
        }
    }.onFailure {
        NPLogger.e("LocalPlaylistRepo", "Failed to save cover mapping", it)
    }
}

internal fun LocalPlaylistRepository.mergeExternalPlaylists(playlists: List<LocalPlaylist>): MutableList<LocalPlaylist> {
    val preservedLocalFiles = LocalFilesPlaylist.firstOrNull(_playlists.value, context)
    return playlists
        .filterNot { LocalFilesPlaylist.isSystemPlaylist(it, context) }
        .toMutableList()
        .apply { preservedLocalFiles?.let(::add) }
}

internal fun LocalPlaylistRepository.resolveLikedNeteasePlaylistId(client: NeteaseClient): Long? {
    val raw = runCatching { client.getLikedPlaylistId(0) }
        .getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "getLikedPlaylistId failed: ${error.message}", error)
            return null
        }
    if (parseNeteaseCode(raw) == 301 && client.hasLogin()) {
        runCatching { client.ensureWeapiSession() }.onFailure {
            NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession retry failed: ${it.message}")
        }
        val retried = runCatching { client.getLikedPlaylistId(0) }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "getLikedPlaylistId retry failed: ${error.message}", error)
                return null
            }
        return parseNeteaseLikedPlaylistId(retried).playlistId
    }
    return parseNeteaseLikedPlaylistId(raw).playlistId
}

internal fun LocalPlaylistRepository.buildNeteasePlaylistSyncPlan(
    client: NeteaseClient,
    targetPlaylistId: Long,
    totalSongs: Int,
    validatedSummary: NeteaseCandidateValidationResult
): NeteaseRemotePlaylistSyncPlan {
    val targetSnapshot = fetchNeteasePlaylistTrackSnapshot(client, targetPlaylistId)
    if (!targetSnapshot.compareSucceeded) {
        return NeteaseRemotePlaylistSyncPlan(
            targetPlaylistId = targetPlaylistId,
            totalSongs = totalSongs,
            supportedSongs = validatedSummary.supportedSongs,
            skippedUnsupported = validatedSummary.skippedUnsupported,
            skippedExisting = validatedSummary.skippedExisting,
            candidates = emptyList(),
            compareSucceeded = false,
            message = targetSnapshot.message ?: LocalPlaylistRepository.NETEASE_COMPARE_FAILED_MESSAGE
        )
    }

    var skippedExisting = validatedSummary.skippedExisting
    val pendingCandidates = ArrayList<NeteaseResolvedCandidate>(validatedSummary.candidates.size)
    validatedSummary.candidates.forEach { candidate ->
        val fingerprint = candidate.song.toNeteaseFingerprint()
        if (candidate.neteaseId in targetSnapshot.trackIds ||
            (fingerprint != null && fingerprint in targetSnapshot.fingerprints)
        ) {
            skippedExisting += 1
        } else {
            pendingCandidates += candidate
        }
    }

    val message = if (pendingCandidates.isEmpty()) {
        context.getString(R.string.local_playlist_sync_netease_all_synced)
    } else {
        null
    }

    return NeteaseRemotePlaylistSyncPlan(
        targetPlaylistId = targetPlaylistId,
        totalSongs = totalSongs,
        supportedSongs = validatedSummary.supportedSongs,
        skippedUnsupported = validatedSummary.skippedUnsupported,
        skippedExisting = skippedExisting,
        candidates = pendingCandidates,
        compareSucceeded = true,
        message = message
    )
}
