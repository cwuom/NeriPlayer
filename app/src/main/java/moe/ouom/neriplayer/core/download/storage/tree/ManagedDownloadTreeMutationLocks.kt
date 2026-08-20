package moe.ouom.neriplayer.core.download.storage.tree

import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.ConcurrentHashMap

internal object ManagedDownloadTreeMutationLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun <T> withLock(
        baseUri: Uri,
        parentDocumentId: String? = null,
        block: () -> T
    ): T {
        val key = keyFor(baseUri, parentDocumentId)
        val lock = locks.computeIfAbsent(key) { Any() }
        return synchronized(lock, block)
    }

    fun keyFor(baseUri: Uri, parentDocumentId: String? = null): String {
        val treeDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(baseUri)
        }.getOrNull()?.takeIf(String::isNotBlank)
        val documentId = parentDocumentId?.takeIf(String::isNotBlank)
            ?: runCatching { DocumentsContract.getDocumentId(baseUri) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        val scope = treeDocumentId ?: baseUri.toString()
        return "${baseUri.authority.orEmpty()}|$scope|${documentId.orEmpty()}"
    }
}
