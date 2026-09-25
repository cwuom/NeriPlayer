package moe.ouom.neriplayer.data.model

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.model/SongIdentity
 * Updated: 2026/3/23
 */


import android.content.Context
import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.model.SyncSong
import moe.ouom.neriplayer.data.sync.model.sanitizeCoverUrlForSync
import java.util.Locale

@Parcelize
data class SongIdentity(
    val id: Long,
    val album: String,
    val mediaUri: String?
) : Parcelable

private const val YOUTUBE_MUSIC_IDENTITY_ALBUM = "youtube_music"
private const val BILIBILI_IDENTITY_HINT = "Bilibili"

fun SongIdentity.stableKey(): String = buildString {
    append(id)
    append('|')
    append(album)
    append('|')
    append(mediaUri.orEmpty())
}

fun SongItem.identity(): SongIdentity {
    normalizedSourceStableIdentity()?.let { return it }
    normalizedRemoteIdentity()?.let { return it }
    return SongIdentity(
        id = normalizedYouTubeMusicId(this) ?: id,
        album = normalizedYouTubeMusicAlbum(this),
        mediaUri = normalizedIdentityMediaUri(this)
    )
}

fun SongItem.stableKey(): String = identity().stableKey()

/**
 * 为播放界面的封面和背景提供不随本地引用迁移而变化的身份
 *
 * 下载、删除和同步仍使用 stableKey。这里单独排除会在私有目录和 SAF
 * 之间变化的路径，只让视觉缓存跟随同一首歌而不是跟随某次扫描结果
 */
internal fun SongItem.playbackVisualKey(): String {
    remoteDownloadIdentityOrNull()?.stableKey()?.let { return "remote:$it" }

    if (!LocalSongSupport.isLocalSong(this, null)) {
        return "song:${stableKey()}"
    }

    val sourceKey = sourceStableKey
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeUnless(::isVolatileLocalSourceKey)
    if (sourceKey != null) {
        return "local-source:$sourceKey"
    }

    localPlaybackAudioId()?.let { return "local-audio:$it" }

    val fallback = localVisualMetadataKey(this)
    if (fallback.isNotBlank()) {
        // 文件名和原始标签在目录迁移后仍保持不变, 比路径哈希更适合做视觉身份
        return "local:$fallback"
    }

    return "local-id:$id"
}

/**
 * 返回视觉缓存使用的身份集合
 *
 * 本地文件不能仅凭文件名和标签归属到同一首歌, 因此不把元数据候选
 * 当作缓存所有者。目录迁移由稳定的 sourceStableKey 或 audioId 负责
 */
internal fun SongItem.playbackVisualKeyAliases(): List<String> {
    val aliases = linkedSetOf(playbackVisualKey())
    if (!LocalSongSupport.isLocalSong(this, null)) {
        return aliases.toList()
    }

    remoteDownloadIdentityOrNull()
        ?.stableKey()
        ?.let { aliases += "remote:$it" }

    sourceStableKey
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeUnless(::isVolatileLocalSourceKey)
        ?.let { aliases += "local-source:$it" }

    localPlaybackAudioId()?.let { aliases += "local-audio:$it" }
    return aliases.toList()
}

private fun isVolatileLocalSourceKey(sourceKey: String): Boolean {
    if (
        sourceKey.startsWith("/", ignoreCase = false) ||
        sourceKey.contains("content://", ignoreCase = true) ||
        sourceKey.contains("file://", ignoreCase = true)
    ) {
        return true
    }
    val identity = parseStableSongIdentity(sourceKey) ?: return false
    return identity.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY ||
        identity.mediaUri?.let(LocalSongSupport::isLocalMediaUri) == true
}

private fun localVisualFileName(song: SongItem): String? {
    song.localFileName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    song.localFilePath
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    val rawReference = song.mediaUri
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
    val pathSegment = runCatching {
        Uri.parse(rawReference).lastPathSegment
    }.getOrNull()
    return (pathSegment ?: rawReference.substringAfterLast('/'))
        .let(Uri::decode)
        ?.takeIf(String::isNotBlank)
}

