package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.remoteSourceIdentityOrNull as songRemoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey

private const val BILIBILI_SOURCE_ALBUM_PREFIX = "Bilibili"

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
    val stableKey: String? = null,
    val sourceIdentityAlbum: String? = null,
    val sourceMediaUri: String? = null,
    val sourceChannelId: String? = null,
    val sourceAudioId: String? = null,
    val sourceSubAudioId: String? = null,
    val sourcePlaylistContextId: String? = null
) {
    fun displayName(): String = customName ?: name
    fun displayArtist(): String = customArtist ?: artist

    internal fun deletionIdentity(): String {
        return mediaUri
            ?.takeIf(String::isNotBlank)
            ?: filePath
    }
}

internal fun DownloadedSong.remoteSourceIdentityOrNull(): SongIdentity? {
    stableKey.toRemoteSourceIdentityOrNull()?.let { return it }
    return rebuildRemoteSourceIdentity()
}

internal fun DownloadedSong.remoteSourceStableKeyOrNull(): String? {
    return remoteSourceIdentityOrNull()?.stableKey()
}

internal fun DownloadedSong.withRecoveredRemoteSourceStableKey(): DownloadedSong {
    val recoveredStableKey = remoteSourceStableKeyOrNull() ?: return this
    return if (stableKey == recoveredStableKey) this else copy(stableKey = recoveredStableKey)
}

private fun String?.toRemoteSourceIdentityOrNull(): SongIdentity? {
    val sourceStableKey = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    return SongItem(
        id = 0L,
        name = "",
        artist = "",
        album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
        albumId = 0L,
        durationMs = 0L,
        coverUrl = null,
        sourceStableKey = sourceStableKey
    ).songRemoteSourceIdentityOrNull()
}

private fun DownloadedSong.rebuildRemoteSourceIdentity(): SongIdentity? {
    val sourceChannel = sourceChannelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
    val sourceAlbum = sourceIdentityAlbum
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != LocalSongSupport.LOCAL_ALBUM_IDENTITY }
    val identityAlbum = sourceAlbum ?: sourceChannel ?: return null
    val sourceAudio = sourceAudioId?.trim()?.takeIf(String::isNotBlank)
    val sourceMedia = sourceMediaUri
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeUnless(LocalSongSupport::isLocalMediaUri)
    if (sourceAudio == null && sourceMedia == null && id == 0L) {
        return null
    }

    val sourceIdentity = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = identityAlbum,
        albumId = 0L,
        durationMs = durationMs,
        coverUrl = null,
        mediaUri = sourceMedia,
        channelId = sourceChannel,
        audioId = sourceAudio,
        subAudioId = sourceSubAudioId?.trim()?.takeIf(String::isNotBlank)
    ).identity()
    return sourceIdentity.takeUnless { identity ->
        identity.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY
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
    val remoteSourceIdentity = remoteSourceIdentityOrNull()
    val hasLegacyBiliSource = album.startsWith(
        BILIBILI_SOURCE_ALBUM_PREFIX,
        ignoreCase = true
    )
    val legacyBiliCid = album
        .substringAfter('|', "")
        .substringBefore('|')
        .trim()
        .takeIf { hasLegacyBiliSource && it.isNotBlank() }
    val remoteSourceChannel = sourceChannelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
    val resolvedSourceChannel = remoteSourceChannel
        ?: remoteSourceIdentity?.album
        ?: sourceChannelId
        ?: "bilibili".takeIf { hasLegacyBiliSource }
    val isBiliSource = resolvedSourceChannel.equals("bilibili", ignoreCase = true)
    val resolvedSourceAudioId = sourceAudioId
        ?.trim()
        ?.takeIf { remoteSourceChannel != null && it.isNotBlank() }
        ?: remoteSourceIdentity
            ?.takeIf { resolvedSourceChannel.equals("netease", ignoreCase = true) }
            ?.id
            ?.toString()
        ?: sourceAudioId
        ?: id.takeIf { isBiliSource && it > 0L }?.toString()
    val resolvedSourceSubAudioId = sourceSubAudioId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: legacyBiliCid
    val resolvedSongId = remoteSourceIdentity
        ?.takeIf { identity ->
            resolvedSourceChannel.equals("netease", ignoreCase = true) &&
                identity.album.equals("netease", ignoreCase = true) &&
                identity.mediaUri == null &&
                identity.id > 0L
        }
        ?.id
        ?: id
    return SongItem(
        id = resolvedSongId,
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
        originalCoverUrl = originalCoverUrl
            ?: coverUrl?.takeUnless(LocalSongSupport::isLocalMediaUri),
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = resolvedSourceChannel,
        audioId = resolvedSourceAudioId,
        subAudioId = resolvedSourceSubAudioId,
        playlistContextId = sourcePlaylistContextId,
        sourceStableKey = remoteSourceIdentity?.stableKey() ?: stableKey
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
