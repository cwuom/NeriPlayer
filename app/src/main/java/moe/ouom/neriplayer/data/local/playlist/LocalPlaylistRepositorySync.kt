package moe.ouom.neriplayer.data.local.playlist

import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.NeteaseResolvedCandidate
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.LocalNeteaseCandidateSummary
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.NeteaseCandidateValidationResult
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.ParsedNeteasePlaylistId
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.ParsedNeteasePlaylistTrackIds
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.NeteasePlaylistTrackSnapshot
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.NeteaseSongDetailSummary
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.ParsedNeteaseSongDetailSummary
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject
import java.util.LinkedHashSet
import java.util.Locale

internal fun LocalPlaylistRepository.fetchNeteasePlaylistTrackSnapshot(
    client: NeteaseClient,
    playlistId: Long
): NeteasePlaylistTrackSnapshot {
    val raw = runCatching { client.getPlaylistDetail(playlistId) }
        .getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "getPlaylistDetail failed: ${error.message}", error)
            return NeteasePlaylistTrackSnapshot(
                trackIds = emptySet(),
                fingerprints = emptySet(),
                compareSucceeded = false,
                message = LocalPlaylistRepository.NETEASE_COMPARE_FAILED_MESSAGE
            )
        }
    val retriedRaw = if (parseNeteaseCode(raw) == 301 && client.hasLogin()) {
        runCatching { client.ensureWeapiSession() }.onFailure {
            NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession retry failed: ${it.message}")
        }
        runCatching { client.getPlaylistDetail(playlistId) }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "getPlaylistDetail retry failed: ${error.message}", error)
                return NeteasePlaylistTrackSnapshot(
                    trackIds = emptySet(),
                    fingerprints = emptySet(),
                    compareSucceeded = false,
                    message = LocalPlaylistRepository.NETEASE_COMPARE_FAILED_MESSAGE
                )
            }
    } else {
        raw
    }

    val parsed = parseNeteaseTrackIdsFromPlaylistDetail(retriedRaw)
    if (!parsed.success) {
        return NeteasePlaylistTrackSnapshot(
            trackIds = emptySet(),
            fingerprints = emptySet(),
            compareSucceeded = false,
            message = LocalPlaylistRepository.NETEASE_COMPARE_FAILED_MESSAGE
        )
    }
    if (parsed.trackIds.isEmpty() && parsed.trackCount > 0) {
        NPLogger.w(
            "LocalPlaylistRepo",
            "Playlist detail returned empty trackIds but trackCount=${parsed.trackCount} for playlistId=$playlistId"
        )
        return NeteasePlaylistTrackSnapshot(
            trackIds = emptySet(),
            fingerprints = emptySet(),
            compareSucceeded = false,
            message = LocalPlaylistRepository.NETEASE_COMPARE_FAILED_MESSAGE
        )
    }

    val detailSummary = fetchNeteaseLikedSongDetailSummaryByPages(client, parsed.trackIds)
    return NeteasePlaylistTrackSnapshot(
        trackIds = LinkedHashSet(parsed.trackIds),
        fingerprints = detailSummary.fingerprints,
        compareSucceeded = true
    )
}

internal fun LocalPlaylistRepository.addNeteasePlaylistSongIdsBatch(
    client: NeteaseClient,
    playlistId: Long,
    songIds: List<Long>
): Boolean {
    if (songIds.isEmpty()) return true
    val raw = runCatching { client.addSongsToPlaylist(playlistId, songIds) }
        .getOrElse { error ->
            NPLogger.e(
                "LocalPlaylistRepo",
                "addSongsToPlaylist failed for playlistId=$playlistId: ${error.message}",
                error
            )
            return false
        }
    val code = parseNeteaseCode(raw)
    if (code == 200) return true
    if (code == 301 && client.hasLogin()) {
        runCatching { client.ensureWeapiSession() }.onFailure {
            NPLogger.w("LocalPlaylistRepo", "ensureWeapiSession retry failed: ${it.message}")
        }
        val retry = runCatching { client.addSongsToPlaylist(playlistId, songIds) }
            .getOrElse { error ->
                NPLogger.e(
                    "LocalPlaylistRepo",
                    "addSongsToPlaylist retry failed for playlistId=$playlistId: ${error.message}",
                    error
                )
                return false
            }
        return parseNeteaseCode(retry) == 200
    }
    NPLogger.w(
        "LocalPlaylistRepo",
        "addSongsToPlaylist returned code=$code for playlistId=$playlistId, size=${songIds.size}"
    )
    return false
}

