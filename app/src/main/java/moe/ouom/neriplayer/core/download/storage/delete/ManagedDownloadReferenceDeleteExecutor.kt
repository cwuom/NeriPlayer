package moe.ouom.neriplayer.core.download.storage.delete

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.core.download.storage.SAF_DELETE_MAX_ATTEMPTS
import moe.ouom.neriplayer.core.download.storage.SAF_DELETE_RETRY_DELAY_MS
import moe.ouom.neriplayer.core.download.storage.SAF_REFERENCE_DELETE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadReferenceDeleteExecutor(
    private val tag: String,
    private val isReferenceAllowed: (
        String,
        Set<String>,
        Collection<String>,
        Collection<String>
    ) -> Boolean
) {
    fun deleteReferences(
        context: Context,
        references: Collection<String?>,
        deletePolicy: ManagedDownloadDeletePolicy
    ): ManagedDownloadReferenceDeleteResult {
        val normalizedReferences = normalizeReferences(references)
        if (normalizedReferences.isEmpty()) {
            return ManagedDownloadReferenceDeleteResult.empty()
        }
        val deletedReferences = linkedSetOf<String>()
        normalizedReferences.forEach { reference ->
            val deleted = deleteReference(context, reference, deletePolicy)
            if (deleted) {
                deletedReferences += reference
            }
        }
        return ManagedDownloadReferenceDeleteResult(
            requestedReferences = normalizedReferences,
            deletedReferences = deletedReferences
        )
    }

    suspend fun deleteReferencesConcurrently(
        context: Context,
        references: Collection<String?>,
        deletePolicy: ManagedDownloadDeletePolicy
    ): ManagedDownloadReferenceDeleteResult {
        val normalizedReferences = normalizeReferences(references)
        if (normalizedReferences.isEmpty()) {
            return ManagedDownloadReferenceDeleteResult.empty()
        }
        val startedAtMs = System.currentTimeMillis()
        val deleteLimiter = Semaphore(SAF_REFERENCE_DELETE_PARALLELISM)
        val deletedReferences = coroutineScope {
            normalizedReferences.map { reference ->
                async(Dispatchers.IO) {
                    deleteLimiter.withPermit {
                        reference.takeIf { deleteReference(context, reference, deletePolicy) }
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }
        NPLogger.d(
            tag,
            "批量删除引用完成: requested=${normalizedReferences.size}, deleted=${deletedReferences.size}, costMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return ManagedDownloadReferenceDeleteResult(
            requestedReferences = normalizedReferences,
            deletedReferences = deletedReferences
        )
    }

    fun deleteContentReference(context: Context, reference: String, uri: android.net.Uri): Boolean {
        val deleted = ManagedDownloadReferenceIo.deleteContentReference(
            context = context,
            uri = uri,
            maxAttempts = SAF_DELETE_MAX_ATTEMPTS,
            retryDelayMs = SAF_DELETE_RETRY_DELAY_MS
        )
        if (!deleted) {
            NPLogger.w(tag, "删除下载 content 引用失败: $reference")
        }
        return deleted
    }

    private fun deleteReference(
        context: Context,
        reference: String,
        deletePolicy: ManagedDownloadDeletePolicy
    ): Boolean {
        if (
            !isReferenceAllowed(
                reference,
                deletePolicy.trustedReferences,
                deletePolicy.managedFileRoots,
                deletePolicy.managedTreeRoots
            )
        ) {
            NPLogger.w(tag, "拒绝删除非托管下载引用: $reference")
            return false
        }
        return when {
            reference.startsWith("/") -> {
                val file = File(reference)
                !file.exists() || file.delete()
            }

            else -> {
                val uri = runCatching { reference.toUri() }.getOrNull() ?: return false
                deleteContentReference(context, reference, uri)
            }
        }
    }

    private fun normalizeReferences(references: Collection<String?>): List<String> {
        return references
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .distinct()
    }
}

internal data class ManagedDownloadReferenceDeleteResult(
    val requestedReferences: List<String>,
    val deletedReferences: Set<String>
) {
    val hasUnconfirmedDeletes: Boolean
        get() = deletedReferences.size != requestedReferences.size

    companion object {
        fun empty(): ManagedDownloadReferenceDeleteResult {
            return ManagedDownloadReferenceDeleteResult(
                requestedReferences = emptyList(),
                deletedReferences = emptySet()
            )
        }
    }
}
