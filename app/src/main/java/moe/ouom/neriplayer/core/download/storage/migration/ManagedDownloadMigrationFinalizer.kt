package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.SAF_COMMITTED_SIZE_TOLERANCE_BYTES
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitVerifier
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadMigrationFinalizer(
    private val tag: String,
    private val rewriteParallelism: (ManagedDownloadRootHandle) -> Int,
    private val deleteParallelism: (ManagedDownloadRootHandle) -> Int,
    private val readText: (Context, String) -> String?,
    private val writeRootText: (Context, ManagedDownloadRootHandle, String, String) -> ManagedDownloadStorage.StoredEntry?,
    private val deleteReference: (Context, String, ManagedDownloadRootHandle) -> Boolean,
    private val rewriteMetadataReferences: (String, Map<String, String>) -> String
) {
    suspend fun rewriteMigratedMetadataReferences(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        copiedEntries: List<CopiedMigrationEntry>,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val referenceMap = copiedEntries.associate { copied ->
            copied.original.entry.reference to copied.copiedEntry.reference
        }
        val rewriteLimiter = Semaphore(rewriteParallelism(targetRoot))
        copiedEntries
            .filter { it.original.entry.name.endsWith(METADATA_SUFFIX) }
            .map { copied ->
                async(Dispatchers.IO) {
                    rewriteLimiter.withPermit {
                        rewriteMetadataEntry(context, targetRoot, copied, referenceMap, progressTracker)
                    }
                }
            }
            .awaitAll()
            .sum()
    }

    suspend fun cleanupMigratedEntries(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        sourceRoot: ManagedDownloadRootHandle,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val cleanupLimiter = Semaphore(deleteParallelism(sourceRoot))
        copiedEntries.map { migrationEntry ->
            async(Dispatchers.IO) {
                cleanupLimiter.withPermit {
                    cleanupMigratedEntry(context, copiedEntries.size, migrationEntry, sourceRoot, progressTracker)
                }
            }
        }.awaitAll().sum()
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
    ): Int {
        progressTracker?.startRewrite(copied.copiedEntry.name)
        val raw = readText(context, copied.copiedEntry.reference)
        val rewritten = runCatching {
            val metadataText = raw
                ?: throw IOException("无法读取已迁移 metadata: ${copied.copiedEntry.name}")
            rewriteMetadataReferences(metadataText, referenceMap)
        }.onFailure {
            NPLogger.w(tag, "迁移后重写 metadata 引用失败: ${copied.copiedEntry.reference}, ${it.message}")
        }.getOrNull()
        if (rewritten == null) {
            progressTracker?.finishRewrite(copied.copiedEntry.name)
            return 1
        }
        if (rewritten == raw) {
            progressTracker?.finishRewrite(copied.copiedEntry.name)
            return 0
        }
        runCatching {
            writeRootText(context, targetRoot, copied.copiedEntry.name, rewritten)
        }.onFailure {
            NPLogger.w(tag, "回写迁移后的 metadata 失败: ${copied.copiedEntry.reference}, ${it.message}")
        }.getOrElse {
            progressTracker?.finishRewrite(copied.copiedEntry.name)
            return 1
        }
        progressTracker?.finishRewrite(copied.copiedEntry.name)
        return 0
    }

    private fun cleanupMigratedEntry(
        context: Context,
        totalEntries: Int,
        migrationEntry: CopiedMigrationEntry,
        sourceRoot: ManagedDownloadRootHandle,
        progressTracker: ManagedMigrationProgressReporter?
    ): Int {
        progressTracker?.startCleanup(totalEntries, migrationEntry.original.entry.name)
        val sourceSize = migrationEntry.original.entry.sizeBytes
        val copiedSize = migrationEntry.copiedEntry.sizeBytes
        if (shouldKeepSourceForSizeMismatch(sourceSize, copiedSize)) {
            NPLogger.w(
                tag,
                "迁移后目标大小不匹配，跳过删除源文件: ${migrationEntry.original.entry.name}, source=$sourceSize, copied=$copiedSize"
            )
            progressTracker?.finishCleanup(migrationEntry.original.entry.name)
            return 1
        }
        val deleted = runCatching {
            deleteReference(context, migrationEntry.original.entry.reference, sourceRoot)
        }.onFailure {
            NPLogger.w(tag, "迁移后删除旧下载文件失败: ${migrationEntry.original.entry.reference}, ${it.message}")
        }.getOrDefault(false)
        progressTracker?.finishCleanup(migrationEntry.original.entry.name)
        return if (deleted) 0 else 1
    }

    private fun rollbackMigratedEntry(
        context: Context,
        migrationEntry: CopiedMigrationEntry,
        targetRoot: ManagedDownloadRootHandle
    ): Int {
        if (!migrationEntry.createdNew) {
            return 0
        }
        val deleted = runCatching {
            deleteReference(context, migrationEntry.copiedEntry.reference, targetRoot)
        }.onFailure {
            NPLogger.w(tag, "回滚迁移目标文件失败: ${migrationEntry.copiedEntry.reference}, ${it.message}")
        }.getOrDefault(false)
        return if (deleted) 0 else 1
    }

    companion object {
        // 返回 true = 保留源文件（跳过删除）；false = 确认拷贝可信、允许删源
        internal fun shouldKeepSourceForSizeMismatch(sourceSize: Long, copiedSize: Long): Boolean {
            // 目标尺寸不可知或为空(<=0)时无法确认拷贝完整，保守保留源文件，避免误删导致数据丢失
            // 原实现在 copiedSize<=0 时返回 false（继续删源），方向恰好相反，是 #D3 数据丢失根因
            if (copiedSize <= 0L) {
                return true
            }
            // 目标已确认非空后，再按容差比对源/目标尺寸：不一致才保留源
            // sourceSize<=0（源本就为空/未知）时，仅当目标同样落在容差内才会判为一致而删源，
            // 与"源本就为空才可删"的语义一致
            return !ManagedDownloadCommitVerifier.isSizeWithinTolerance(
                actualSizeBytes = copiedSize,
                expectedSizeBytes = sourceSize,
                toleranceBytes = SAF_COMMITTED_SIZE_TOLERANCE_BYTES
            )
        }
    }
}
