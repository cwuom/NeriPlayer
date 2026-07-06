package moe.ouom.neriplayer.data.local.playlist.model

import android.content.Context
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import java.util.Locale

data class LocalArtistSummary(
    val name: String,
    val songs: List<SongItem>
) {
    val id: Long
        get() = localArtistStableId(name)

    val stableKey: String
        get() = localArtistStableKey(name)

    val coverSong: SongItem?
        get() = songs.lastOrNull()
}

fun localArtistStableKey(name: String): String {
    return name.trim().lowercase(Locale.ROOT)
}

fun localArtistStableId(name: String): Long {
    val key = localArtistStableKey(name)
    var hash = FNV_64_OFFSET_BASIS
    key.forEach { char ->
        hash = hash xor char.code.toLong()
        hash *= FNV_64_PRIME
    }
    return hash and Long.MAX_VALUE
}

fun buildLocalArtistSummaries(
    playlists: List<LocalPlaylist>,
    context: Context
): List<LocalArtistSummary> {
    val sourceSongs = playlists
        .flatMap { it.songs }
        .distinctBy { it.identity() }

    if (sourceSongs.isEmpty()) return emptyList()

    val unknownArtist = context.getString(R.string.music_unknown_artist)
    val groups = linkedMapOf<String, MutableLocalArtistGroup>()
    sourceSongs.forEach { song ->
        val artistName = song.displayArtist().trim().ifBlank { unknownArtist }
        val key = localArtistStableKey(artistName)
        groups.getOrPut(key) { MutableLocalArtistGroup(artistName) }
            .songs
            .add(song)
    }

    return groups.values
        .map { group -> LocalArtistSummary(name = group.name, songs = group.songs.toList()) }
        .sortedWith(
            compareByDescending<LocalArtistSummary> { summary ->
                summary.coverSong?.addedAt ?: 0L
            }.thenBy { summary ->
                summary.name.lowercase(Locale.ROOT)
            }
        )
}

private class MutableLocalArtistGroup(
    val name: String,
    val songs: MutableList<SongItem> = mutableListOf()
)

private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
