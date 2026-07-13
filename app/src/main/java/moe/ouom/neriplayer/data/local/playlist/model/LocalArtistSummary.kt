package moe.ouom.neriplayer.data.local.playlist.model

import android.content.Context
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.SongItem
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
        get() = songs.firstOrNull()
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
    val sourceSongs = distinctLocalArtistSourceSongs(playlists.flatMap { it.songs })

    return buildLocalArtistSummaries(
        songs = sourceSongs,
        unknownArtist = context.getString(R.string.music_unknown_artist)
    )
}

internal fun buildLocalArtistSummaries(
    songs: List<SongItem>,
    unknownArtist: String
): List<LocalArtistSummary> {
    val sourceSongs = distinctLocalArtistSourceSongs(songs)
    if (sourceSongs.isEmpty()) return emptyList()

    val groups = linkedMapOf<String, MutableLocalArtistGroup>()
    sourceSongs.forEach { song ->
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

private fun distinctLocalArtistSourceSongs(songs: List<SongItem>): List<SongItem> {
    if (songs.size <= 1) return songs

    val duplicateIndex = LocalArtistSongDuplicateIndex()
    return songs.filter { song ->
        if (duplicateIndex.contains(song)) {
            false
        } else {
            duplicateIndex.add(song)
            true
        }
    }
}

private class LocalArtistSongDuplicateIndex {
    private val identities = HashSet<SongIdentity>()
    private val localKeys = HashSet<String>()
    private val localMetadataKeys = HashSet<String>()
    private val remoteMetadataKeys = HashSet<String>()

    fun add(song: SongItem) {
        identities += song.identity()
        localKeys += LocalSongSupport.localDuplicateKeys(
            song = song,
            includeMetadataFallback = true
        )

        val metadataKeys = localArtistMetadataDuplicateKeys(song)
        if (LocalSongSupport.isLocalSong(song, null)) {
            localMetadataKeys += metadataKeys
        } else {
            remoteMetadataKeys += metadataKeys
        }
    }

    fun contains(song: SongItem): Boolean {
        if (song.identity() in identities) return true

        val songLocalKeys = LocalSongSupport.localDuplicateKeys(
            song = song,
            includeMetadataFallback = true
        )
        if (songLocalKeys.any(localKeys::contains)) return true

        val metadataKeys = localArtistMetadataDuplicateKeys(song)
        if (LocalSongSupport.isLocalSong(song, null)) {
            return metadataKeys.any(localMetadataKeys::contains) ||
                metadataKeys.any(remoteMetadataKeys::contains)
        }
        return metadataKeys.any(localMetadataKeys::contains)
    }
}

private fun localArtistMetadataDuplicateKeys(song: SongItem): Set<String> {
    val durationMs = song.durationMs.takeIf { it > 0L } ?: return emptySet()
    val title = normalizeLocalArtistDuplicateText(
        song.originalName?.takeIf { it.isNotBlank() } ?: song.displayName()
    )
    val artist = normalizeLocalArtistDuplicateText(
        song.originalArtist?.takeIf { it.isNotBlank() } ?: song.displayArtist()
    )
    if (title.isBlank() || artist.isBlank()) {
        return emptySet()
    }
    return setOf("meta:$title|$artist|$durationMs")
}

private fun normalizeLocalArtistDuplicateText(value: String): String {
    return value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(LOCAL_ARTIST_DUPLICATE_WHITESPACE_PATTERN, " ")
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
private val LOCAL_ARTIST_DUPLICATE_WHITESPACE_PATTERN = Regex("\\s+")

private const val FNV_64_OFFSET_BASIS = -3750763034362895579L
private const val FNV_64_PRIME = 1099511628211L
