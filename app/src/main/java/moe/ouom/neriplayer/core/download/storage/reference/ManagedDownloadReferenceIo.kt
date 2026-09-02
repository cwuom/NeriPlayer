package moe.ouom.neriplayer.core.download.storage.reference

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageMutationLocks

internal object ManagedDownloadReferenceIo {
    private const val DOCUMENT_QUERY_ATTEMPTS = 2
    private const val NULL_DOCUMENT_CURSOR_MESSAGE =
        "provider returned null document cursor"

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data object Missing : DeleteResult
        data object PermissionLost : DeleteResult
        data class ProviderFailure(val error: Throwable) : DeleteResult
    }

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
                } catch (error: CancellationException) {
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
        return inspect(context, uri)
    }

    fun inspect(context: Context, uri: Uri): AccessResult {
        uri.toLocalFile()?.let { return inspectFile(it) }
        return inspectDocument(context, uri)
    }

    fun inspectDirectory(context: Context, uri: Uri): AccessResult {
        uri.toLocalFile()?.let { return inspectDirectoryFile(it) }
        return inspectDocumentDirectory(context, uri)
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
    ): DeleteResult {
        repeat(maxAttempts) { attempt ->
            when (val result = deleteContentReferenceOnce(context, uri)) {
                DeleteResult.Deleted,
                DeleteResult.Missing -> return result
                DeleteResult.PermissionLost -> return result
                is DeleteResult.ProviderFailure -> {
                    if (attempt == maxAttempts - 1) return result
                }
            }
            if (attempt < maxAttempts - 1) {
                runCatching {
                    Thread.sleep(retryDelayMs * (attempt + 1L))
                }
            }
        }
        return DeleteResult.ProviderFailure(
            IllegalStateException("content reference delete attempts exhausted")
        )
    }

    fun deleteFileReference(file: File): DeleteResult {
        return try {
            FileStorageMutationLocks.withTargetLockBlocking(file) {
                when {
                    !file.exists() -> DeleteResult.Missing
                    file.deleteRecursively() -> DeleteResult.Deleted
                    else -> DeleteResult.ProviderFailure(
                        IllegalStateException("file reference delete was not confirmed")
                    )
                }
            }
        } catch (_: SecurityException) {
            DeleteResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DeleteResult.ProviderFailure(error)
        }
    }

    fun deleteFileReference(path: String): DeleteResult {
        return deleteFileReference(File(path))
    }

    fun isFileReferenceGone(path: String): Boolean {
        return !File(path).exists()
    }

    fun isContentReferenceGone(context: Context, uri: Uri): Boolean {
        return when (inspect(context, uri.toString())) {
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
        } catch (_: SecurityException) {
            AccessResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (_: FileNotFoundException) {
            AccessResult.Missing
        } catch (error: Throwable) {
            AccessResult.ProviderFailure(error)
        }
    }

    private fun inspectDirectoryFile(file: File): AccessResult {
        return try {
            if (!file.isDirectory) AccessResult.Missing
            else AccessResult.Accessible
        } catch (_: SecurityException) {
            AccessResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (_: FileNotFoundException) {
            AccessResult.Missing
        } catch (error: Throwable) {
            AccessResult.ProviderFailure(error)
        }
    }

    private fun inspectDocument(context: Context, uri: Uri): AccessResult {
        try {
            val cursor = queryDocumentCursor(
                context = context,
                uri = uri,
                projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            ) ?: return inspectDocumentWithoutCursor(context, uri)
            cursor.use {
                if (!it.moveToFirst()) return AccessResult.Missing
            }
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    return AccessResult.Accessible
                } ?: return AccessResult.ProviderFailure(
                    IllegalStateException("provider returned null file descriptor")
                )
            } catch (_: SecurityException) {
                return AccessResult.PermissionLost
            } catch (error: CancellationException) {
                throw error
            } catch (error: FileNotFoundException) {
                return classifyDocumentOpenFailure(error)
            } catch (error: Throwable) {
                return classifyDocumentOpenFailure(error)
            }
        } catch (_: SecurityException) {
            return AccessResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: FileNotFoundException) {
            return classifyDocumentFailure(error)
        } catch (error: Throwable) {
            return classifyDocumentFailure(error)
        }
    }

    private fun inspectDocumentDirectory(context: Context, uri: Uri): AccessResult {
        try {
            val cursor = queryDocumentCursor(
                context = context,
                uri = uri,
                projection = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)
            ) ?: return AccessResult.ProviderFailure(
                nullDocumentCursorFailure(uri)
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
        } catch (_: SecurityException) {
            return AccessResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: FileNotFoundException) {
            return classifyDocumentFailure(error)
        } catch (error: Throwable) {
            return classifyDocumentFailure(error)
        }
    }

    private fun queryDocumentCursor(
        context: Context,
        uri: Uri,
        projection: Array<String>
    ): Cursor? {
        repeat(DOCUMENT_QUERY_ATTEMPTS) {
            context.contentResolver.query(uri, projection, null, null, null)?.let { cursor ->
                return cursor
            }
        }
        return null
    }

    private fun inspectDocumentWithoutCursor(context: Context, uri: Uri): AccessResult {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                AccessResult.Accessible
            } ?: AccessResult.ProviderFailure(nullDocumentCursorFailure(uri))
        } catch (_: SecurityException) {
            AccessResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AccessResult.ProviderFailure(nullDocumentCursorFailure(uri, error))
        }
    }

    private fun nullDocumentCursorFailure(uri: Uri, descriptorError: Throwable? = null): Throwable {
        return IllegalStateException("$NULL_DOCUMENT_CURSOR_MESSAGE: $uri").also { failure ->
            descriptorError?.let(failure::addSuppressed)
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

    private fun deleteContentReferenceOnce(context: Context, uri: Uri): DeleteResult {
        try {
            if (DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                return DeleteResult.Deleted
            }
        } catch (_: SecurityException) {
            return DeleteResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return deleteExceptionResult(error) ?: deleteContentReferenceWithFallback(context, uri)
        }
        return deleteContentReferenceWithFallback(context, uri)
    }

    private fun deleteContentReferenceWithFallback(context: Context, uri: Uri): DeleteResult {
        when (val access = inspect(context, uri.toString())) {
            AccessResult.Missing -> return DeleteResult.Missing
            AccessResult.PermissionLost -> return DeleteResult.PermissionLost
            is AccessResult.ProviderFailure -> return DeleteResult.ProviderFailure(access.error)
            AccessResult.Accessible -> Unit
        }
        try {
            if (context.contentResolver.delete(uri, null, null) > 0) {
                return DeleteResult.Deleted
            }
        } catch (_: SecurityException) {
            return DeleteResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            deleteExceptionResult(error)?.let { return it }
        }
        when (val access = inspect(context, uri.toString())) {
            AccessResult.Missing -> return DeleteResult.Deleted
            AccessResult.PermissionLost -> return DeleteResult.PermissionLost
            is AccessResult.ProviderFailure -> return DeleteResult.ProviderFailure(access.error)
            AccessResult.Accessible -> Unit
        }
        try {
            if (resolveDocumentFile(context, uri)?.delete() != true) {
                return DeleteResult.ProviderFailure(
                    IllegalStateException("DocumentFile delete was not accepted")
                )
            }
        } catch (_: SecurityException) {
            return DeleteResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return deleteExceptionResult(error) ?: DeleteResult.ProviderFailure(error)
        }
        return when (val access = inspect(context, uri.toString())) {
            AccessResult.Missing -> DeleteResult.Deleted
            AccessResult.PermissionLost -> DeleteResult.PermissionLost
            is AccessResult.ProviderFailure -> DeleteResult.ProviderFailure(access.error)
            AccessResult.Accessible -> DeleteResult.ProviderFailure(
                IllegalStateException("content reference delete was not confirmed")
            )
        }
    }

    private fun deleteExceptionResult(error: Exception): DeleteResult? {
        return when {
            isMissingDocumentFailure(error) -> DeleteResult.Missing
            isPermissionDocumentFailure(error) -> DeleteResult.PermissionLost
            else -> null
        }
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
        normalized.contains("no item at content://media/") ||
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
