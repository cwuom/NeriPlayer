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

    fun exists(context: Context, reference: String?): Boolean {
        return inspect(context, reference) == AccessResult.Accessible
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

    internal fun isAccessibleDocumentReference(
        documentExists: Boolean,
        descriptorAccessible: Boolean
    ): Boolean = documentExists || descriptorAccessible

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
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { false } ?: false
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            isMissingDocumentFailure(error)
        }
    }

    fun resolveDocumentFile(context: Context, uri: Uri): DocumentFile? {
        return DocumentFile.fromSingleUri(context, uri)
            ?: DocumentFile.fromTreeUri(context, uri)
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
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                return AccessResult.Accessible
            } ?: return AccessResult.ProviderFailure(
                IllegalStateException("provider returned null file descriptor")
            )
        } catch (error: SecurityException) {
            return AccessResult.PermissionLost
        } catch (error: FileNotFoundException) {
            return AccessResult.Missing
        } catch (error: Throwable) {
            return if (isMissingDocumentFailure(error)) {
                AccessResult.Missing
            } else {
                AccessResult.ProviderFailure(error)
            }
        }
    }

    fun isMissingDocumentFailure(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            when (cause) {
                is FileNotFoundException -> true
                is IllegalArgumentException -> {
                    val message = cause.message.orEmpty()
                    message.contains("Missing file", ignoreCase = true) ||
                        message.contains("Failed to determine if", ignoreCase = true)
                }

                else -> false
            }
        }
    }

    private fun deleteContentReferenceOnce(context: Context, uri: Uri): Boolean {
        if (isContentReferenceGone(context, uri)) {
            return true
        }
        val deletedByContract = try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            if (isMissingDocumentFailure(error)) {
                return true
            }
            false
        }
        if (deletedByContract) {
            return true
        }

        return try {
            resolveDocumentFile(context, uri)?.delete() ?: false
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            if (isMissingDocumentFailure(error)) {
                return true
            }
            false
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
