package moe.ouom.neriplayer.core.download.storage.reference

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException
import java.net.URI

internal object ManagedDownloadReferenceIo {
    sealed interface AccessResult {
        data object Accessible : AccessResult
        data object Missing : AccessResult
        data object PermissionLost : AccessResult
        data class ProviderFailure(val error: Throwable) : AccessResult
    }

    fun readText(context: Context, reference: String): String? {
        return when {
            reference.startsWith("/") -> {
                val localContent = try {
                    File(reference).inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (error: SecurityException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                localContent
            }
            else -> {
                reference.toLocalFileReference()
                    ?.takeIf(File::exists)
                    ?.let { return it.readText(Charsets.UTF_8) }
                val uri = reference.toUri()
                uri.toLocalFile()?.takeIf(File::exists)?.let { return it.readText(Charsets.UTF_8) }
                runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                }.getOrElse { error ->
                    if (isMissingDocumentFailure(error)) {
                        null
                    } else {
                        throw error
                    }
                }
            }
        }
    }

    fun inspect(context: Context, reference: String?): AccessResult {
        val normalized = reference?.trim()?.takeIf(String::isNotBlank)
            ?: return AccessResult.Missing
        if (normalized.startsWith("/")) {
            return inspectFile(File(normalized))
        }
        normalized.toLocalFileReference()?.let { return inspectFile(it) }
        val uri = runCatching { normalized.toUri() }.getOrElse { error ->
            return AccessResult.ProviderFailure(error)
        }
        uri.toLocalFile()?.let { return inspectFile(it) }
        return inspectDocument(context, uri)
    }

    fun inspectDirectory(context: Context, uri: Uri): AccessResult {
        return inspectDirectory(context, uri.toString())
    }

    fun inspectDirectory(context: Context, reference: String?): AccessResult {
        val normalized = reference?.trim()?.takeIf(String::isNotBlank)
            ?: return AccessResult.Missing
        if (normalized.startsWith("/")) {
            return inspectDirectoryFile(File(normalized))
        }
        normalized.toLocalFileReference()?.let { return inspectDirectoryFile(it) }
        val uri = runCatching { normalized.toUri() }.getOrElse { error ->
            return AccessResult.ProviderFailure(error)
        }
        uri.toLocalFile()?.let { return inspectDirectoryFile(it) }
        return inspectDocumentDirectory(context, uri)
    }

    fun deleteContentReference(
        context: Context,
        uri: Uri,
        maxAttempts: Int,
        retryDelayMs: Long
    ): Boolean {
        repeat(maxAttempts) { attempt ->
            if (deleteContentReferenceOnce(context, uri)) {
                return true
            }
            if (attempt < maxAttempts - 1) {
                runCatching {
                    Thread.sleep(retryDelayMs * (attempt + 1L))
                }
            }
        }
        return false
    }

    fun isContentReferenceGone(context: Context, uri: Uri): Boolean {
        return when (val result = inspect(context, uri.toString())) {
            AccessResult.Missing -> true
            AccessResult.PermissionLost -> throw SecurityException(
                "SAF permission lost while confirming deletion: $uri"
            )
            AccessResult.Accessible,
            is AccessResult.ProviderFailure -> false
        }
    }

    fun resolveDocumentFile(context: Context, uri: Uri): DocumentFile? {
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        }
    }

    private fun inspectFile(file: File): AccessResult {
        return try {
            if (!file.isFile) AccessResult.Missing
            else file.inputStream().use { AccessResult.Accessible }
        } catch (error: SecurityException) {
            AccessResult.PermissionLost
        } catch (error: FileNotFoundException) {
            AccessResult.Missing
        } catch (error: Throwable) {
            AccessResult.ProviderFailure(error)
        }
    }

    private fun inspectDirectoryFile(file: File): AccessResult {
        return try {
            if (!file.isDirectory) AccessResult.Missing
            else AccessResult.Accessible
        } catch (error: SecurityException) {
            AccessResult.PermissionLost
        } catch (error: FileNotFoundException) {
            AccessResult.Missing
        } catch (error: Throwable) {
            AccessResult.ProviderFailure(error)
        }
    }

