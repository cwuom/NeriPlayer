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
private const val BILIBILI_CHANNEL = "bilibili"
private const val NETEASE_CHANNEL = "netease"

fun localFileNameFromFileReference(reference: String?): String? {
    val path = filePathOf(reference?.takeIf(String::isNotBlank) ?: return null) ?: return null
    return File(path).name.takeIf(String::isNotBlank)
}

private fun filePathOf(reference: String): String? = when {
    reference.startsWith('/') -> reference
    reference.startsWith("file:", ignoreCase = true) -> runCatching { URI(reference).path }.getOrNull()
    else -> null
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
    val sourceStableKey = trimmedOrNull() ?: return null
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
    val sourceChannel = remoteChannelOrNull(sourceChannelId)
    val identityAlbum = remoteSourceAlbumOrNull() ?: sourceChannel ?: return null
    val sourceAudio = sourceAudioId.trimmedOrNull()
    val sourceMedia = remoteSourceMediaOrNull()
    if (!hasRemoteAddress(sourceAudio, sourceMedia)) return null

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
        subAudioId = sourceSubAudioId.trimmedOrNull()
    ).identity()
    return sourceIdentity.takeUnless { it.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY }
}

private fun DownloadedSong.remoteSourceAlbumOrNull(): String? =
    sourceIdentityAlbum.trimmedOrNull()?.takeUnless { it == LocalSongSupport.LOCAL_ALBUM_IDENTITY }

private fun DownloadedSong.remoteSourceMediaOrNull(): String? =
    sourceMediaUri.trimmedOrNull()?.takeUnless(LocalSongSupport::isLocalMediaUri)

private fun DownloadedSong.hasRemoteAddress(sourceAudio: String?, sourceMedia: String?): Boolean =
    sourceAudio != null || sourceMedia != null || id != 0L

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
    val remoteSourceChannel = remoteChannelOrNull(sourceChannelId)
    val resolvedSourceChannel = playbackChannel(remoteSourceChannel, remoteSourceIdentity)
    val creation = managedCreation()
    return SongItem(
        id = neteaseSongIdOrNull(resolvedSourceChannel, remoteSourceIdentity) ?: id,
        name = name,
        artist = artist,
        album = playbackAlbum(remoteSourceIdentity),
        albumId = 0L,
        durationMs = resolvedDurationMs.coerceAtLeast(0L),
        coverUrl = coverPath ?: coverUrl,
        mediaUri = playbackUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedRomanizedLyric = matchedRomanizedLyric,
        matchedLyricSource = musicPlatformOrNull(matchedLyricSource),
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalRemoteCoverUrl(),
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        originalRomanizedLyric = originalRomanizedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = resolvedSourceChannel,
        audioId = playbackAudioId(remoteSourceChannel, resolvedSourceChannel, remoteSourceIdentity),
        subAudioId = playbackSubAudioId(),
        playlistContextId = sourcePlaylistContextId,
        sourceStableKey = playbackStableKey(remoteSourceIdentity),
        logicalCreatedAtMs = creation.createdAtMs,
        createdAtSource = creation.source,
        createdAtConfidence = creation.confidence
    )
}

private val DownloadedSong.hasLegacyBiliSource: Boolean
    get() = album.startsWith(BILIBILI_SOURCE_ALBUM_PREFIX, ignoreCase = true)

private fun DownloadedSong.playbackChannel(remoteSourceChannel: String?, identity: SongIdentity?): String? =
    remoteSourceChannel ?: identity?.album ?: sourceChannelId ?: BILIBILI_CHANNEL.takeIf { hasLegacyBiliSource }

private fun DownloadedSong.playbackAudioId(
    remoteSourceChannel: String?,
    channel: String?,
    identity: SongIdentity?
): String? =
    sourceAudioId.trimmedOrNull()?.takeIf { remoteSourceChannel != null }
        ?: neteaseSourceAudioId(channel, identity)
        ?: sourceAudioId
        ?: biliDownloadAudioId(channel)

private fun neteaseSourceAudioId(channel: String?, identity: SongIdentity?): String? =
    if (channel.equals(NETEASE_CHANNEL, ignoreCase = true)) identity?.id?.toString() else null

private fun DownloadedSong.biliDownloadAudioId(channel: String?): String? =
    if (channel.equals(BILIBILI_CHANNEL, ignoreCase = true) && id > 0L) id.toString() else null

private fun DownloadedSong.playbackSubAudioId(): String? =
    sourceSubAudioId.trimmedOrNull() ?: legacyBiliCid()

private fun DownloadedSong.legacyBiliCid(): String? {
    if (!hasLegacyBiliSource) return null
    return album.substringAfter('|', "").substringBefore('|').trim().takeIf(String::isNotBlank)
}

private fun neteaseSongIdOrNull(channel: String?, identity: SongIdentity?): Long? {
    if (identity == null || !channel.equals(NETEASE_CHANNEL, ignoreCase = true)) return null
    return identity.id.takeIf { isPlainNeteaseIdentity(identity) }
}

private fun isPlainNeteaseIdentity(identity: SongIdentity): Boolean =
    identity.album.equals(NETEASE_CHANNEL, ignoreCase = true) && identity.mediaUri == null && identity.id > 0L

private fun DownloadedSong.playbackAlbum(identity: SongIdentity?): String {
    if (identity == null) return LocalSongSupport.LOCAL_ALBUM_IDENTITY
    return album.trim().takeIf { it.isNotBlank() && it != LocalSongSupport.LOCAL_ALBUM_IDENTITY } ?: identity.album
}

private fun DownloadedSong.playbackStableKey(identity: SongIdentity?): String? = identity?.stableKey() ?: stableKey

private fun DownloadedSong.originalRemoteCoverUrl(): String? =
    originalCoverUrl ?: coverUrl?.takeUnless(LocalSongSupport::isLocalMediaUri)

private fun musicPlatformOrNull(name: String?): MusicPlatform? =
    name?.let { runCatching { MusicPlatform.valueOf(it) }.getOrNull() }

private class ManagedCreation(val createdAtMs: Long?, val source: String?, val confidence: String?)

private val UNKNOWN_CREATION = ManagedCreation(createdAtMs = null, source = null, confidence = null)

private fun DownloadedSong.managedCreation(): ManagedCreation =
    if (downloadTime > 0L) ManagedCreation(downloadTime, "MANAGED_COMMIT", "EXACT") else UNKNOWN_CREATION
