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
import moe.ouom.neriplayer.core.download.storage.SAF_COMMITTED_SIZE_TOLERANCE_BYTES
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitVerifier
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

internal class ManagedDownloadMigrationFinalizer(
    private val tag: String,
    private val rewriteParallelism: (ManagedDownloadRootHandle) -> Int,
    private val deleteParallelism: (ManagedDownloadRootHandle) -> Int,
    private val readText: (Context, String) -> String?,
    private val openInputStream: (Context, ManagedDownloadStorage.StoredEntry) -> InputStream?,
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
    }
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
        ManagedMigrationVerificationResult(
            failedFiles = outcomes.count(VerificationOutcome::failed),
            error = outcomes.mapNotNull(VerificationOutcome::error)
                .firstOrNull { error -> !error.retryable }
                ?: outcomes.mapNotNull(VerificationOutcome::error).firstOrNull()
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
        val verifiedIndices = copiedEntries.indices.filterTo(mutableSetOf()) { index ->
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
            verified
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
            if (!copied.createdNew && rewriteInput.targetMetadata != rewriteInput.sourceMetadata) {
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

    private fun isMigrationTargetVerified(
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

    private fun sha256ForEntry(
        context: Context,
        entry: ManagedDownloadStorage.StoredEntry,
        onProgress: (Long) -> Unit = {}
    ): String? {
        return try {
            openInputStream(context, entry)?.use { input ->
                sha256Hex(input, onProgress)
            } ?: return null
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
        if (!migrationEntry.createdNew) {
            return 0
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
