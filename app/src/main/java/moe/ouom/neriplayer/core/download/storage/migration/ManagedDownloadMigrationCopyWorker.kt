package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.MIGRATION_IO_MAX_ATTEMPTS
import moe.ouom.neriplayer.core.download.storage.MIGRATION_IO_RETRY_DELAY_MS
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.commit.sameMigrationReplacementBackupIdentity
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.logging.NPLogger

internal data class ManagedMigrationCopyResult(
    val copiedEntry: CopiedMigrationEntry? = null,
    val error: ManagedDownloadMigrationException? = null,
    /** source disappeared after the complete preflight scan */
    val sourceDeleted: Boolean = false,
    val sourceReference: String = ""
) {
    init {
        require(
            listOf(copiedEntry != null, error != null, sourceDeleted).count { it } == 1
        ) {
            "迁移复制结果必须且只能包含 copiedEntry、error 或 sourceDeleted"
        }
    }
}

private class ManagedMigrationSourceDeletedException : Exception()

internal suspend fun requireSuccessfulMigrationCopies(
    results: List<ManagedMigrationCopyResult>,
    rollback: suspend (List<CopiedMigrationEntry>) -> Unit
): List<CopiedMigrationEntry> {
    val copiedEntries = results.mapNotNull(ManagedMigrationCopyResult::copiedEntry)
    val errors = results.mapNotNull(ManagedMigrationCopyResult::error)
    if (errors.isNotEmpty()) {
        rollback(copiedEntries)
        throw errors.firstOrNull { error -> !error.retryable } ?: errors.first()
    }
    return copiedEntries
}

internal fun migrationContentMatches(
    source: InputStream,
    target: InputStream
): Boolean {
    return sha256MigrationContent(source) == sha256MigrationContent(target)
}

internal fun sha256MigrationContent(
    input: InputStream,
    onProgress: (Long) -> Unit = {}
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var processedBytes = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        digest.update(buffer, 0, count)
        processedBytes += count
        onProgress(processedBytes)
    }
    return digest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

internal fun scaledMigrationHashProgress(
    hashedBytes: Long,
    expectedBytes: Long,
    logicalOffset: Long,
    logicalSpan: Long
): Long {
    if (expectedBytes <= 0L || logicalSpan <= 0L) return logicalOffset
    val boundedBytes = hashedBytes.coerceIn(0L, expectedBytes)
    val scaledBytes = (
        boundedBytes.toDouble() / expectedBytes.toDouble() * logicalSpan.toDouble()
        ).toLong()
    return logicalOffset + scaledBytes.coerceIn(0L, logicalSpan)
}

private data class WrittenMigrationEntry(
    val result: StoredWriteResult,
    val sourceDigest: String?,
    val verifiedTargetDigest: String? = null,
    val reusedFromReceipt: Boolean = false
)

