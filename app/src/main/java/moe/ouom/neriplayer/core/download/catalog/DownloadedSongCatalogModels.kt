package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.remoteSourceIdentityOrNull as downloadedRemoteSourceIdentityOrNull
import moe.ouom.neriplayer.core.download.remoteSourceStableKeyOrNull
import moe.ouom.neriplayer.core.download.withRecoveredRemoteSourceStableKey
import moe.ouom.neriplayer.data.model.remoteSourceIdentityOrNull
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
        val requiresVerifiedRemoteIdentity = song.requiresVerifiedRemoteDownloadIdentity()
        val stableIdentityKey = song.stableKey()
        if (requiresVerifiedRemoteIdentity) {
            songsByStableIdentityKey[stableIdentityKey]?.let { return it }
            val localCandidates = listOfNotNull(
                song.localFilePath?.takeIf(String::isNotBlank),
                song.mediaUri?.takeIf(String::isNotBlank)
            )
            localCandidates.firstNotNullOfOrNull(songsByLocalReference::get)
                ?.takeIf { downloadedSong ->
                    downloadedSong.matchesVerifiedRemoteIdentity(song)
                }
                ?.let { return it }
            return songsByLegacyIdentityKey[
                downloadedSongCatalogIdentityKey(song.id, song.name, song.artist)
            ]?.takeIf(DownloadedSong::isLegacyRemoteIdentityMissing)
        }
        val localCandidates = listOfNotNull(
            song.localFilePath?.takeIf(String::isNotBlank),
            song.mediaUri?.takeIf(String::isNotBlank)
        )
        localCandidates.firstNotNullOfOrNull(songsByLocalReference::get)?.let { return it }
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
        song.catalogStableKey()
            ?.takeIf(String::isNotBlank)
            ?.let { stableIdentityKey ->
                if (stableIdentityKey !in songsByStableIdentityKey) {
                    songsByStableIdentityKey[stableIdentityKey] = song
                }
            }
        if (song.isLegacyRemoteIdentityMissing()) {
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
    if (song.requiresVerifiedRemoteDownloadIdentity()) {
        return downloadedSong.matchesVerifiedRemoteIdentity(song) ||
            (
                downloadedSong.isLegacyRemoteIdentityMissing() &&
                    song.id == downloadedSong.id &&
                    song.name == downloadedSong.name &&
                    song.artist == downloadedSong.artist
                )
    }
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
    downloadedSong.catalogStableKey()
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
    if (
        existing.requiresVerifiedRemoteDownloadIdentity() ||
        target.requiresVerifiedRemoteDownloadIdentity()
    ) {
        return existing.matchesVerifiedRemoteIdentity(target)
    }
    val targetMediaUri = target.mediaUri
        ?.takeIf(String::isNotBlank)
        ?.takeIf(::isResolvableLocalReference)
    if (!targetMediaUri.isNullOrBlank() && existing.mediaUri == targetMediaUri) {
        return true
    }
    target.catalogStableKey()
        ?.takeIf(String::isNotBlank)
        ?.let { stableIdentityKey ->
            if (existing.catalogStableKey() == stableIdentityKey) {
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

internal fun projectDownloadedSongMetadata(
    existing: DownloadedSong,
    updatedSong: SongItem
): DownloadedSong {
    val existingRemoteSource = existing.downloadedRemoteSourceIdentityOrNull()
    val updatedRemoteSource = updatedSong.remoteSourceIdentityOrNull()
    val remoteSource = existingRemoteSource ?: updatedRemoteSource
    val existingRemoteSourceChannel = existing.sourceChannelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
    val customCover = updatedSong.customCoverUrl?.trim()?.takeIf(String::isNotBlank)
    val restoredLocalCover = updatedSong.coverUrl?.takeIf(::isResolvableLocalReference)
    val updatedAlbum = updatedSong.album
        .trim()
        .takeIf { it.isNotBlank() }
    val updatedSourceChannel = updatedSong.channelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
    val sourceChannelId = existingRemoteSourceChannel ?: updatedSourceChannel ?: remoteSource?.album
    val sourceAudioId = existing.sourceAudioId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: updatedSong.audioId
            ?.trim()
            ?.takeIf { updatedSourceChannel != null && it.isNotBlank() }
        ?: remoteSource
            ?.takeIf { sourceChannelId.equals("netease", ignoreCase = true) }
            ?.id
            ?.toString()
    val sourceSubAudioId = existing.sourceSubAudioId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: updatedSong.subAudioId
            ?.trim()
            ?.takeIf { updatedSourceChannel != null && it.isNotBlank() }
    val preservesExistingRemoteSource = existingRemoteSource != null
    return existing.copy(
        id = if (preservesExistingRemoteSource) existing.id else updatedSong.id,
        name = updatedSong.name,
        artist = updatedSong.artist,
        album = updatedAlbum ?: existing.album,
        coverPath = if (customCover == null) restoredLocalCover else existing.coverPath,
        coverUrl = updatedSong.coverUrl ?: existing.coverUrl,
        matchedLyric = updatedSong.matchedLyric,
        matchedTranslatedLyric = updatedSong.matchedTranslatedLyric,
        matchedRomanizedLyric = updatedSong.matchedRomanizedLyric,
        matchedLyricSource = updatedSong.matchedLyricSource?.name,
        matchedSongId = updatedSong.matchedSongId,
        userLyricOffsetMs = updatedSong.userLyricOffsetMs,
        customCoverUrl = customCover,
        customName = updatedSong.customName,
        customArtist = updatedSong.customArtist,
        originalName = existing.originalName
            ?: existing.name.takeIf { preservesExistingRemoteSource }
            ?: updatedSong.originalName,
        originalArtist = existing.originalArtist
            ?: existing.artist.takeIf { preservesExistingRemoteSource }
            ?: updatedSong.originalArtist,
        originalCoverUrl = existing.originalCoverUrl
            ?: existing.coverUrl.takeIf { preservesExistingRemoteSource }
            ?: updatedSong.originalCoverUrl,
        originalLyric = existing.originalLyric ?: updatedSong.originalLyric,
        originalTranslatedLyric = existing.originalTranslatedLyric
            ?: updatedSong.originalTranslatedLyric,
        originalRomanizedLyric = existing.originalRomanizedLyric
            ?: updatedSong.originalRomanizedLyric,
        mediaUri = updatedSong.mediaUri?.takeIf(::isResolvableLocalReference) ?: existing.mediaUri,
        durationMs = updatedSong.durationMs.takeIf { it > 0L } ?: existing.durationMs,
        stableKey = remoteSource?.stableKey()
            ?: updatedSong.stableKey().takeIf(String::isNotBlank)
            ?: existing.stableKey,
        sourceIdentityAlbum = remoteSource?.album ?: existing.sourceIdentityAlbum,
        sourceMediaUri = remoteSource?.mediaUri ?: existing.sourceMediaUri,
        sourceChannelId = sourceChannelId,
        sourceAudioId = sourceAudioId,
        sourceSubAudioId = sourceSubAudioId,
        sourcePlaylistContextId = existing.sourcePlaylistContextId
            ?: updatedSong.playlistContextId
    ).withRecoveredRemoteSourceStableKey()
}

internal fun DownloadedSong.toMetadataPersistenceSong(updatedSong: SongItem): SongItem {
    val remoteSource = downloadedRemoteSourceIdentityOrNull()
    val sourceChannel = sourceChannelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
        ?: remoteSource?.album
    val resolvedSourceAudioId = sourceAudioId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: remoteSource
            ?.takeIf { sourceChannel.equals("netease", ignoreCase = true) }
            ?.id
            ?.toString()
    return updatedSong.copy(
        id = if (remoteSource != null) id else updatedSong.id,
        originalName = originalName ?: updatedSong.originalName,
        originalArtist = originalArtist ?: updatedSong.originalArtist,
        originalCoverUrl = originalCoverUrl ?: updatedSong.originalCoverUrl,
        originalLyric = originalLyric ?: updatedSong.originalLyric,
        originalTranslatedLyric = originalTranslatedLyric ?: updatedSong.originalTranslatedLyric,
        originalRomanizedLyric = originalRomanizedLyric ?: updatedSong.originalRomanizedLyric,
        channelId = sourceChannel ?: updatedSong.channelId,
        audioId = resolvedSourceAudioId ?: updatedSong.audioId,
        subAudioId = sourceSubAudioId ?: updatedSong.subAudioId,
        playlistContextId = sourcePlaylistContextId ?: updatedSong.playlistContextId,
        sourceStableKey = remoteSource?.stableKey() ?: updatedSong.sourceStableKey
    )
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
                        put("matchedLyric", song.matchedLyric)
                        put("matchedTranslatedLyric", song.matchedTranslatedLyric)
                        put("matchedRomanizedLyric", song.matchedRomanizedLyric)
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
                        put("stableKey", song.catalogStableKey())
                        put("sourceIdentityAlbum", song.sourceIdentityAlbum)
                        put("sourceMediaUri", song.sourceMediaUri)
                        put("sourceChannelId", song.sourceChannelId)
                        put("sourceAudioId", song.sourceAudioId)
                        put("sourceSubAudioId", song.sourceSubAudioId)
                        put("sourcePlaylistContextId", song.sourcePlaylistContextId)
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
                    matchedLyric = item.optPresentCatalogString("matchedLyric"),
                    matchedTranslatedLyric = item.optPresentCatalogString("matchedTranslatedLyric"),
                    matchedRomanizedLyric = item.optPresentCatalogString("matchedRomanizedLyric"),
                    matchedLyricSource = item.optString("matchedLyricSource").takeIf(String::isNotBlank),
                    matchedSongId = item.optString("matchedSongId").takeIf(String::isNotBlank),
                    userLyricOffsetMs = item.optLong("userLyricOffsetMs"),
                    customCoverUrl = item.optString("customCoverUrl").takeIf(String::isNotBlank),
                    customName = item.optString("customName").takeIf(String::isNotBlank),
                    customArtist = item.optString("customArtist").takeIf(String::isNotBlank),
                    originalName = item.optString("originalName").takeIf(String::isNotBlank),
                    originalArtist = item.optString("originalArtist").takeIf(String::isNotBlank),
                    originalCoverUrl = item.optString("originalCoverUrl").takeIf(String::isNotBlank),
                    // original lyric fields stay in Room and metadata snapshots; keeping the
                    // legacy startup cache limited to list data avoids a multi-megabyte JSON
                    // parse on devices with a large downloaded library
                    originalLyric = null,
                    originalTranslatedLyric = null,
                    originalRomanizedLyric = null,
                    mediaUri = item.optString("mediaUri").takeIf(String::isNotBlank),
                    durationMs = item.optLong("durationMs"),
                    stableKey = item.optString("stableKey").takeIf(String::isNotBlank),
                    sourceIdentityAlbum = item.optString("sourceIdentityAlbum")
                        .takeIf(String::isNotBlank),
                    sourceMediaUri = item.optString("sourceMediaUri").takeIf(String::isNotBlank),
                    sourceChannelId = item.optString("sourceChannelId").takeIf(String::isNotBlank),
                    sourceAudioId = item.optString("sourceAudioId").takeIf(String::isNotBlank),
                    sourceSubAudioId = item.optString("sourceSubAudioId")
                        .takeIf(String::isNotBlank),
                    sourcePlaylistContextId = item.optString("sourcePlaylistContextId")
                        .takeIf(String::isNotBlank)
                ).withRecoveredRemoteSourceStableKey()
            )
        }
    }
}

