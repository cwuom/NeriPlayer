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
import moe.ouom.neriplayer.core.logging.NPLogger

internal data class ManagedMigrationCopyResult(
    val copiedEntry: CopiedMigrationEntry?
)

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
        val copiedEntry = retryWrite(migrationEntry.entry.reference) {
            copyEntryOnce(
                context = context,
                targetRoot = targetRoot,
                migrationEntry = migrationEntry,
                targetIndex = targetIndex,
                namePlan = namePlan,
                progressTracker = progressTracker,
                coroutineContext = coroutineContext
            )
        } ?: return ManagedMigrationCopyResult(copiedEntry = null)

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
            namePlan.reusedTargetFor(migrationEntry.toRef())?.let { existingEntry ->
                return WrittenMigrationEntry(
                    result = StoredWriteResult(
                        entry = existingEntry,
                        createdNew = false
                    ),
                    sourceDigest = null
                )
            }
            openInputStream(context, migrationEntry.entry)?.use { input ->
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

    private suspend fun <T> retryWrite(reference: String, block: () -> T): T? {
        repeat(MIGRATION_IO_MAX_ATTEMPTS) { attempt ->
            val result = runCatching(block).onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                NPLogger.w(
                    tag,
                    "迁移下载文件失败: $reference, attempt=${attempt + 1}/$MIGRATION_IO_MAX_ATTEMPTS, ${error.message}"
                )
            }.getOrNull()
            if (result != null) {
                return result
            }
            if (attempt < MIGRATION_IO_MAX_ATTEMPTS - 1) {
                delay(MIGRATION_IO_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return null
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
