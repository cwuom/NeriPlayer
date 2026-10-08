package moe.ouom.neriplayer.data.identity

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

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.identity.normalizedChannelId
import moe.ouom.neriplayer.data.sync.identity.normalizedSubAudioId
import moe.ouom.neriplayer.data.sync.identity.stableRemoteIdentityId
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.stableKey
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.mapping.sanitizeCoverUrlForSync
import java.util.Locale

private const val YOUTUBE_MUSIC_IDENTITY_ALBUM = "youtube_music"

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
fun SongItem.playbackVisualKey(): String {
    remoteVisualAlias()?.let { return it }

    if (!LocalSongSupport.isLocalSong(this, null)) {
        return "song:${stableKey()}"
    }

    stableLocalSourceKey()?.let { return "local-source:$it" }

    localPlaybackAudioId()?.let { return "local-audio:$it" }

    val fallback = localVisualMetadataKey(this)
    // 文件名和原始标签在目录迁移后仍保持不变, 比路径哈希更适合做视觉身份
    return if (fallback.isNotBlank()) "local:$fallback" else "local-id:$id"
}

/**
 * 返回视觉缓存使用的身份集合
 *
 * 本地文件不能仅凭文件名和标签归属到同一首歌, 因此不把元数据候选
 * 当作缓存所有者。目录迁移由稳定的 sourceStableKey 或 audioId 负责
 */
fun SongItem.playbackVisualKeyAliases(): List<String> {
    val aliases = linkedSetOf(playbackVisualKey())
    if (LocalSongSupport.isLocalSong(this, null)) {
        aliases += listOfNotNull(
            remoteVisualAlias(),
            stableLocalSourceKey()?.let { "local-source:$it" },
            localPlaybackAudioId()?.let { "local-audio:$it" }
        )
    }
    return aliases.toList()
}

private fun SongItem.remoteVisualAlias(): String? =
    remoteDownloadIdentityOrNull()?.let { "remote:${it.stableKey()}" }

private fun SongItem.stableLocalSourceKey(): String? =
    sourceStableKey.trimmedOrNull()?.takeUnless(::isVolatileLocalSourceKey)

internal fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf(String::isNotBlank)

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

private fun localVisualFileName(song: SongItem): String? =
    song.localFileName.trimmedOrNull()
        ?: song.localFilePath.trimmedOrNull()?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: song.mediaUri.trimmedOrNull()?.let(::uriFileName)

private fun uriFileName(reference: String): String? {
    val pathSegment = runCatching { reference.toUri().lastPathSegment }.getOrNull()
    return Uri.decode(pathSegment ?: reference.substringAfterLast('/'))?.takeIf(String::isNotBlank)
}

private fun SongItem.localPlaybackAudioId(): String? {
    if (!"local".equals(channelId, ignoreCase = true)) return null
    return audioId.trimmedOrNull()?.takeUnless { it == "0" }
}