private fun SongItem.localPlaybackAudioId(): String? {
    return audioId
        ?.trim()
        ?.takeIf {
            it.isNotBlank() &&
                !it.equals("0", ignoreCase = true) &&
                channelId?.equals("local", ignoreCase = true) == true
        }
}

private fun localVisualMetadataKey(song: SongItem): String {
    val fileName = localVisualFileName(song)
    val title = song.originalName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: song.name.trim().takeIf(String::isNotBlank)
    val artistName = song.originalArtist
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: song.artist.trim().takeIf(String::isNotBlank)
    return listOfNotNull(fileName, title, artistName)
        .map(::normalizeVisualIdentityToken)
        .filter(String::isNotBlank)
        .joinToString("|")
}

private fun normalizeVisualIdentityToken(value: String): String {
    return value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
}

internal fun SongItem.remoteSourceIdentityOrNull(): SongIdentity? =
    normalizedSourceStableIdentity()

/**
 * 返回下载目录使用的远端身份, 即使歌曲当前带有本地播放引用也不丢失来源
 * 旧版本或异步恢复期间可能没有 sourceStableKey, 但 channel/audio 字段仍足以确认来源
 */
internal fun SongItem.remoteDownloadIdentityOrNull(): SongIdentity? {
    remoteSourceIdentityOrNull()?.let { return it }
    val rawChannel = channelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
    if (rawChannel == null) {
        return null
    }
    val sourceChannel = normalizedChannelId(
        rawChannelId = rawChannel,
        album = album,
        mediaUri = null,
        inferNeteaseForBlankRemote = false
    ) ?: return null
    val sourceAudio = audioId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: id.takeIf { it > 0L }?.toString()
        ?: return null
    val sourceSong = copy(
        id = id,
        album = sourceChannel,
        albumId = 0L,
        mediaUri = null,
        localFileName = null,
        localFilePath = null,
        channelId = sourceChannel,
        audioId = sourceAudio,
        subAudioId = subAudioId?.trim()?.takeIf(String::isNotBlank),
        sourceStableKey = null
    )
    return sourceSong.normalizedRemoteIdentity()
}

internal fun SongItem.isSyncableRemoteSong(context: Context? = null): Boolean {
    return !LocalSongSupport.isLocalSong(this, context) ||
        remoteSourceIdentityOrNull() != null
}

internal fun SongItem.toSyncableRemoteSongOrNull(context: Context? = null): SongItem? {
    if (!LocalSongSupport.isLocalSong(this, context)) {
        return this
    }
    val sourceIdentity = remoteSourceIdentityOrNull() ?: return null
    val rawSourceChannel = channelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
    val sourceChannel = rawSourceChannel ?: sourceIdentity.album
    val sourceAudioId = audioId
        ?.trim()
        ?.takeIf { rawSourceChannel != null && it.isNotBlank() }
        ?: sourceIdentity.id.toString().takeIf { sourceChannel == "netease" }
    val sourceSubAudioId = subAudioId
        ?.trim()
        ?.takeIf { rawSourceChannel != null && it.isNotBlank() }
    val retainsSourceAddress = rawSourceChannel != null && sourceAudioId != null
    val sourceIsNetease = sourceChannel.equals("netease", ignoreCase = true) &&
        sourceIdentity.album.equals("netease", ignoreCase = true) &&
        sourceIdentity.mediaUri == null
    val mapper = context?.let(CoverUrlMapper::getInstance)
    val syncCoverUrl = sanitizeCoverUrlForSync(coverUrl, mapper)
        ?: sanitizeCoverUrlForSync(originalCoverUrl, mapper)
    val syncCustomCoverUrl = sanitizeCoverUrlForSync(customCoverUrl, mapper)
    val syncOriginalCoverUrl = sanitizeCoverUrlForSync(originalCoverUrl, mapper)

    return copy(
        id = if (sourceIsNetease) {
            sourceIdentity.id
        } else if (retainsSourceAddress) {
            id
        } else {
            sourceIdentity.id
        },
        album = sourceIdentity.album,
        albumId = 0L,
        mediaUri = sourceIdentity.mediaUri,
        localFileName = null,
        localFilePath = null,
        coverUrl = syncCoverUrl,
        customCoverUrl = syncCustomCoverUrl,
        originalCoverUrl = syncOriginalCoverUrl,
        channelId = sourceChannel,
        audioId = sourceAudioId,
        subAudioId = sourceSubAudioId,
        streamUrl = null
    )
}

