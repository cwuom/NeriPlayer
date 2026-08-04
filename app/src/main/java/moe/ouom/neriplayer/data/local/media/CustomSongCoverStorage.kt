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
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.io.readBytesLimited
import java.io.File
import java.net.URLConnection
import java.util.UUID

object CustomSongCoverStorage {
    private const val DIRECTORY_NAME = "custom_song_covers"
    private const val MAX_COVER_BYTES = 8L * 1024L * 1024L

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
