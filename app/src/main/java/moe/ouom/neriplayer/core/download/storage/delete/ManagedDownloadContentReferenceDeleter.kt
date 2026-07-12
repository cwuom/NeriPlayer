package moe.ouom.neriplayer.core.download.storage.delete

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo

internal object ManagedDownloadContentReferenceDeleter {
    fun deleteContentReference(
        context: Context,
        uri: Uri,
        maxAttempts: Int,
        retryDelayMs: Long
    ): Boolean {
        return ManagedDownloadReferenceIo.deleteContentReference(
            context = context,
            uri = uri,
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs
        )
    }

    fun isMissingManagedDocumentFailure(error: Throwable): Boolean {
        return ManagedDownloadReferenceIo.isMissingDocumentFailure(error)
    }

    fun resolveDocumentFile(context: Context, uri: Uri): DocumentFile? {
        return ManagedDownloadReferenceIo.resolveDocumentFile(context, uri)
    }
}
