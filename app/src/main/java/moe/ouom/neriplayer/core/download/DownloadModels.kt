package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem

data class DownloadedSong(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val fileSize: Long,
    val downloadTime: Long,
    val coverPath: String? = null,
    val coverUrl: String? = null,
    val matchedLyric: String? = null,
    val matchedTranslatedLyric: String? = null,
    val matchedLyricSource: String? = null,
    val matchedSongId: String? = null,
    val userLyricOffsetMs: Long = 0L,
    val customCoverUrl: String? = null,
    val customName: String? = null,
    val customArtist: String? = null,
    val originalName: String? = null,
    val originalArtist: String? = null,
    val originalCoverUrl: String? = null,
    val originalLyric: String? = null,
    val originalTranslatedLyric: String? = null,
    val mediaUri: String? = null,
    val durationMs: Long = 0L,
    val stableKey: String? = null
) {
    fun displayName(): String = customName ?: name
    fun displayArtist(): String = customArtist ?: artist

    internal fun deletionIdentity(): String {
        return mediaUri
            ?.takeIf(String::isNotBlank)
            ?: filePath
    }
}

internal fun DownloadedSong.toPlaybackSongItem(): SongItem {
    val localFileName = filePath.substringAfterLast('/').takeIf(String::isNotBlank)
    return toPlaybackSongItem(
        playbackUri = mediaUri?.takeIf(String::isNotBlank) ?: filePath,
        localFileName = localFileName,
        localFilePath = filePath.takeIf { it.startsWith("/") },
        resolvedDurationMs = durationMs
    )
}

internal fun DownloadedSong.toPlaybackSongItem(
    playbackUri: String,
    localFileName: String?,
    localFilePath: String?,
    resolvedDurationMs: Long
): SongItem {
    return SongItem(
        id = id,
        name = name,
        artist = artist,
        album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
        albumId = 0L,
        durationMs = resolvedDurationMs.coerceAtLeast(0L),
        coverUrl = coverPath ?: coverUrl,
        mediaUri = playbackUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedLyricSource = matchedLyricSource?.let {
            runCatching { MusicPlatform.valueOf(it) }.getOrNull()
        },
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        sourceStableKey = stableKey
    )
}

data class DownloadedSongDeleteResult(
    val deletedSongs: List<DownloadedSong>,
    val failedSongs: List<DownloadedSong>
) {
    companion object {
        fun empty(): DownloadedSongDeleteResult {
            return DownloadedSongDeleteResult(
                deletedSongs = emptyList(),
                failedSongs = emptyList()
            )
        }
    }
}

internal fun resolveDownloadedSongDeleteResult(
    deletePlans: List<ManagedDownloadSongDeletePlan>,
    deletedReferences: Set<String>
): DownloadedSongDeleteResult {
    val deletedSongs = deletePlans
        .filter { deletePlan ->
            deletePlan.requiredReferences.all(deletedReferences::contains)
        }
        .map(ManagedDownloadSongDeletePlan::song)
    return DownloadedSongDeleteResult(
        deletedSongs = deletedSongs,
        failedSongs = deletePlans
            .map(ManagedDownloadSongDeletePlan::song)
            .filterNot(deletedSongs::contains)
    )
}

internal fun mergeDownloadedSongsAfterDelete(
    currentSongs: List<DownloadedSong>,
    previousSongs: List<DownloadedSong>,
    deletedSongs: List<DownloadedSong>,
    restoredSongs: List<DownloadedSong>
): List<DownloadedSong> {
    val deletedIdentities = deletedSongs.mapTo(mutableSetOf()) {
        it.deletionIdentity()
    }
    val restoredIdentities = restoredSongs
        .mapTo(mutableSetOf()) { it.deletionIdentity() }
        .apply { removeAll(deletedIdentities) }
    val survivingSongs = currentSongs.filterNot { song ->
        song.deletionIdentity() in deletedIdentities
    }
    if (restoredIdentities.isEmpty()) return survivingSongs

    val currentIdentities = survivingSongs.mapTo(mutableSetOf()) {
        it.deletionIdentity()
    }
    val restoredFromPrevious = previousSongs.filter { song ->
        song.deletionIdentity() in restoredIdentities &&
            currentIdentities.add(song.deletionIdentity())
    }
    return if (restoredFromPrevious.isEmpty()) {
        survivingSongs
    } else {
        (survivingSongs + restoredFromPrevious)
            .sortedByDescending(DownloadedSong::downloadTime)
    }
}
