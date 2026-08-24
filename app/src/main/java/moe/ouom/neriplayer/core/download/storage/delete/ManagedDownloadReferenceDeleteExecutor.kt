package moe.ouom.neriplayer.core.download.storage.delete

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadReferenceDeleteExecutor(
    private val tag: String,
    private val isReferenceAllowed: (
        String,
        Set<TrustedManagedRef>,
        Collection<String>,
        Collection<String>
    ) -> Boolean,
    private val referenceDeleteParallelism: Int = SAF_REFERENCE_DELETE_PARALLELISM,
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
        references: Collection<TrustedManagedRef>,
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
                deletedReferences += reference.externalReference
            }
        }
        return ManagedDownloadReferenceDeleteResult(
            requestedReferences = normalizedReferences.map(TrustedManagedRef::externalReference),
            deletedReferences = deletedReferences
        )
    }

    suspend fun deleteReferencesConcurrently(
        context: Context,
        references: Collection<TrustedManagedRef>,
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
            deletedReferences += deletedInAttempt.map(TrustedManagedRef::externalReference)
            unresolvedReferences.removeAll(deletedInAttempt.toSet())
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
            }.map(TrustedManagedRef::externalReference)
        }
        NPLogger.d(
            tag,
            "批量删除引用完成: requested=${normalizedReferences.size}, deleted=${deletedReferences.size}, costMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return ManagedDownloadReferenceDeleteResult(
            requestedReferences = normalizedReferences.map(TrustedManagedRef::externalReference),
            deletedReferences = deletedReferences
        )
    }

    fun deleteContentReference(context: Context, reference: String, uri: Uri): Boolean {
        val candidateUris = documentUriAliases(uri)
        var deletedByProvider = false
        var deleted = false
        var permissionFailure: SecurityException? = null
        for (candidateUri in candidateUris) {
            if (deleted) break
            try {
                val providerDeleted = contentReferenceDeleteOperation(
                    context,
                    candidateUri,
                    SAF_DELETE_MAX_ATTEMPTS,
                    SAF_DELETE_RETRY_DELAY_MS
                )
                deletedByProvider = deletedByProvider || providerDeleted
                deleted = isReferenceGone(
                    context,
                    TrustedManagedRef(
                        reference = StorageReference.SafRef(candidateUri),
                        externalReference = reference
                    )
                )
            } catch (error: SecurityException) {
                permissionFailure = error
            }
        }
        if (!deleted) {
            permissionFailure?.let { throw it }
        }
        NPLogger.d(
            tag,
            "SAF 删除结果: reference=$reference, provider=$deletedByProvider, confirmed=$deleted"
        )
        if (!deleted) {
            NPLogger.w(tag, "删除下载 content 引用失败: $reference")
        }
        return deleted
    }

    private fun documentUriAliases(uri: Uri): List<Uri> {
        if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank()) {
            return listOf(uri)
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return listOf(uri)
        return buildList {
            add(uri)
            runCatching {
                DocumentsContract.buildDocumentUri(uri.authority, documentId)
            }.getOrNull()?.let(::add)
            if (uri.pathSegments.any { it == "tree" }) {
                runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                }.getOrNull()?.let(::add)
            }
        }.distinct()
    }

    private fun deleteReference(context: Context, reference: TrustedManagedRef): Boolean {
        return when (val storageReference = reference.reference) {
            is StorageReference.FileRef -> {
                val file = File(storageReference.logicalPath)
                !file.exists() || file.delete()
            }

            is StorageReference.SafRef -> {
                deleteContentReference(
                    context,
                    reference.externalReference,
                    storageReference.uri
                )
            }
        }
    }

    private fun deleteReferenceOnce(context: Context, reference: TrustedManagedRef): Boolean {
        return when (val storageReference = reference.reference) {
            is StorageReference.FileRef -> {
                val file = File(storageReference.logicalPath)
                !file.exists() || file.delete()
            }

            is StorageReference.SafRef -> {
                contentReferenceDeleteOperation(context, storageReference.uri, 1, 0L)
            }
        }
    }

    private fun filterAllowedReferences(
        references: List<TrustedManagedRef>,
        deletePolicy: ManagedDownloadDeletePolicy
    ): List<TrustedManagedRef> {
        return references.mapNotNull { reference ->
            val enumerated = deletePolicy.trustedReferences.firstOrNull {
                it.externalReference == reference.externalReference
            }
            val trusted = enumerated ?: reference
                .takeIf { it.reference is StorageReference.FileRef }
                ?.let { fileReference ->
                    val underManagedRoot = isReferenceAllowed(
                        fileReference.externalReference,
                        emptySet(),
                        deletePolicy.managedFileRoots,
                        deletePolicy.managedTreeRoots
                    )
                    fileReference.takeIf { underManagedRoot }
                }
            if (trusted == null) {
                NPLogger.w(tag, "拒绝删除未由完整枚举确认的下载引用: ${reference.externalReference}")
                return@mapNotNull null
            }
            val isAllowed = when (trusted.reference) {
                is StorageReference.FileRef -> isReferenceAllowed(
                    trusted.externalReference,
                    if (enumerated != null) {
                        setOf(trusted)
                    } else {
                        emptySet()
                    },
                    deletePolicy.managedFileRoots,
                    deletePolicy.managedTreeRoots
                )
                is StorageReference.SafRef -> trusted.externalReference.startsWith(
                    "content://",
                    ignoreCase = true
                )
            }
            if (!isAllowed) {
                NPLogger.w(
                    tag,
                    "拒绝删除不在托管范围内的下载引用: ${reference.externalReference}"
                )
                null
            } else {
                trusted
            }
        }.distinctBy(TrustedManagedRef::externalReference)
    }

    private suspend fun runReferencesWithFixedWorkers(
        references: List<TrustedManagedRef>,
        operation: (TrustedManagedRef) -> Boolean
    ): Set<TrustedManagedRef> {
        if (references.isEmpty()) {
            return emptySet()
        }
        val successfulReferences = ConcurrentHashMap.newKeySet<TrustedManagedRef>()
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
                            .getOrElse { error ->
                                if (error is SecurityException) {
                                    throw error
                                }
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

    private fun isReferenceGone(context: Context, reference: TrustedManagedRef): Boolean {
        return when (val storageReference = reference.reference) {
            is StorageReference.FileRef -> !File(storageReference.logicalPath).exists()
            is StorageReference.SafRef -> {
                contentReferenceGoneOperation(context, storageReference.uri)
            }
        }
    }

    private fun normalizeReferences(references: Collection<TrustedManagedRef>): List<TrustedManagedRef> {
        return references
            .filter { it.externalReference.isNotBlank() }
            .distinctBy(TrustedManagedRef::externalReference)
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
