package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.SAF_COMMITTED_SIZE_TOLERANCE_BYTES
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteArtifacts
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageConfidence
import moe.ouom.neriplayer.core.download.storage.backend.StorageDirectorySnapshot
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.backend.asTrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitVerifier
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

internal data class ManagedMigrationTargetLayoutEntry(
    val subdirectory: String?,
    val name: String,
    val documentIdentity: String
)

internal fun validateMigrationTargetLayout(
    expected: List<ManagedMigrationTargetLayoutEntry>,
    observed: List<ManagedMigrationTargetLayoutEntry>
): String? {
    val expectedGroups = expected.groupBy(ManagedMigrationTargetLayoutEntry::layoutKey)
    val observedGroups = observed.groupBy(ManagedMigrationTargetLayoutEntry::layoutKey)
    for ((key, plannedEntries) in expectedGroups) {
        val displayName = plannedEntries.first().name
        val plannedIdentities = plannedEntries
            .map(ManagedMigrationTargetLayoutEntry::documentIdentity)
            .filter(String::isNotBlank)
            .distinct()
        if (plannedIdentities.size != 1 || plannedEntries.any { it.documentIdentity.isBlank() }) {
            return "迁移计划名称指向多个目标文档: $displayName"
        }
        val matchingEntries = observedGroups[key].orEmpty()
        if (matchingEntries.size != 1) {
            return "SAF 迁移目标名称不唯一: $displayName, count=${matchingEntries.size}"
        }
        if (matchingEntries.single().documentIdentity != plannedIdentities.single()) {
            return "SAF 迁移目标文档已变化: $displayName"
        }
    }
    return null
}

private data class ManagedMigrationTargetLayoutKey(
    val subdirectory: String?,
    val canonicalName: String
)

private fun ManagedMigrationTargetLayoutEntry.layoutKey(): ManagedMigrationTargetLayoutKey {
    return ManagedMigrationTargetLayoutKey(
        subdirectory = subdirectory,
        canonicalName = ManagedDownloadStorageNaming.canonicalNameKey(name)
    )
}

private data class SafMigrationTargetObservation(
    val layoutEntry: ManagedMigrationTargetLayoutEntry,
    val parent: StorageReference.SafRef
)

private fun ManagedDownloadStorage.StoredEntry.safDocumentIdentities(): Set<String> {
    return sequenceOf(reference, mediaUri)
        .mapNotNull { value ->
            val uri = runCatching { value.toUri() }.getOrNull()
                ?.takeIf { parsed ->
                    parsed.scheme.equals("content", ignoreCase = true) &&
                        !parsed.authority.isNullOrBlank()
                }
                ?: return@mapNotNull null
            StorageReference.SafRef(uri).documentIdentity()
        }
        .toSet()
}

private fun StorageReference.SafRef.documentIdentity(): String? {
    val authority = uri.authority?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
        ?: return null
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return null
    return "$authority\u0000$documentId"
}

