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

    return buildLocalArtistSummaries(
        songs = sourceSongs,
        unknownArtist = context.getString(R.string.music_unknown_artist)
    )
}

internal fun buildLocalArtistSummaries(
    songs: List<SongItem>,
    unknownArtist: String
): List<LocalArtistSummary> {
    if (songs.isEmpty()) return emptyList()

    val groups = linkedMapOf<String, MutableLocalArtistGroup>()
    songs.forEach { song ->
        localArtistNamesForSong(song, unknownArtist).forEach { artistName ->
            val key = localArtistStableKey(artistName)
            groups.getOrPut(key) { MutableLocalArtistGroup(artistName) }
                .songs
                .add(song)
        }
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

internal fun splitLocalArtistNames(
    rawArtist: String,
    unknownArtist: String
): List<String> {
    return rawArtist
        .split(LOCAL_ARTIST_TEXT_SPLIT_PATTERN)
        .flatMap(::splitSlashSeparatedLocalArtists)
        .map { artist -> artist.trim() }
        .filter { artist -> artist.isNotBlank() }
        .distinctBy { artist -> localArtistStableKey(artist) }
        .ifEmpty { listOf(unknownArtist) }
}

private fun localArtistNamesForSong(
    song: SongItem,
    unknownArtist: String
): List<String> {
    song.customArtist?.takeIf { it.isNotBlank() }?.let { customArtist ->
        return splitLocalArtistNames(customArtist, unknownArtist)
    }

    val structuredArtists = song.neteaseArtists.orEmpty()
        .map { artist -> artist.name.trim() }
        .filter { artist -> artist.isNotBlank() }
        .distinctBy { artist -> localArtistStableKey(artist) }
    if (structuredArtists.isNotEmpty()) {
        return structuredArtists
    }

    return splitLocalArtistNames(song.displayArtist(), unknownArtist)
}

private class MutableLocalArtistGroup(
    val name: String,
    val songs: MutableList<SongItem> = mutableListOf()
)

private fun splitSlashSeparatedLocalArtists(rawArtist: String): List<String> {
    val artist = rawArtist.trim()
    if (artist.isBlank()) return emptyList()

    val spacedParts = SPACED_SLASH_SPLIT_PATTERN.split(artist)
    if (spacedParts.size > 1) {
        return spacedParts.flatMap(::splitCompactSlashSeparatedLocalArtists)
    }

    return splitCompactSlashSeparatedLocalArtists(artist)
}

private fun splitCompactSlashSeparatedLocalArtists(rawArtist: String): List<String> {
    val artist = rawArtist.trim()
    if (artist.isBlank()) return emptyList()
    val slashParts = artist.split('/', '／')
    if (slashParts.size <= 1 || shouldKeepCompactSlashArtistName(slashParts)) {
        return listOf(artist)
    }
    return slashParts
}

private fun shouldKeepCompactSlashArtistName(parts: List<String>): Boolean {
    if (parts.size != 2) return false
    return parts.all { part -> part.trim().isShortUpperAsciiArtistToken() }
}

private fun String.isShortUpperAsciiArtistToken(): Boolean {
    val token = trim()
    return token.length in 1..4 &&
        token.all { char -> char in 'A'..'Z' || char in '0'..'9' }
}

private val LOCAL_ARTIST_TEXT_SPLIT_PATTERN = Regex(
    pattern = """\s+(?:feat\.?|ft\.?|with|和|与)\s+|[\u0000;；、，]""",
    option = RegexOption.IGNORE_CASE
)

private val SPACED_SLASH_SPLIT_PATTERN = Regex("""\s+[/／]\s+""")

private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
