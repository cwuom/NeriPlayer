package moe.ouom.neriplayer.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BackgroundImageStorage {
    private const val DIRECTORY_NAME = "custom_background"
    private const val FILE_NAME_PREFIX = "background"
    private const val TEMP_FILE_NAME = "$FILE_NAME_PREFIX.tmp"

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
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val path = when {
            uri.scheme.equals("file", ignoreCase = true) -> uri.path
            uri.scheme.isNullOrBlank() -> uriString
            else -> null
        } ?: return null
        val file = File(path)
        val managedDir = File(context.filesDir, DIRECTORY_NAME)
        return file.takeIf { it.absolutePath.startsWith(managedDir.absolutePath) }
    }

    private fun queryExtension(context: Context, uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        }.getOrNull()

        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: context.contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.substringAfter('+')
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }

        return extension ?: "jpg"
    }

    private fun buildManagedFileName(extension: String): String {
        return "${FILE_NAME_PREFIX}_${System.currentTimeMillis()}.$extension"
    }
}