internal class ManagedDownloadMigrationFinalizer(
    private val tag: String,
    private val rewriteParallelism: (ManagedDownloadRootHandle) -> Int,
    private val deleteParallelism: (ManagedDownloadRootHandle) -> Int,
    private val readText: (Context, String) -> String?,
    private val entryReader: ManagedMigrationEntryReader,
    private val writeRootText: (Context, ManagedDownloadRootHandle, String, String) -> ManagedDownloadStorage.StoredEntry?,
    private val restoreLastModified: (
        Context,
        ManagedDownloadStorage.StoredEntry,
        Long
    ) -> Unit = { _, _, _ -> },
    private val deleteReference: (
        Context,
        TrustedManagedRef,
        ManagedDownloadRootHandle
    ) -> StorageMutationResult,
    private val rewriteMetadataReferences: (String, Map<String, String>) -> String,
    private val deleteReferences: (
        Context,
        Collection<TrustedManagedRef>,
        ManagedDownloadRootHandle,
        (TrustedManagedRef) -> Unit,
        (TrustedManagedRef) -> Unit
    ) -> Map<TrustedManagedRef, StorageMutationResult> = {
            context,
            references,
            root,
            onDeleteStarted,
            onDeleteFinished ->
        references.associateWith { reference ->
            onDeleteStarted(reference)
            try {
                deleteReference(context, reference, root)
            } finally {
                onDeleteFinished(reference)
            }
        }
    },
    private val restoreReplacement: (
        Context,
        ManagedDownloadRootHandle,
        CopiedMigrationEntry
    ) -> Boolean = { _, _, copied -> copied.replacementBackup == null }
) {
    suspend fun rewriteMigratedMetadataReferences(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        copiedEntries: List<CopiedMigrationEntry>,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): ManagedMigrationMetadataRewriteResult = coroutineScope {
        if (copiedEntries.isEmpty()) {
            return@coroutineScope ManagedMigrationMetadataRewriteResult(
                copiedEntries = emptyList(),
                failedFiles = 0
            )
        }
        val referenceMap = copiedEntries.migrationReferenceMap()
        val rewriteLimiter = Semaphore(rewriteParallelism(targetRoot))
        val outcomesByIndex = copiedEntries
            .mapIndexedNotNull { index, copied ->
                if (!ManagedDownloadTreeNaming.isMetadataName(copied.original.entry.name)) {
                    return@mapIndexedNotNull null
                }
                async(Dispatchers.IO) {
                    index to rewriteLimiter.withPermit {
                        rewriteMetadataEntry(context, targetRoot, copied, referenceMap, progressTracker)
                    }
                }
            }
            .awaitAll()
            .toMap()
        ManagedMigrationMetadataRewriteResult(
            copiedEntries = copiedEntries.mapIndexed { index, copied ->
                outcomesByIndex[index]?.copied ?: copied
            },
            failedFiles = outcomesByIndex.values.count(MetadataRewriteOutcome::failed),
            error = outcomesByIndex.values
                .mapNotNull(MetadataRewriteOutcome::error)
                .firstOrNull { error -> !error.retryable }
                ?: outcomesByIndex.values.mapNotNull(MetadataRewriteOutcome::error).firstOrNull()
        )
    }

    suspend fun verifyMigratedEntries(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        copiedEntries: List<CopiedMigrationEntry>,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): Int {
        return verifyMigratedEntriesDetailed(
            context = context,
            targetRoot = targetRoot,
            copiedEntries = copiedEntries,
            progressTracker = progressTracker
        ).failedFiles
    }

    suspend fun verifyMigratedEntriesDetailed(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        copiedEntries: List<CopiedMigrationEntry>,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): ManagedMigrationVerificationResult = coroutineScope {
        if (copiedEntries.isEmpty()) {
            return@coroutineScope ManagedMigrationVerificationResult(failedFiles = 0)
        }
        progressTracker?.startVerification(copiedEntries)
        val referenceMap = copiedEntries.migrationReferenceMap()
        val verificationLimiter = Semaphore(rewriteParallelism(targetRoot))
        val outcomes = copiedEntries.map { migrationEntry ->
            async(Dispatchers.IO) {
                verificationLimiter.withPermit {
                    progressTracker?.startVerificationEntry(migrationEntry)
                    var verificationCompleted = false
                    try {
                        val outcome = if (
                            isMigrationTargetVerified(
                                context = context,
                                migrationEntry = migrationEntry,
                                referenceMap = referenceMap,
                                onProgress = { verifiedBytes ->
                                    progressTracker?.onVerificationProgress(
                                        migrationEntry,
                                        verifiedBytes
                                    )
                                }
                            )
                        ) {
                            VerificationOutcome(failed = false)
                        } else {
                            NPLogger.w(
                                tag,
                                "迁移后目标校验失败，保留源文件: ${migrationEntry.original.entry.name}"
                            )
                            VerificationOutcome(failed = true)
                        }
                        verificationCompleted = true
                        outcome
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        val migrationError = retryableMigrationIoFailure(
                            message = "迁移后目标校验暂时失败: ${migrationEntry.original.entry.name}",
                            error = error
                        )
                        NPLogger.w(
                            tag,
                            "迁移后目标校验失败，保留源文件: " +
                                "${migrationEntry.original.entry.name}, ${error.message}",
                            error
                        )
                        VerificationOutcome(
                            failed = true,
                            error = migrationError
                        ).also { verificationCompleted = true }
                    } finally {
                        if (verificationCompleted) {
                            progressTracker?.finishVerification(migrationEntry)
                        }
                    }
                }
            }
        }.awaitAll()
        val contentError = outcomes.mapNotNull(VerificationOutcome::error)
            .firstOrNull { error -> !error.retryable }
            ?: outcomes.mapNotNull(VerificationOutcome::error).firstOrNull()
        val contentFailedFiles = outcomes.count(VerificationOutcome::failed)
        if (contentFailedFiles > 0) {
            return@coroutineScope ManagedMigrationVerificationResult(
                failedFiles = contentFailedFiles,
                error = contentError
            )
        }
        val layoutError = if (targetRoot is ManagedDownloadRootHandle.TreeRoot) {
            try {
                verifySafTargetLayoutAndCleanupTemporaryWrites(
                    context = context,
                    targetRoot = targetRoot,
                    copiedEntries = copiedEntries
                )
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: ManagedDownloadMigrationException) {
                error
            } catch (error: Throwable) {
                ManagedDownloadMigrationException.transient(
                    "SAF 迁移目标批量校验暂时失败",
                    error
                )
            }
        } else {
            null
        }
        ManagedMigrationVerificationResult(
            failedFiles = if (layoutError == null) 0 else copiedEntries.size,
            error = layoutError
        )
    }

    private suspend fun verifySafTargetLayoutAndCleanupTemporaryWrites(
        context: Context,
        targetRoot: ManagedDownloadRootHandle.TreeRoot,
        copiedEntries: List<CopiedMigrationEntry>
    ) {
        val backend = SafStorageBackend(context)
        val rootParent = StorageReference.SafRef(targetRoot.tree.uri)
        val snapshotsByParent = linkedMapOf<StorageReference.SafRef, StorageDirectorySnapshot>()
        val observations = mutableListOf<SafMigrationTargetObservation>()
        val rootSnapshot = backend.list(rootParent)
        requireCompleteSafMigrationSnapshot(rootSnapshot, "下载目录")
        snapshotsByParent[rootParent] = rootSnapshot
        rootSnapshot.entries.asSequence()
            .filterNot(StorageStat::isDirectory)
            .mapTo(observations) { stat -> stat.toSafMigrationObservation(null, rootParent) }

        copiedEntries.asSequence()
            .mapNotNull { copied -> copied.original.subdirectory }
            .filter { subdirectory ->
                subdirectory == COVER_SUBDIRECTORY || subdirectory == LYRIC_SUBDIRECTORY
            }
            .distinct()
            .forEach { subdirectory ->
                rootSnapshot.entries.asSequence()
                    .filter(StorageStat::isDirectory)
                    .filter { directory ->
                        ManagedDownloadTreeNaming.matchesManagedSubdirectoryName(
                            directory.displayName,
                            subdirectory
                        )
                    }
                    .forEach { directory ->
                        val parent = directory.reference as? StorageReference.SafRef
                            ?: throw ManagedDownloadMigrationException.targetChanged(
                                "SAF 迁移子目录缺少可信文档引用: $subdirectory"
                            )
                        val snapshot = backend.list(parent)
                        requireCompleteSafMigrationSnapshot(snapshot, subdirectory)
                        snapshotsByParent[parent] = snapshot
                        snapshot.entries.asSequence()
                            .filterNot(StorageStat::isDirectory)
                            .mapTo(observations) { stat ->
                                stat.toSafMigrationObservation(subdirectory, parent)
                            }
                    }
            }

        val expectedLayout = copiedEntries.map { copied ->
            ManagedMigrationTargetLayoutEntry(
                subdirectory = copied.original.subdirectory,
                name = copied.copiedEntry.name,
                documentIdentity = copied.copiedEntry.safDocumentIdentities().singleOrNull().orEmpty()
            )
        }
        validateMigrationTargetLayout(
            expected = expectedLayout,
            observed = observations.map(SafMigrationTargetObservation::layoutEntry)
        )?.let { detail ->
            throw ManagedDownloadMigrationException.targetChanged(detail)
        }

        val expectedTargets = expectedLayout.map { expected ->
            val observed = observations.single { observation ->
                observation.layoutEntry.layoutKey() == expected.layoutKey() &&
                    observation.layoutEntry.documentIdentity == expected.documentIdentity
            }
            observed.parent to StorageTarget.SafTarget(
                parent = observed.parent,
                displayName = expected.name,
                mimeType = "application/octet-stream"
            )
        }.distinct()
        val cleanupCandidates = expectedTargets.groupBy(
            keySelector = { (parent, _) -> parent },
            valueTransform = { (_, target) -> target }
        ).flatMap { (parent, targets) ->
            val snapshot = requireNotNull(snapshotsByParent[parent]) {
                "missing verified SAF parent snapshot"
            }
            val plan = ManagedTemporaryWriteArtifacts.planTerminalCleanup(
                parent = parent,
                targets = targets,
                snapshot = snapshot
            )
            plan.skipReason?.let { reason ->
                throw ManagedDownloadMigrationException.transient(
                    "SAF 迁移临时文件清理无法确认: $reason"
                )
            }
            plan.candidates
        }.distinctBy(StorageStat::reference)
        cleanupCandidates.forEach { candidate ->
            when (val result = backend.delete(candidate.asTrustedManagedRef())) {
                StorageMutationResult.Deleted,
                StorageMutationResult.Missing -> Unit
                StorageMutationResult.OutOfScope,
                StorageMutationResult.PermissionLost,
                is StorageMutationResult.ProviderFailure,
                is StorageMutationResult.Unsupported -> {
                    throw ManagedDownloadMigrationException.transient(
                        "SAF 迁移临时文件清理未确认: ${candidate.displayName}, result=$result"
                    )
                }
            }
        }
    }

    private fun requireCompleteSafMigrationSnapshot(
        snapshot: StorageDirectorySnapshot,
        description: String
    ) {
        when (val confidence = snapshot.confidence) {
            StorageConfidence.Complete -> Unit
            StorageConfidence.Missing,
            StorageConfidence.OutOfScope -> throw ManagedDownloadMigrationException.targetChanged(
                "SAF 迁移目标目录已变化: $description"
            )
            StorageConfidence.PermissionLost -> throw ManagedDownloadMigrationException.transient(
                "SAF 迁移目标目录权限暂时不可用: $description"
            )
            is StorageConfidence.ProviderFailure -> throw ManagedDownloadMigrationException.transient(
                "SAF 迁移目标目录枚举失败: $description",
                confidence.error
            )
        }
    }

    private fun StorageStat.toSafMigrationObservation(
        subdirectory: String?,
        parent: StorageReference.SafRef
    ): SafMigrationTargetObservation {
        val safReference = reference as? StorageReference.SafRef
            ?: throw ManagedDownloadMigrationException.targetChanged(
                "SAF 迁移目标缺少文档引用: $displayName"
            )
        val identity = safReference.documentIdentity()
            ?: throw ManagedDownloadMigrationException.targetChanged(
                "SAF 迁移目标文档身份不可解析: $displayName"
            )
        return SafMigrationTargetObservation(
            layoutEntry = ManagedMigrationTargetLayoutEntry(
                subdirectory = subdirectory,
                name = displayName,
                documentIdentity = identity
            ),
            parent = parent
        )
    }

    suspend fun cleanupMigratedEntries(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        sourceRoot: ManagedDownloadRootHandle,
        targetsAlreadyVerified: Boolean = false,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): Int {
        return cleanupMigratedEntriesDetailed(
            context = context,
            copiedEntries = copiedEntries,
            sourceRoot = sourceRoot,
            targetsAlreadyVerified = targetsAlreadyVerified,
            progressTracker = progressTracker
        ).failedFiles
    }

    suspend fun cleanupMigratedEntriesDetailed(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        sourceRoot: ManagedDownloadRootHandle,
        targetsAlreadyVerified: Boolean = false,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): ManagedMigrationCleanupResult = coroutineScope {
        if (copiedEntries.isEmpty()) {
            return@coroutineScope ManagedMigrationCleanupResult(
                failedFiles = 0,
                retryableFailedFiles = 0
            )
        }
        progressTracker?.startCleanup(copiedEntries.size, null)
        val referenceMap = if (targetsAlreadyVerified) {
            emptyMap()
        } else {
            copiedEntries.migrationReferenceMap()
        }
        val verifiedIndices = mutableSetOf<Int>()
        for (index in copiedEntries.indices) {
            val migrationEntry = copiedEntries[index]
            val verified = targetsAlreadyVerified || isMigrationTargetVerified(
                context = context,
                migrationEntry = migrationEntry,
                referenceMap = referenceMap
            )
            if (!verified) {
                NPLogger.w(
                    tag,
                    "迁移后目标校验失败，跳过删除源文件: ${migrationEntry.original.entry.name}"
                )
            }
            if (verified) {
                verifiedIndices += index
            }
        }
        val deletionReferencesByIndex = verifiedIndices.mapNotNull { index ->
            copiedEntries[index].original.entry.toTrustedManagedRef()?.let { reference ->
                index to reference
            }
        }
        val indicesByReference = deletionReferencesByIndex
            .groupBy(
                keySelector = { (_, reference) -> reference.externalReference },
                valueTransform = { (index, _) -> index }
            )
        val progressLock = Any()
        val startedIndices = mutableSetOf<Int>()
        val finishedIndices = mutableSetOf<Int>()
        fun startCleanup(index: Int) {
            synchronized(progressLock) {
                if (startedIndices.add(index)) {
                    progressTracker?.startCleanup(
                        copiedEntries.size,
                        copiedEntries[index].original.entry.name
                    )
                }
            }
        }
        fun finishCleanup(index: Int) {
            synchronized(progressLock) {
                if (startedIndices.add(index)) {
                    progressTracker?.startCleanup(
                        copiedEntries.size,
                        copiedEntries[index].original.entry.name
                    )
                }
                if (finishedIndices.add(index)) {
                    progressTracker?.finishCleanup(
                        copiedEntries[index].original.entry.name
                    )
                }
            }
        }
        fun startReference(reference: TrustedManagedRef) {
            indicesByReference[reference.externalReference].orEmpty().forEach(::startCleanup)
        }
        fun finishReference(reference: TrustedManagedRef) {
            indicesByReference[reference.externalReference].orEmpty().forEach(::finishCleanup)
        }
        val deletionReferences = deletionReferencesByIndex.map { (_, reference) -> reference }
        val deletionResults = if (deletionReferences.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                deleteReferences(
                    context,
                    deletionReferences,
                    sourceRoot,
                    ::startReference,
                    ::finishReference
                )
            }.getOrElse { error ->
                val result = error.toStorageMutationResult()
                deletionReferences.associateWith { result }
            }
        }
        copiedEntries.indices.forEach(::finishCleanup)
        val cleanupOutcomes = copiedEntries.mapIndexed { index, migrationEntry ->
            val verified = index in verifiedIndices
            if (!verified) {
                CleanupOutcome(failed = true, retryable = false)
            } else {
                val reference = migrationEntry.original.entry.toTrustedManagedRef()
                val result = reference?.let(deletionResults::get)
                    ?: StorageMutationResult.OutOfScope
                if (!result.isCleanupConfirmed()) {
                    NPLogger.w(
                        tag,
                        "迁移后删除旧下载文件未确认: " +
                            "name=${migrationEntry.original.entry.name}, " +
                            "reference=${migrationEntry.original.entry.reference}, " +
                            "result=$result"
                    )
                }
                CleanupOutcome(
                    failed = !result.isCleanupConfirmed(),
                    retryable = result.isRetryableCleanupFailure()
                )
            }
        }
        ManagedMigrationCleanupResult(
            failedFiles = cleanupOutcomes.count(CleanupOutcome::failed),
            retryableFailedFiles = cleanupOutcomes.count { outcome ->
                outcome.failed && outcome.retryable
            }
        )
    }

    suspend fun rollbackMigratedEntries(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        targetRoot: ManagedDownloadRootHandle
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val cleanupLimiter = Semaphore(deleteParallelism(targetRoot))
        copiedEntries.map { migrationEntry ->
            async(Dispatchers.IO) {
                cleanupLimiter.withPermit {
                    rollbackMigratedEntry(context, migrationEntry, targetRoot)
                }
            }
        }.awaitAll().sum()
    }

    private fun rewriteMetadataEntry(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        copied: CopiedMigrationEntry,
        referenceMap: Map<String, String>,
        progressTracker: ManagedMigrationProgressReporter?
    ): MetadataRewriteOutcome {
        progressTracker?.startRewrite(copied.copiedEntry.name)
        try {
            val rewriteInput = try {
                val sourceMetadata = readText(context, copied.original.entry.reference)
                    ?: throw IllegalStateException("无法读取源 metadata: ${copied.original.entry.name}")
                val targetMetadata = readText(context, copied.copiedEntry.reference)
                    ?: throw IllegalStateException("无法读取已迁移 metadata: ${copied.copiedEntry.name}")
                MetadataRewriteInput(
                    sourceMetadata = sourceMetadata,
                    targetMetadata = targetMetadata,
                    rewrittenMetadata = rewriteMetadataReferences(sourceMetadata, referenceMap)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NPLogger.w(
                    tag,
                    "迁移后重写 metadata 引用失败: " +
                        "${copied.copiedEntry.reference}, ${error.message}"
                )
                return MetadataRewriteOutcome(
                    copied = copied,
                    failed = true,
                    error = retryableMigrationIoFailure(
                        message = "迁移后 metadata 读取暂时失败: ${copied.copiedEntry.name}",
                        error = error
                    )
                )
            }
            if (rewriteInput.rewrittenMetadata == rewriteInput.targetMetadata) {
                return MetadataRewriteOutcome(copied = copied, failed = false)
            }
            if (
                !copied.createdNew &&
                !copied.sourceAuthoritative &&
                rewriteInput.targetMetadata != rewriteInput.sourceMetadata
            ) {
                NPLogger.w(
                    tag,
                    "拒绝覆盖无法确认来源的迁移 metadata: ${copied.copiedEntry.reference}"
                )
                return MetadataRewriteOutcome(copied = copied, failed = true)
            }
            val rewrittenEntry = try {
                writeRootText(
                    context,
                    targetRoot,
                    copied.copiedEntry.name,
                    rewriteInput.rewrittenMetadata
                ) ?: throw IllegalStateException(
                    "无法读取回写后的 metadata: ${copied.copiedEntry.name}"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NPLogger.w(
                    tag,
                    "回写迁移后的 metadata 失败: " +
                        "${copied.copiedEntry.reference}, ${error.message}"
                )
                return MetadataRewriteOutcome(
                    copied = copied,
                    failed = true,
                    error = retryableMigrationIoFailure(
                        message = "迁移后 metadata 回写暂时失败: ${copied.copiedEntry.name}",
                        error = error
                    )
                )
            }
            val rewrittenCopied = copied.copy(copiedEntry = rewrittenEntry)
            val restoreError = runCatching {
                restoreLastModified(
                    context,
                    rewrittenEntry,
                    copied.original.entry.lastModifiedMs
                )
            }.onFailure {
                NPLogger.w(
                    tag,
                    "恢复迁移 metadata 时间失败: ${rewrittenEntry.reference}, ${it.message}"
                )
            }.exceptionOrNull()
            return MetadataRewriteOutcome(
                copied = rewrittenCopied,
                failed = restoreError != null,
                error = restoreError?.let { error ->
                    retryableMigrationIoFailure(
                        message = "恢复迁移 metadata 时间暂时失败: ${rewrittenEntry.name}",
                        error = error
                    )
                }
            )
        } finally {
            progressTracker?.finishRewrite(copied.copiedEntry.name)
        }
    }

    private data class MetadataRewriteInput(
        val sourceMetadata: String,
        val targetMetadata: String,
        val rewrittenMetadata: String
    )

    private data class MetadataRewriteOutcome(
        val copied: CopiedMigrationEntry,
        val failed: Boolean,
        val error: ManagedDownloadMigrationException? = null
    )

    private data class VerificationOutcome(
        val failed: Boolean,
        val error: ManagedDownloadMigrationException? = null
    )

    private suspend fun isMigrationTargetVerified(
        context: Context,
        migrationEntry: CopiedMigrationEntry,
        referenceMap: Map<String, String>,
        onProgress: (Long) -> Unit = {}
    ): Boolean {
        val sourceEntry = migrationEntry.original.entry
        if (ManagedDownloadTreeNaming.isMetadataName(sourceEntry.name)) {
            return hasEquivalentMigratedMetadata(
                context = context,
                migrationEntry = migrationEntry,
                referenceMap = referenceMap
            )
        }
        val persistedSourceDigest = migrationEntry.sourceDigest?.takeIf(String::isNotBlank)
        var sourceVerifiedBytes = 0L
        val sourceDigest = persistedSourceDigest
            ?: sha256ForEntry(context, sourceEntry) { verifiedBytes ->
                sourceVerifiedBytes = verifiedBytes
                onProgress(verifiedBytes)
            }
            ?: return false
        val targetOffset = if (persistedSourceDigest != null) {
            0L
        } else {
            maxOf(sourceVerifiedBytes, sourceEntry.sizeBytes.coerceAtLeast(0L))
        }
        val targetDigest = sha256ForEntry(context, migrationEntry.copiedEntry) { verifiedBytes ->
            onProgress(saturatedMigrationByteSum(targetOffset, verifiedBytes))
        } ?: return false
        return sourceDigest == targetDigest
    }

    private fun hasEquivalentMigratedMetadata(
        context: Context,
        migrationEntry: CopiedMigrationEntry,
        referenceMap: Map<String, String>
    ): Boolean {
        return try {
            val sourceMetadata = readText(context, migrationEntry.original.entry.reference)
                ?: return false
            val targetMetadata = readText(context, migrationEntry.copiedEntry.reference)
                ?: return false
            val expectedTargetMetadata = rewriteMetadataReferences(sourceMetadata, referenceMap)
            areEquivalentJsonValues(
                JSONObject(expectedTargetMetadata),
                JSONObject(targetMetadata)
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                tag,
                "迁移 metadata 校验失败: ${migrationEntry.original.entry.reference}, ${error.message}"
            )
            retryableMigrationIoFailure(
                message = "迁移 metadata 校验暂时失败: ${migrationEntry.original.entry.name}",
                error = error
            )?.let { migrationError -> throw migrationError }
            false
        }
    }

    private fun areEquivalentJsonValues(expected: Any?, actual: Any?): Boolean {
        if (isJsonNull(expected) || isJsonNull(actual)) {
            return isJsonNull(expected) && isJsonNull(actual)
        }
        return when {
            expected is JSONObject && actual is JSONObject -> {
                expected.length() == actual.length() && expected.keys().asSequence().all { key ->
                    actual.has(key) && areEquivalentJsonValues(expected.opt(key), actual.opt(key))
                }
            }

            expected is JSONArray && actual is JSONArray -> {
                expected.length() == actual.length() && (0 until expected.length()).all { index ->
                    areEquivalentJsonValues(expected.opt(index), actual.opt(index))
                }
            }

            expected is Number && actual is Number -> {
                BigDecimal(expected.toString()).compareTo(BigDecimal(actual.toString())) == 0
            }

            else -> expected == actual
        }
    }

    private fun isJsonNull(value: Any?): Boolean {
        return value == null || value === JSONObject.NULL
    }

    private suspend fun sha256ForEntry(
        context: Context,
        entry: ManagedDownloadStorage.StoredEntry,
        onProgress: (Long) -> Unit = {}
    ): String? {
        return try {
            when (val result = entryReader.read(context, entry) { input ->
                sha256Hex(input, onProgress)
            }) {
                is StorageLookupResult.Found -> result.value.getOrThrow()
                is StorageLookupResult.ProviderFailure -> throw ManagedDownloadRootProviderException(
                    entry.reference,
                    result.error
                )
                StorageLookupResult.Missing,
                StorageLookupResult.PermissionLost,
                StorageLookupResult.OutOfScope,
                is StorageLookupResult.Unsupported -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(tag, "迁移文件 hash 校验失败: ${entry.reference}, ${error.message}")
            retryableMigrationIoFailure(
                message = "迁移文件 hash 校验暂时失败: ${entry.name}",
                error = error
            )?.let { migrationError -> throw migrationError }
            null
        }
    }

    private fun retryableMigrationIoFailure(
        message: String,
        error: Throwable
    ): ManagedDownloadMigrationException? {
        if (error is CancellationException) {
            throw error
        }
        return when (error) {
            is ManagedDownloadMigrationException -> error
            is ManagedDownloadRootProviderException,
            is IOException -> ManagedDownloadMigrationException.transient(message, error)
            else -> null
        }
    }

    private fun saturatedMigrationByteSum(left: Long, right: Long): Long {
        val safeLeft = left.coerceAtLeast(0L)
        val safeRight = right.coerceAtLeast(0L)
        return if (safeLeft > Long.MAX_VALUE - safeRight) {
            Long.MAX_VALUE
        } else {
            safeLeft + safeRight
        }
    }

    private fun List<CopiedMigrationEntry>.migrationReferenceMap(): Map<String, String> {
        return buildMap {
            this@migrationReferenceMap.forEach { copied ->
                val source = copied.original.entry
                val target = copied.copiedEntry
                source.reference.takeIf(String::isNotBlank)?.let { reference ->
                    contentReferenceAliases(reference).forEach { alias ->
                        put(alias, target.reference)
                    }
                }
                source.localFilePath?.takeIf(String::isNotBlank)?.let { localPath ->
                    put(localPath, target.localFilePath ?: target.reference)
                }
                source.mediaUri.takeIf(String::isNotBlank)?.let { mediaUri ->
                    contentReferenceAliases(mediaUri).forEach { alias ->
                        put(alias, target.metadataReference())
                    }
                }
                source.localFileUriAliases(target).forEach { (sourceUri, targetUri) ->
                    put(sourceUri, targetUri)
                }
            }
        }
    }

    private fun contentReferenceAliases(reference: String): Set<String> {
        val normalized = reference.trim().takeIf(String::isNotBlank) ?: return emptySet()
        val uri = runCatching { normalized.toUri() }.getOrNull()
            ?: return setOf(normalized)
        if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank()) {
            return setOf(normalized)
        }
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return setOf(normalized)
        val aliases = linkedSetOf(normalized)
        runCatching {
            DocumentsContract.buildDocumentUri(uri.authority, documentId).toString()
        }.getOrNull()?.let(aliases::add)
        if (uri.pathSegments.any { it == "tree" }) {
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(uri, documentId).toString()
            }.getOrNull()?.let(aliases::add)
        }
        return aliases
    }

    private fun ManagedDownloadStorage.StoredEntry.localFileUriAliases(
        target: ManagedDownloadStorage.StoredEntry
    ): List<Pair<String, String>> {
        val sourcePath = localFilePath
            ?.takeIf(String::isNotBlank)
            ?: reference.takeIf { it.startsWith('/') }
            ?: mediaUri
                .takeIf { it.startsWith("file:", ignoreCase = true) }
                ?.let { value -> runCatching { URI(value).path }.getOrNull() }
                ?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val sourceFile = File(sourcePath)
        val targetReference = target.metadataReference()
        return listOf(
            sourceFile.toURI().toString() to targetReference,
            sourceFile.toFileUriWithAuthority() to targetReference
        )
    }

    private fun File.toFileUriWithAuthority(): String {
        return toURI().toString().replaceFirst("file:", "file://")
    }

    private fun ManagedDownloadStorage.StoredEntry.metadataReference(): String {
        return localFilePathForMigrationTarget()
            ?.let(::File)
            ?.toURI()
            ?.toString()
            ?: mediaUri.takeIf(String::isNotBlank)
            ?: reference
    }

    private fun ManagedDownloadStorage.StoredEntry.localFilePathForMigrationTarget(): String? {
        return localFilePath
            ?.takeIf(String::isNotBlank)
            ?: reference.takeIf { it.startsWith('/') }
    }

    private fun rollbackMigratedEntry(
        context: Context,
        migrationEntry: CopiedMigrationEntry,
        targetRoot: ManagedDownloadRootHandle
    ): Int {
        if (!migrationEntry.createdNew && migrationEntry.replacementBackup == null) {
            return 0
        }
        if (migrationEntry.replacementBackup != null) {
            return if (restoreReplacement(context, targetRoot, migrationEntry)) 0 else 1
        }
        val targetReference = migrationEntry.copiedEntry.toTrustedManagedRef()
        val result = targetReference?.let { reference ->
            runCatching {
                deleteReference(context, reference, targetRoot)
            }.onFailure {
                NPLogger.w(
                    tag,
                    "回滚迁移目标文件失败: " +
                        "${migrationEntry.copiedEntry.reference}, ${it.message}"
                )
            }.getOrElse { error -> error.toStorageMutationResult() }
        } ?: StorageMutationResult.OutOfScope
        return if (result.isCleanupConfirmed()) 0 else 1
    }

    private fun ManagedDownloadStorage.StoredEntry.toTrustedManagedRef(): TrustedManagedRef? {
        val normalizedReference = reference.trim().takeIf(String::isNotBlank) ?: return null
        val uri = runCatching { normalizedReference.toUri() }.getOrNull()
        return when {
            normalizedReference.startsWith("/") -> {
                TrustedManagedRef(
                    reference = StorageReference.FileRef(normalizedReference),
                    externalReference = normalizedReference
                )
            }

            uri != null &&
                uri.scheme.equals("content", ignoreCase = true) &&
                !uri.authority.isNullOrBlank() -> {
                TrustedManagedRef(
                    reference = StorageReference.SafRef(uri),
                    externalReference = normalizedReference
                )
            }

            uri != null && uri.scheme.equals("file", ignoreCase = true) -> {
                val path = uri.path?.takeIf(String::isNotBlank) ?: return null
                TrustedManagedRef(
                    reference = StorageReference.FileRef(path),
                    externalReference = normalizedReference
                )
            }

            else -> null
        }
    }

    private fun Throwable.toStorageMutationResult(): StorageMutationResult {
        return if (this is SecurityException) {
            StorageMutationResult.PermissionLost
        } else {
            StorageMutationResult.ProviderFailure(this)
        }
    }

    private fun StorageMutationResult.isCleanupConfirmed(): Boolean {
        return this is StorageMutationResult.Deleted || this is StorageMutationResult.Missing
    }

    private fun StorageMutationResult.isRetryableCleanupFailure(): Boolean {
        return this is StorageMutationResult.ProviderFailure && error !is SecurityException
    }

    private data class CleanupOutcome(
        val failed: Boolean,
        val retryable: Boolean
    )

    companion object {
        // 返回 true = 保留源文件 (跳过删除) ; false = 确认拷贝可信, 允许删源
        internal fun shouldKeepSourceForSizeMismatch(sourceSize: Long, copiedSize: Long): Boolean {
            // 目标尺寸不可知或为空(<=0)时无法确认拷贝完整, 保守保留源文件, 避免误删导致数据丢失
            // 原实现在 copiedSize<=0 时返回 false (继续删源) , 方向恰好相反, 是 #D3 数据丢失根因
            if (copiedSize <= 0L) {
                return true
            }
            // 目标已确认非空后, 再按容差比对源/目标尺寸: 不一致才保留源
            // sourceSize<=0 (源本就为空/未知) 时, 仅当目标同样落在容差内才会判为一致而删源
            // 与"源本就为空才可删"的语义一致
            return !ManagedDownloadCommitVerifier.isSizeWithinTolerance(
                actualSizeBytes = copiedSize,
                expectedSizeBytes = sourceSize,
                toleranceBytes = SAF_COMMITTED_SIZE_TOLERANCE_BYTES
            )
        }

        internal fun shouldKeepSourceForMigrationSize(sourceSize: Long, copiedSize: Long): Boolean {
            if (copiedSize <= 0L) {
                return true
            }
            return sourceSize > 0L && shouldKeepSourceForSizeMismatch(sourceSize, copiedSize)
        }

        internal fun sha256Hex(input: InputStream): String {
            return sha256Hex(input, onProgress = {})
        }

        internal fun sha256Hex(
            input: InputStream,
            onProgress: (Long) -> Unit
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_SIZE_BYTES)
            var processedBytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    return digest.digest().joinToString(separator = "") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                }
                if (count > 0) {
                    digest.update(buffer, 0, count)
                    processedBytes += count
                    onProgress(processedBytes)
                }
            }
        }

        internal fun hasCompatibleStableKeys(
            sourceStableKey: String?,
            targetStableKey: String?,
            sourceReferences: Set<String>,
            targetReferences: Set<String>
        ): Boolean {
            val sourceKey = sourceStableKey?.trim()?.takeIf(String::isNotBlank)
            val targetKey = targetStableKey?.trim()?.takeIf(String::isNotBlank)
            if (sourceKey == targetKey) {
                return true
            }
            if (sourceKey == null || targetKey == null) {
                return false
            }
            val sourceContainsLocalReference = sourceReferences.any(sourceKey::contains)
            val targetContainsLocalReference = targetReferences.any(targetKey::contains)
            return sourceContainsLocalReference && targetContainsLocalReference
        }

        private const val HASH_BUFFER_SIZE_BYTES = 64 * 1024
    }
}
