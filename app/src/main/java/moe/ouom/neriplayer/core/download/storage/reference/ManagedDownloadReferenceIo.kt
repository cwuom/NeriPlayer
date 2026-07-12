package moe.ouom.neriplayer.core.download.storage.reference

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException

internal object ManagedDownloadReferenceIo {
    fun readText(context: Context, reference: String): String? {
        return when {
            reference.startsWith("/") -> File(reference).takeIf(File::exists)?.readText(Charsets.UTF_8)
            else -> runCatching {
                context.contentResolver.openInputStream(reference.toUri())
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

    fun exists(context: Context, reference: String?): Boolean {
        if (reference.isNullOrBlank()) return false
        return when {
            reference.startsWith("/") -> File(reference).exists()
            else -> {
                val uri = runCatching { reference.toUri() }.getOrNull() ?: return false
                resolveDocumentFile(context, uri)?.exists()
                    ?: runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
            }
        }
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

    fun resolveDocumentFile(context: Context, uri: Uri): DocumentFile? {
        return DocumentFile.fromSingleUri(context, uri)
            ?: DocumentFile.fromTreeUri(context, uri)
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
        val deletedByContract = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrElse { error ->
            if (isMissingDocumentFailure(error)) {
                return true
            }
            false
        }
        if (deletedByContract) {
            return true
        }

        return runCatching {
            resolveDocumentFile(context, uri)?.delete() ?: false
        }.getOrElse { error ->
            if (isMissingDocumentFailure(error)) {
                return true
            }
            false
        }
    }
}