internal fun LocalPlaylistRepository.resolveNeteaseSongId(song: SongItem): Long? {
    if (song.channelId.equals("netease", ignoreCase = true)) {
        song.audioId
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }
    }
    val songId = song.id.takeIf { it > 0 } ?: return null
    if (song.channelId.equals("netease", ignoreCase = true)) return songId
    if (song.album.startsWith(LocalPlaylistRepository.NETEASE_ALBUM_PREFIX)) {
        return songId
    }
    if (song.matchedLyricSource == MusicPlatform.CLOUD_MUSIC) {
        val matched = song.matchedSongId?.toLongOrNull()
        if (matched != null && matched > 0) return matched
    }
    if (song.coverUrl.isNeteaseCoverUrl() || song.originalCoverUrl.isNeteaseCoverUrl()) {
        return songId
    }
    return null
}

internal fun LocalPlaylistRepository.buildLocalNeteaseCandidates(songs: List<SongItem>): LocalNeteaseCandidateSummary {
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

internal fun LocalPlaylistRepository.resolveLocalNeteaseCandidates(songs: List<SongItem>): List<NeteaseResolvedCandidate> {
    return songs.mapNotNull { song ->
        resolveNeteaseSongId(song)?.let { neteaseId ->
            NeteaseResolvedCandidate(song = song, neteaseId = neteaseId)
        }
    }
}

internal fun LocalPlaylistRepository.validateNeteaseSyncCandidates(
    client: NeteaseClient,
    summary: LocalNeteaseCandidateSummary
): NeteaseCandidateValidationResult {
    if (summary.candidates.isEmpty()) {
        return NeteaseCandidateValidationResult(
            supportedSongs = 0,
            skippedUnsupported = summary.skippedUnsupported,
            skippedExisting = summary.skippedExisting,
            candidates = emptyList()
        )
    }

    val validatedCandidates = ArrayList<NeteaseResolvedCandidate>(summary.candidates.size)
    var skippedUnsupported = summary.skippedUnsupported
    summary.candidates.chunked(LocalPlaylistRepository.NETEASE_SONG_DETAIL_BATCH_SIZE).forEachIndexed { pageIndex, chunk ->
        val resolvedIds = fetchResolvableNeteaseSongIds(
            client = client,
            ids = chunk.map(NeteaseResolvedCandidate::neteaseId),
            logLabel = "validateNeteaseSyncCandidates page ${pageIndex + 1}"
        )
        if (resolvedIds == null) {
            validatedCandidates.addAll(chunk)
            return@forEachIndexed
        }

        chunk.forEach { candidate ->
            if (candidate.neteaseId in resolvedIds) {
                validatedCandidates += candidate
            } else {
                skippedUnsupported += 1
                NPLogger.w(
                    "LocalPlaylistRepo",
                    "Filtered invalid netease songId before sync: songId=${candidate.neteaseId} name=${candidate.song.name}"
                )
            }
        }
    }

    return NeteaseCandidateValidationResult(
        supportedSongs = validatedCandidates.size,
        skippedUnsupported = skippedUnsupported,
        skippedExisting = summary.skippedExisting,
        candidates = validatedCandidates
    )
}

internal fun LocalPlaylistRepository.parseNeteaseLikedPlaylistId(raw: String): ParsedNeteasePlaylistId {
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

internal fun LocalPlaylistRepository.parseNeteaseTrackIdsFromPlaylistDetail(raw: String): ParsedNeteasePlaylistTrackIds {
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
        val trackIdsArr = playlist?.optJSONArray("trackIds")
        val ids = LinkedHashSet<Long>()
        if (trackIdsArr != null) {
            for (i in 0 until trackIdsArr.length()) {
                val id = trackIdsArr.optJSONObject(i)?.optLong("id", 0L) ?: 0L
                if (id > 0L) {
                    ids.add(id)
                }
            }
        }
        ParsedNeteasePlaylistTrackIds(
            trackIds = ids.toList(),
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

internal fun LocalPlaylistRepository.fetchNeteaseLikedSongDetailSummaryByPages(
    client: NeteaseClient,
    trackIds: List<Long>
): NeteaseSongDetailSummary {
    if (trackIds.isEmpty()) {
        return NeteaseSongDetailSummary(
            ids = emptySet(),
            fingerprints = emptySet()
        )
    }

    val resolvedIds = LinkedHashSet<Long>(trackIds.size)
    val fingerprints = mutableSetOf<String>()
    trackIds.chunked(LocalPlaylistRepository.NETEASE_SONG_DETAIL_BATCH_SIZE).forEachIndexed { pageIndex, ids ->
        val raw = runCatching { client.getSongDetail(ids) }
            .getOrElse { error ->
                NPLogger.e(
                    "LocalPlaylistRepo",
                    "getSongDetail page ${pageIndex + 1} failed: ${error.message}",
                    error
                )
                return@forEachIndexed
            }
        val parsed = parseNeteaseSongDetailSummary(raw)
        if (!parsed.success) {
            NPLogger.w(
                "LocalPlaylistRepo",
                "getSongDetail page ${pageIndex + 1} returned invalid payload"
            )
            return@forEachIndexed
        }
        resolvedIds.addAll(parsed.ids)
        fingerprints.addAll(parsed.fingerprints)
    }
    return NeteaseSongDetailSummary(
        ids = resolvedIds,
        fingerprints = fingerprints
    )
}

internal fun LocalPlaylistRepository.parseNeteaseSongDetailSummary(raw: String): ParsedNeteaseSongDetailSummary {
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
        val songs = root.optJSONArray("songs")
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
        ParsedNeteaseSongDetailSummary(
            ids = ids,
            fingerprints = fingerprints,
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

internal fun LocalPlaylistRepository.fetchResolvableNeteaseSongIds(
    client: NeteaseClient,
    ids: List<Long>,
    logLabel: String
): Set<Long>? {
    if (ids.isEmpty()) return emptySet()

    fun requestSongDetail(): String {
        return client.getSongDetail(ids)
    }

    val raw = runCatching { requestSongDetail() }
        .getOrElse { error ->
            NPLogger.e("LocalPlaylistRepo", "$logLabel failed: ${error.message}", error)
            return null
        }

    val retriedRaw = if (parseNeteaseCode(raw) == 301 && client.hasLogin()) {
        runCatching { client.ensureWeapiSession() }.onFailure {
            NPLogger.w("LocalPlaylistRepo", "$logLabel ensureWeapiSession retry failed: ${it.message}")
        }
        runCatching { requestSongDetail() }
            .getOrElse { error ->
                NPLogger.e("LocalPlaylistRepo", "$logLabel retry failed: ${error.message}", error)
                return null
            }
    } else {
        raw
    }

    val parsed = parseNeteaseSongDetailSummary(retriedRaw)
    if (!parsed.success) {
        NPLogger.w("LocalPlaylistRepo", "$logLabel returned invalid payload")
        return null
    }
    return parsed.ids
}

internal fun LocalPlaylistRepository.parseNeteaseCode(raw: String): Int {
    if (raw.isBlank()) return -1
    return runCatching { JSONObject(raw).optInt("code", -1) }.getOrElse { -1 }
}

internal fun LocalPlaylistRepository.buildNeteaseFingerprint(
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

internal fun LocalPlaylistRepository.parseNeteaseSongArtist(song: JSONObject): String {
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

internal fun LocalPlaylistRepository.normalizeArtistToken(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.splitToSequence("/", "&", " feat. ", " feat ", ",", "，", "、")
        .map(::normalizeFingerprintToken)
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .joinToString("|")
}

internal fun LocalPlaylistRepository.normalizeFingerprintToken(raw: String?): String {
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
