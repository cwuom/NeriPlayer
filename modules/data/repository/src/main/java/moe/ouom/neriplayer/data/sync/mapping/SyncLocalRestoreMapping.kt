package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist

internal class SyncLocalRestoreMapping(private val localizedContext: Context) {
    fun playlists(data: SyncData, current: List<LocalPlaylist>): List<LocalPlaylist> {
        val currentPlaylists = current.associateBy(::normalizedLocalPlaylistId)
        return data.playlists.filterNot(SyncPlaylist::isDeleted).map { playlist ->
            restorePlaylist(playlist, currentPlaylists)
        }
    }

    private fun normalizedLocalPlaylistId(playlist: LocalPlaylist): Long =
        SystemLocalPlaylists.resolve(playlist.id, playlist.name, localizedContext)?.id ?: playlist.id

    private fun restorePlaylist(playlist: SyncPlaylist, current: Map<Long, LocalPlaylist>): LocalPlaylist {
        val descriptor = SystemLocalPlaylists.resolve(playlist.id, playlist.name, localizedContext)
        val id = descriptor?.id ?: playlist.id
        val existing = current[id]
        val songs = playlist.songs.map { it.toSongItem() }.distinctBy { it.identity() }
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
        val syncedHistory = data.recentPlays.mapNotNull { syncPlay ->
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
        val localOnlyHistory = current.filter {
            LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext)
        }
        val playHistory = mergeLocalOnlyHistory(syncedHistory, localOnlyHistory)
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
            .take(500)
    }

}
