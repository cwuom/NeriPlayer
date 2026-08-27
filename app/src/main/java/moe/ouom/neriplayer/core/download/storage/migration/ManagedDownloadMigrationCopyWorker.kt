package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import java.io.IOException
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
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.logging.NPLogger

internal data class ManagedMigrationCopyResult(
    val copiedEntry: CopiedMigrationEntry? = null,
    val error: ManagedDownloadMigrationException? = null
) {
    init {
        require((copiedEntry == null) != (error == null)) {
            "迁移复制结果必须且只能包含 copiedEntry 或 error"
        }
    }
}

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
    val sourceDigest: String?
)

internal class ManagedDownloadMigrationCopyWorker(
    private val tag: String,
    private val openInputStream: (Context, ManagedDownloadStorage.StoredEntry) -> InputStream?,
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
    ) -> StoredWriteResult
) {
    suspend fun copyEntry(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: ManagedMigrationTargetIndex,
        namePlan: ManagedMigrationNamePlan,
        progressTracker: ManagedMigrationProgressReporter? = null
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
                    coroutineContext = coroutineContext
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ManagedDownloadMigrationException) {
            return ManagedMigrationCopyResult(error = error)
        } catch (error: Throwable) {
            return ManagedMigrationCopyResult(
                error = ManagedDownloadMigrationException.permanent(
                    "迁移下载文件发生不可恢复错误: ${migrationEntry.entry.reference}",
                    error
                )
            )
        }

        return ManagedMigrationCopyResult(
            copiedEntry = CopiedMigrationEntry(
                original = migrationEntry,
                copiedEntry = copiedEntry.result.entry,
                createdNew = copiedEntry.result.createdNew,
                sourceDigest = copiedEntry.sourceDigest
            )
        )
    }

    private fun copyEntryOnce(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: ManagedMigrationTargetIndex,
        namePlan: ManagedMigrationNamePlan,
        progressTracker: ManagedMigrationProgressReporter?,
        coroutineContext: CoroutineContext
    ): WrittenMigrationEntry {
        coroutineContext.ensureActive()
        progressTracker?.startCopy(migrationEntry)
        return try {
            namePlan.conflictFor(migrationEntry.toRef())?.let { detail ->
                throw ManagedDownloadMigrationException.permanent(
                    "迁移目标冲突: ${migrationEntry.entry.name}, $detail"
                )
            }
            val reusedEntry = namePlan.reusedTargetFor(migrationEntry.toRef())?.let { existingEntry ->
                if (!ManagedDownloadTreeNaming.isMetadataName(migrationEntry.entry.name)) {
                    val logicalBytes = migrationEntry.entry.sizeBytes.coerceAtLeast(0L)
                    val sourceLogicalBytes = logicalBytes / 2L + logicalBytes % 2L
                    val targetLogicalBytes = logicalBytes - sourceLogicalBytes
                    val sourceDigest = openInputStream(context, migrationEntry.entry)
                        ?.use { input ->
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
                        ?: throw ManagedDownloadMigrationException.transient(
                            "无法读取源文件以验证迁移复用: ${migrationEntry.entry.name}"
                        )
                    progressTracker?.onCopyProgress(migrationEntry, sourceLogicalBytes)
                    val targetExpectedBytes = existingEntry.sizeBytes
                        .takeIf { sizeBytes -> sizeBytes > 0L }
                        ?: migrationEntry.entry.sizeBytes
                    val targetDigest = openInputStream(context, existingEntry)
                        ?.use { input ->
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
                        ?: throw ManagedDownloadMigrationException.transient(
                            "无法读取目标文件以验证迁移复用: ${existingEntry.name}"
                        )
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
            reusedEntry ?: openInputStream(context, migrationEntry.entry)?.use { input ->
                val sourceDigest = MessageDigest.getInstance("SHA-256")
                val digestingInput = DigestInputStream(input, sourceDigest)
                val writeResult = if (migrationEntry.subdirectory == null) {
                    writeRoot(
                        context = context,
                        targetRoot = targetRoot,
                        migrationEntry = migrationEntry,
                        targetIndex = targetIndex,
                        namePlan = namePlan,
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
            } ?: throw IOException("无法读取源下载文件: ${migrationEntry.entry.name}")
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
        input: InputStream,
        onProgress: (Long) -> Unit
    ): StoredWriteResult {
        val targetName = namePlan.targetNameFor(migrationEntry.toRef())
        return writeRootStream(
            context,
            targetRoot,
            targetName,
            mimeTypeFor(migrationEntry),
            input,
            migrationEntry.entry,
            targetIndex.namesFor(migrationEntry.subdirectory),
            targetIndex.entryFor(migrationEntry.subdirectory, targetName)
        ) { copiedBytes -> onProgress(copiedBytes) }
    }

    private fun writeSubdirectory(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: ManagedMigrationTargetIndex,
        namePlan: ManagedMigrationNamePlan,
        input: InputStream,
        onProgress: (Long) -> Unit
    ): StoredWriteResult {
        val subdirectory = migrationEntry.subdirectory ?: error("缺少迁移子目录")
        val targetName = namePlan.targetNameFor(migrationEntry.toRef())
        return writeSubdirectoryStream(
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
    }

    private suspend fun <T> retryWrite(reference: String, block: () -> T): T {
        var lastError: Throwable? = null
        repeat(MIGRATION_IO_MAX_ATTEMPTS) { attempt ->
            val result = runCatching(block).onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                if (error is ManagedDownloadMigrationException && !error.retryable) {
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
            }.getOrNull()
            if (result != null) {
                return result
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
