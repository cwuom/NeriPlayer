package moe.ouom.neriplayer.data.settings.background

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
 * File: moe.ouom.neriplayer.data.settings.background/BackgroundImageStorage
 * Updated: 2026/3/23
 */


import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

object BackgroundImageStorage {
    private const val DIRECTORY_NAME = "custom_background"
    private const val FILE_NAME_PREFIX = "background"
    private const val TEMP_FILE_NAME = "$FILE_NAME_PREFIX.tmp"
    private const val DEFAULT_EXTENSION = "jpg"

    suspend fun importFromUri(
        context: Context,
        sourceUri: Uri,
        previousUriString: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val extension = queryExtension(context, sourceUri)
        val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }
        val targetFile = File(directory, buildManagedFileName(extension))
        val tempFile = File(directory, TEMP_FILE_NAME)

        resolver.openInputStream(sourceUri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return@withContext null

        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        directory.listFiles()
            ?.filter { it.name.startsWith(FILE_NAME_PREFIX) && it != targetFile }
            ?.forEach(File::delete)

        deleteManagedBackground(context, previousUriString, keepPath = targetFile.absolutePath)
        Uri.fromFile(targetFile)
    }

    suspend fun deleteManagedBackground(
        context: Context,
        uriString: String?,
        keepPath: String? = null
    ) = withContext(Dispatchers.IO) {
        val file = resolveManagedFile(context, uriString) ?: return@withContext
        if (keepPath != null && file.absolutePath == keepPath) {
            return@withContext
        }
        if (file.exists()) {
            file.delete()
        }
    }

    private fun resolveManagedFile(context: Context, uriString: String?): File? {
        if (uriString.isNullOrBlank()) return null
        val path = localPathOf(uriString) ?: return null
        val managedDir = File(context.filesDir, DIRECTORY_NAME)
        return File(path).takeIf { it.absolutePath.startsWith(managedDir.absolutePath) }
    }

    private fun localPathOf(uriString: String): String? {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return null
        return when {
            uri.scheme.equals("file", ignoreCase = true) -> uri.path
            uri.scheme.isNullOrBlank() -> uriString
            else -> null
        }
    }

    private fun queryExtension(context: Context, uri: Uri): String {
        return displayNameExtension(queryDisplayName(context, uri))
            ?: mimeTypeExtension(context.contentResolver.getType(uri))
            ?: DEFAULT_EXTENSION
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use(::firstDisplayName)
    }.getOrNull()

    private fun firstDisplayName(cursor: Cursor): String? {
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        return if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

    private fun displayNameExtension(displayName: String?): String? =
        displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }

    private fun mimeTypeExtension(mimeType: String?): String? =
        mimeType?.substringAfterLast('/')?.substringAfter('+')?.lowercase()?.takeIf { it.isNotBlank() }

    private fun buildManagedFileName(extension: String): String {
        return "${FILE_NAME_PREFIX}_${System.currentTimeMillis()}.$extension"
    }
}
