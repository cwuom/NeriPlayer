package moe.ouom.neriplayer.core.download.storage.delete

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.download.storage.SAF_DELETE_MAX_ATTEMPTS
import moe.ouom.neriplayer.core.download.storage.SAF_DELETE_RETRY_DELAY_MS
import moe.ouom.neriplayer.core.download.storage.SAF_REFERENCE_DELETE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
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
    private val contentReferenceDeleteOperation:
        (Context, TrustedManagedRef, Int, Long) -> StorageMutationResult =
        { context, reference, maxAttempts, retryDelayMs ->
            val uri = (reference.reference as StorageReference.SafRef).uri
            ManagedDownloadReferenceIo.deleteContentReference(
                context = context,
                uri = uri,
                maxAttempts = maxAttempts,
                retryDelayMs = retryDelayMs
            ).toStorageMutationResult()
        },
    private val contentReferenceGoneOperation:
        (Context, TrustedManagedRef) -> ManagedDownloadReferenceIo.AccessResult =
        { context, reference ->
            ManagedDownloadReferenceIo.inspect(
                context,
                (reference.reference as StorageReference.SafRef).uri.toString()
            )
        },
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    init {
        require(referenceDeleteParallelism > 0)
    }

    fun deleteReferences(
        context: Context,
        references: Collection<TrustedManagedRef>,
        deletePolicy: ManagedDownloadDeletePolicy,
        onDeleteStarted: (TrustedManagedRef) -> Unit = {},
        onDeleteAttemptFinished: (TrustedManagedRef, Boolean) -> Unit = { _, _ -> }
    ): ManagedDownloadReferenceDeleteResult {
        val normalizedReferences = normalizeReferences(references)
        if (normalizedReferences.isEmpty()) {
            return ManagedDownloadReferenceDeleteResult.empty()
        }
        val allowedReferences = filterAllowedReferences(normalizedReferences, deletePolicy)
        val deletedReferences = linkedSetOf<String>()
        allowedReferences.forEach { reference ->
            onDeleteStarted(reference)
            var deleted = false
            try {
                deleted = deleteReference(context, reference)
                if (deleted) {
                    deletedReferences += reference.externalReference
                }
            } finally {
                onDeleteAttemptFinished(reference, deleted)
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
        deletePolicy: ManagedDownloadDeletePolicy,
        parallelism: Int = referenceDeleteParallelism,
        onDeleteStarted: (TrustedManagedRef) -> Unit = {},
        onDeleteAttemptFinished: (TrustedManagedRef, Boolean) -> Unit = { _, _ -> }
    ): ManagedDownloadReferenceDeleteResult {
        require(parallelism > 0)
        val normalizedReferences = normalizeReferences(references)
        if (normalizedReferences.isEmpty()) {
            return ManagedDownloadReferenceDeleteResult.empty()
        }
        val startedAtMs = System.currentTimeMillis()
        val allowedReferences = filterAllowedReferences(
            normalizedReferences,
            deletePolicy
        )
        val unresolvedReferences = allowedReferences.toMutableList()
        val deletedReferences = linkedSetOf<String>()
        val startedReferences = ConcurrentHashMap.newKeySet<TrustedManagedRef>()
        val finishedReferences = ConcurrentHashMap.newKeySet<TrustedManagedRef>()
        fun finishReference(reference: TrustedManagedRef, deleted: Boolean) {
            if (finishedReferences.add(reference)) {
                onDeleteAttemptFinished(reference, deleted)
            }
        }
        try {
            repeat(SAF_DELETE_MAX_ATTEMPTS) { attempt ->
                if (unresolvedReferences.isEmpty()) {
                    return@repeat
                }
                val deletedInAttempt = runReferencesWithFixedWorkers(
                    references = unresolvedReferences,
                    parallelism = parallelism,
                    beforeOperation = { reference ->
                        if (startedReferences.add(reference)) {
                            onDeleteStarted(reference)
                        }
                    },
                    afterOperationSucceeded = { reference ->
                        finishReference(reference, deleted = true)
                    }
                ) { reference ->
                    deleteReferenceOnce(context, reference)
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
                deletedReferences += runReferencesWithFixedWorkers(
                    references = unresolvedReferences,
                    parallelism = parallelism,
                    afterOperationSucceeded = { reference ->
                        finishReference(reference, deleted = true)
                    }
                ) { reference -> isReferenceGone(context, reference) }
                    .map(TrustedManagedRef::externalReference)
            }
        } finally {
            startedReferences.forEach { reference ->
                finishReference(
                    reference,
                    deleted = reference.externalReference in deletedReferences
                )
            }
        }
        NPLogger.d(
            tag,
            "批量删除引用完成: requested=${normalizedReferences.size}, " +
                "deleted=${deletedReferences.size}, " +
                "costMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return ManagedDownloadReferenceDeleteResult(
            requestedReferences = normalizedReferences.map(TrustedManagedRef::externalReference),
            deletedReferences = deletedReferences
        )
    }

    fun deleteTrustedContentReference(
        context: Context,
        reference: TrustedManagedRef
    ): StorageMutationResult {
        val storageReference = reference.reference as? StorageReference.SafRef
            ?: return StorageMutationResult.Unsupported("SAF reference required")
        val uri = storageReference.uri
        val candidateUris = documentUriAliases(uri)
        var deletedByProvider = false
        var missingAliasObserved = false
        var firstFailure: StorageMutationResult? = null
        for (candidateUri in candidateUris) {
            try {
                val providerResult = contentReferenceDeleteOperation(
                    context,
                    TrustedManagedRef(
                        reference = StorageReference.SafRef(candidateUri),
                        externalReference = reference.externalReference
                    ),
                    SAF_DELETE_MAX_ATTEMPTS,
                    SAF_DELETE_RETRY_DELAY_MS
                )
                when (providerResult) {
                    StorageMutationResult.Deleted -> {
                        // 删除接口已经返回确认结果，再做一次 stat 只会增加延迟并引入刷新竞态
                        deletedByProvider = true
                        break
                    }
                    StorageMutationResult.Missing -> {
                        // tree 和 single-document alias 可能只有一个能定位真实对象
                        missingAliasObserved = true
                    }
                    else -> {
                        if (firstFailure == null) {
                            firstFailure = providerResult
                        }
                    }
                }
            } catch (_: SecurityException) {
                if (firstFailure == null) {
                    firstFailure = StorageMutationResult.PermissionLost
                }
            }
        }
        val goneAfterFailure = !deletedByProvider &&
            firstFailure != null &&
            isReferenceGone(context, reference)
        val confirmedGone = deletedByProvider ||
            goneAfterFailure ||
            (missingAliasObserved && firstFailure == null)
        NPLogger.d(
            tag,
            "SAF 删除结果: reference=${reference.externalReference}, " +
                "provider=$deletedByProvider, confirmed=$confirmedGone"
        )
        if (deletedByProvider) {
            return StorageMutationResult.Deleted
        }
        if (goneAfterFailure) {
            return StorageMutationResult.Deleted
        }
        firstFailure?.let { return it }
        if (confirmedGone) {
            return StorageMutationResult.Missing
        }
        return StorageMutationResult.ProviderFailure(
            IllegalStateException("SAF delete was not confirmed")
        )
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
                ManagedDownloadReferenceIo.deleteFileReference(storageReference.logicalPath)
                    .toStorageMutationResult()
                    .isConfirmedMutation()
            }

            is StorageReference.SafRef -> {
                deleteTrustedContentReference(context, reference).isConfirmedMutation()
            }
        }
    }

    private fun deleteReferenceOnce(context: Context, reference: TrustedManagedRef): Boolean {
        return when (val storageReference = reference.reference) {
            is StorageReference.FileRef -> {
                ManagedDownloadReferenceIo.deleteFileReference(storageReference.logicalPath)
                    .isConfirmedDelete()
            }

            is StorageReference.SafRef -> {
                deleteSafReferenceOnceWithAliases(context, reference)
            }
        }
    }

    /**
     * 部分 DocumentsProvider 只接受 single-document URI
     * 快照可能保留 tree-scoped URI, 每次尝试都要覆盖等价别名
     */
    private fun deleteSafReferenceOnceWithAliases(
        context: Context,
        reference: TrustedManagedRef
    ): Boolean {
        val uri = (reference.reference as StorageReference.SafRef).uri
        var missingAliasObserved = false
        var firstFailure: StorageMutationResult? = null
        documentUriAliases(uri).forEach { candidateUri ->
            val candidate = TrustedManagedRef(
                reference = StorageReference.SafRef(candidateUri),
                externalReference = reference.externalReference
            )
            when (val result = contentReferenceDeleteOperation(context, candidate, 1, 0L)) {
                StorageMutationResult.Deleted -> return true
                StorageMutationResult.Missing -> missingAliasObserved = true
                else -> if (firstFailure == null) {
                    firstFailure = result
                }
            }
        }
        return firstFailure == null && missingAliasObserved
    }

    private fun filterAllowedReferences(
        references: List<TrustedManagedRef>,
        deletePolicy: ManagedDownloadDeletePolicy
    ): List<TrustedManagedRef> {
        val trustedByExternalReference = buildMap {
            deletePolicy.trustedReferences.forEach { trusted ->
                putIfAbsent(trusted.externalReference, trusted)
            }
        }
        return references.mapNotNull { reference ->
            val enumerated = trustedByExternalReference[reference.externalReference]
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
        parallelism: Int = referenceDeleteParallelism,
        beforeOperation: (TrustedManagedRef) -> Unit = {},
        afterOperationSucceeded: (TrustedManagedRef) -> Unit = {},
        operation: (TrustedManagedRef) -> Boolean
    ): Set<TrustedManagedRef> {
        if (references.isEmpty()) {
            return emptySet()
        }
        val successfulReferences = ConcurrentHashMap.newKeySet<TrustedManagedRef>()
        val nextIndex = AtomicInteger(0)
        val operationFailures = AtomicInteger(0)
        val cancellation = AtomicReference<CancellationException?>()
        val workerCount = minOf(parallelism, references.size)
        coroutineScope {
            repeat(workerCount) {
                launch(workerDispatcher) {
                    while (isActive && cancellation.get() == null) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= references.size) {
                            return@launch
                        }
                        val reference = references[index]
                        try {
                            beforeOperation(reference)
                        } catch (error: CancellationException) {
                            cancellation.compareAndSet(null, error)
                            return@launch
                        }
                        val succeeded = try {
                            operation(reference)
                        } catch (error: CancellationException) {
                            cancellation.compareAndSet(null, error)
                            return@launch
                        } catch (error: SecurityException) {
                            throw error
                        } catch (_: Throwable) {
                            operationFailures.incrementAndGet()
                            false
                        }
                        if (succeeded) {
                            successfulReferences += reference
                            afterOperationSucceeded(reference)
                        }
                    }
                }
            }
        }
        cancellation.get()?.let { error -> throw error }
        if (operationFailures.get() > 0) {
            NPLogger.w(tag, "批量删除引用执行异常: count=${operationFailures.get()}")
        }
        return successfulReferences
    }

    private fun isReferenceGone(context: Context, reference: TrustedManagedRef): Boolean {
        return when (val storageReference = reference.reference) {
            is StorageReference.FileRef -> ManagedDownloadReferenceIo.isFileReferenceGone(
                storageReference.logicalPath
            )
            is StorageReference.SafRef -> {
                documentUriAliases(storageReference.uri).all { candidateUri ->
                    val candidate = TrustedManagedRef(
                        reference = StorageReference.SafRef(candidateUri),
                        externalReference = reference.externalReference
                    )
                    contentReferenceGoneOperation(context, candidate) ==
                        ManagedDownloadReferenceIo.AccessResult.Missing
                }
            }
        }
    }

    private fun normalizeReferences(references: Collection<TrustedManagedRef>): List<TrustedManagedRef> {
        return references
            .filter { it.externalReference.isNotBlank() }
            .distinctBy(TrustedManagedRef::externalReference)
    }
}

private fun ManagedDownloadReferenceIo.DeleteResult.isConfirmedDelete(): Boolean {
    return this is ManagedDownloadReferenceIo.DeleteResult.Deleted ||
        this is ManagedDownloadReferenceIo.DeleteResult.Missing
}

private fun ManagedDownloadReferenceIo.DeleteResult.toStorageMutationResult(): StorageMutationResult {
    return when (this) {
        ManagedDownloadReferenceIo.DeleteResult.Deleted -> StorageMutationResult.Deleted
        ManagedDownloadReferenceIo.DeleteResult.Missing -> StorageMutationResult.Missing
        ManagedDownloadReferenceIo.DeleteResult.PermissionLost -> StorageMutationResult.PermissionLost
        is ManagedDownloadReferenceIo.DeleteResult.ProviderFailure -> {
            StorageMutationResult.ProviderFailure(error)
        }
    }
}

private fun StorageMutationResult.isConfirmedMutation(): Boolean {
    return this is StorageMutationResult.Deleted || this is StorageMutationResult.Missing
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
