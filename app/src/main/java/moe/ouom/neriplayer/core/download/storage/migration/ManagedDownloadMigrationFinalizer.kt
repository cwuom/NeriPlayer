package moe.ouom.neriplayer.core.download.storage.migration

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.SAF_COMMITTED_SIZE_TOLERANCE_BYTES
import moe.ouom.neriplayer.core.download.storage.commit.ManagedDownloadCommitVerifier
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONArray
import org.json.JSONObject

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
        val referenceMap = copiedEntries.migrationReferenceMap()
        val rewriteLimiter = Semaphore(rewriteParallelism(targetRoot))
        copiedEntries
            .filter {
                ManagedDownloadTreeNaming.isMetadataName(it.original.entry.name)
            }
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

    suspend fun verifyMigratedEntries(
        context: Context,
        targetRoot: ManagedDownloadRootHandle,
        copiedEntries: List<CopiedMigrationEntry>
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val referenceMap = copiedEntries.migrationReferenceMap()
        val verificationLimiter = Semaphore(rewriteParallelism(targetRoot))
        copiedEntries.map { migrationEntry ->
            async(Dispatchers.IO) {
                verificationLimiter.withPermit {
                    if (isMigrationTargetVerified(context, migrationEntry, referenceMap)) {
                        0
                    } else {
                        NPLogger.w(
                            tag,
                            "迁移后目标校验失败，保留源文件: ${migrationEntry.original.entry.name}"
                        )
                        1
                    }
                }
            }
        }.awaitAll().sum()
    }

    suspend fun cleanupMigratedEntries(
        context: Context,
        copiedEntries: List<CopiedMigrationEntry>,
        sourceRoot: ManagedDownloadRootHandle,
        targetsAlreadyVerified: Boolean = false,
        progressTracker: ManagedMigrationProgressReporter? = null
    ): Int = coroutineScope {
        if (copiedEntries.isEmpty()) return@coroutineScope 0
        val referenceMap = if (targetsAlreadyVerified) {
            emptyMap()
        } else {
            copiedEntries.migrationReferenceMap()
        }
        val cleanupLimiter = Semaphore(deleteParallelism(sourceRoot))
        copiedEntries.map { migrationEntry ->
            async(Dispatchers.IO) {
                cleanupLimiter.withPermit {
                    cleanupMigratedEntry(
                        context = context,
                        totalEntries = copiedEntries.size,
                        migrationEntry = migrationEntry,
                        sourceRoot = sourceRoot,
                        targetsAlreadyVerified = targetsAlreadyVerified,
                        referenceMap = referenceMap,
                        progressTracker = progressTracker
                    )
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
        val rewriteInput = runCatching {
            val sourceMetadata = readText(context, copied.original.entry.reference)
                ?: throw IOException("无法读取源 metadata: ${copied.original.entry.name}")
            val targetMetadata = readText(context, copied.copiedEntry.reference)
                ?: throw IOException("无法读取已迁移 metadata: ${copied.copiedEntry.name}")
            MetadataRewriteInput(
                sourceMetadata = sourceMetadata,
                targetMetadata = targetMetadata,
                rewrittenMetadata = rewriteMetadataReferences(sourceMetadata, referenceMap)
            )
        }.onFailure {
            NPLogger.w(tag, "迁移后重写 metadata 引用失败: ${copied.copiedEntry.reference}, ${it.message}")
        }.getOrNull()
        if (rewriteInput == null) {
            progressTracker?.finishRewrite(copied.copiedEntry.name)
            return 1
        }
        if (rewriteInput.rewrittenMetadata == rewriteInput.targetMetadata) {
            progressTracker?.finishRewrite(copied.copiedEntry.name)
            return 0
        }
        if (!copied.createdNew && rewriteInput.targetMetadata != rewriteInput.sourceMetadata) {
            NPLogger.w(
                tag,
                "拒绝覆盖无法确认来源的迁移 metadata: ${copied.copiedEntry.reference}"
            )
            progressTracker?.finishRewrite(copied.copiedEntry.name)
            return 1
        }
        runCatching {
            val rewrittenEntry = writeRootText(
                context,
                targetRoot,
                copied.copiedEntry.name,
                rewriteInput.rewrittenMetadata
            ) ?: throw IOException("无法读取回写后的 metadata: ${copied.copiedEntry.name}")
            restoreLastModified(
                context,
                rewrittenEntry,
                copied.original.entry.lastModifiedMs
            )
        }.onFailure {
            NPLogger.w(tag, "回写迁移后的 metadata 失败: ${copied.copiedEntry.reference}, ${it.message}")
        }.getOrElse {
            progressTracker?.finishRewrite(copied.copiedEntry.name)
            return 1
        }
        progressTracker?.finishRewrite(copied.copiedEntry.name)
        return 0
    }

    private data class MetadataRewriteInput(
        val sourceMetadata: String,
        val targetMetadata: String,
        val rewrittenMetadata: String
    )

    private fun cleanupMigratedEntry(
        context: Context,
        totalEntries: Int,
        migrationEntry: CopiedMigrationEntry,
        sourceRoot: ManagedDownloadRootHandle,
        targetsAlreadyVerified: Boolean,
        referenceMap: Map<String, String>,
        progressTracker: ManagedMigrationProgressReporter?
    ): Int {
        progressTracker?.startCleanup(totalEntries, migrationEntry.original.entry.name)
        val targetVerified = targetsAlreadyVerified || isMigrationTargetVerified(
            context = context,
            migrationEntry = migrationEntry,
            referenceMap = referenceMap
        )
        if (!targetVerified) {
            NPLogger.w(
                tag,
                "迁移后目标校验失败，跳过删除源文件: ${migrationEntry.original.entry.name}"
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

    private fun isMigrationTargetVerified(
        context: Context,
        migrationEntry: CopiedMigrationEntry,
        referenceMap: Map<String, String>
    ): Boolean {
        val sourceEntry = migrationEntry.original.entry
        if (ManagedDownloadTreeNaming.isMetadataName(sourceEntry.name)) {
            return hasEquivalentMigratedMetadata(
                context = context,
                migrationEntry = migrationEntry,
                referenceMap = referenceMap
            )
        }
        val sourceDigest = migrationEntry.sourceDigest?.takeIf(String::isNotBlank)
            ?: sha256ForEntry(context, sourceEntry)
            ?: return false
        val targetDigest = sha256ForEntry(context, migrationEntry.copiedEntry) ?: return false
        return sourceDigest == targetDigest
    }

    private fun hasEquivalentMigratedMetadata(
        context: Context,
        migrationEntry: CopiedMigrationEntry,
        referenceMap: Map<String, String>
    ): Boolean {
        return runCatching {
            val sourceMetadata = readText(context, migrationEntry.original.entry.reference)
                ?: throw IOException("无法读取源 metadata: ${migrationEntry.original.entry.name}")
            val targetMetadata = readText(context, migrationEntry.copiedEntry.reference)
                ?: throw IOException("无法读取目标 metadata: ${migrationEntry.copiedEntry.name}")
            val expectedTargetMetadata = rewriteMetadataReferences(sourceMetadata, referenceMap)
            areEquivalentJsonValues(
                JSONObject(expectedTargetMetadata),
                JSONObject(targetMetadata)
            )
        }.onFailure { error ->
            NPLogger.w(
                tag,
                "迁移 metadata 校验失败: ${migrationEntry.original.entry.reference}, ${error.message}"
            )
        }.getOrDefault(false)
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
        entry: ManagedDownloadStorage.StoredEntry
    ): String? {
        return runCatching {
            openInputStream(context, entry)?.use(::sha256Hex)
                ?: throw IOException("无法读取迁移校验文件: ${entry.name}")
        }.onFailure { error ->
            NPLogger.w(tag, "迁移文件 hash 校验失败: ${entry.reference}, ${error.message}")
        }.getOrNull()
    }

    private fun List<CopiedMigrationEntry>.migrationReferenceMap(): Map<String, String> {
        return buildMap {
            this@migrationReferenceMap.forEach { copied ->
                val source = copied.original.entry
                val target = copied.copiedEntry
                source.reference.takeIf(String::isNotBlank)?.let { reference ->
                    put(reference, target.reference)
                }
                source.localFilePath?.takeIf(String::isNotBlank)?.let { localPath ->
                    put(localPath, target.localFilePath ?: target.reference)
                }
                source.mediaUri.takeIf(String::isNotBlank)?.let { mediaUri ->
                    put(mediaUri, target.metadataReference())
                }
                source.localFileUriAliases(target).forEach { (sourceUri, targetUri) ->
                    put(sourceUri, targetUri)
                }
            }
        }
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
        val deleted = runCatching {
            deleteReference(context, migrationEntry.copiedEntry.reference, targetRoot)
        }.onFailure {
            NPLogger.w(tag, "回滚迁移目标文件失败: ${migrationEntry.copiedEntry.reference}, ${it.message}")
        }.getOrDefault(false)
        return if (deleted) 0 else 1
    }

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
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_SIZE_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    return digest.digest().joinToString(separator = "") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                }
                if (count > 0) {
                    digest.update(buffer, 0, count)
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
