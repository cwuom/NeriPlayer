package moe.ouom.neriplayer.data.identity

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.stableKey
import java.io.File
import java.net.URI

private const val BILIBILI_SOURCE_ALBUM_PREFIX = "Bilibili"

fun localFileNameFromFileReference(reference: String?): String? {
    val raw = reference?.takeIf(String::isNotBlank) ?: return null
    val path = when {
        raw.startsWith('/') -> raw
        raw.startsWith("file:", ignoreCase = true) -> runCatching { URI(raw).path }.getOrNull()
        else -> null
    } ?: return null
    return File(path).name.takeIf(String::isNotBlank)
}

fun DownloadedSong.resolvedLocalFileName(): String? {
    // document id 只用于定位，真实文件名由扫描或下载时的 StoredEntry 提供
    return localFileName?.takeIf(String::isNotBlank)
        ?: localFileNameFromFileReference(mediaUri?.takeIf(String::isNotBlank) ?: filePath)
}

fun DownloadedSong.remoteSourceIdentityOrNull(): SongIdentity? {
    stableKey.toRemoteSourceIdentityOrNull()?.let { return it }
    return rebuildRemoteSourceIdentity()
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
    ).remoteSourceIdentityOrNull()
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

fun DownloadedSong.toPlaybackSongItem(): SongItem {
    return toPlaybackSongItem(
        playbackUri = mediaUri?.takeIf(String::isNotBlank) ?: filePath,
        localFileName = resolvedLocalFileName(),
        localFilePath = filePath.takeIf { it.startsWith("/") },
        resolvedDurationMs = durationMs
    )
}

fun DownloadedSong.toPlaybackSongItem(
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
    val resolvedAlbum = if (remoteSourceIdentity != null) {
        album.trim()
            .takeIf { it.isNotBlank() && it != LocalSongSupport.LOCAL_ALBUM_IDENTITY }
            ?: remoteSourceIdentity.album
    } else {
        LocalSongSupport.LOCAL_ALBUM_IDENTITY
    }
    return SongItem(
        id = resolvedSongId,
        name = name,
        artist = artist,
        album = resolvedAlbum,
        albumId = 0L,
        durationMs = resolvedDurationMs.coerceAtLeast(0L),
        coverUrl = coverPath ?: coverUrl,
        mediaUri = playbackUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedRomanizedLyric = matchedRomanizedLyric,
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
        originalRomanizedLyric = originalRomanizedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = resolvedSourceChannel,
        audioId = resolvedSourceAudioId,
        subAudioId = resolvedSourceSubAudioId,
        playlistContextId = sourcePlaylistContextId,
        sourceStableKey = remoteSourceIdentity?.stableKey() ?: stableKey,
        logicalCreatedAtMs = downloadTime.takeIf { it > 0L },
        createdAtSource = "MANAGED_COMMIT".takeIf { downloadTime > 0L },
        createdAtConfidence = "EXACT".takeIf { downloadTime > 0L }
    )
}
