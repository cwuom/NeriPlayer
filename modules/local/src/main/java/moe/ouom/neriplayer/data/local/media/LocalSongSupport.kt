package moe.ouom.neriplayer.data.local.media

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
 * File: moe.ouom.neriplayer.data.local.media/LocalSongSupport
 * Updated: 2026/3/23
 */

import android.content.Context
import androidx.core.net.toUri
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import java.io.File
import java.util.Locale

object LocalSongSupport {
    const val LOCAL_ALBUM_IDENTITY = "__local_files__"

    /** “本地文件”这类兜底专辑只用于显示，持久化、命名和校验都按无专辑处理 */
    fun isPlaceholderAlbum(album: String?): Boolean {
        val normalized = album?.trim()
        return normalized.isNullOrBlank() ||
            normalized == LOCAL_ALBUM_IDENTITY ||
            LocalFilesPlaylist.matches(normalized)
    }

    fun isLocalSong(song: SongItem, context: Context? = null): Boolean {
        return !song.localFilePath.isNullOrBlank() ||
            isLocalMediaUri(song.mediaUri) ||
            isLikelyLegacyLocalSong(song, context)
    }

    fun isLocalSong(
        album: String?,
        mediaUri: String?,
        albumId: Long? = null,
        context: Context? = null
    ): Boolean {
        return isLocalMediaUri(mediaUri) ||
            (
                mediaUri.isNullOrBlank() &&
                    albumId == 0L &&
                    isLocalAlbumPlaceholder(album, context)
            )
    }

    internal fun isLocalSong(
        album: String?,
        mediaUri: String?,
        albumId: Long?,
        localAlbumNames: Set<String>
    ): Boolean = isLocalMediaUri(mediaUri) ||
        (isUnaddressedLegacySong(mediaUri, albumId) && isKnownLocalAlbum(album, localAlbumNames))

    private fun isUnaddressedLegacySong(mediaUri: String?, albumId: Long?): Boolean =
        mediaUri.isNullOrBlank() && albumId == 0L

    private fun isKnownLocalAlbum(album: String?, localAlbumNames: Set<String>): Boolean {
        if (album.isNullOrBlank()) return false
        return album == LOCAL_ALBUM_IDENTITY || localAlbumNames.any { it.equals(album, ignoreCase = true) }
    }

    fun isLocalMediaUri(mediaUri: String?): Boolean =
        moe.ouom.neriplayer.data.sync.policy.isLocalMediaUri(mediaUri)

    fun sanitizeMediaUriForSync(mediaUri: String?): String? {
        return mediaUri?.takeUnless { isLocalMediaUri(it) }
    }

    fun identityMediaReference(song: SongItem): String? {
        val preferred = preferredLocalMediaReference(
            localFilePath = song.localFilePath,
            mediaUri = song.mediaUri
        )
        return normalizedLocalReference(preferred)
            ?: normalizedLocalReference(song.localFilePath)
            ?: normalizedLocalReference(song.mediaUri)
            ?: preferred
            ?: song.localFilePath
            ?: song.mediaUri
    }

    fun localDuplicateKeys(
        song: SongItem,
        includeMetadataFallback: Boolean = false
    ): Set<String> {
        if (!isLocalSong(song, null)) {
            return emptySet()
        }

        return buildSet {
            normalizedLocalReference(song.localFilePath)?.let { add("ref:$it") }
            normalizedLocalReference(song.mediaUri)?.let { add("ref:$it") }
            localAudioId(song)?.let { add("audio:$it") }
            song.sourceStableKey.trimmedOrNull()?.let { add("source:$it") }

            // 元信息只能识别历史上丢失来源引用的条目, 不能把两个真实文件合并
            if (includeMetadataFallback && isEmpty()) {
                addMetadataFallbackKeys(song)
            }
        }
    }

    fun hasSameLocalSource(
        first: SongItem,
        second: SongItem,
        includeMetadataFallback: Boolean = false
    ): Boolean {
        val firstKeys = localDuplicateKeys(first, includeMetadataFallback)
        if (firstKeys.isEmpty()) {
            return false
        }
        return localDuplicateKeys(second, includeMetadataFallback).any(firstKeys::contains)
    }

    private fun isLikelyLegacyLocalSong(song: SongItem, context: Context?): Boolean {
        return song.mediaUri.isNullOrBlank() &&
            song.albumId == 0L &&
            isLocalAlbumPlaceholder(song.album, context)
    }

    private fun isLocalAlbumPlaceholder(album: String?, context: Context?): Boolean {
        if (album.isNullOrBlank()) return false
        return album == LOCAL_ALBUM_IDENTITY || LocalFilesPlaylist.matches(album, context)
    }

    internal fun identityAlbumKey(song: SongItem): String {
        return if (isLocalSong(song, null)) LOCAL_ALBUM_IDENTITY else song.album
    }

    private fun localAudioId(song: SongItem): String? =
        song.audioId?.trim()?.takeIf { it.isNotBlank() && song.channelId.equals("local", ignoreCase = true) }

    private fun MutableSet<String>.addMetadataFallbackKeys(song: SongItem) {
        if (song.durationMs <= 0L) return
        localFileName(song)?.let(::metadataToken)?.let { add("file:$it|${song.durationMs}") }
        metadataKey(song)?.let { add("meta:$it|${song.durationMs}") }
    }

    private fun metadataKey(song: SongItem): String? {
        val title = metadataToken(song.originalName ?: song.name) ?: return null
        val artist = metadataToken(song.originalArtist ?: song.artist) ?: return null
        return "$title|$artist"
    }

    private fun metadataToken(value: String): String? =
        value.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank)

    private fun localFileName(song: SongItem): String? =
        song.localFileName.nonBlankOrNull()
            ?: song.localFilePath.nonBlankOrNull()?.let { File(it).name }
            ?: song.mediaUri.nonBlankOrNull()?.let(::mediaFileName)

    private fun mediaFileName(mediaUri: String): String? =
        if (mediaUri.startsWith("/")) {
            File(mediaUri).name
        } else {
            runCatching { mediaUri.toUri().lastPathSegment }.getOrNull()
        }

    private fun normalizedLocalReference(reference: String?): String? {
        val raw = reference.trimmedOrNull() ?: return null
        return when {
            raw.startsWith("/") -> File(raw).absolutePath
            raw.startsWith("content://", ignoreCase = true) ||
                raw.startsWith("android.resource://", ignoreCase = true) -> raw
            else -> fileUriPath(raw)?.let { File(it).absolutePath } ?: parsedLocalReference(raw)
        }
    }

    private fun fileUriPath(raw: String): String? {
        if (!raw.startsWith("file://", ignoreCase = true)) return null
        return runCatching { java.net.URI(raw).path }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun parsedLocalReference(raw: String): String? {
        val uri = runCatching { raw.toUri() }.getOrNull() ?: return null
        val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
        if (scheme in LOCAL_URI_SCHEMES) return uri.toString()
        val path = uri.path.orEmpty()
        return if (isLocalFilePath(scheme, path)) File(path).absolutePath else null
    }

    private fun isLocalFilePath(scheme: String, path: String): Boolean =
        if (scheme.isEmpty()) path.startsWith("/") else scheme == "file" && path.isNotBlank()

    private fun String?.nonBlankOrNull(): String? = this?.takeIf(String::isNotBlank)

    private fun String?.trimmedOrNull(): String? = this?.trim()?.nonBlankOrNull()

    private val LOCAL_URI_SCHEMES = setOf("content", "android.resource")
}
