package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONArray
import org.json.JSONObject

internal data class DownloadedSongCatalogIndex(
    val songsByLocalReference: Map<String, DownloadedSong>,
    val songsByStableIdentityKey: Map<String, DownloadedSong>,
    val songsByLegacyIdentityKey: Map<String, DownloadedSong>
) {
    fun find(song: SongItem): DownloadedSong? {
        val localCandidates = listOfNotNull(
            song.localFilePath?.takeIf(String::isNotBlank),
            song.mediaUri?.takeIf(String::isNotBlank)
        )
        localCandidates.firstNotNullOfOrNull(songsByLocalReference::get)?.let { return it }
        val stableIdentityKey = song.stableKey()
        songsByStableIdentityKey[stableIdentityKey]?.let { return it }
        return songsByLegacyIdentityKey[
            downloadedSongCatalogIdentityKey(song.id, song.name, song.artist)
        ]
    }

    fun contains(song: SongItem): Boolean {
        return find(song) != null
    }

    companion object {
        val EMPTY = DownloadedSongCatalogIndex(
            songsByLocalReference = emptyMap(),
            songsByStableIdentityKey = emptyMap(),
            songsByLegacyIdentityKey = emptyMap()
        )
    }
}

internal fun buildDownloadedSongCatalogIndex(
    songs: List<DownloadedSong>
): DownloadedSongCatalogIndex {
    val songsByLocalReference = linkedMapOf<String, DownloadedSong>()
    val songsByStableIdentityKey = linkedMapOf<String, DownloadedSong>()
    val songsByLegacyIdentityKey = linkedMapOf<String, DownloadedSong>()
    songs.forEach { song ->
        song.filePath
            .takeIf(String::isNotBlank)
            ?.let { reference ->
                if (reference !in songsByLocalReference) {
                    songsByLocalReference[reference] = song
                }
            }
        song.mediaUri
            ?.takeIf(String::isNotBlank)
            ?.let { reference ->
                if (reference !in songsByLocalReference) {
                    songsByLocalReference[reference] = song
                }
            }
        song.stableKey
            ?.takeIf(String::isNotBlank)
            ?.let { stableIdentityKey ->
                if (stableIdentityKey !in songsByStableIdentityKey) {
                    songsByStableIdentityKey[stableIdentityKey] = song
                }
            }
            ?: run {
                val legacyIdentityKey = downloadedSongCatalogIdentityKey(
                    song.id,
                    song.name,
                    song.artist
                )
                if (legacyIdentityKey !in songsByLegacyIdentityKey) {
                    songsByLegacyIdentityKey[legacyIdentityKey] = song
                }
            }
    }
    return DownloadedSongCatalogIndex(
        songsByLocalReference = songsByLocalReference,
        songsByStableIdentityKey = songsByStableIdentityKey,
        songsByLegacyIdentityKey = songsByLegacyIdentityKey
    )
}

internal fun matchesDownloadedSong(
    song: SongItem,
    downloadedSong: DownloadedSong
): Boolean {
    val localCandidates = listOfNotNull(
        song.localFilePath?.takeIf(String::isNotBlank),
        song.mediaUri?.takeIf(String::isNotBlank)
    )
    if (localCandidates.any { candidate ->
            candidate == downloadedSong.filePath || candidate == downloadedSong.mediaUri
        }
    ) {
        return true
    }
    downloadedSong.stableKey
        ?.takeIf(String::isNotBlank)
        ?.let { stableIdentityKey ->
            return song.stableKey() == stableIdentityKey
        }
    return song.id == downloadedSong.id &&
        song.name == downloadedSong.name &&
        song.artist == downloadedSong.artist
}

internal fun findDownloadedSongCatalogMatch(
    song: SongItem,
    downloadedSongs: List<DownloadedSong>
): DownloadedSong? {
    return downloadedSongs.firstOrNull { downloadedSong ->
        matchesDownloadedSong(song, downloadedSong)
    }
}

internal fun matchesDownloadedSongCatalogEntry(
    existing: DownloadedSong,
    target: DownloadedSong
): Boolean {
    val existingReference = resolveDownloadedSongPlaybackReference(existing)
    val targetReference = resolveDownloadedSongPlaybackReference(target)
    if (!existingReference.isNullOrBlank() && existingReference == targetReference) {
        return true
    }
    val targetMediaUri = target.mediaUri
        ?.takeIf(String::isNotBlank)
        ?.takeIf(::isResolvableLocalReference)
    if (!targetMediaUri.isNullOrBlank() && existing.mediaUri == targetMediaUri) {
        return true
    }
    target.stableKey
        ?.takeIf(String::isNotBlank)
        ?.let { stableIdentityKey ->
            if (existing.stableKey == stableIdentityKey) {
                return true
            }
        }
    return existing.id == target.id &&
        existing.name == target.name &&
        existing.artist == target.artist
}

internal fun resolveDownloadedSongPlaybackReference(song: DownloadedSong): String? {
    song.filePath
        .takeIf { it.isNotBlank() }
        ?.let { return it }

    return song.mediaUri?.takeIf(::isResolvableLocalReference)
}