private fun localVisualMetadataKey(song: SongItem): String {
    val fileName = localVisualFileName(song)
    val title = song.originalName.trimmedOrNull() ?: song.name.trimmedOrNull()
    val artistName = song.originalArtist.trimmedOrNull() ?: song.artist.trimmedOrNull()
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

fun SongItem.remoteSourceIdentityOrNull(): SongIdentity? =
    normalizedSourceStableIdentity()

/**
 * 返回下载目录使用的远端身份, 即使歌曲当前带有本地播放引用也不丢失来源
 * 旧版本或异步恢复期间可能没有 sourceStableKey, 但 channel/audio 字段仍足以确认来源
 */
fun SongItem.remoteDownloadIdentityOrNull(): SongIdentity? {
    remoteSourceIdentityOrNull()?.let { return it }
    val sourceChannel = remoteChannelOrNull(channelId)?.let {
        normalizedChannelId(
            rawChannelId = it,
            album = album,
            mediaUri = null,
            inferNeteaseForBlankRemote = false
        )
    } ?: return null
    val sourceAudio = audioId.trimmedOrNull() ?: positiveIdOrNull() ?: return null
    val sourceSong = copy(
        id = id,
        album = sourceChannel,
        albumId = 0L,
        mediaUri = null,
        localFileName = null,
        localFilePath = null,
        channelId = sourceChannel,
        audioId = sourceAudio,
        subAudioId = subAudioId.trimmedOrNull(),
        sourceStableKey = null
    )
    return sourceSong.normalizedRemoteIdentity()
}

internal fun remoteChannelOrNull(channelId: String?): String? =
    channelId.trimmedOrNull()?.takeUnless { it.equals("local", ignoreCase = true) }

private fun SongItem.positiveIdOrNull(): String? = id.takeIf { it > 0L }?.toString()

fun SongItem.isSyncableRemoteSong(context: Context? = null): Boolean {
    return !LocalSongSupport.isLocalSong(this, context) ||
        remoteSourceIdentityOrNull() != null
}

fun SongItem.toSyncableRemoteSongOrNull(context: Context? = null): SongItem? {
    if (!LocalSongSupport.isLocalSong(this, context)) {
        return this
    }
    val sourceIdentity = remoteSourceIdentityOrNull() ?: return null
    val source = syncableRemoteSource(sourceIdentity)
    val mapper = context?.let(CoverUrlMapper::getInstance)
    val syncCoverUrl = sanitizeCoverUrlForSync(coverUrl, mapper)
        ?: sanitizeCoverUrlForSync(originalCoverUrl, mapper)
    val syncCustomCoverUrl = sanitizeCoverUrlForSync(customCoverUrl, mapper)
    val syncOriginalCoverUrl = sanitizeCoverUrlForSync(originalCoverUrl, mapper)

    return copy(
        id = source.id,
        album = sourceIdentity.album,
        albumId = 0L,
        mediaUri = sourceIdentity.mediaUri,
        localFileName = null,
        localFilePath = null,
        coverUrl = syncCoverUrl,
        customCoverUrl = syncCustomCoverUrl,
        originalCoverUrl = syncOriginalCoverUrl,
        channelId = source.channel,
        audioId = source.audioId,
        subAudioId = source.subAudioId,
        streamUrl = null
    )
}

private class SyncableRemoteSource(
    val id: Long,
    val channel: String,
    val audioId: String?,
    val subAudioId: String?
)

private fun SongItem.syncableRemoteSource(sourceIdentity: SongIdentity): SyncableRemoteSource {
    val rawChannel = remoteChannelOrNull(channelId) ?: return SyncableRemoteSource(
        id = sourceIdentity.id,
        channel = sourceIdentity.album,
        audioId = neteaseAudioIdOrNull(sourceIdentity.album, sourceIdentity),
        subAudioId = null
    )
    val audio = audioId.trimmedOrNull() ?: neteaseAudioIdOrNull(rawChannel, sourceIdentity)
    val keepsOwnAddress = audio != null && !isNeteaseSource(rawChannel, sourceIdentity)
    return SyncableRemoteSource(
        id = if (keepsOwnAddress) id else sourceIdentity.id,
        channel = rawChannel,
        audioId = audio,
        subAudioId = subAudioId.trimmedOrNull()
    )
}

private fun neteaseAudioIdOrNull(channel: String, sourceIdentity: SongIdentity): String? =
    if (channel == "netease") sourceIdentity.id.toString() else null

private fun isNeteaseSource(channel: String, sourceIdentity: SongIdentity): Boolean =
    channel.equals("netease", ignoreCase = true) &&
        sourceIdentity.album.equals("netease", ignoreCase = true) &&
        sourceIdentity.mediaUri == null

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

    extractYouTubeMusicVideoId(mediaUri)?.let { return youTubeMusicIdentity(it) }

    val channel = normalizedChannelId(
        rawChannelId = channelId,
        album = album,
        mediaUri = mediaUri,
        inferNeteaseForBlankRemote = true
    ) ?: return null
    val audio = remoteAudioOrNull() ?: return null
    if (channel == YOUTUBE_MUSIC_IDENTITY_ALBUM) return youTubeMusicIdentity(audio)

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

private fun SongItem.remoteAudioOrNull(): String? =
    audioId.trimmedOrNull() ?: id.takeIf { it != 0L }?.toString()

private fun youTubeMusicIdentity(videoId: String) = SongIdentity(
    id = stableYouTubeMusicId(videoId),
    album = YOUTUBE_MUSIC_IDENTITY_ALBUM,
    mediaUri = buildYouTubeMusicMediaUri(videoId)
)

private fun SongItem.normalizedSourceStableIdentity(): SongIdentity? {
    val sourceKey = sourceStableKey
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val sourceIdentity = parseStableSongIdentity(sourceKey) ?: return null
    if (sourceIdentity.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY) return null
    return sourceIdentity
}

fun SongItem.recoverNeteaseRemoteSourceFromStaleLocalCopy(): SongItem? {
    if (!LocalSongSupport.isLocalSong(this, null)) return null
    val sourceIdentity = sourceStableKey.trimmedOrNull()?.let(::parseStableSongIdentity) ?: return null
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
