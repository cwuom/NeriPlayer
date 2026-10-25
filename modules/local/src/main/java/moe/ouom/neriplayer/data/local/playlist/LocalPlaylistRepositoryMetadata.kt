package moe.ouom.neriplayer.data.local.playlist

import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.SongMetadataUpdate
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.SongMetadataUpdateIndex
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.preferredLocalMediaReference
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.model.sync.normalizedSyncCausalTokens
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger

internal suspend fun LocalPlaylistRepository.applySongMetadataUpdates(updates: List<SongMetadataUpdate>) {
    if (updates.isEmpty()) {
        return
    }

    commitPlaylistMutation {
        val updateIndex = SongMetadataUpdateIndex(updates)
        val now = System.currentTimeMillis()
        var changed = false
        var syncMutation = LocalPlaylistSyncMutation()
        val updated = _playlists.value.map { playlist ->
            var playlistChanged = false
            var modifiedAt = playlist.modifiedAt
            val refreshedSongs = playlist.songs.map { currentSong ->
                val update = updateIndex.find(currentSong) ?: return@map currentSong
                val metadataUpdate = prepareFavoriteMetadataMembership(
                    playlist = playlist,
                    currentSong = currentSong,
                    mergedSong = mergeSongMetadataForPersistence(
                        currentSong = currentSong,
                        newSongInfo = update.newSongInfo,
                        clearCoverUrl = update.clearCoverUrl,
                        clearOriginalCoverUrl = update.clearOriginalCoverUrl
                    ),
                    triggerSync = false,
                    now = now
                )
                val mergedSongInfo = metadataUpdate.song
                if (currentSong == mergedSongInfo) {
                    currentSong
                } else {
                    syncMutation += metadataUpdate.syncMutation
                    if (!metadataUpdate.syncMutation.isEmpty) modifiedAt = now
                    saveCoverMapping(mergedSongInfo)
                    changed = true
                    playlistChanged = true
                    mergedSongInfo
                }
            }.toMutableList()

            if (playlistChanged) {
                playlist.copy(
                    songs = refreshedSongs,
                    modifiedAt = modifiedAt,
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
                markLocalMutation = false,
                syncMutation = syncMutation
            )
        }
    }
}

internal data class FavoriteMetadataMembershipUpdate(
    val song: SongItem,
    val syncMutation: LocalPlaylistSyncMutation = LocalPlaylistSyncMutation()
)

internal fun LocalPlaylistRepository.prepareFavoriteMetadataMembership(
    playlist: LocalPlaylist,
    currentSong: SongItem,
    mergedSong: SongItem,
    triggerSync: Boolean,
    now: Long
): FavoriteMetadataMembershipUpdate {
    if (!FavoritesPlaylist.isSystemPlaylist(playlist, context)) {
        return FavoriteMetadataMembershipUpdate(mergedSong)
    }
    val previousIdentity = FavoritesPlaylist.toSyncableSongOrNull(currentSong, context)?.identity()
    val nextIdentity = FavoritesPlaylist.toSyncableSongOrNull(mergedSong, context)?.identity()
    if (previousIdentity == nextIdentity) {
        return FavoriteMetadataMembershipUpdate(mergedSong)
    }
    if (!triggerSync && previousIdentity != null) {
        // 自动补全可以刷新文本，但不能静默替换已经收藏的远端成员
        return FavoriteMetadataMembershipUpdate(mergedSong.copy(
            matchedLyricSource = currentSong.matchedLyricSource,
            matchedSongId = currentSong.matchedSongId
        ))
    }

    val updatedSong = if (nextIdentity == null) mergedSong else {
        renewSongsForPlaylistRestore(listOf(mergedSong)).single()
    }
    return FavoriteMetadataMembershipUpdate(
        song = updatedSong,
        syncMutation = buildPlaylistSongDeletionMutation(playlist.id, listOf(currentSong), now) +
            buildPlaylistSongDeletionRemoval(playlist.id, listOf(updatedSong))
    )
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
    clearOriginalCoverUrl: Boolean = false,
    allowScannedSourceReplacement: Boolean = false
): SongItem {
    val currentExplicitReference = currentSong.validExplicitLocalSourceReference()
    val updatedExplicitReference = newSongInfo.validExplicitLocalSourceReference()
    val updatedPlaybackReference = preferredLocalMediaReference(newSongInfo.localFilePath, newSongInfo.mediaUri)
    val replacesUnmarkedSource = allowScannedSourceReplacement && updatedExplicitReference != null &&
        currentExplicitReference == null && currentSong.sameIdentityAs(newSongInfo)
    val restoreEmptyBaseCover = newSongInfo.originalCoverUrl == "" && newSongInfo.coverUrl == null
    val mergedSong = newSongInfo.copy(
        explicitLocalSourceReference = updatedExplicitReference
            ?: currentExplicitReference?.takeIf { it == updatedPlaybackReference },
        addedAt = currentSong.addedAt,
        sourceModifiedAtMs = currentSong.sourceModifiedAtMs ?: newSongInfo.sourceModifiedAtMs,
        logicalCreatedAtMs = currentSong.logicalCreatedAtMs
            ?: newSongInfo.logicalCreatedAtMs,
        createdAtSource = currentSong.createdAtSource
            ?: newSongInfo.createdAtSource,
        createdAtConfidence = currentSong.createdAtConfidence
            ?: newSongInfo.createdAtConfidence,
        membershipAddedAtMs = currentSong.membershipAddedAtMs
            ?: newSongInfo.membershipAddedAtMs,
        coverUrl = if (clearCoverUrl || restoreEmptyBaseCover) {
            null
        } else {
            newSongInfo.coverUrl.takeIf { !it.isNullOrBlank() }
                ?: currentSong.coverUrl
        },
        originalCoverUrl = if (clearOriginalCoverUrl) {
            null
        } else {
            newSongInfo.originalCoverUrl.takeIf { it == "" || !it.isNullOrBlank() }
                ?: currentSong.originalCoverUrl
                ?: currentSong.coverUrl.takeUnless { clearCoverUrl }
        },
        syncMembershipTokens = currentSong.syncMembershipTokens.normalizedSyncCausalTokens()
    )
    if (replacesUnmarkedSource || !shouldPreserveEntryPlaybackSource(currentSong, newSongInfo)) {
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
        explicitLocalSourceReference = currentExplicitReference,
        streamUrl = currentSong.streamUrl,
        neteaseArtists = currentSong.neteaseArtists
    )
}

internal fun SongItem.validExplicitLocalSourceReference(): String? =
    LocalSongSupport.validExplicitLocalSourceReference(this)

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

internal fun LocalPlaylistRepository.mergeExternalPlaylists(
    playlists: List<LocalPlaylist>,
    preserveExplicitLocalSources: Boolean = false
): MutableList<LocalPlaylist> {
    val current = _playlists.value
    val preservedLocalFiles = LocalFilesPlaylist.firstOrNull(current, context)
    val sourcePlaylists = if (preserveExplicitLocalSources) current.associateBy { it.id } else emptyMap()
    return playlists
        .filterNot { LocalFilesPlaylist.isSystemPlaylist(it, context) }
        .map { playlist ->
            val existing = sourcePlaylists[playlist.id] ?: return@map playlist
            preserveSyncedLocalSources(playlist, existing)
        }
        .toMutableList()
        .apply { preservedLocalFiles?.let(::add) }
}

private fun preserveSyncedLocalSources(incoming: LocalPlaylist, current: LocalPlaylist): LocalPlaylist {
    val sources = current.songs.filter { it.validExplicitLocalSourceReference() != null }
        .associateBy { it.identity() }
    if (sources.isEmpty()) return incoming
    return incoming.copy(songs = incoming.songs.map { song ->
        val source = sources[song.identity()] ?: return@map song
        // 只保留当前歌单仍在同步结果中的成员来源，远端编辑和成员顺序沿用同步结果
        song.copy(
            mediaUri = source.mediaUri,
            localFileName = source.localFileName,
            localFilePath = source.localFilePath,
            channelId = source.channelId,
            audioId = source.audioId,
            subAudioId = source.subAudioId,
            playlistContextId = source.playlistContextId,
            sourceStableKey = source.sourceStableKey,
            explicitLocalSourceReference = source.explicitLocalSourceReference,
            streamUrl = source.streamUrl
        )
    }.toMutableList())
}
