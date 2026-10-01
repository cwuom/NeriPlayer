package moe.ouom.neriplayer.platform.netease.playlist

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashSet
import java.util.Locale

internal const val NETEASE_ALBUM_PREFIX = "Netease"

internal fun resolveNeteaseSongId(song: SongItem): Long? {
    resolveNeteaseAudioId(song)?.let { return it }
    val songId = song.id.takeIf { it > 0 } ?: return null
    if (song.hasNeteaseSongSource()) {
        return songId
    }
    resolveMatchedNeteaseSongId(song)?.let { return it }
    if (song.hasNeteaseCoverUrl()) {
        return songId
    }
    return null
}

private fun resolveNeteaseAudioId(song: SongItem): Long? {
    if (!song.channelId.equals("netease", ignoreCase = true)) return null
    return song.audioId.toPositiveNeteaseSongId()
}

private fun resolveMatchedNeteaseSongId(song: SongItem): Long? {
    if (song.matchedLyricSource != MusicPlatform.CLOUD_MUSIC) return null
    return song.matchedSongId.toPositiveNeteaseSongId()
}

private fun String?.toPositiveNeteaseSongId(): Long? {
    return this?.toLongOrNull()?.takeIf { it > 0L }
}

private fun SongItem.hasNeteaseSongSource(): Boolean {
    return channelId.equals("netease", ignoreCase = true) || album.startsWith(NETEASE_ALBUM_PREFIX)
}

private fun SongItem.hasNeteaseCoverUrl(): Boolean {
    return coverUrl.isNeteaseCoverUrl() || originalCoverUrl.isNeteaseCoverUrl()
}

internal fun buildLocalNeteaseCandidates(songs: List<SongItem>): LocalNeteaseCandidateSummary {
    if (songs.isEmpty()) {
        return LocalNeteaseCandidateSummary(
            supportedSongs = 0,
            skippedUnsupported = 0,
            skippedExisting = 0,
            candidates = emptyList()
        )
    }

    var supportedSongs = 0
    var skippedExisting = 0
    val seenNeteaseIds = mutableSetOf<Long>()
    val candidates = ArrayList<NeteaseResolvedCandidate>(songs.size)
    for (candidate in resolveLocalNeteaseCandidates(songs)) {
        val neteaseId = candidate.neteaseId
        if (!seenNeteaseIds.add(neteaseId)) {
            // 同一首网易云歌曲只保留最早出现的那条，保证顺序稳定
            skippedExisting += 1
            continue
        }
        supportedSongs += 1
        candidates += candidate
    }
    val skippedUnsupported = songs.size - supportedSongs - skippedExisting
    return LocalNeteaseCandidateSummary(
        supportedSongs = supportedSongs,
        skippedUnsupported = skippedUnsupported,
        skippedExisting = skippedExisting,
        candidates = candidates
    )
}

internal fun resolveLocalNeteaseCandidates(songs: List<SongItem>): List<NeteaseResolvedCandidate> {
    return songs.mapNotNull { song ->
        resolveNeteaseSongId(song)?.let { neteaseId ->
            NeteaseResolvedCandidate(song = song, neteaseId = neteaseId)
        }
    }
}

internal fun parseNeteaseLikedPlaylistId(raw: String): ParsedNeteasePlaylistId {
    if (raw.isBlank()) return ParsedNeteasePlaylistId(playlistId = null, success = false)
    return runCatching {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) {
            return@runCatching ParsedNeteasePlaylistId(playlistId = null, success = false)
        }
        val id = root.optLong("playlistId", 0L)
        ParsedNeteasePlaylistId(
            playlistId = id.takeIf { it > 0L },
            success = true
        )
    }.getOrElse { error ->
        NPLogger.e("LocalPlaylistRepo", "Failed to parse liked playlist id: ${error.message}", error)
        ParsedNeteasePlaylistId(playlistId = null, success = false)
    }
}

internal fun parseNeteaseTrackIdsFromPlaylistDetail(raw: String): ParsedNeteasePlaylistTrackIds {
    if (raw.isBlank()) {
        return ParsedNeteasePlaylistTrackIds(
            trackIds = emptyList(),
            trackCount = 0,
            success = false
        )
    }
    return runCatching {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) {
            return@runCatching ParsedNeteasePlaylistTrackIds(
                trackIds = emptyList(),
                trackCount = 0,
                success = false
            )
        }
        val playlist = root.optJSONObject("playlist")
        val ids = parsePositiveNeteasePlaylistTrackIds(playlist)
        ParsedNeteasePlaylistTrackIds(
            trackIds = ids,
            trackCount = playlist?.optInt("trackCount", ids.size) ?: ids.size,
            success = true
        )
    }.getOrElse { error ->
        NPLogger.e("LocalPlaylistRepo", "Failed to parse track ids: ${error.message}", error)
        ParsedNeteasePlaylistTrackIds(
            trackIds = emptyList(),
            trackCount = 0,
            success = false
        )
    }
}

