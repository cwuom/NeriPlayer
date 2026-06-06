package moe.ouom.neriplayer.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.text.Normalizer

private val coverRelativePath = "${Environment.DIRECTORY_PICTURES}/NeriPlayer"

/**
 * 下载远程封面并保存到系统图片目录，便于相册直接可见
 */
suspend fun saveCoverToPictures(
    context: Context,
    imageUrl: String,
    suggestedName: String
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        require(imageUrl.isNotBlank()) { "cover url is blank" }

        val request = Request.Builder()
            .url(imageUrl)
            .build()

        AppContainer.sharedOkHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("empty response body")
            val mimeType = resolveMimeType(response.header("Content-Type"), imageUrl)
            val extension = mimeTypeToExtension(mimeType)
            val displayName = buildDisplayName(suggestedName, extension)

            body.byteStream().use { inputStream ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveWithMediaStore(
                        context = context,
                        inputStream = inputStream,
                        displayName = displayName,
                        mimeType = mimeType
                    )
                } else {
                    saveToLegacyPictures(
                        context = context,
                        inputStream = inputStream,
                        displayName = displayName,
                        mimeType = mimeType
                    )
                }
            }
        }
    }
}

private fun saveWithMediaStore(
    context: Context,
    inputStream: InputStream,
    displayName: String,
    mimeType: String
): String {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, coverRelativePath)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    val uri = resolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ) ?: throw IOException("failed to create media store record")

    try {
        resolver.openOutputStream(uri)?.use { outputStream ->
            inputStream.copyTo(outputStream)
        } ?: throw IOException("failed to open output stream")

        resolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            },
            null,
            null
        )
        return displayName
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}

private fun saveToLegacyPictures(
    context: Context,
    inputStream: InputStream,
    displayName: String,
    mimeType: String
): String {
    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val targetDir = File(picturesDir, "NeriPlayer").apply {
        if (!exists() && !mkdirs()) {
            throw IOException("failed to create pictures directory")
        }
    }
    val targetFile = uniqueFile(targetDir, displayName)

    targetFile.outputStream().use { outputStream ->
        inputStream.copyTo(outputStream)
    }

    MediaScannerConnection.scanFile(
        context,
        arrayOf(targetFile.absolutePath),
        arrayOf(mimeType),
        null
    )
    return targetFile.name
}

private fun uniqueFile(directory: File, displayName: String): File {
    val extension = displayName.substringAfterLast('.', "")
    val baseName = if (extension.isBlank()) {
        displayName
    } else {
        displayName.removeSuffix(".$extension")
    }

    var index = 0
    var candidate = File(directory, displayName)
    while (candidate.exists()) {
        index += 1
        val fileName = if (extension.isBlank()) {
            "$baseName ($index)"
        } else {
            "$baseName ($index).$extension"
        }
        candidate = File(directory, fileName)
    }
    return candidate
}

private fun buildDisplayName(suggestedName: String, extension: String): String {
    val safeName = sanitizeFileName(suggestedName)
        .replace(Regex("""\.(jpe?g|png|webp|gif|bmp|avif)$""", RegexOption.IGNORE_CASE), "")
    return "$safeName.$extension"
}

private fun sanitizeFileName(name: String): String {
    val normalized = Normalizer.normalize(name, Normalizer.Form.NFKD)
    return normalized
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .trimEnd('.')
        .ifBlank { "cover" }
}

private fun resolveMimeType(contentType: String?, imageUrl: String): String {
    val headerMimeType = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.startsWith("image/") }
    if (headerMimeType != null) {
        return headerMimeType
    }

    val guessedMimeType = URLConnection.guessContentTypeFromName(
        imageUrl.substringBefore('?').substringBefore('#')
    )?.lowercase()

    return guessedMimeType?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
}

private fun mimeTypeToExtension(mimeType: String): String = when (mimeType.lowercase()) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/bmp" -> "bmp"
    "image/avif" -> "avif"
    else -> "jpg"
}