internal class ManagedDownloadMigrationCopyWorker(
    private val tag: String,
    private val entryReader: ManagedMigrationEntryReader,
    private val mimeTypeFor: (ManagedMigrationEntry) -> String,
    private val writeRootStream: (
        Context,
        ManagedDownloadRootHandle,
        String,
        String,
        InputStream,
        ManagedDownloadStorage.StoredEntry,
        Set<String>,
        ManagedDownloadStorage.StoredEntry?,
        ((Long) -> Unit)?
    ) -> StoredWriteResult,
    private val writeSubdirectoryStream: (
        Context,
        ManagedDownloadRootHandle,
        String,
        String,
        String,
        InputStream,
        ManagedDownloadStorage.StoredEntry,
        Set<String>,
        ManagedDownloadStorage.StoredEntry?,
        ((Long) -> Unit)?
    ) -> StoredWriteResult,
    private val writeReplacementRootStream: (
        Context,
        ManagedDownloadRootHandle,
        String,
        String,
        InputStream,
        ManagedDownloadStorage.StoredEntry,
        Set<String>,
        ManagedDownloadStorage.StoredEntry?,
        ManagedMigrationReplacementPlan,
        ((Long) -> Unit)?
    ) -> StoredWriteResult = { _, _, _, _, _, _, _, _, _, _ ->
        throw ManagedDownloadMigrationException.transient(
            "迁移替换写入器未配置"
        )
    },
    private val writeReplacementSubdirectoryStream: (
        Context,
        ManagedDownloadRootHandle,
        String,
        String,
        String,
        InputStream,
        ManagedDownloadStorage.StoredEntry,
        Set<String>,
        ManagedDownloadStorage.StoredEntry?,
        ManagedMigrationReplacementPlan,
        ((Long) -> Unit)?
    ) -> StoredWriteResult = { _, _, _, _, _, _, _, _, _, _, _ ->
        throw ManagedDownloadMigrationException.transient(
            "迁移替换写入器未配置"
        )
    }
) {
    suspend fun copyEntry(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: ManagedMigrationTargetIndex,
        namePlan: ManagedMigrationNamePlan,
        progressTracker: ManagedMigrationProgressReporter? = null,
        resumeReceipt: ManagedMigrationCopyReceipt? = null
    ): ManagedMigrationCopyResult {
        val coroutineContext = currentCoroutineContext()
        val copiedEntry = try {
            retryWrite(migrationEntry.entry.reference) {
                copyEntryOnce(
                    context = context,
                    targetRoot = targetRoot,
                    migrationEntry = migrationEntry,
                    targetIndex = targetIndex,
                    namePlan = namePlan,
                    progressTracker = progressTracker,
                    coroutineContext = coroutineContext,
                    resumeReceipt = resumeReceipt
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ManagedMigrationSourceDeletedException) {
            return ManagedMigrationCopyResult(
                sourceDeleted = true,
                sourceReference = migrationEntry.entry.reference
            )
        } catch (error: ManagedDownloadMigrationException) {
            return ManagedMigrationCopyResult(
                error = error,
                sourceReference = migrationEntry.entry.reference
            )
        } catch (error: Throwable) {
            return ManagedMigrationCopyResult(
                error = ManagedDownloadMigrationException.permanent(
                    "迁移下载文件发生不可恢复错误: ${migrationEntry.entry.reference}",
                    error
                ),
                sourceReference = migrationEntry.entry.reference
            )
        }

        return ManagedMigrationCopyResult(
            copiedEntry = CopiedMigrationEntry(
                original = migrationEntry,
                copiedEntry = copiedEntry.result.entry,
                createdNew = copiedEntry.result.createdNew,
                sourceDigest = copiedEntry.sourceDigest,
                verifiedTargetDigest = copiedEntry.verifiedTargetDigest
                    ?: copiedEntry.sourceDigest?.takeIf {
                        copiedEntry.result.targetContentMatchesSource &&
                            !ManagedDownloadTreeNaming.isMetadataName(migrationEntry.entry.name)
                    },
                replacementBackup = copiedEntry.result.replacementBackup,
                sourceAuthoritative = copiedEntry.result.sourceAuthoritative,
                targetContentMatchesSource = copiedEntry.result.targetContentMatchesSource,
                reusedFromReceipt = copiedEntry.reusedFromReceipt
            ),
            sourceReference = migrationEntry.entry.reference
        )
    }

    private suspend fun copyEntryOnce(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: ManagedMigrationTargetIndex,
        namePlan: ManagedMigrationNamePlan,
        progressTracker: ManagedMigrationProgressReporter?,
        coroutineContext: CoroutineContext,
        resumeReceipt: ManagedMigrationCopyReceipt?
    ): WrittenMigrationEntry {
        coroutineContext.ensureActive()
        progressTracker?.startCopy(migrationEntry)
        return try {
            namePlan.conflictFor(migrationEntry.toRef())?.let { detail ->
                throw ManagedDownloadMigrationException.permanent(
                    "迁移目标冲突: ${migrationEntry.entry.name}, $detail"
                )
            }
            val replacementPlan = namePlan.replacementFor(migrationEntry.toRef())
            val plannedTargetName = namePlan.targetNameFor(migrationEntry.toRef())
            val receiptTarget = targetIndex.entryFor(
                migrationEntry.subdirectory,
                plannedTargetName
            )
            val canReuseReceipt = resumeReceipt?.let { receipt ->
                canReuseMigrationCopyReceipt(
                    receipt = receipt,
                    sourceEntry = migrationEntry.entry,
                    sourceSubdirectory = migrationEntry.subdirectory,
                    targetName = plannedTargetName,
                    targetEntry = receiptTarget,
                    replacementPlan = replacementPlan
                )
            } == true
            if (resumeReceipt != null && !canReuseReceipt) {
                progressTracker?.unseedCopy(migrationEntry)
            }
            if (canReuseReceipt) {
                progressTracker?.onCopyProgress(
                    migrationEntry,
                    migrationEntry.entry.sizeBytes.coerceAtLeast(0L)
                )
                return WrittenMigrationEntry(
                    result = StoredWriteResult(
                        entry = checkNotNull(receiptTarget),
                        createdNew = resumeReceipt.createdNew,
                        replacementBackup = resumeReceipt.replacementBackup,
                        sourceAuthoritative = resumeReceipt.sourceAuthoritative
                    ),
                    sourceDigest = resumeReceipt.sourceDigest,
                    verifiedTargetDigest = resumeReceipt.verifiedTargetDigest,
                    reusedFromReceipt = true
                )
            }
            val interruptedReplacement = replacementPlan?.let { plan ->
                val backupEntry = targetIndex.entryFor(
                    migrationEntry.subdirectory,
                    plan.backupName
                )
                val currentTarget = receiptTarget
                if (backupEntry == null || currentTarget == null) {
                    null
                } else {
                    if (!sameMigrationReplacementBackupIdentity(
                            expectedTarget = plan.targetEntry,
                            actualBackup = backupEntry,
                            expectedBackupName = plan.backupName
                        )
                    ) {
                        throw ManagedDownloadMigrationException.targetChanged(
                            "迁移替换备份身份无法确认: ${plan.backupName}"
                        )
                    }
                    val sourceDigest = readSourceOrThrow(
                        context = context,
                        entry = migrationEntry.entry,
                        operation = "无法读取源文件以恢复未落盘的迁移替换"
                    ) { input ->
                        sha256MigrationContent(input)
                    }
                    val targetDigest = entryReader.readOrThrow(
                        context = context,
                        entry = currentTarget,
                        operation = "无法读取迁移替换目标以恢复未落盘状态"
                    ) { input ->
                        sha256MigrationContent(input)
                    }
                    if (sourceDigest != targetDigest) {
                        throw ManagedDownloadMigrationException.targetChanged(
                            "迁移替换目标内容无法确认，保留外部文件: " +
                                migrationEntry.entry.name
                        )
                    }
                    progressTracker?.onCopyProgress(
                        migrationEntry,
                        migrationEntry.entry.sizeBytes.coerceAtLeast(0L)
                    )
                    WrittenMigrationEntry(
                        result = StoredWriteResult(
                            entry = currentTarget,
                            createdNew = false,
                            replacementBackup = backupEntry,
                            sourceAuthoritative = true
                        ),
                        sourceDigest = sourceDigest,
                        verifiedTargetDigest = targetDigest,
                        reusedFromReceipt = true
                    )
                }
            }
            val reusedEntry = if (replacementPlan == null) {
                namePlan.reusedTargetFor(migrationEntry.toRef())?.let { existingEntry ->
                if (!ManagedDownloadTreeNaming.isMetadataName(migrationEntry.entry.name)) {
                    val logicalBytes = migrationEntry.entry.sizeBytes.coerceAtLeast(0L)
                    val sourceLogicalBytes = logicalBytes / 2L + logicalBytes % 2L
                    val targetLogicalBytes = logicalBytes - sourceLogicalBytes
                    val sourceDigest = readSourceOrThrow(
                        context = context,
                        entry = migrationEntry.entry,
                        operation = "无法读取源文件以验证迁移复用"
                    ) { input ->
                            sha256MigrationContent(input) { hashedBytes ->
                                coroutineContext.ensureActive()
                                progressTracker?.onCopyProgress(
                                    migrationEntry,
                                    scaledMigrationHashProgress(
                                        hashedBytes = hashedBytes,
                                        expectedBytes = migrationEntry.entry.sizeBytes,
                                        logicalOffset = 0L,
                                        logicalSpan = sourceLogicalBytes
                                    )
                                )
                            }
                        }
                    progressTracker?.onCopyProgress(migrationEntry, sourceLogicalBytes)
                    val targetExpectedBytes = existingEntry.sizeBytes
                        .takeIf { sizeBytes -> sizeBytes > 0L }
                        ?: migrationEntry.entry.sizeBytes
                    val targetDigest = entryReader.readOrThrow(
                        context = context,
                        entry = existingEntry,
                        operation = "无法读取目标文件以验证迁移复用"
                    ) { input ->
                            sha256MigrationContent(input) { hashedBytes ->
                                coroutineContext.ensureActive()
                                progressTracker?.onCopyProgress(
                                    migrationEntry,
                                    scaledMigrationHashProgress(
                                        hashedBytes = hashedBytes,
                                        expectedBytes = targetExpectedBytes,
                                        logicalOffset = sourceLogicalBytes,
                                        logicalSpan = targetLogicalBytes
                                    )
                                )
                            }
                        }
                    progressTracker?.onCopyProgress(migrationEntry, logicalBytes)
                    if (sourceDigest != targetDigest) {
                        throw ManagedDownloadMigrationException.permanent(
                            "same stableKey has different audio bytes: " +
                                "source=${migrationEntry.entry.name} target=${existingEntry.name}"
                        )
                    }
                    return@let WrittenMigrationEntry(
                        result = StoredWriteResult(
                            entry = existingEntry,
                            createdNew = false
                        ),
                        sourceDigest = sourceDigest
                    )
                }
                WrittenMigrationEntry(
                    result = StoredWriteResult(
                        entry = existingEntry,
                        createdNew = false
                    ),
                    sourceDigest = null
                )
                }
            } else {
                null
            }
            interruptedReplacement ?: reusedEntry ?: readSourceOrThrow(
                context = context,
                entry = migrationEntry.entry,
                operation = "无法读取源下载文件"
            ) { input ->
                val sourceDigest = MessageDigest.getInstance("SHA-256")
                val digestingInput = DigestInputStream(input, sourceDigest)
                val writeResult = if (migrationEntry.subdirectory == null) {
                    writeRoot(
                        context = context,
                        targetRoot = targetRoot,
                        migrationEntry = migrationEntry,
                        targetIndex = targetIndex,
                        namePlan = namePlan,
                        replacementPlan = replacementPlan,
                        input = digestingInput,
                        onProgress = { copiedBytes ->
                            coroutineContext.ensureActive()
                            progressTracker?.onCopyProgress(migrationEntry, copiedBytes)
                        }
                    )
                } else {
                    writeSubdirectory(
                        context = context,
                        targetRoot = targetRoot,
                        migrationEntry = migrationEntry,
                        targetIndex = targetIndex,
                        namePlan = namePlan,
                        replacementPlan = replacementPlan,
                        input = digestingInput,
                        onProgress = { copiedBytes ->
                            coroutineContext.ensureActive()
                            progressTracker?.onCopyProgress(migrationEntry, copiedBytes)
                        }
                    )
                }
                if (!writeResult.createdNew) {
                    drainInput(digestingInput, coroutineContext)
                }
                coroutineContext.ensureActive()
                WrittenMigrationEntry(
                    result = writeResult,
                    sourceDigest = digestHex(sourceDigest)
                )
            }
        } catch (error: ManagedMigrationSourceDeletedException) {
            // 用户在预扫描后删除源文件时，该条目已完成处理，不应让进度停住
            progressTracker?.completeCopy(migrationEntry)
            throw error
        } catch (error: Throwable) {
            progressTracker?.failCopy(migrationEntry)
            throw error
        }.also {
            progressTracker?.completeCopy(migrationEntry)
        }
    }

    private fun writeRoot(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: ManagedMigrationTargetIndex,
        namePlan: ManagedMigrationNamePlan,
        replacementPlan: ManagedMigrationReplacementPlan?,
        input: InputStream,
        onProgress: (Long) -> Unit
    ): StoredWriteResult {
        val targetName = namePlan.targetNameFor(migrationEntry.toRef())
        return if (replacementPlan == null) {
            writeRootStream(
                context,
                targetRoot,
                targetName,
                mimeTypeFor(migrationEntry),
                input,
                migrationEntry.entry,
                targetIndex.namesFor(migrationEntry.subdirectory),
                targetIndex.entryFor(migrationEntry.subdirectory, targetName)
            ) { copiedBytes -> onProgress(copiedBytes) }
        } else {
            writeReplacementRootStream(
                context,
                targetRoot,
                targetName,
                mimeTypeFor(migrationEntry),
                input,
                migrationEntry.entry,
                targetIndex.namesFor(migrationEntry.subdirectory),
                targetIndex.entryFor(migrationEntry.subdirectory, targetName),
                replacementPlan
            ) { copiedBytes -> onProgress(copiedBytes) }
        }
    }

    private fun writeSubdirectory(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: ManagedMigrationTargetIndex,
        namePlan: ManagedMigrationNamePlan,
        replacementPlan: ManagedMigrationReplacementPlan?,
        input: InputStream,
        onProgress: (Long) -> Unit
    ): StoredWriteResult {
        val subdirectory = migrationEntry.subdirectory ?: error("缺少迁移子目录")
        val targetName = namePlan.targetNameFor(migrationEntry.toRef())
        return if (replacementPlan == null) {
            writeSubdirectoryStream(
                context,
                targetRoot,
                subdirectory,
                targetName,
                mimeTypeFor(migrationEntry),
                input,
                migrationEntry.entry,
                targetIndex.namesFor(subdirectory),
                targetIndex.entryFor(subdirectory, targetName)
            ) { copiedBytes -> onProgress(copiedBytes) }
        } else {
            writeReplacementSubdirectoryStream(
                context,
                targetRoot,
                subdirectory,
                targetName,
                mimeTypeFor(migrationEntry),
                input,
                migrationEntry.entry,
                targetIndex.namesFor(subdirectory),
                targetIndex.entryFor(subdirectory, targetName),
                replacementPlan
            ) { copiedBytes -> onProgress(copiedBytes) }
        }
    }

    private suspend fun <T> retryWrite(reference: String, block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(MIGRATION_IO_MAX_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                if (error is ManagedMigrationSourceDeletedException) {
                    throw error
                }
                if (error is ManagedDownloadMigrationException && !error.retryWithinEntry) {
                    throw error
                }
                lastError = error
                NPLogger.w(
                    tag,
                    "迁移下载文件失败: $reference, " +
                        "attempt=${attempt + 1}/$MIGRATION_IO_MAX_ATTEMPTS, " +
                        "${error::class.java.name}: ${error.message}",
                    error
                )
            }
            if (attempt < MIGRATION_IO_MAX_ATTEMPTS - 1) {
                delay(MIGRATION_IO_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        val cause = checkNotNull(lastError) {
            "迁移复制重试结束时缺少失败原因"
        }
        throw when (cause) {
            is ManagedDownloadMigrationException -> cause
            else -> ManagedDownloadMigrationException.transient(
                "迁移下载文件多次失败: $reference",
                cause
            )
        }
    }

    private suspend fun <T> readSourceOrThrow(
        context: Context,
        entry: ManagedDownloadStorage.StoredEntry,
        operation: String,
        block: suspend (InputStream) -> T
    ): T {
        return when (val result = entryReader.read(context, entry, block)) {
            is StorageLookupResult.Found ->
                result.value.getOrThrow()
            StorageLookupResult.Missing ->
                throw ManagedMigrationSourceDeletedException()
            StorageLookupResult.PermissionLost ->
                throw ManagedDownloadMigrationException.transient(
                    "$operation: storage permission lost for ${entry.name}"
                )
            is StorageLookupResult.ProviderFailure ->
                throw ManagedDownloadMigrationException.transient(
                    "$operation: provider failure for ${entry.name}",
                    result.error
                )
            StorageLookupResult.OutOfScope ->
                throw ManagedDownloadMigrationException.permanent(
                    "$operation: out-of-scope reference for ${entry.name}"
                )
            is StorageLookupResult.Unsupported ->
                throw ManagedDownloadMigrationException.permanent(
                    "$operation: unsupported ${result.operation} for ${entry.name}"
                )
        }
    }

    private fun drainInput(input: InputStream, coroutineContext: CoroutineContext) {
        val buffer = ByteArray(64 * 1024)
        while (input.read(buffer) >= 0) {
            coroutineContext.ensureActive()
        }
    }

    private fun digestHex(digest: MessageDigest): String {
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