private fun parsePositiveNeteasePlaylistTrackIds(playlist: JSONObject?): List<Long> {
    val trackIds = playlist?.optJSONArray("trackIds") ?: return emptyList()
    val ids = LinkedHashSet<Long>()
    for (i in 0 until trackIds.length()) {
        val id = trackIds.optJSONObject(i)?.optLong("id", 0L) ?: 0L
        if (id > 0L) {
            ids.add(id)
        }
    }
    return ids.toList()
}

internal fun parseNeteaseSongDetailSummary(raw: String): ParsedNeteaseSongDetailSummary {
    if (raw.isBlank()) {
        return ParsedNeteaseSongDetailSummary(
            ids = emptySet(),
            fingerprints = emptySet(),
            success = false
        )
    }
    return runCatching {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) {
            return@runCatching ParsedNeteaseSongDetailSummary(
                ids = emptySet(),
                fingerprints = emptySet(),
                success = false
            )
        }
        val summary = parseNeteaseSongDetailEntries(root.optJSONArray("songs"))
        ParsedNeteaseSongDetailSummary(
            ids = summary.ids,
            fingerprints = summary.fingerprints,
            success = true
        )
    }.getOrElse { error ->
        NPLogger.e("LocalPlaylistRepo", "Failed to parse song detail ids: ${error.message}", error)
        ParsedNeteaseSongDetailSummary(
            ids = emptySet(),
            fingerprints = emptySet(),
            success = false
        )
    }
}

private fun parseNeteaseSongDetailEntries(songs: JSONArray?): NeteaseSongDetailSummary {
    val ids = LinkedHashSet<Long>()
    val fingerprints = mutableSetOf<String>()
    if (songs != null) {
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optLong("id", 0L)
            if (id > 0L) {
                ids.add(id)
            }
            buildNeteaseFingerprint(
                name = song.optString("name", ""),
                artist = parseNeteaseSongArtist(song),
                durationMs = song.optLong("dt", 0L)
            )?.let(fingerprints::add)
        }
    }
    return NeteaseSongDetailSummary(ids = ids, fingerprints = fingerprints)
}

internal fun parseNeteaseCode(raw: String): Int {
    if (raw.isBlank()) return -1
    return runCatching { JSONObject(raw).optInt("code", -1) }.getOrElse { -1 }
}

internal fun buildNeteaseFingerprint(
    name: String?,
    artist: String?,
    durationMs: Long
): String? {
    val normalizedName = normalizeFingerprintToken(name)
    val normalizedArtist = normalizeArtistToken(artist)
    if (normalizedName.isBlank() || normalizedArtist.isBlank()) return null
    val durationBucket = if (durationMs > 0L) ((durationMs + 2_500L) / 5_000L).toString() else "0"
    return "$normalizedName|$normalizedArtist|$durationBucket"
}

internal fun parseNeteaseSongArtist(song: JSONObject): String {
    val artists = song.optJSONArray("ar") ?: return ""
    val names = ArrayList<String>(artists.length())
    for (i in 0 until artists.length()) {
        val name = artists.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
        if (name.isNotBlank()) {
            names += name
        }
    }
    return names.joinToString(" / ")
}

internal fun normalizeArtistToken(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.splitToSequence("/", "&", " feat. ", " feat ", ",", "，", "、")
        .map(::normalizeFingerprintToken)
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .joinToString("|")
}

internal fun normalizeFingerprintToken(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val lowered = raw.lowercase(Locale.ROOT)
    val builder = StringBuilder(lowered.length)
    lowered.forEach { ch ->
        if (Character.isLetterOrDigit(ch)) {
            builder.append(ch)
        }
    }
    return builder.toString()
}

internal fun SongItem.toNeteaseFingerprint(): String? {
    return buildNeteaseFingerprint(
        name = originalName ?: customName ?: name,
        artist = originalArtist ?: customArtist ?: artist,
        durationMs = durationMs
    )
}

internal fun String?.isNeteaseCoverUrl(): Boolean {
    if (this.isNullOrBlank()) return false
    return contains("music.126.net", ignoreCase = true)
}