internal fun isResolvableLocalReference(reference: String): Boolean {
    return reference.startsWith("/") ||
        reference.startsWith("content://") ||
        reference.startsWith("file://")
}

private fun JSONObject.optPresentCatalogString(fieldName: String): String? {
    if (!has(fieldName) || isNull(fieldName)) return null
    return optString(fieldName)
}

private fun downloadedSongCatalogIdentityKey(
    id: Long,
    name: String,
    artist: String
): String {
    return "$id|$name|$artist"
}

private fun DownloadedSong.catalogStableKey(): String? {
    return remoteSourceStableKeyOrNull() ?: stableKey?.takeIf(String::isNotBlank)
}

private fun SongItem.requiresVerifiedRemoteDownloadIdentity(): Boolean {
    if (!sourceStableKey.isNullOrBlank()) {
        return true
    }
    val sourceChannel = channelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
    return sourceChannel != null && (
        !audioId.isNullOrBlank() ||
            !subAudioId.isNullOrBlank() ||
            id > 0L
        )
}

private fun DownloadedSong.requiresVerifiedRemoteDownloadIdentity(): Boolean {
    if (remoteSourceStableKeyOrNull() != null) {
        return true
    }
    val sourceChannel = sourceChannelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
    return sourceChannel != null && (
        !sourceAudioId.isNullOrBlank() ||
            !sourceSubAudioId.isNullOrBlank() ||
            id > 0L
        )
}

