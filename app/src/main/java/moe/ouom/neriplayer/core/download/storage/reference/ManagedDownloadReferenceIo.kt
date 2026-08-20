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
    fun readText(context: Context, reference: String): String? {
        return when {
            reference.startsWith("/") -> {
                runCatching { File(reference).inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() } }
                    .getOrNull()
                    ?: legacyDocumentUri(reference)?.let { uri ->
                        runCatching {
                            context.contentResolver.openInputStream(uri)
                                ?.bufferedReader(Charsets.UTF_8)
                                ?.use { it.readText() }
                        }.getOrNull()
                    }
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
        if (reference.isNullOrBlank()) return false
        return when {
            reference.startsWith("/") -> {
                runCatching { File(reference).inputStream().use { }; true }.getOrDefault(false) ||
                    legacyDocumentUri(reference)?.let { uri ->
                    resolveDocumentFile(context, uri)?.exists()
                        ?: runCatching {
                            context.contentResolver.openFileDescriptor(uri, "r")
                                ?.use { true } ?: false
                        }.getOrDefault(false)
                    } == true
            }
            else -> {
                reference.toLocalFileReference()?.let(File::exists)?.let { return it }
                val uri = runCatching { reference.toUri() }.getOrNull() ?: return false
                uri.toLocalFile()?.let(File::exists)?.let { return it }
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

    fun isContentReferenceGone(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { false } ?: false
        }.getOrElse { error -> isMissingDocumentFailure(error) }
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

    private fun legacyDocumentUri(reference: String): Uri? {
        val root = "/storage/emulated/0/neriplayer-download"
        val normalized = runCatching { File(reference).canonicalPath }.getOrNull() ?: return null
        val prefix = "$root/"
        if (!normalized.startsWith(prefix)) return null
        val relativePath = normalized.removePrefix(prefix).takeIf(String::isNotBlank) ?: return null
        val treeUri = runCatching {
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:neriplayer-download"
            )
        }.getOrNull() ?: return null
        val documentId = "primary:neriplayer-download/$relativePath"
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        }.getOrNull()
    }
}