    private fun inspectDocument(context: Context, uri: Uri): AccessResult {
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            ) ?: return AccessResult.ProviderFailure(
                IllegalStateException("provider returned null document cursor")
            )
            cursor.use {
                if (!it.moveToFirst()) return AccessResult.Missing
            }
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    return AccessResult.Accessible
                } ?: return AccessResult.ProviderFailure(
                    IllegalStateException("provider returned null file descriptor")
                )
            } catch (error: SecurityException) {
                return AccessResult.PermissionLost
            } catch (error: FileNotFoundException) {
                return classifyDocumentOpenFailure(error)
            } catch (error: Throwable) {
                return classifyDocumentOpenFailure(error)
            }
        } catch (error: SecurityException) {
            return AccessResult.PermissionLost
        } catch (error: FileNotFoundException) {
            return classifyDocumentFailure(error)
        } catch (error: Throwable) {
            return classifyDocumentFailure(error)
        }
    }

    private fun inspectDocumentDirectory(context: Context, uri: Uri): AccessResult {
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            ) ?: return AccessResult.ProviderFailure(
                IllegalStateException("provider returned null document cursor")
            )
            cursor.use {
                if (!it.moveToFirst()) return AccessResult.Missing
                val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (mimeIndex < 0 || it.isNull(mimeIndex)) {
                    return AccessResult.ProviderFailure(
                        IllegalStateException("provider omitted document mime type")
                    )
                }
                if (it.getString(mimeIndex) != DocumentsContract.Document.MIME_TYPE_DIR) {
                    return AccessResult.Missing
                }
            }
            return AccessResult.Accessible
        } catch (error: SecurityException) {
            return AccessResult.PermissionLost
        } catch (error: FileNotFoundException) {
            return classifyDocumentFailure(error)
        } catch (error: Throwable) {
            return classifyDocumentFailure(error)
        }
    }

    private fun classifyDocumentFailure(error: Throwable): AccessResult {
        return when {
            isPermissionDocumentFailure(error) -> AccessResult.PermissionLost
            isMissingDocumentFailure(error) -> AccessResult.Missing
            else -> AccessResult.ProviderFailure(error)
        }
    }

    private fun classifyDocumentOpenFailure(error: Throwable): AccessResult {
        return when {
            isPermissionDocumentFailure(error) -> AccessResult.PermissionLost
            isMissingDocumentFailure(error) -> {
                AccessResult.Missing
            }
            else -> AccessResult.ProviderFailure(error)
        }
    }

    fun isMissingDocumentFailure(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any(::isPermissionDocumentFailure)) {
            return false
        }
        return causes.any { cause ->
            when (cause) {
                is FileNotFoundException -> cause.message.isExplicitMissingDocumentMessage()
                is IllegalArgumentException -> {
                    cause.message.isExplicitMissingDocumentMessage()
                }

                else -> false
            }
        }
    }

    fun isPermissionDocumentFailure(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            cause is SecurityException || cause.message.isPermissionDocumentMessage()
        }
    }

    private fun deleteContentReferenceOnce(context: Context, uri: Uri): Boolean {
        if (isContentReferenceGone(context, uri)) {
            return true
        }
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            if (isMissingDocumentFailure(error)) {
                return true
            }
        }
        if (isContentReferenceGone(context, uri)) return true

        try {
            context.contentResolver.delete(uri, null, null)
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            if (isMissingDocumentFailure(error)) {
                return true
            }
        }
        if (isContentReferenceGone(context, uri)) return true

        try {
            resolveDocumentFile(context, uri)?.delete() ?: false
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            if (isMissingDocumentFailure(error)) {
                return true
            }
        }
        return isContentReferenceGone(context, uri)
    }

    private fun String.toLocalFileReference(): File? {
        if (!startsWith("file:", ignoreCase = true)) return null
        return runCatching { File(URI(this)) }.getOrNull()
            ?: substringAfter(':', missingDelimiterValue = "")
                .removePrefix("//")
                .substringBefore('?')
                .takeIf(String::isNotBlank)
                ?.let(::File)
    }

    private fun Uri.toLocalFile(): File? {
        if (scheme?.equals("file", ignoreCase = true) != true) return null
        val filePath = path?.takeIf(String::isNotBlank)
            ?: schemeSpecificPart?.substringBefore('?')?.takeIf(String::isNotBlank)
        return filePath?.let(::File)
    }

}

private fun String?.isExplicitMissingDocumentMessage(): Boolean {
    val normalized = this?.lowercase().orEmpty()
    return normalized.contains("missing file") ||
        normalized.contains("missing document") ||
        normalized.contains("document not found") ||
        normalized.contains("no such file") ||
        normalized.contains("not found") ||
        normalized.contains("does not exist") ||
        normalized.contains("enoent")
}

private fun String?.isPermissionDocumentMessage(): Boolean {
    val normalized = this?.lowercase().orEmpty()
    return normalized.contains("permission denied") ||
        normalized.contains("access denied") ||
        normalized.contains("operation not permitted") ||
        normalized.contains("not permitted") ||
        normalized.contains("eacces") ||
        normalized.contains("security exception")
}