fun upsertDownloadedSongCatalog(
    currentSongs: List<DownloadedSong>,
    updatedSong: DownloadedSong
): List<DownloadedSong> {
    return currentSongs
        .filterNot { existing ->
            matchesDownloadedSongCatalogEntry(existing, updatedSong)
        }
        .plus(updatedSong)
        .sortedByDescending { it.downloadTime }
}

internal fun shouldPublishDownloadedSongCatalogUpdate(
    currentSong: DownloadedSong,
    updatedSong: DownloadedSong
): Boolean {
    return currentSong.listPresentationKey() != updatedSong.listPresentationKey()
}

internal fun serializeDownloadedSongsCatalog(
    cacheKey: String,
    songs: List<DownloadedSong>
): String {
    return JSONObject().apply {
        put("cacheKey", cacheKey)
        put("songs", JSONArray().apply {
            songs.forEach { song ->
                put(
                    JSONObject().apply {
                        put("id", song.id)
                        put("name", song.name)
                        put("artist", song.artist)
                        put("album", song.album)
                        put("filePath", song.filePath)
                        put("fileSize", song.fileSize)
                        put("downloadTime", song.downloadTime)
                        put("coverPath", song.coverPath)
                        put("coverUrl", song.coverUrl)
                        put("matchedLyricSource", song.matchedLyricSource)
                        put("matchedSongId", song.matchedSongId)
                        put("userLyricOffsetMs", song.userLyricOffsetMs)
                        put("customCoverUrl", song.customCoverUrl)
                        put("customName", song.customName)
                        put("customArtist", song.customArtist)
                        put("originalName", song.originalName)
                        put("originalArtist", song.originalArtist)
                        put("originalCoverUrl", song.originalCoverUrl)
                        put("mediaUri", song.mediaUri)
                        put("durationMs", song.durationMs)
                        put("stableKey", song.stableKey)
                    }
                )
            }
        })
    }.toString()
}

internal fun deserializeDownloadedSongsCatalog(
    raw: String,
    expectedCacheKey: String
): List<DownloadedSong>? {
    val root = JSONObject(raw)
    if (root.optString("cacheKey") != expectedCacheKey) {
        return null
    }
    val songs = root.optJSONArray("songs") ?: return emptyList()
    return buildList(songs.length()) {
        for (index in 0 until songs.length()) {
            val item = songs.optJSONObject(index) ?: continue
            add(
                DownloadedSong(
                    id = item.optLong("id"),
                    name = item.optString("name"),
                    artist = item.optString("artist"),
                    album = item.optString("album"),
                    filePath = item.optString("filePath"),
                    fileSize = item.optLong("fileSize"),
                    downloadTime = item.optLong("downloadTime"),
                    coverPath = item.optString("coverPath").takeIf(String::isNotBlank),
                    coverUrl = item.optString("coverUrl").takeIf(String::isNotBlank),
                    matchedLyric = item.optString("matchedLyric").takeIf(String::isNotBlank),
                    matchedTranslatedLyric = item.optString("matchedTranslatedLyric").takeIf(String::isNotBlank),
                    matchedLyricSource = item.optString("matchedLyricSource").takeIf(String::isNotBlank),
                    matchedSongId = item.optString("matchedSongId").takeIf(String::isNotBlank),
                    userLyricOffsetMs = item.optLong("userLyricOffsetMs"),
                    customCoverUrl = item.optString("customCoverUrl").takeIf(String::isNotBlank),
                    customName = item.optString("customName").takeIf(String::isNotBlank),
                    customArtist = item.optString("customArtist").takeIf(String::isNotBlank),
                    originalName = item.optString("originalName").takeIf(String::isNotBlank),
                    originalArtist = item.optString("originalArtist").takeIf(String::isNotBlank),
                    originalCoverUrl = item.optString("originalCoverUrl").takeIf(String::isNotBlank),
                    originalLyric = item.optString("originalLyric").takeIf(String::isNotBlank),
                    originalTranslatedLyric = item.optString("originalTranslatedLyric").takeIf(String::isNotBlank),
                    mediaUri = item.optString("mediaUri").takeIf(String::isNotBlank),
                    durationMs = item.optLong("durationMs"),
                    stableKey = item.optString("stableKey").takeIf(String::isNotBlank)
                )
            )
        }
    }
}

internal fun isResolvableLocalReference(reference: String): Boolean {
    return reference.startsWith("/") ||
        reference.startsWith("content://") ||
        reference.startsWith("file://")
}

private fun downloadedSongCatalogIdentityKey(
    id: Long,
    name: String,
    artist: String
): String {
    return "$id|$name|$artist"
}

private fun DownloadedSong.listPresentationKey(): String {
    return buildString {
        append(id)
        append('|')
        append(filePath)
        append('|')
        append(displayName())
        append('|')
        append(displayArtist())
        append('|')
        append(fileSize)
        append('|')
        append(downloadTime)
        append('|')
        append(customCoverUrl.orEmpty())
        append('|')
        append(coverPath.orEmpty())
        append('|')
        append(coverUrl.orEmpty())
    }
}
