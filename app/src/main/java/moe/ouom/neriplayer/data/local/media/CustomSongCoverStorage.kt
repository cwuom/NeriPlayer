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
 */

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.util.io.readBytesLimited
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.net.URLConnection
import java.util.Locale
import java.util.UUID
import okhttp3.OkHttpClient

object CustomSongCoverStorage {
    private const val DIRECTORY_NAME = "custom_song_covers"
    private const val ORIGINAL_DIRECTORY_NAME = "original_song_covers"
    private const val REMOTE_DIRECTORY_NAME = "RemoteCovers"
    private const val MAX_COVER_BYTES = 20L * 1024L * 1024L

    internal var remoteCoverHttpClientProvider: () -> OkHttpClient = {
        AppContainer.sharedOkHttpClient
    }
    internal var remoteCoverImageValidator: (ByteArray) -> Boolean = { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null
    }
    internal var remoteCoverMappingSink: ((String, String) -> Unit)? = null

    suspend fun importFromUri(
        context: Context,
        song: SongItem,
        sourceUri: Uri
    ): Uri? = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(sourceUri)
        if (mimeType != null && !mimeType.startsWith("image/", ignoreCase = true)) {
            return@withContext null
        }

        val bytes = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                input.readBytesLimited(MAX_COVER_BYTES)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@withContext null

        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            return@withContext null
        }

        val target = File(
            directory,
            "${song.id}_${System.currentTimeMillis()}_${UUID.randomUUID()}.${resolveExtension(context, sourceUri)}"
        )
        runCatching {
            target.outputStream().use { output -> output.write(bytes) }
        }.getOrNull() ?: return@withContext null
        Uri.fromFile(target)
    }

    /**
     * keeps the cover that was present before a local tag is replaced
     * app-private copies survive embedded-cover cache invalidation and rescans
     */
    suspend fun persistOriginalCover(
        context: Context,
        song: SongItem,
        reference: String?
    ): String? = withContext(Dispatchers.IO) {
        val normalizedReference = reference
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        if (isRemoteReference(normalizedReference)) {
            return@withContext persistRemoteCover(
                context = context,
                sourceUrl = normalizedReference
            )?.also { localReference ->
                val mappingSink = remoteCoverMappingSink
                if (mappingSink != null) {
                    mappingSink(localReference, normalizedReference)
                } else {
                    CoverUrlMapper.getInstance(context).saveCoverMapping(
                        localUrl = localReference,
                        networkUrl = normalizedReference
                    )
                }
            }
        }

        val sourceUri = runCatching { normalizedReference.toUri() }.getOrNull()
        val sourceFile = resolveLocalFileReference(normalizedReference, sourceUri)
        val directory = File(context.filesDir, ORIGINAL_DIRECTORY_NAME)
        val persistentDirectory = runCatching { directory.canonicalFile }.getOrNull()
        if (sourceFile?.isDirectory == true) {
            if (persistentDirectory == null || !isInsideDirectory(sourceFile, persistentDirectory)) {
                return@withContext null
            }
            return@withContext findStoredOriginalCover(directory, song)
                ?.toURI()
                ?.toString()
        }
        if (sourceFile?.isFile == true && persistentDirectory != null &&
            isInsideDirectory(sourceFile, persistentDirectory)
        ) {
            return@withContext sourceFile.toURI().toString()
        }

        val declaredMimeType = sourceUri
            ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
            ?.let { uri -> runCatching { context.contentResolver.getType(uri) }.getOrNull() }
        if (declaredMimeType != null &&
            !declaredMimeType.startsWith("image/", ignoreCase = true)
        ) {
            return@withContext normalizedReference
        }

        val contentUri = sourceUri?.takeIf {
            it.scheme.equals("content", ignoreCase = true)
        }
        val bytes = runCatching {
            when {
                sourceFile?.isFile == true -> sourceFile.inputStream().use { input ->
                    input.readBytesLimited(MAX_COVER_BYTES)
                }

                contentUri != null -> {
                    context.contentResolver.openInputStream(contentUri)?.use { input ->
                        input.readBytesLimited(MAX_COVER_BYTES)
                    }
                }

                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return@withContext normalizedReference

        if (!directory.exists() && !directory.mkdirs()) {
            return@withContext normalizedReference
        }

        val extension = resolveExtension(
            context = context,
            uri = sourceUri,
            fallbackName = sourceFile?.name ?: normalizedReference
        )
        val target = File(directory, originalCoverFileName(song, extension))
        if (target.isFile && target.length() > 0L) {
            return@withContext target.toURI().toString()
        }

        val temporary = File(
            directory,
            ".${target.name}.${UUID.randomUUID()}.tmp"
        )
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(target) && !target.isFile) {
                return@withContext normalizedReference
            }
            if (target.isFile && target.length() > 0L) {
                target.toURI().toString()
            } else {
                normalizedReference
            }
        } catch (_: Exception) {
            normalizedReference
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    internal suspend fun resolveLegacyOriginalCoverReference(
        context: Context,
        song: SongItem,
        references: Iterable<String?>
    ): String? {
        for (reference in references) {
            val normalizedReference = reference
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: continue
            if (!isDirectoryReference(normalizedReference)) continue

            val resolved = persistOriginalCover(
                context = context,
                song = song,
                reference = normalizedReference
            ) ?: continue
            if (!isDirectoryReference(resolved)) return resolved
        }
        return null
    }

    internal fun originalCoverFileName(song: SongItem, extension: String): String {
        val normalizedExtension = extension
            .trim()
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { "jpg" }
        return "${sha256(song.stableKey())}.$normalizedExtension"
    }

    internal fun remoteCoverFileName(contentHash: String, extension: String): String {
        val normalizedExtension = extension
            .trim()
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { "jpg" }
        return "$contentHash.$normalizedExtension"
    }

    internal fun isRemoteReference(reference: String): Boolean {
        return reference.startsWith("http://", ignoreCase = true) ||
            reference.startsWith("https://", ignoreCase = true)
    }

    internal fun isDirectoryReference(reference: String?): Boolean {
        val normalized = reference?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val uri = runCatching { normalized.toUri() }.getOrNull()
        return resolveLocalFileReference(normalized, uri)?.isDirectory == true
    }

    private fun resolveLocalFileReference(reference: String, uri: Uri?): File? {
        return when {
            reference.startsWith("/", ignoreCase = false) -> File(reference)
            uri != null && uri.scheme.equals("file", ignoreCase = true) -> {
                uri.path?.let(::File)
            }
            reference.startsWith("file:", ignoreCase = true) -> {
                runCatching { File(URI(reference)) }.getOrNull()
            }
            else -> null
        }
    }

    private fun isInsideDirectory(file: File, directory: File): Boolean {
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val directoryPath = runCatching { directory.canonicalPath }.getOrNull() ?: return false
        return filePath == directoryPath || filePath.startsWith("$directoryPath${File.separator}")
    }

    private fun findStoredOriginalCover(directory: File, song: SongItem): File? {
        val filePrefix = sha256(song.stableKey())
        return runCatching {
            directory.listFiles()
                ?.asSequence()
                ?.filter { file ->
                    file.isFile &&
                        file.length() > 0L &&
                        file.name.substringBeforeLast('.', file.name) == filePrefix
                }
                ?.maxByOrNull(File::lastModified)
        }.getOrNull()
    }

    private suspend fun persistRemoteCover(
        context: Context,
        sourceUrl: String
    ): String? {
        val request = runCatching {
            Request.Builder()
                .url(sourceUrl)
                .header("Accept", "image/*")
                .build()
        }.getOrNull() ?: return null
        val bytes = runCatching {
            remoteCoverHttpClientProvider().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type")
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                if (contentType != null && !contentType.startsWith("image/")) {
                    return@use null
                }
                val body = response.body
                val contentLength = body.contentLength()
                if (contentLength > MAX_COVER_BYTES) return@use null
                body.byteStream().use { input ->
                    input.readBytesLimited(MAX_COVER_BYTES)
                }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (!remoteCoverImageValidator(bytes)) {
            return null
        }

        val contentHash = sha256(bytes)
        val extension = contentTypeExtension(sourceUrl, request.url.toString())
        val managedFileName = remoteCoverFileName(contentHash, extension)
        if (!ManagedDownloadStorage.configuredDirectoryUri().isNullOrBlank()) {
            runCatching {
                ManagedDownloadStorage.persistRemoteCoverBytes(
                    context = context,
                    bytes = bytes,
                    fileName = managedFileName,
                    mimeType = "image/$extension"
                )
            }.getOrNull()?.let { return it }
        }

        val directory = File(context.filesDir, REMOTE_DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = File(directory, remoteCoverFileName(contentHash, extension))
        if (target.isFile && target.length() == bytes.size.toLong()) {
            return target.toURI().toString()
        }
        val existing = directory.listFiles()
            ?.firstOrNull { file ->
                file.isFile && file.name.substringBeforeLast('.') == contentHash
            }
        if (existing != null && existing.length() > 0L) {
            return existing.toURI().toString()
        }

        val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        return try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(target) && !target.isFile) {
                null
            } else {
                target.takeIf { file -> file.isFile && file.length() > 0L }
                    ?.toURI()
                    ?.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun contentTypeExtension(sourceUrl: String, normalizedUrl: String): String {
        return normalizedUrl.substringAfterLast('.', "")
            .substringBefore('?')
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .take(8)
            .takeIf { it.isNotBlank() }
            ?: sourceUrl.substringAfterLast('.', "")
                .substringBefore('?')
                .lowercase(Locale.ROOT)
                .filter { it.isLetterOrDigit() }
                .take(8)
                .takeIf { it.isNotBlank() }
                ?: "jpg"
    }

    private fun resolveExtension(
        context: Context,
        uri: Uri?,
        fallbackName: String
    ): String {
        val displayName = uri?.let { sourceUri ->
            runCatching {
                context.contentResolver.query(
                    sourceUri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull()
        }
        val fileName = displayName
            ?: uri?.lastPathSegment
            ?: fallbackName
        val fromName = fileName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .take(8)
            .takeIf { it.isNotBlank() }
        if (fromName != null) {
            return fromName
        }

        val fromMimeType = uri?.let { sourceUri ->
            runCatching { context.contentResolver.getType(sourceUri) }.getOrNull()
        }?.substringAfter('/', "")
            ?.substringAfter('+', "")
            ?.lowercase(Locale.ROOT)
            ?.filter { it.isLetterOrDigit() }
            ?.take(8)
            ?.takeIf { it.isNotBlank() }
        if (fromMimeType != null) {
            return fromMimeType
        }

        return URLConnection.guessContentTypeFromName(fileName)
            ?.substringAfter('/', "")
            ?.lowercase(Locale.ROOT)
            ?.filter { it.isLetterOrDigit() }
            ?.take(8)
            ?.takeIf { it.isNotBlank() }
            ?: "jpg"
    }

    private fun sha256(value: String): String {
        return sha256(value.toByteArray(Charsets.UTF_8))
    }

    private fun sha256(value: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun resolveExtension(context: Context, uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        val fileName = displayName ?: uri.lastPathSegment.orEmpty()
        val fromName = fileName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotBlank() }
        if (fromName != null) {
            return fromName
        }

        val fromMimeType = context.contentResolver.getType(uri)
            ?.substringAfter('/', "")
            ?.substringAfter('+', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        if (fromMimeType != null) {
            return fromMimeType
        }

        return URLConnection.guessContentTypeFromName(fileName)
            ?.substringAfter('/', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: "jpg"
    }
}