private fun DownloadedSong.isLegacyRemoteIdentityMissing(): Boolean {
    return remoteSourceStableKeyOrNull() == null && !requiresVerifiedRemoteDownloadIdentity()
}

private fun DownloadedSong.matchesVerifiedRemoteIdentity(song: SongItem): Boolean {
    val expectedStableKey = song.stableKey()
    val downloadedStableKey = remoteSourceStableKeyOrNull()
    if (downloadedStableKey != null) {
        return downloadedStableKey == expectedStableKey
    }
    val downloadedTrackKey = buildRemoteTrackKey(
        channelId = sourceChannelId,
        audioId = sourceAudioId,
        subAudioId = sourceSubAudioId
    ) ?: return false
    return downloadedTrackKey == buildRemoteTrackKey(
        channelId = song.channelId,
        audioId = song.audioId,
        subAudioId = song.subAudioId
    )
}

private fun DownloadedSong.matchesVerifiedRemoteIdentity(other: DownloadedSong): Boolean {
    val existingStableKey = remoteSourceStableKeyOrNull()
    val targetStableKey = other.remoteSourceStableKeyOrNull()
    if (existingStableKey != null || targetStableKey != null) {
        return existingStableKey != null && existingStableKey == targetStableKey
    }
    val existingTrackKey = buildRemoteTrackKey(
        channelId = sourceChannelId,
        audioId = sourceAudioId,
        subAudioId = sourceSubAudioId
    ) ?: return false
    return existingTrackKey == buildRemoteTrackKey(
        channelId = other.sourceChannelId,
        audioId = other.sourceAudioId,
        subAudioId = other.sourceSubAudioId
    )
}

private fun buildRemoteTrackKey(
    channelId: String?,
    audioId: String?,
    subAudioId: String?
): String? {
    val normalizedChannelId = channelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
        ?: return null
    val normalizedAudioId = audioId?.trim().orEmpty()
    val normalizedSubAudioId = subAudioId?.trim().orEmpty()
    if (normalizedAudioId.isBlank() && normalizedSubAudioId.isBlank()) {
        return null
    }
    return "$normalizedChannelId|$normalizedAudioId|$normalizedSubAudioId"
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