fun SyncSong.identity(): SongIdentity {
    normalizedRemoteIdentity()?.let { return it }
    return SongIdentity(
        id = extractYouTubeMusicVideoId(mediaUri)?.let(::stableYouTubeMusicId) ?: id,
        album = extractYouTubeMusicVideoId(mediaUri)?.let { YOUTUBE_MUSIC_IDENTITY_ALBUM } ?: album,
        mediaUri = extractYouTubeMusicVideoId(mediaUri)?.let { buildYouTubeMusicMediaUri(it) } ?: mediaUri
    )
}

fun SyncSong.stableKey(): String = identity().stableKey()

fun SongItem.sameIdentityAs(other: SongItem?): Boolean {
    if (other == null) return false
    if (identity() == other.identity()) return true
    if (!LocalSongSupport.isLocalSong(this, null) || !LocalSongSupport.isLocalSong(other, null)) {
        return false
    }
    return LocalSongSupport.hasSameLocalSource(
        first = this,
        second = other
    )
}

fun SyncSong.sameIdentityAs(other: SyncSong?): Boolean {
    return other != null && identity() == other.identity()
}

private fun normalizedYouTubeMusicId(song: SongItem): Long? {
    return extractYouTubeMusicVideoId(song.mediaUri)?.let(::stableYouTubeMusicId)
}

private fun normalizedYouTubeMusicAlbum(song: SongItem): String {
    return if (extractYouTubeMusicVideoId(song.mediaUri) != null) {
        YOUTUBE_MUSIC_IDENTITY_ALBUM
    } else {
        LocalSongSupport.identityAlbumKey(song)
    }
}

private fun normalizedIdentityMediaUri(song: SongItem): String? {
    val videoId = extractYouTubeMusicVideoId(song.mediaUri)
    return if (videoId != null) {
        buildYouTubeMusicMediaUri(videoId)
    } else if (LocalSongSupport.isLocalSong(song, null)) {
        LocalSongSupport.identityMediaReference(song)
    } else {
        song.localFilePath ?: song.mediaUri
    }
}

private fun SongItem.normalizedRemoteIdentity(): SongIdentity? {
    if (LocalSongSupport.isLocalSong(this, null)) return null

    val videoId = extractYouTubeMusicVideoId(mediaUri)
    if (videoId != null) {
        return SongIdentity(
            id = stableYouTubeMusicId(videoId),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(videoId)
        )
    }

    val channel = normalizedChannelId(
        rawChannelId = channelId,
        album = album,
        mediaUri = mediaUri,
        inferNeteaseForBlankRemote = true
    )
    val audio = audioId?.trim()?.takeIf { it.isNotBlank() } ?: id.takeIf { it != 0L }?.toString()
    if (channel == null || audio == null) return null
    if (channel == YOUTUBE_MUSIC_IDENTITY_ALBUM) {
        return SongIdentity(
            id = stableYouTubeMusicId(audio),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(audio)
        )
    }

    return SongIdentity(
        id = stableRemoteIdentityId(
            channel = channel,
            audio = audio,
            subAudio = normalizedSubAudioId(channel, subAudioId, album)
        ),
        album = channel,
        mediaUri = null
    )
}

private fun SongItem.normalizedSourceStableIdentity(): SongIdentity? {
    val sourceKey = sourceStableKey
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val sourceIdentity = parseStableSongIdentity(sourceKey) ?: return null
    if (sourceIdentity.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY) return null
    return sourceIdentity
}

