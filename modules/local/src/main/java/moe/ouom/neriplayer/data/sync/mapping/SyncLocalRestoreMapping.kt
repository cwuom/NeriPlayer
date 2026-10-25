package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.history.toPlayedEntry
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.sync.identity.identity
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.validExplicitLocalSourceReference
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.toLegacyLyricRecoveryCandidateOrNull

internal class SyncLocalRestoreMapping(
    private val localizedContext: Context,
    private val retainLegacyLyrics: (List<SyncSong>) -> Unit = {}
) {
    fun playlists(data: SyncData, current: List<LocalPlaylist>): List<LocalPlaylist> {
        val candidates = mutableListOf<SyncSong>()
        val currentPlaylists = current.associateBy(::normalizedLocalPlaylistId)
        val restoredIds = mutableSetOf<Long>()
        val restored = data.playlists.filterNot(SyncPlaylist::isDeleted).map { playlist ->
            restorePlaylist(playlist, currentPlaylists, candidates).also { restoredIds += it.id }
        }
        for ((id, playlist) in currentPlaylists) {
            if (id !in restoredIds) playlist.songs.forEach { it.toLegacyLyricRecoveryCandidateOrNull()?.let(candidates::add) }
        }
        retainLegacyLyrics(candidates)
        return restored
    }

    private fun normalizedLocalPlaylistId(playlist: LocalPlaylist): Long =
        SystemLocalPlaylists.resolve(playlist.id, playlist.name, localizedContext)?.id ?: playlist.id

    private fun restorePlaylist(playlist: SyncPlaylist, current: Map<Long, LocalPlaylist>, candidates: MutableList<SyncSong>): LocalPlaylist {
        val descriptor = SystemLocalPlaylists.resolve(playlist.id, playlist.name, localizedContext)
        val id = descriptor?.id ?: playlist.id
        val isFavorites = id == FavoritesPlaylist.SYSTEM_ID
        val existing = current[id]
        val existingSongs = indexPlaylistSongs(existing, isFavorites, candidates)
        val songs = playlist.songs.map { song ->
            val currentSong = existingSongs[song.identity()]
            val restored = song.toSongItem(currentSong)
            if (isFavorites && currentSong != null && LocalSongSupport.isLocalSong(currentSong, localizedContext)) {
                restoreFavoriteLocalSource(restored, currentSong)
            } else {
                restored
            }
        }.distinctBy { it.identity() }
        return LocalPlaylist(
            id = id,
            name = restoredName(playlist, descriptor),
            songs = mergeLocalOnlySongs(songs, preservedLocalSongs(existing, isFavorites)),
            modifiedAt = playlist.modifiedAt,
            customCoverUrl = existing?.customCoverUrl,
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
        )
    }

    private fun restoredName(playlist: SyncPlaylist, descriptor: SystemLocalPlaylists.Descriptor?): String =
        descriptor?.currentName ?: playlist.name

    private fun indexPlaylistSongs(
        playlist: LocalPlaylist?,
        isFavorites: Boolean,
        candidates: MutableList<SyncSong>
    ): Map<SongIdentity, SongItem> {
        if (playlist == null) return emptyMap()
        playlist.songs.forEach { song ->
            song.toLegacyLyricRecoveryCandidateOrNull()?.let(candidates::add)
        }
        val songs = if (isFavorites) FavoritesPlaylist.mergedSongs(listOf(playlist)) else playlist.songs
        return songs.associateBy { song ->
            if (isFavorites) {
                FavoritesPlaylist.toSyncableSongOrNull(song, localizedContext)?.identity() ?: song.identity()
            } else {
                song.identity()
            }
        }
    }

    private fun restoreFavoriteLocalSource(restored: SongItem, local: SongItem): SongItem = restored.copy(
        id = local.id,
        album = local.album,
        albumId = local.albumId,
        mediaUri = local.mediaUri,
        localFileName = local.localFileName,
        localFilePath = local.localFilePath,
        channelId = local.channelId,
        audioId = local.audioId,
        subAudioId = local.subAudioId,
        playlistContextId = local.playlistContextId,
        sourceStableKey = local.sourceStableKey,
        streamUrl = local.streamUrl,
        neteaseArtists = local.neteaseArtists,
        logicalCreatedAtMs = local.logicalCreatedAtMs,
        createdAtSource = local.createdAtSource,
        createdAtConfidence = local.createdAtConfidence,
        membershipAddedAtMs = restored.addedAt,
        sourceModifiedAtMs = local.sourceModifiedAtMs,
        explicitLocalSourceReference = local.explicitLocalSourceReference,
        // 纯本地文件仍依靠这组匹配字段关联收藏，远端条目可能没有歌词匹配信息
        matchedLyricSource = if (local.remoteSourceIdentityOrNull() == null) local.matchedLyricSource else restored.matchedLyricSource,
        matchedSongId = if (local.remoteSourceIdentityOrNull() == null) local.matchedSongId else restored.matchedSongId
    )

    private fun preservedLocalSongs(playlist: LocalPlaylist?, isFavorites: Boolean): List<SongItem> =
        playlist?.songs.orEmpty().filter {
            LocalSongSupport.isLocalSong(it, localizedContext) &&
                !(isFavorites && FavoritesPlaylist.toSyncableSongOrNull(it, localizedContext) != null) &&
                !(it.validExplicitLocalSourceReference() != null && it.remoteSourceIdentityOrNull() != null)
        }

    fun history(data: SyncData, current: List<PlayedEntry>): List<PlayedEntry> {
        val candidates = mutableListOf<SyncSong>()
        val existingEntries = indexExistingHistory(current, candidates)
        val syncedHistory = data.recentPlays.mapNotNull { restoreHistoryEntry(it, existingEntries) }
        val localOnlyHistory = current.filter(::isLocalOnlyHistory)
        val playHistory = mergeLocalOnlyHistory(syncedHistory, localOnlyHistory)
        retainLegacyLyrics(candidates)
        return playHistory
    }

    private fun indexExistingHistory(
        current: List<PlayedEntry>,
        candidates: MutableList<SyncSong>
    ): Map<SongIdentity, PlayedEntry> = current.associateBy {
        val song = it.toSongItem()
        song.toLegacyLyricRecoveryCandidateOrNull()?.let(candidates::add)
        song.identity()
    }

    private fun restoreHistoryEntry(
        syncPlay: SyncRecentPlay,
        existing: Map<SongIdentity, PlayedEntry>
    ): PlayedEntry? {
        val source = syncPlay.song
        if (LocalSongSupport.isLocalSong(source.album, source.mediaUri, source.albumId, localizedContext)) {
            return null
        }
        val song = source.toSongItem()
        return source.toSongItem(existing[song.identity()]?.toSongItem())
            .toPlayedEntry(syncPlay.playedAt)
            .copy(resumePositionMs = syncPlay.resumePositionMs)
    }

    private fun isLocalOnlyHistory(entry: PlayedEntry): Boolean =
        !entry.localFilePath.isNullOrBlank() ||
            LocalSongSupport.isLocalSong(entry.album, entry.mediaUri, entry.albumId, localizedContext)

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
    }

}
