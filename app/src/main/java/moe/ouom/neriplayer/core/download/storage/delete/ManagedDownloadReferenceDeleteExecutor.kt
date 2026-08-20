package moe.ouom.neriplayer.core.download.storage.delete

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    ) -> Boolean,
    private val referenceDeleteParallelism: Int = SAF_REFERENCE_DELETE_PARALLELISM,
    private val referenceUriParser: (String) -> Uri? = { reference ->
        runCatching { reference.toUri() }.getOrNull()
    },
    private val contentReferenceDeleteOperation: (Context, Uri, Int, Long) -> Boolean =
        { context, uri, maxAttempts, retryDelayMs ->
            ManagedDownloadReferenceIo.deleteContentReference(
                context = context,
                uri = uri,
                maxAttempts = maxAttempts,
                retryDelayMs = retryDelayMs
            )
        },
    private val contentReferenceGoneOperation: (Context, Uri) -> Boolean =
        ManagedDownloadReferenceIo::isContentReferenceGone
) {
    init {
        require(referenceDeleteParallelism > 0)
    }

    fun deleteReferences(
        context: Context,
        references: Collection<String?>,
        deletePolicy: ManagedDownloadDeletePolicy
    ): ManagedDownloadReferenceDeleteResult {
        val normalizedReferences = normalizeReferences(references)
        if (normalizedReferences.isEmpty()) {
            return ManagedDownloadReferenceDeleteResult.empty()
        }
        val allowedReferences = filterAllowedReferences(normalizedReferences, deletePolicy)
        val deletedReferences = linkedSetOf<String>()
        allowedReferences.forEach { reference ->
            val deleted = deleteReference(context, reference)
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
        val unresolvedReferences = filterAllowedReferences(
            normalizedReferences,
            deletePolicy
        ).toMutableList()
        val deletedReferences = linkedSetOf<String>()
        repeat(SAF_DELETE_MAX_ATTEMPTS) { attempt ->
            if (unresolvedReferences.isEmpty()) {
                return@repeat
            }
            val deletedInAttempt = runReferencesWithFixedWorkers(unresolvedReferences) {
                reference -> deleteReferenceOnce(context, reference)
            }
            deletedReferences += deletedInAttempt
            unresolvedReferences.removeAll(deletedInAttempt)
            if (
                unresolvedReferences.isNotEmpty() &&
                attempt < SAF_DELETE_MAX_ATTEMPTS - 1
            ) {
                delay(SAF_DELETE_RETRY_DELAY_MS * (attempt + 1L))
            }
        }
        if (unresolvedReferences.isNotEmpty()) {
            deletedReferences += runReferencesWithFixedWorkers(unresolvedReferences) {
                reference -> isReferenceGone(context, reference)
            }
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

    fun deleteContentReference(context: Context, reference: String, uri: Uri): Boolean {
        val deleted = contentReferenceDeleteOperation(
            context,
            uri,
            SAF_DELETE_MAX_ATTEMPTS,
            SAF_DELETE_RETRY_DELAY_MS
        ) || isReferenceGone(context, reference)
        if (!deleted) {
            NPLogger.w(tag, "删除下载 content 引用失败: $reference")
        }
        return deleted
    }

    private fun deleteReference(context: Context, reference: String): Boolean {
        return when {
            reference.startsWith("/") -> {
                val file = File(reference)
                !file.exists() || file.delete()
            }

            else -> {
                val uri = referenceUriParser(reference) ?: return false
                deleteContentReference(context, reference, uri)
            }
        }
    }

    private fun deleteReferenceOnce(context: Context, reference: String): Boolean {
        return when {
            reference.startsWith("/") -> {
                val file = File(reference)
                !file.exists() || file.delete()
            }

            else -> {
                val uri = referenceUriParser(reference) ?: return false
                contentReferenceDeleteOperation(context, uri, 1, 0L)
            }
        }
    }

    private fun filterAllowedReferences(
        references: List<String>,
        deletePolicy: ManagedDownloadDeletePolicy
    ): List<String> {
        return references.filter { reference ->
            isReferenceAllowed(
                reference,
                deletePolicy.trustedReferences,
                deletePolicy.managedFileRoots,
                deletePolicy.managedTreeRoots
            ).also { isAllowed ->
                if (!isAllowed) {
                    NPLogger.w(tag, "拒绝删除非托管下载引用: $reference")
                }
            }
        }
    }

    private suspend fun runReferencesWithFixedWorkers(
        references: List<String>,
        operation: (String) -> Boolean
    ): Set<String> {
        if (references.isEmpty()) {
            return emptySet()
        }
        val successfulReferences = ConcurrentHashMap.newKeySet<String>()
        val nextIndex = AtomicInteger(0)
        val operationFailures = AtomicInteger(0)
        val workerCount = minOf(referenceDeleteParallelism, references.size)
        coroutineScope {
            repeat(workerCount) {
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= references.size) {
                            return@launch
                        }
                        val reference = references[index]
                        val succeeded = runCatching { operation(reference) }
                            .getOrElse {
                                operationFailures.incrementAndGet()
                                false
                            }
                        if (succeeded) {
                            successfulReferences += reference
                        }
                    }
                }
            }
        }
        if (operationFailures.get() > 0) {
            NPLogger.w(tag, "批量删除引用执行异常: count=${operationFailures.get()}")
        }
        return successfulReferences
    }

    private fun isReferenceGone(context: Context, reference: String): Boolean {
        if (reference.startsWith("/")) {
            return !File(reference).exists()
        }
        val uri = referenceUriParser(reference) ?: return false
        return contentReferenceGoneOperation(context, uri)
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