internal fun SongItem.recoverNeteaseRemoteSourceFromStaleLocalCopy(): SongItem? {
    if (!LocalSongSupport.isLocalSong(this, null)) return null
    val sourceIdentity = sourceStableKey
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::parseStableSongIdentity)
        ?: return null
    if (sourceIdentity.album != "netease" || sourceIdentity.mediaUri != null) {
        return null
    }

    return copy(
        id = sourceIdentity.id,
        album = "Netease",
        albumId = 0L,
        mediaUri = null,
        localFileName = null,
        localFilePath = null,
        channelId = "netease",
        audioId = sourceIdentity.id.toString(),
        subAudioId = null,
        sourceStableKey = null,
        streamUrl = null
    )
}

private fun SyncSong.normalizedRemoteIdentity(): SongIdentity? {
    val videoId = extractYouTubeMusicVideoId(mediaUri)
    if (videoId != null) {
        return SongIdentity(
            id = stableYouTubeMusicId(videoId),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(videoId)
        )
    }

    val channel = normalizedChannelId(
        rawChannelId = channelId,
        album = album,
        mediaUri = mediaUri,
        inferNeteaseForBlankRemote = true
    )
    val audio = audioId?.trim()?.takeIf { it.isNotBlank() } ?: id.takeIf { it != 0L }?.toString()
    if (channel == null || audio == null) return null
    if (channel == YOUTUBE_MUSIC_IDENTITY_ALBUM) {
        return SongIdentity(
            id = stableYouTubeMusicId(audio),
            album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
            mediaUri = buildYouTubeMusicMediaUri(audio)
        )
    }

    return SongIdentity(
        id = stableRemoteIdentityId(
            channel = channel,
            audio = audio,
            subAudio = normalizedSubAudioId(channel, subAudioId, album)
        ),
        album = channel,
        mediaUri = null
    )
}

private fun parseStableSongIdentity(stableKey: String): SongIdentity? {
    val firstSeparator = stableKey.indexOf('|')
    if (firstSeparator <= 0) return null
    val secondSeparator = stableKey.indexOf('|', firstSeparator + 1)
    if (secondSeparator <= firstSeparator) return null

    val id = stableKey.substring(0, firstSeparator).toLongOrNull() ?: return null
    val album = stableKey.substring(firstSeparator + 1, secondSeparator)
    val mediaUri = stableKey.substring(secondSeparator + 1).takeIf { it.isNotBlank() }
    if (album.isBlank()) return null
    return SongIdentity(id = id, album = album, mediaUri = mediaUri)
}

private fun normalizedChannelId(
    rawChannelId: String?,
    album: String,
    mediaUri: String?,
    inferNeteaseForBlankRemote: Boolean
): String? {
    val channel = rawChannelId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.lowercase(Locale.US)
        ?.let(::normalizeChannelAlias)
    if (channel != null) return channel

    return when {
        extractYouTubeMusicVideoId(mediaUri) != null -> YOUTUBE_MUSIC_IDENTITY_ALBUM
        album.startsWith(BILIBILI_IDENTITY_HINT, ignoreCase = true) -> "bilibili"
        album.startsWith("Netease", ignoreCase = true) -> "netease"
        inferNeteaseForBlankRemote && mediaUri.isNullOrBlank() -> "netease"
        else -> null
    }
}

private fun normalizeChannelAlias(channel: String): String {
    return when (channel) {
        "youtube", "ytmusic", "youtubemusic" -> YOUTUBE_MUSIC_IDENTITY_ALBUM
        else -> channel
    }
}

private fun normalizedSubAudioId(
    channel: String,
    rawSubAudioId: String?,
    album: String
): String {
    val explicitSubAudioId = rawSubAudioId?.trim()?.takeIf { it.isNotBlank() }
    if (channel != "bilibili") return ""
    return explicitSubAudioId ?: album
        .substringAfter('|', "")
        .substringBefore('|')
        .takeIf { it.isNotBlank() }
        .orEmpty()
}

private fun stableRemoteIdentityId(channel: String, audio: String, subAudio: String): Long {
    return when {
        channel == "netease" -> audio.toLongOrNull() ?: stableYouTubeMusicId("$channel|$audio")
        else -> stableYouTubeMusicId("$channel|$audio|$subAudio")
    }
}
