package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.history.toPlayedEntry
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.sync.identity.identity
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
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
        val existing = current[id]
        val existingSongs = existing?.songs.orEmpty().associateBy {
            it.toLegacyLyricRecoveryCandidateOrNull()?.let(candidates::add)
            it.identity()
        }
        val songs = playlist.songs.map { song ->
            song.toSongItem(existingSongs[song.identity()])
        }.distinctBy { it.identity() }
        return LocalPlaylist(
            id = id,
            name = restoredName(playlist, descriptor),
            songs = mergeLocalOnlySongs(songs, preservedLocalSongs(existing)),
            modifiedAt = playlist.modifiedAt,
            customCoverUrl = existing?.customCoverUrl,
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
        )
    }

    private fun restoredName(playlist: SyncPlaylist, descriptor: SystemLocalPlaylists.Descriptor?): String =
        descriptor?.currentName ?: playlist.name

    private fun preservedLocalSongs(playlist: LocalPlaylist?): List<moe.ouom.neriplayer.data.model.SongItem> =
        playlist?.songs.orEmpty().filter { LocalSongSupport.isLocalSong(it, localizedContext) }

    fun history(data: SyncData, current: List<PlayedEntry>): List<PlayedEntry> {
        val candidates = mutableListOf<SyncSong>()
        val existingEntries = current.associateBy {
            val song = it.toSongItem()
            song.toLegacyLyricRecoveryCandidateOrNull()?.let(candidates::add)
            song.identity()
        }
        val syncedHistory = data.recentPlays.mapNotNull { syncPlay ->
            if (LocalSongSupport.isLocalSong(syncPlay.song.album, syncPlay.song.mediaUri, syncPlay.song.albumId, localizedContext)) {
                return@mapNotNull null
            }

            val song = syncPlay.song.toSongItem()
            syncPlay.song.toSongItem(existingEntries[song.identity()]?.toSongItem())
                .toPlayedEntry(syncPlay.playedAt)
                .copy(resumePositionMs = syncPlay.resumePositionMs)
        }
        val localOnlyHistory = current.filter {
            LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext)
        }
        val playHistory = mergeLocalOnlyHistory(syncedHistory, localOnlyHistory)
        retainLegacyLyrics(candidates)
        return playHistory
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
    }

}
