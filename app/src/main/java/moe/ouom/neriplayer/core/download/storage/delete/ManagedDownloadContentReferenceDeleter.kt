package moe.ouom.neriplayer.core.download.storage.delete

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

internal object ManagedDownloadContentReferenceDeleter {
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

    fun isMissingManagedDocumentFailure(error: Throwable): Boolean {
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

    fun resolveDocumentFile(context: Context, uri: Uri): DocumentFile? {
        return DocumentFile.fromSingleUri(context, uri)
            ?: DocumentFile.fromTreeUri(context, uri)
    }

    private fun deleteContentReferenceOnce(context: Context, uri: Uri): Boolean {
        val deletedByContract = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrElse { error ->
            if (isMissingManagedDocumentFailure(error)) {
                return true
            }
            false
        }
        if (deletedByContract) {
            return true
        }

        val deletedByDocumentFile = runCatching {
            resolveDocumentFile(context, uri)?.delete() ?: false
        }.getOrElse { error ->
            if (isMissingManagedDocumentFailure(error)) {
                return true
            }
            false
        }
        return deletedByDocumentFile
    }
}
