package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.MIGRATION_IO_MAX_ATTEMPTS
import moe.ouom.neriplayer.core.download.storage.MIGRATION_IO_RETRY_DELAY_MS
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.logging.NPLogger

internal data class ManagedMigrationCopyResult(
    val copiedEntry: CopiedMigrationEntry?
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
        val copiedEntry = retryWrite(migrationEntry.entry.reference) {
            copyEntryOnce(
                context = context,
                targetRoot = targetRoot,
                migrationEntry = migrationEntry,
                targetIndex = targetIndex,
                namePlan = namePlan,
                progressTracker = progressTracker
            )
        } ?: return ManagedMigrationCopyResult(copiedEntry = null)

        return ManagedMigrationCopyResult(
            copiedEntry = CopiedMigrationEntry(
                original = migrationEntry,
                copiedEntry = copiedEntry.entry,
                createdNew = copiedEntry.createdNew
            )
        )
    }

    private fun copyEntryOnce(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: ManagedMigrationTargetIndex,
        namePlan: ManagedMigrationNamePlan,
        progressTracker: ManagedMigrationProgressReporter?
    ): StoredWriteResult {
        progressTracker?.startCopy(migrationEntry)
        return try {
            openInputStream(context, migrationEntry.entry)?.use { input ->
                if (migrationEntry.subdirectory == null) {
                    writeRoot(
                        context = context,
                        targetRoot = targetRoot,
                        migrationEntry = migrationEntry,
                        targetIndex = targetIndex,
                        namePlan = namePlan,
                        input = input,
                        progressTracker = progressTracker
                    )
                } else {
                    writeSubdirectory(
                        context = context,
                        targetRoot = targetRoot,
                        migrationEntry = migrationEntry,
                        targetIndex = targetIndex,
                        namePlan = namePlan,
                        input = input,
                        progressTracker = progressTracker
                    )
                }
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
        progressTracker: ManagedMigrationProgressReporter?
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
        ) { copiedBytes ->
            progressTracker?.onCopyProgress(migrationEntry, copiedBytes)
        }
    }

    private fun writeSubdirectory(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        migrationEntry: ManagedMigrationEntry,
        targetIndex: ManagedMigrationTargetIndex,
        namePlan: ManagedMigrationNamePlan,
        input: InputStream,
        progressTracker: ManagedMigrationProgressReporter?
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
        ) { copiedBytes ->
            progressTracker?.onCopyProgress(migrationEntry, copiedBytes)
        }
    }

    private suspend fun <T> retryWrite(reference: String, block: () -> T): T? {
        repeat(MIGRATION_IO_MAX_ATTEMPTS) { attempt ->
            val result = runCatching(block).onFailure { error ->
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
}
