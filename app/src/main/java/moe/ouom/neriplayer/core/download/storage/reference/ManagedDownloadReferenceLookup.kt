package moe.ouom.neriplayer.core.download.storage.reference

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.net.URI

/**
 * keeps provider failures separate from evidence that a managed reference is gone
 */
internal object ManagedDownloadReferenceLookup {
    sealed interface Result {
        data object Present : Result
        data object Missing : Result
        data object OutOfScope : Result
        data class PermissionLost(val cause: SecurityException) : Result
        data class ProviderFailure(val cause: Throwable) : Result
    }

    fun canMarkMissing(result: Result): Boolean = result is Result.Missing

    fun inspect(context: Context, reference: String?): Result {
        val normalized = reference?.trim().orEmpty()
        if (normalized.isBlank()) return Result.OutOfScope
        return try {
            val localFile = normalized.toLocalFile()
            if (localFile != null) {
                if (localFile.isFile) Result.Present else Result.Missing
            } else {
                inspectUri(context, Uri.parse(normalized))
            }
        } catch (error: SecurityException) {
            Result.PermissionLost(error)
        } catch (error: Throwable) {
            if (isMissingFailure(error)) Result.Missing
            else Result.ProviderFailure(error)
        }
    }

    fun isMissingFailure(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            when (cause) {
                is FileNotFoundException -> true
                is IllegalArgumentException -> {
                    val message = cause.message.orEmpty()
                    message.contains("missing file", ignoreCase = true) ||
                        message.contains("no such file", ignoreCase = true) ||
                        message.contains("document not found", ignoreCase = true)
                }
                else -> false
            }
        }
    }

    private fun inspectUri(context: Context, uri: Uri): Result {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content") return Result.OutOfScope
        val resolver = context.contentResolver
        return try {
            val queried = resolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )
            if (queried != null) {
                queried.use { cursor ->
                    if (!cursor.moveToFirst()) return Result.Missing
                }
            }
            resolver.openFileDescriptor(uri, "r")?.use { Result.Present }
                ?: Result.Missing
        } catch (error: SecurityException) {
            Result.PermissionLost(error)
        } catch (error: Throwable) {
            if (isMissingFailure(error)) Result.Missing
            else Result.ProviderFailure(error)
        }
    }

    private fun String.toLocalFile(): File? {
        if (startsWith("/")) return File(this)
        if (!startsWith("file:", ignoreCase = true)) return null
        return runCatching { File(URI(this)) }.getOrNull()
            ?: substringAfter(':', missingDelimiterValue = "")
                .removePrefix("//")
                .substringBefore('?')
                .takeIf(String::isNotBlank)
                ?.let(::File)
    }
}
