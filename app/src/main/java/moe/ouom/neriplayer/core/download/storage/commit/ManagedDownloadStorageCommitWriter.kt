package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.download.storage.TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageMutationLocks
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafParentDocumentCache
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend.SafQueryResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.StorageTargetChangedException
import moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementPlan
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadStorageCommitWriter(
    private val treeChildRegistry: ManagedDownloadTreeChildRegistry,
    private val treeDirectories: ManagedDownloadTreeDirectories,
    private val tag: String
) {
    private val safParentDocumentCache = SafParentDocumentCache<SafQueryResult>()
    private val migrationTargetResolver = ManagedDownloadCommitMigrationTargetResolver(
        treeChildRegistry = treeChildRegistry,
        tag = tag
    )

    /** 校验失败时恢复保留的目标文件，备份已不存在时视为已经恢复以保持重试幂等 */
    fun restoreMigrationReplacement(
        context: Context,
        root: ManagedDownloadRootHandle,
        copied: CopiedMigrationEntry
    ): Boolean {
        val backup = copied.replacementBackup ?: return true
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                val parent = copied.original.subdirectory
                    ?.let { runCatching { resolveManagedFileSubdirectory(root.dir, it) }.getOrNull() }
                    ?: root.dir
                val targetFile = File(parent, copied.copiedEntry.name)
                val backupFile = backup.localFilePath?.let(::File)
                    ?: backup.reference.takeIf { it.startsWith("/") }?.let(::File)
                val safeBackupFile = backupFile?.takeIf { isFileEntryInParent(it, parent) }
                    ?: return false
                FileStorageMutationLocks.withTargetLocksBlocking(targetFile, safeBackupFile) {
                    val targetEntry = targetFile.takeIf(File::exists)?.let(
                        ManagedDownloadStoredEntryMapper::fromFile
                    )
                    if (isRestoredMigrationReplacementTarget(
                            expectedTarget = copied.copiedEntry,
                            replacementBackup = backup,
                            actualTarget = targetEntry
                        )
                    ) {
                        return@withTargetLocksBlocking true
                    }
                    if (!safeBackupFile.exists()) {
                        return@withTargetLocksBlocking targetEntry != null &&
                            sameManagedMigrationStoredEntryIdentity(
                                copied.copiedEntry,
                                targetEntry
                            )
                    }
                    val actualBackup = ManagedDownloadStoredEntryMapper.fromFile(safeBackupFile)
                    if (!sameMigrationReplacementBackupIdentity(
                            expectedTarget = copied.copiedEntry,
                            actualBackup = actualBackup,
                            expectedBackupName = backup.name
                        )
                    ) {
                        return@withTargetLocksBlocking false
                    }
                    if (targetFile.exists()) {
                        val actualTarget = ManagedDownloadStoredEntryMapper.fromFile(targetFile)
                        if (!isSafeReplacementTargetIdentity(
                                expectedTarget = copied.copiedEntry,
                                actualTarget = actualTarget,
                                requireStableFingerprint = true
                            )
                        ) {
                            return@withTargetLocksBlocking false
                        }
                        if (!targetFile.delete() && targetFile.exists()) {
                            return@withTargetLocksBlocking false
                        }
                    }
                    runCatching {
                        java.nio.file.Files.move(safeBackupFile.toPath(), targetFile.toPath())
                        targetFile.exists()
                    }.getOrDefault(false)
                }
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                restoreSafMigrationReplacementDirect(
                    context = context,
                    root = root,
                    copied = copied,
                    backup = backup
                )?.let { return it }
                val parents = findMigrationReplacementParents(
                    context = context,
                    root = root,
                    subdirectory = copied.original.subdirectory
                )
                if (parents.isEmpty()) return false
                parents.forEach { parent ->
                    val target = parent.findFile(copied.copiedEntry.name)
                    val targetEntry = target?.let(ManagedDownloadStoredEntryMapper::fromDocumentFile)
                    if (isRestoredMigrationReplacementTarget(
                            expectedTarget = copied.copiedEntry,
                            replacementBackup = backup,
                            actualTarget = targetEntry
                        )
                    ) {
                        return true
                    }
                    if (targetEntry != null) {
                        val targetMatches = isSafeReplacementTargetIdentity(
                            expectedTarget = copied.copiedEntry,
                            actualTarget = targetEntry,
                            requireStableFingerprint = true
                        )
                        if (!targetMatches) {
                            NPLogger.w(
                                tag,
                                "迁移恢复目标身份无法确认，保留外部文件: " +
                                    copied.copiedEntry.name
                            )
                            return@forEach
                        }
                    }
                    val backupDocument = parent.findFile(backup.name)
                    val backupEntry = backupDocument?.let(
                        ManagedDownloadStoredEntryMapper::fromDocumentFile
                    )
                    if (backupEntry == null ||
                        !sameMigrationReplacementBackupIdentity(
                            expectedTarget = copied.copiedEntry,
                            actualBackup = backupEntry,
                            expectedBackupName = backup.name
                        )
                    ) {
                        return@forEach
                    }
                    val backend = SafStorageBackend(
                        context,
                        parentDocumentCache = safParentDocumentCache
                    )
                    if (target != null) {
                        val targetRef = StorageReference.SafRef(target.uri)
                        val deleted = runBlocking(Dispatchers.IO) {
                            backend.delete(
                                moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef(
                                    reference = targetRef,
                                    externalReference = target.uri.toString()
                                )
                            )
                        }
                        if (deleted !is moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult.Deleted &&
                            deleted !is moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult.Missing
                        ) {
                            return@forEach
                        }
                    }
                    val renamed = runBlocking(Dispatchers.IO) {
                        backend.rename(
                            StorageReference.SafRef(backupDocument.uri).let { reference ->
                                moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef(
                                    reference = reference,
                                    externalReference = backupDocument.uri.toString()
                                )
                            },
                            copied.copiedEntry.name
                        )
                    }
                    if (renamed is StorageRenameResult.Renamed) return true
                }
                false
            }
        }
    }

    /** 优先使用凭据中的不透明文档引用，不依赖 Provider 对目录的编号
     * 这样重启恢复不会受 Covers 等目录编号变化影响
     */
    private fun restoreSafMigrationReplacementDirect(
        context: Context,
        root: ManagedDownloadRootHandle.TreeRoot,
        copied: CopiedMigrationEntry,
        backup: ManagedDownloadStorage.StoredEntry
    ): Boolean? {
        val backupUri = safDocumentUri(backup) ?: return null
        if (!isSafReferenceBoundToRoot(root.tree.uri, backupUri)) return null
        val backupDocument = DocumentFile.fromSingleUri(context, backupUri)
            ?: return null
        if (!backupDocument.exists()) return null
        val actualBackup = ManagedDownloadStoredEntryMapper.fromDocumentFile(backupDocument)
            ?: return null
        if (!sameManagedMigrationStoredEntryIdentity(backup, actualBackup)) return false

        val targetUri = safDocumentUri(copied.copiedEntry)
            ?.takeIf { uri -> isSafReferenceBoundToRoot(root.tree.uri, uri) }
            ?: return null
        if (targetUri == backupUri) {
            // 某些 Provider 重命名后仍返回同一个文档 URI
            return runBlocking(Dispatchers.IO) {
                val backend = SafStorageBackend(
                    context,
                    parentDocumentCache = safParentDocumentCache
                )
                when (val renamed = backend.rename(
                    moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef(
                        reference = StorageReference.SafRef(backupUri),
                        externalReference = backupUri.toString()
                    ),
                    copied.copiedEntry.name
                )) {
                    is StorageRenameResult.Renamed ->
                        renamed.stat.displayName == copied.copiedEntry.name
                    StorageRenameResult.Missing,
                    StorageRenameResult.OutOfScope,
                    StorageRenameResult.PermissionLost,
                    is StorageRenameResult.ProviderFailure,
                    is StorageRenameResult.Unsupported -> false
                }
            }
        }
        val targetDocument = DocumentFile.fromSingleUri(context, targetUri)
            ?.takeIf(DocumentFile::exists)
        if (targetDocument == null) return null
        val actualTarget = ManagedDownloadStoredEntryMapper.fromDocumentFile(targetDocument)
        if (actualTarget != null) {
            if (
                isRestoredMigrationReplacementTarget(
                    expectedTarget = copied.copiedEntry,
                    replacementBackup = backup,
                    actualTarget = actualTarget
                )
            ) {
                return true
            }
            if (!isSafeReplacementTargetIdentity(
                    expectedTarget = copied.copiedEntry,
                    actualTarget = actualTarget,
                    requireStableFingerprint = true
                )
            ) {
                return false
            }
            val backend = SafStorageBackend(
                context,
                parentDocumentCache = safParentDocumentCache
            )
            val deleted = runBlocking(Dispatchers.IO) {
                backend.delete(
                    moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef(
                        reference = StorageReference.SafRef(targetDocument.uri),
                        externalReference = targetDocument.uri.toString()
                    )
                )
            }
            if (
                deleted !is moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult.Deleted &&
                deleted !is moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult.Missing
            ) {
                return false
            }
        }

        val backend = SafStorageBackend(
            context,
            parentDocumentCache = safParentDocumentCache
        )
        val renamed = runBlocking(Dispatchers.IO) {
            backend.rename(
                moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef(
                    reference = StorageReference.SafRef(backupUri),
                    externalReference = backupUri.toString()
                ),
                copied.copiedEntry.name
            )
        }
        return when (renamed) {
            is StorageRenameResult.Renamed -> renamed.stat.displayName == copied.copiedEntry.name
            StorageRenameResult.Missing,
            StorageRenameResult.OutOfScope,
            StorageRenameResult.PermissionLost,
            is StorageRenameResult.ProviderFailure,
            is StorageRenameResult.Unsupported -> false
        }
    }

    private fun safDocumentUri(entry: ManagedDownloadStorage.StoredEntry): android.net.Uri? {
        return sequenceOf(entry.reference, entry.mediaUri)
            .mapNotNull { raw -> runCatching { raw.trim().toUri() }.getOrNull() }
            .firstOrNull { uri ->
                uri.scheme.equals("content", ignoreCase = true) &&
                    !uri.authority.isNullOrBlank()
            }
    }

    private fun isSafReferenceBoundToRoot(
        rootUri: android.net.Uri,
        referenceUri: android.net.Uri
    ): Boolean {
        if (!referenceUri.authority.equals(rootUri.authority, ignoreCase = true)) {
            return false
        }
        val rootDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(rootUri)
        }.getOrNull() ?: return false
        val referenceTreeId = runCatching {
            DocumentsContract.getTreeDocumentId(referenceUri)
        }.getOrNull() ?: return false
        return referenceTreeId == rootDocumentId
    }

    fun writeMigrationRootStream(
        context: Context,
        root: ManagedDownloadRootHandle,
        displayName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry? = null,
        onProgress: ((Long) -> Unit)? = null,
        replacementPlan: ManagedMigrationReplacementPlan? = null
    ): StoredWriteResult {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> writeMigrationFileRootStream(
                root = root,
                displayName = displayName,
                input = input,
                sourceEntry = sourceEntry,
                targetNames = targetNames,
                targetEntry = targetEntry,
                onProgress = onProgress,
                replacementPlan = replacementPlan
            )

            is ManagedDownloadRootHandle.TreeRoot -> writeMigrationTreeRootStream(
                context = context,
                root = root,
                displayName = displayName,
                mimeType = mimeType,
                input = input,
                sourceEntry = sourceEntry,
                targetNames = targetNames,
                targetEntry = targetEntry,
                onProgress = onProgress,
                replacementPlan = replacementPlan
            )
        }
    }

    fun writeSubdirectoryBytes(
        context: Context,
        root: ManagedDownloadRootHandle,
        subdirectory: String,
        displayName: String,
        bytes: ByteArray,
        mimeType: String
    ): ManagedDownloadStorage.StoredEntry? {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                val dir = resolveManagedFileSubdirectory(root.dir, subdirectory)
                treeDirectories.ensureManagedMediaScanIsolation(subdirectory, dir)
                val target = File(dir, displayName)
                writeFileEntry(root = root.dir, target = target, displayName = displayName) { output ->
                    output.write(bytes)
                }
                val verifiedSize = ManagedDownloadCommitIo.verifyFileCommittedLength(
                    target = target,
                    expectedSizeBytes = bytes.size.toLong(),
                    description = displayName
                )
                ManagedDownloadStoredEntryMapper.fromFile(target).copy(sizeBytes = verifiedSize)
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                val directory = treeDirectories.findOrCreateDirectory(context, root.tree, subdirectory)
                    ?: return null
                treeDirectories.ensureManagedMediaScanIsolation(context, subdirectory, directory)
                val knownChild = treeChildRegistry.peekTreeChild(
                    parent = directory,
                    childName = displayName
                )
                val entry = writeSafEntry(
                    context = context,
                    parent = directory,
                    displayName = displayName,
                    mimeType = mimeType,
                    expectedSizeBytes = bytes.size.toLong(),
                    expectedAbsent = knownChild == null,
                    knownExistingReference = knownChild?.documentUri?.toString(),
                    fallbackOnOptimisticCommitFailure = true
                ) { output -> output.write(bytes) }
                entry
            }
        }
    }

    fun writeMigrationSubdirectoryStream(
        context: Context,
        root: ManagedDownloadRootHandle,
        subdirectory: String,
        displayName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry? = null,
        onProgress: ((Long) -> Unit)? = null,
        replacementPlan: ManagedMigrationReplacementPlan? = null
    ): StoredWriteResult {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> writeMigrationFileSubdirectoryStream(
                root = root,
                subdirectory = subdirectory,
                displayName = displayName,
                input = input,
                sourceEntry = sourceEntry,
                targetNames = targetNames,
                targetEntry = targetEntry,
                onProgress = onProgress,
                replacementPlan = replacementPlan
            )

            is ManagedDownloadRootHandle.TreeRoot -> writeMigrationTreeSubdirectoryStream(
                context = context,
                root = root,
                subdirectory = subdirectory,
                displayName = displayName,
                mimeType = mimeType,
                input = input,
                sourceEntry = sourceEntry,
                targetNames = targetNames,
                targetEntry = targetEntry,
                onProgress = onProgress,
                replacementPlan = replacementPlan
            )
        }
    }

    fun writeRootText(
        context: Context,
        root: ManagedDownloadRootHandle,
        displayName: String,
        content: String,
        expectedAbsent: Boolean = false,
        knownTargetEntry: ManagedDownloadStorage.StoredEntry? = null
    ): ManagedDownloadStorage.StoredEntry? {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                val target = File(root.dir, displayName)
                val encoded = content.toByteArray(Charsets.UTF_8)
                writeFileEntry(root = root.dir, target = target, displayName = displayName) { output ->
                    output.write(encoded)
                }
                val verifiedSize = ManagedDownloadCommitIo.verifyFileCommittedLength(
                    target = target,
                    expectedSizeBytes = encoded.size.toLong(),
                    description = displayName
                )
                ManagedDownloadStoredEntryMapper.fromFile(target).copy(sizeBytes = verifiedSize)
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                val encoded = content.toByteArray(Charsets.UTF_8)
                // 扫描已确认的根目录快照时复用目标 URI，可省去一次大目录查询
                // 缓存不完整或过期时仍走原有安全路径
                val cachedTargetEntry = if (knownTargetEntry == null && !expectedAbsent) {
                    selectCachedSafWriteChild(
                        displayName = displayName,
                        cachedChildren = treeChildRegistry.cachedTreeChildrenIfFresh(
                            parent = root.tree,
                            maxCacheAgeMs = TREE_CHILDREN_WRITE_CACHE_VALIDATE_INTERVAL_MS
                        )
                    )
                        ?.let(ManagedDownloadStoredEntryMapper::fromTreeChild)
                        ?.takeIf { entry ->
                            safDocumentUri(entry)?.let { uri ->
                                isSafReferenceBoundToRoot(root.tree.uri, uri)
                            } == true
                        }
                } else {
                    null
                }
                val effectiveKnownTarget = knownTargetEntry ?: cachedTargetEntry
                writeSafEntry(
                    context = context,
                    parent = root.tree,
                    displayName = displayName,
                    mimeType = "application/json",
                    expectedSizeBytes = encoded.size.toLong(),
                    expectedAbsent = expectedAbsent,
                    knownExistingReference = effectiveKnownTarget?.reference,
                    // 外部文件管理器可能在缓存有效期内改名，失败时回退完整查找
                    fallbackOnOptimisticCommitFailure = cachedTargetEntry != null
                ) { output -> output.write(encoded) }
            }
        }
    }

    private fun writeFileEntry(
        root: File,
        target: File,
        displayName: String,
        writer: suspend (java.io.OutputStream) -> Unit
    ) {
        val logicalPath = target.relativeTo(root).path
        val result = runBlocking(Dispatchers.IO) {
            FileStorageBackend(root).writeRecoverable(
                target = StorageTarget.FileTarget(logicalPath),
                writer = writer
            )
        }
        when (result) {
            is StorageWriteResult.Written -> Unit
            StorageWriteResult.Missing -> throw IOException("文件写入目标不存在: $displayName")
            StorageWriteResult.OutOfScope -> throw IOException("文件写入目标越界: $displayName")
            StorageWriteResult.PermissionLost -> throw SecurityException("文件写入权限丢失: $displayName")
            is StorageWriteResult.ProviderFailure -> throw IOException(
                "文件写入失败: $displayName",
                result.error
            )
            is StorageWriteResult.Unsupported -> throw IOException(
                "文件系统不支持写入: $displayName (${result.operation})"
            )
        }
    }

    private fun writeFileReplacementStream(
        root: File,
        displayName: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        expectedTargetEntry: ManagedDownloadStorage.StoredEntry?,
        plan: ManagedMigrationReplacementPlan,
        onProgress: ((Long) -> Unit)?
    ): StoredWriteResult {
        if (plan.targetName != displayName) {
            throw ManagedDownloadMigrationException.targetChanged(
                "迁移替换计划目标名发生变化: $displayName"
            )
        }
        val existingTarget = File(root, displayName)
        val existingBackup = File(root, plan.backupName)
        val expectedTarget = expectedTargetEntry ?: plan.targetEntry
        if (existingTarget.exists()) {
            val actualTarget = ManagedDownloadStoredEntryMapper.fromFile(existingTarget)
            val backupAlreadyOwned = existingBackup.isFile &&
                sameMigrationReplacementBackupIdentity(
                    plan.targetEntry,
                    ManagedDownloadStoredEntryMapper.fromFile(existingBackup),
                    expectedBackupName = plan.backupName
                )
            if (actualTarget.isDirectory ||
                !backupAlreadyOwned &&
                !isSafeReplacementTargetIdentity(
                    expectedTarget = expectedTarget,
                    actualTarget = actualTarget,
                    requireStableFingerprint = false
                )
            ) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "迁移替换目标文档已发生变化: $displayName"
                )
            }
        }
        if (existingBackup.exists()) {
            if (!existingBackup.isFile) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "迁移替换备份不是普通文件: $displayName"
                )
            }
            val actualBackup = ManagedDownloadStoredEntryMapper.fromFile(existingBackup)
            if (!sameMigrationReplacementBackupIdentity(
                    expectedTarget = plan.targetEntry,
                    actualBackup = actualBackup,
                    expectedBackupName = plan.backupName
                )
            ) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "迁移替换备份文档已发生变化: $displayName"
                )
            }
        }
        val replacement = ManagedDownloadCommitIo.copyFileReplacementAtomically(
            parent = root,
            targetName = displayName,
            backupName = plan.backupName,
            input = input,
            bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
            onProgress = onProgress
        )
        val expectedSize = sourceEntry.sizeBytes.takeIf { it > 0L }
            ?: replacement.copiedBytes.takeIf { it >= 0L }
            ?: replacement.target.length()
        val verifiedSize = ManagedDownloadCommitIo.verifyFileCommittedLength(
            target = replacement.target,
            expectedSizeBytes = expectedSize,
            description = displayName
        )
        sourceEntry.lastModifiedMs.takeIf { it > 0L }?.let {
            replacement.target.setLastModified(it)
        }
        val backupEntry = replacement.backup
            ?.takeIf(File::isFile)
            ?.let(ManagedDownloadStoredEntryMapper::fromFile)
        return StoredWriteResult(
            entry = ManagedDownloadStoredEntryMapper.fromFile(replacement.target)
                .copy(sizeBytes = verifiedSize),
            createdNew = backupEntry == null,
            replacementBackup = backupEntry,
            sourceAuthoritative = true,
            targetContentMatchesSource = true
        )
    }

    private fun writeTreeReplacementStream(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        expectedTargetEntry: ManagedDownloadStorage.StoredEntry?,
        plan: ManagedMigrationReplacementPlan,
        onProgress: ((Long) -> Unit)?
    ): StoredWriteResult {
        if (plan.targetName != displayName) {
            throw ManagedDownloadMigrationException.targetChanged(
                "SAF 迁移替换计划目标名发生变化: $displayName"
            )
        }
        val backend = SafStorageBackend(
            context,
            parentDocumentCache = safParentDocumentCache
        )
        val existingTarget = parent.findFile(displayName)
        val existingBackup = parent.findFile(plan.backupName)
        var backupEntry = existingBackup?.let(ManagedDownloadStoredEntryMapper::fromDocumentFile)
        var target = existingTarget
        val expectedTarget = expectedTargetEntry ?: plan.targetEntry
        if (target != null) {
            val actualTarget = ManagedDownloadStoredEntryMapper.fromDocumentFile(target)
                ?: throw ManagedDownloadMigrationException.targetChanged(
                    "SAF 迁移替换目标无法读取: $displayName"
                )
            val backupAlreadyOwned = backupEntry != null &&
                sameMigrationReplacementBackupIdentity(
                    expectedTarget = plan.targetEntry,
                    actualBackup = backupEntry,
                    expectedBackupName = plan.backupName
                )
            if (actualTarget.isDirectory ||
                !backupAlreadyOwned &&
                !isSafeReplacementTargetIdentity(
                    expectedTarget = expectedTarget,
                    actualTarget = actualTarget,
                    requireStableFingerprint = false
                )
            ) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "SAF 迁移替换目标文档已发生变化: $displayName"
                )
            }
        }
        if (existingBackup != null) {
            val actualBackup = backupEntry
                ?: throw ManagedDownloadMigrationException.targetChanged(
                    "SAF 迁移替换备份无法读取: $displayName"
                )
            if (!sameMigrationReplacementBackupIdentity(
                    expectedTarget = plan.targetEntry,
                    actualBackup = actualBackup,
                    expectedBackupName = plan.backupName
                )
            ) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "SAF 迁移替换备份文档已发生变化: $displayName"
                )
            }
        }
        if (target != null && backupEntry == null) {
            val trustedTarget = StorageReference.SafRef(target.uri).let { reference ->
                moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef(
                    reference = reference,
                    externalReference = target.uri.toString()
                )
            }
            val renameResult = runBlocking(Dispatchers.IO) {
                backend.rename(trustedTarget, plan.backupName)
            }
            when (renameResult) {
                is StorageRenameResult.Renamed -> {
                    backupEntry = renameResult.stat.toStoredEntry()
                    target = null
                }
                StorageRenameResult.Missing -> target = null
                StorageRenameResult.PermissionLost ->
                    throw ManagedDownloadMigrationException.transient(
                        "SAF 迁移替换目标权限丢失: $displayName"
                    )
                StorageRenameResult.OutOfScope ->
                    throw ManagedDownloadMigrationException.transient(
                        "SAF 迁移替换目标越界: $displayName"
                    )
                is StorageRenameResult.Unsupported ->
                    throw ManagedDownloadMigrationException.transient(
                        "SAF Provider 不支持保留替换备份: ${renameResult.operation}"
                    )
                is StorageRenameResult.ProviderFailure ->
                    throw ManagedDownloadMigrationException.transient(
                        "SAF 迁移替换备份失败: $displayName",
                        renameResult.error
                    )
            }
        }
        val existingTargetReference = target
            ?.takeIf { backupEntry != null }
            ?.let { StorageReference.SafRef(it.uri) }
        var copiedBytes = 0L
        val result = runBlocking(Dispatchers.IO) {
            val targetSpec = StorageTarget.SafTarget(
                parent = StorageReference.SafRef(parent.uri),
                displayName = displayName,
                mimeType = mimeType
            )
            suspend fun copySource(output: OutputStream) {
                copiedBytes = ManagedDownloadCommitIo.copyStreamWithProgress(
                    input = input,
                    output = output,
                    bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
                    onProgress = onProgress
                )
            }
            if (existingTargetReference != null) {
                // 计划备份已经保留旧目标，先删除同名残留再 create-only 写入
                // 避免 backend 再生成不可归属的随机 backup 文档
                val deleted = backend.delete(
                    moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef(
                        reference = existingTargetReference,
                        externalReference = existingTargetReference.uri.toString()
                    )
                )
                when (deleted) {
                    StorageMutationResult.Deleted,
                    StorageMutationResult.Missing -> Unit
                    StorageMutationResult.PermissionLost -> throw ManagedDownloadMigrationException.transient(
                        "SAF 迁移替换目标删除权限丢失: $displayName"
                    )
                    StorageMutationResult.OutOfScope -> throw ManagedDownloadMigrationException.transient(
                        "SAF 迁移替换目标越界: $displayName"
                    )
                    is StorageMutationResult.ProviderFailure ->
                        throw ManagedDownloadMigrationException.transient(
                            "SAF 迁移替换目标删除失败: $displayName",
                            deleted.error
                        )
                    is StorageMutationResult.Unsupported ->
                        throw ManagedDownloadMigrationException.transient(
                            "SAF Provider 不支持迁移替换目标删除: ${deleted.operation}"
                        )
                }
            }
            backend.writeCreateOnlyRecoverable(
                target = targetSpec,
                writer = ::copySource
            )
        }
        val stat = when (result) {
            is StorageWriteResult.Written -> result.stat
            StorageWriteResult.Missing -> throw ManagedDownloadMigrationException.transient(
                "SAF 迁移替换目录不存在: $displayName"
            )
            StorageWriteResult.OutOfScope -> throw ManagedDownloadMigrationException.transient(
                "SAF 迁移替换目录越界: $displayName"
            )
            StorageWriteResult.PermissionLost -> throw ManagedDownloadMigrationException.transient(
                "SAF 迁移替换目录权限丢失: $displayName"
            )
            is StorageWriteResult.ProviderFailure -> {
                if (result.error is StorageTargetChangedException) {
                    throw ManagedDownloadMigrationException.targetChanged(
                        "SAF 迁移替换目标已发生变化: $displayName",
                        result.error
                    )
                }
                throw ManagedDownloadMigrationException.transient(
                    "SAF 迁移替换写入失败: $displayName",
                    result.error
                )
            }
            is StorageWriteResult.Unsupported -> throw ManagedDownloadMigrationException.transient(
                "SAF Provider 不支持迁移替换写入: ${result.operation}"
            )
        }
        val actualSize = verifySafCommittedSize(
            backend = backend,
            stat = stat,
            expectedSizeBytes = sourceEntry.sizeBytes.takeIf { it > 0L },
            writtenBytes = copiedBytes,
            displayName = displayName,
            allowSourceSizeDrift = true
        )
        val entry = stat.toStoredEntry().copy(sizeBytes = actualSize)
        treeChildRegistry.rememberTreeChild(parent, entry)
        return StoredWriteResult(
            entry = entry,
            createdNew = backupEntry == null,
            replacementBackup = backupEntry,
            sourceAuthoritative = true,
            targetContentMatchesSource = true
        )
    }

    private fun writeMigrationFileRootStream(
        root: ManagedDownloadRootHandle.FileRoot,
        displayName: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        onProgress: ((Long) -> Unit)?,
        replacementPlan: ManagedMigrationReplacementPlan?
    ): StoredWriteResult {
        replacementPlan?.let { plan ->
            return writeFileReplacementStream(
                root = root.dir,
                displayName = displayName,
                input = input,
                sourceEntry = sourceEntry,
                expectedTargetEntry = targetEntry,
                plan = plan,
                onProgress = onProgress
            )
        }
        val target = migrationTargetResolver.resolveFileTarget(
            parent = root.dir,
            displayName = displayName,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry
        )
        if (!target.createdNew) {
            return target
        }
        val targetFile = File(root.dir, target.entry.name)
        val copiedBytes = ManagedDownloadCommitIo.copyFileAtomically(
            parent = root.dir,
            targetName = target.entry.name,
            input = input,
            bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
            onProgress = onProgress
        )
        val verifiedSize = ManagedDownloadCommitIo.verifyFileCommittedLength(
            target = targetFile,
            expectedSizeBytes = copiedBytes,
            description = target.entry.name
        )
        sourceEntry.lastModifiedMs
            .takeIf { it > 0L }
            ?.let { targetFile.setLastModified(it) }
        return StoredWriteResult(
            entry = ManagedDownloadStoredEntryMapper.fromFile(targetFile).copy(sizeBytes = verifiedSize),
            createdNew = true,
            targetContentMatchesSource = true
        )
    }

    private fun writeSafEntry(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long?,
        expectedAbsent: Boolean = false,
        knownExistingReference: String? = null,
        fallbackOnOptimisticCommitFailure: Boolean = false,
        writer: suspend (java.io.OutputStream) -> Unit
    ): ManagedDownloadStorage.StoredEntry {
        val backend = moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend(
            context,
            parentDocumentCache = safParentDocumentCache
        )
        var writtenBytes = 0L

        suspend fun writeCounting(output: OutputStream) {
            val countingOutput = CountingOutputStream(output)
            writer(countingOutput)
            countingOutput.flush()
            writtenBytes = countingOutput.count
        }

        val result = runBlocking(Dispatchers.IO) {
            val target = StorageTarget.SafTarget(
                parent = StorageReference.SafRef(parent.uri),
                displayName = displayName,
                mimeType = mimeType
            )
            val optimisticResult = if (expectedAbsent) {
                backend.writeCreateOnlyRecoverable(target = target, writer = ::writeCounting)
            } else if (!knownExistingReference.isNullOrBlank()) {
                backend.replaceKnownRecoverable(
                    target = target,
                    existingReference = StorageReference.SafRef(
                        knownExistingReference.toUri()
                    ),
                    writer = ::writeCounting
                )
            } else {
                backend.writeRecoverable(target = target, writer = ::writeCounting)
            }
            if (
                fallbackOnOptimisticCommitFailure &&
                    shouldFallbackAfterOptimisticCommitFailure(
                        expectedAbsent = expectedAbsent,
                        knownExistingReference = knownExistingReference,
                        result = optimisticResult
                    )
            ) {
                backend.writeRecoverable(target = target, writer = ::writeCounting)
            } else {
                optimisticResult
            }
        }
        val stat = when (result) {
            is StorageWriteResult.Written -> result.stat
            StorageWriteResult.Missing -> throw IOException("SAF 目标不存在: $displayName")
            StorageWriteResult.OutOfScope -> throw IOException("SAF 目标越界: $displayName")
            StorageWriteResult.PermissionLost -> throw SecurityException("SAF 写入权限丢失: $displayName")
            is StorageWriteResult.ProviderFailure -> throw IOException(
                "SAF 写入失败: $displayName",
                result.error
            )
            is StorageWriteResult.Unsupported -> throw IOException(
                "SAF 不支持写入: $displayName (${result.operation})"
            )
        }
        val verifiedSize = verifySafCommittedSize(
            backend = backend,
            stat = stat,
            expectedSizeBytes = expectedSizeBytes,
            writtenBytes = writtenBytes,
            displayName = displayName,
            allowSourceSizeDrift = false
        )
        val entry = stat.toStoredEntry().copy(sizeBytes = verifiedSize)
        treeChildRegistry.rememberTreeChild(parent, entry)
        return entry
    }

    /**
     * SAF 的 COLUMN_SIZE 可能在写入刚完成时仍是旧值，以输出计数为主
     * 只有报告值漂移时才读回一次，避免把 Provider 延迟误判为截断
     */
    private fun verifySafCommittedSize(
        backend: SafStorageBackend,
        stat: StorageStat,
        expectedSizeBytes: Long?,
        writtenBytes: Long,
        displayName: String,
        allowSourceSizeDrift: Boolean
    ): Long {
        if (writtenBytes < 0L) {
            throw IOException("SAF 写入字节计数无效: $displayName")
        }
        val expected = expectedSizeBytes?.takeIf { it >= 0L }
        if (!allowSourceSizeDrift && expected != null && writtenBytes != expected) {
            throw IOException(
                "SAF 写入源字节不匹配: $displayName, expected=$expected, actual=$writtenBytes"
            )
        }
        val reported = stat.sizeBytes?.takeIf { it >= 0L }
        if (reported != null && reported != writtenBytes) {
            val counted = runBlocking(Dispatchers.IO) {
                when (val measured = backend.read(stat.reference) { input ->
                    ManagedDownloadCommitIo.countInputStreamBytes(
                        input,
                        STREAM_COPY_BUFFER_SIZE_BYTES
                    )
                }) {
                    is moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult.Found -> {
                        measured.value
                    }
                    else -> null
                }
            }
            if (!isSafProviderSizeDriftRecoverable(
                    copiedBytes = writtenBytes,
                    reportedBytes = reported,
                    countedBytes = counted
                )
            ) {
                throw IOException(
                    "SAF 写入读回字节不匹配: $displayName, " +
                        "written=$writtenBytes, reported=$reported, counted=${counted ?: -1L}"
                )
            }
            NPLogger.w(
                tag,
                "SAF Provider 大小报告延迟，使用已读回字节: " +
                    "file=$displayName, reported=$reported, counted=$counted"
            )
        }
        if (allowSourceSizeDrift && expected != null && writtenBytes != expected) {
            NPLogger.w(
                tag,
                "迁移源大小提示与实际读取不一致，交由摘要校验: " +
                    "file=$displayName, source=$expected, copied=$writtenBytes"
            )
        }
        return writtenBytes
    }

    private class CountingOutputStream(
        private val delegate: OutputStream
    ) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            count += length.toLong()
        }

        override fun flush() {
            delegate.flush()
        }
    }

    private fun shouldFallbackAfterOptimisticCommitFailure(
        expectedAbsent: Boolean,
        knownExistingReference: String?,
        result: StorageWriteResult
    ): Boolean {
        val error = (result as? StorageWriteResult.ProviderFailure)?.error
        val targetChanged = error is StorageTargetChangedException
        val renameUnsupported = result is StorageWriteResult.Unsupported &&
            result.operation.contains("rename", ignoreCase = true)
        return (expectedAbsent || !knownExistingReference.isNullOrBlank()) &&
            (targetChanged || renameUnsupported)
    }

    private fun StorageStat.toStoredEntry(): ManagedDownloadStorage.StoredEntry {
        val externalReference = when (val storageReference = reference) {
            is StorageReference.FileRef -> storageReference.logicalPath
            is StorageReference.SafRef -> storageReference.uri.toString()
        }
        val localPath = (reference as? StorageReference.FileRef)?.logicalPath
        return ManagedDownloadStorage.StoredEntry(
            name = displayName,
            reference = externalReference,
            mediaUri = externalReference,
            localFilePath = localPath,
            sizeBytes = sizeBytes ?: 0L,
            lastModifiedMs = lastModifiedMs ?: 0L,
            sizeKnown = sizeBytes != null,
            isDirectory = isDirectory
        )
    }

    private fun writeMigrationTreeRootStream(
        context: Context,
        root: ManagedDownloadRootHandle.TreeRoot,
        displayName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        onProgress: ((Long) -> Unit)?,
        replacementPlan: ManagedMigrationReplacementPlan?
    ): StoredWriteResult {
        replacementPlan?.let { plan ->
            return writeTreeReplacementStream(
                context = context,
                parent = root.tree,
                displayName = displayName,
                mimeType = mimeType,
                input = input,
                sourceEntry = sourceEntry,
                expectedTargetEntry = targetEntry,
                plan = plan,
                onProgress = onProgress
            )
        }
        val targetPlan = migrationTargetResolver.resolveTreeTarget(
            context = context,
            parent = root.tree,
            displayName = displayName,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry
        )
        if (!targetPlan.createdNew) {
            return targetPlan
        }
        return writeMigrationTreeStream(
            context = context,
            parent = root.tree,
            finalName = targetPlan.entry.name,
            mimeType = mimeType,
            input = input,
            sourceEntry = sourceEntry,
            onProgress = onProgress,
            description = displayName
        )
    }

    private fun writeMigrationFileSubdirectoryStream(
        root: ManagedDownloadRootHandle.FileRoot,
        subdirectory: String,
        displayName: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        onProgress: ((Long) -> Unit)?,
        replacementPlan: ManagedMigrationReplacementPlan?
    ): StoredWriteResult {
        replacementPlan?.let { plan ->
            val dir = resolveManagedFileSubdirectory(root.dir, subdirectory)
            treeDirectories.ensureManagedMediaScanIsolation(subdirectory, dir)
            return writeFileReplacementStream(
                root = dir,
                displayName = displayName,
                input = input,
                sourceEntry = sourceEntry,
                expectedTargetEntry = targetEntry,
                plan = plan,
                onProgress = onProgress
            )
        }
        val dir = resolveManagedFileSubdirectory(root.dir, subdirectory)
        treeDirectories.ensureManagedMediaScanIsolation(subdirectory, dir)
        val target = migrationTargetResolver.resolveFileTarget(
            parent = dir,
            displayName = displayName,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry
        )
        if (!target.createdNew) {
            return target
        }
        val targetFile = File(dir, target.entry.name)
        val copiedBytes = ManagedDownloadCommitIo.copyFileAtomically(
            parent = dir,
            targetName = target.entry.name,
            input = input,
            bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
            onProgress = onProgress
        )
        val verifiedSize = ManagedDownloadCommitIo.verifyFileCommittedLength(
            target = targetFile,
            expectedSizeBytes = copiedBytes,
            description = target.entry.name
        )
        sourceEntry.lastModifiedMs
            .takeIf { it > 0L }
            ?.let { targetFile.setLastModified(it) }
        return StoredWriteResult(
            entry = ManagedDownloadStoredEntryMapper.fromFile(targetFile).copy(sizeBytes = verifiedSize),
            createdNew = true,
            targetContentMatchesSource = true
        )
    }

    private fun writeMigrationTreeSubdirectoryStream(
        context: Context,
        root: ManagedDownloadRootHandle.TreeRoot,
        subdirectory: String,
        displayName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        onProgress: ((Long) -> Unit)?,
        replacementPlan: ManagedMigrationReplacementPlan?
    ): StoredWriteResult {
        val directory = treeDirectories.findOrCreateDirectory(context, root.tree, subdirectory)
            ?: throw IOException("无法创建目录: $subdirectory")
        treeDirectories.ensureManagedMediaScanIsolation(context, subdirectory, directory)
        replacementPlan?.let { plan ->
            return writeTreeReplacementStream(
                context = context,
                parent = directory,
                displayName = displayName,
                mimeType = mimeType,
                input = input,
                sourceEntry = sourceEntry,
                expectedTargetEntry = targetEntry,
                plan = plan,
                onProgress = onProgress
            )
        }
        val targetPlan = migrationTargetResolver.resolveTreeTarget(
            context = context,
            parent = directory,
            displayName = displayName,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry
        )
        if (!targetPlan.createdNew) {
            return targetPlan
        }
        return writeMigrationTreeStream(
            context = context,
            parent = directory,
            finalName = targetPlan.entry.name,
            mimeType = mimeType,
            input = input,
            sourceEntry = sourceEntry,
            onProgress = onProgress,
            description = displayName
        )
    }

    private fun writeMigrationTreeStream(
        context: Context,
        parent: DocumentFile,
        finalName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        onProgress: ((Long) -> Unit)?,
        description: String
    ): StoredWriteResult {
        var copiedBytes = 0L
        val backend = moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend(
            context,
            parentDocumentCache = safParentDocumentCache
        )
        val result = runBlocking(Dispatchers.IO) {
            backend.writeCreateOnlyRecoverable(
                target = StorageTarget.SafTarget(
                    parent = StorageReference.SafRef(parent.uri),
                    displayName = finalName,
                    mimeType = mimeType
                )
            ) { output ->
                copiedBytes = ManagedDownloadCommitIo.copyStreamWithProgress(
                    input = input,
                    output = output,
                    bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
                    onProgress = onProgress
                )
            }
        }
        val stat = when (result) {
            is StorageWriteResult.Written -> result.stat
            StorageWriteResult.Missing -> throw IOException("迁移目标不存在: $finalName")
            StorageWriteResult.OutOfScope -> throw IOException("迁移目标越界: $finalName")
            StorageWriteResult.PermissionLost -> throw SecurityException("迁移目标权限丢失: $finalName")
            is StorageWriteResult.ProviderFailure -> {
                if (result.error is StorageTargetChangedException) {
                    throw ManagedDownloadMigrationException.targetChanged(
                        "迁移目标目录已发生变化: $finalName",
                        result.error
                    )
                }
                throw IOException("SAF 迁移写入失败: $description", result.error)
            }
            is StorageWriteResult.Unsupported -> throw IOException(
                "SAF 不支持迁移写入: $finalName (${result.operation})"
            )
        }
        val verifiedSize = verifySafCommittedSize(
            backend = backend,
            stat = stat,
            expectedSizeBytes = sourceEntry.sizeBytes.takeIf { it > 0L },
            writtenBytes = copiedBytes,
            displayName = description,
            allowSourceSizeDrift = true
        )
        val entry = stat.toStoredEntry().copy(
            sizeBytes = verifiedSize,
            // SAF Provider 决定物理时间，来源时间保存在元数据中
            lastModifiedMs = stat.lastModifiedMs ?: sourceEntry.lastModifiedMs
        )
        treeChildRegistry.rememberTreeChild(parent, entry)
        return StoredWriteResult(
            entry = entry,
            createdNew = true,
            targetContentMatchesSource = true
        )
    }

    private fun findMigrationReplacementParents(
        context: Context,
        root: ManagedDownloadRootHandle.TreeRoot,
        subdirectory: String?
    ): List<DocumentFile> {
        if (subdirectory.isNullOrBlank()) return listOf(root.tree)
        return treeDirectories.findSubdirectories(
            context = context,
            root = root,
            desiredName = subdirectory,
            canonicalLast = false
        ).mapNotNull { childRoot ->
            (childRoot as? ManagedDownloadRootHandle.TreeRoot)?.tree
        }
    }

}

internal fun isSafProviderSizeDriftRecoverable(
    copiedBytes: Long,
    reportedBytes: Long?,
    countedBytes: Long?
): Boolean {
    if (copiedBytes < 0L) return false
    if (reportedBytes == null || reportedBytes <= 0L || reportedBytes == copiedBytes) {
        return true
    }
    return countedBytes == copiedBytes
}

internal fun selectCachedSafWriteChild(
    displayName: String,
    cachedChildren: Collection<QueriedTreeChild>?
): QueriedTreeChild? {
    return cachedChildren?.firstOrNull { child ->
        child.name == displayName && !child.isDirectory
    }
}

internal fun sameManagedMigrationStoredEntryIdentity(
    expected: ManagedDownloadStorage.StoredEntry,
    actual: ManagedDownloadStorage.StoredEntry
): Boolean {
    if (expected.reference.isNotBlank() && expected.reference == actual.reference) return true
    if (expected.mediaUri.isNotBlank() && expected.mediaUri == actual.mediaUri) return true
    if (
        expected.localFilePath?.isNotBlank() == true &&
        actual.localFilePath?.isNotBlank() == true &&
        runCatching {
            File(expected.localFilePath).canonicalFile == File(actual.localFilePath).canonicalFile
        }.getOrDefault(false)
    ) {
        return true
    }
    val expectedSafIdentity = sequenceOf(expected.reference, expected.mediaUri)
        .mapNotNull(::managedMigrationSafIdentity)
        .toSet()
    val actualSafIdentity = sequenceOf(actual.reference, actual.mediaUri)
        .mapNotNull(::managedMigrationSafIdentity)
        .toSet()
    return expectedSafIdentity.any(actualSafIdentity::contains)
}

internal fun sameMigrationReplacementBackupIdentity(
    expectedTarget: ManagedDownloadStorage.StoredEntry,
    actualBackup: ManagedDownloadStorage.StoredEntry,
    expectedBackupName: String? = null
): Boolean {
    if (expectedTarget.isDirectory || actualBackup.isDirectory) return false
    if (expectedBackupName != null && actualBackup.name != expectedBackupName) return false
    val expectedSafIdentity = sequenceOf(expectedTarget.reference, expectedTarget.mediaUri)
        .mapNotNull(::managedMigrationSafIdentity)
        .toSet()
    val actualSafIdentity = sequenceOf(actualBackup.reference, actualBackup.mediaUri)
        .mapNotNull(::managedMigrationSafIdentity)
        .toSet()
    if (
        expectedSafIdentity.isNotEmpty() &&
            actualSafIdentity.isNotEmpty() &&
            expectedSafIdentity.none(actualSafIdentity::contains)
    ) {
        return false
    }
    if (sameManagedMigrationStoredEntryIdentity(expectedTarget, actualBackup)) return true
    val hasSafReference = sequenceOf(
        expectedTarget.reference,
        expectedTarget.mediaUri,
        actualBackup.reference,
        actualBackup.mediaUri
    ).any { value ->
        value.trim().substringBefore(':').equals("content", ignoreCase = true)
    }
    if (hasSafReference) {
        return expectedTarget.sizeBytes > 0L &&
            actualBackup.sizeBytes > 0L &&
            expectedTarget.sizeBytes == actualBackup.sizeBytes &&
            (expectedTarget.lastModifiedMs <= 0L ||
                actualBackup.lastModifiedMs <= 0L ||
                expectedTarget.lastModifiedMs == actualBackup.lastModifiedMs)
    }
    val expectedPath = expectedTarget.localFilePath
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return false
    val actualPath = actualBackup.localFilePath
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return false
    val sameParent = runCatching {
        File(expectedPath).canonicalFile.parentFile == File(actualPath).canonicalFile.parentFile
    }.getOrDefault(false)
    if (!sameParent) return false
    return expectedTarget.sizeBytes > 0L &&
        actualBackup.sizeBytes > 0L &&
        expectedTarget.sizeBytes == actualBackup.sizeBytes &&
        expectedTarget.lastModifiedMs > 0L &&
        actualBackup.lastModifiedMs > 0L &&
        expectedTarget.lastModifiedMs == actualBackup.lastModifiedMs
}

internal fun isSafeReplacementTargetIdentity(
    expectedTarget: ManagedDownloadStorage.StoredEntry,
    actualTarget: ManagedDownloadStorage.StoredEntry,
    requireStableFingerprint: Boolean
): Boolean {
    if (!sameManagedMigrationStoredEntryIdentity(expectedTarget, actualTarget)) {
        return false
    }
    if (!requireStableFingerprint) return true
    if (
        expectedTarget.sizeBytes > 0L &&
            actualTarget.sizeBytes > 0L &&
            expectedTarget.sizeBytes != actualTarget.sizeBytes
    ) {
        return false
    }
    if (
        expectedTarget.lastModifiedMs > 0L &&
            actualTarget.lastModifiedMs > 0L &&
            expectedTarget.lastModifiedMs != actualTarget.lastModifiedMs
    ) {
        return false
    }
    return true
}

internal fun isRestoredMigrationReplacementTarget(
    expectedTarget: ManagedDownloadStorage.StoredEntry,
    replacementBackup: ManagedDownloadStorage.StoredEntry?,
    actualTarget: ManagedDownloadStorage.StoredEntry?
): Boolean {
    val backup = replacementBackup ?: return false
    val actual = actualTarget ?: return false
    if (
        expectedTarget.isDirectory ||
        backup.isDirectory ||
        actual.isDirectory ||
        actual.name != expectedTarget.name
    ) {
        return false
    }
    if (sameManagedMigrationStoredEntryIdentity(expectedTarget, actual)) return false
    val backupIdentity = sameManagedMigrationStoredEntryIdentity(backup, actual)
    val hasSafReference = sequenceOf(backup.reference, backup.mediaUri, actual.reference, actual.mediaUri)
        .mapNotNull { value -> value.trim().takeIf(String::isNotBlank) }
        .any { value -> value.substringBefore(':').equals("content", ignoreCase = true) }
    if (hasSafReference) {
        // SAF 文档 ID 才是 Provider 条目的稳定身份，大小和时间相同仍然不够
        return backupIdentity
    }
    return backupIdentity || (
        migrationFingerprintCompatible(backup.sizeBytes, actual.sizeBytes) &&
            migrationFingerprintCompatible(backup.lastModifiedMs, actual.lastModifiedMs) &&
            backup.sizeBytes > 0L &&
            backup.lastModifiedMs > 0L &&
            actual.sizeBytes > 0L &&
            actual.lastModifiedMs > 0L
        )
}

private fun migrationFingerprintCompatible(expected: Long, actual: Long): Boolean {
    return expected <= 0L || actual <= 0L || expected == actual
}

private fun managedMigrationSafIdentity(value: String): String? {
    val normalized = value.trim().takeIf(String::isNotBlank) ?: return null
    val uri = runCatching { normalized.toUri() }.getOrNull()
    if (
        uri != null &&
        (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank())
    ) {
        return null
    }
    val authority = uri?.authority ?: rawSafAuthority(normalized) ?: return null
    val documentId = rawSafDocumentId(normalized)
        ?: uri?.let { parsed ->
            runCatching { DocumentsContract.getDocumentId(parsed) }.getOrNull()
                ?: parsed.pathSegments.documentIdFromSafPath()
                ?: parsed.pathSegments
                    .takeIf { segments -> segments.firstOrNull() == "tree" }
                    ?.let { segments ->
                        runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
                            ?: segments.getOrNull(1)
                    }
        }
        ?: return null
    return authority.lowercase(Locale.ROOT) + "\u0000" + documentId
}

private fun rawSafAuthority(value: String): String? {
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0 || !value.regionMatches(0, "content", 0, schemeEnd, true)) {
        return null
    }
    val authorityStart = schemeEnd + 3
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
    return value.substring(
        authorityStart,
        if (authorityEnd >= 0) authorityEnd else value.length
    ).takeIf(String::isNotBlank)
}

private fun rawSafDocumentId(value: String): String? {
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null
    val pathStart = value.indexOf('/', schemeEnd + 3)
    if (pathStart < 0) return null
    val pathEnd = value.indexOfAny(charArrayOf('?', '#'), pathStart)
        .let { end -> if (end >= 0) end else value.length }
    val segments = value.substring(pathStart, pathEnd)
        .split('/')
        .filter(String::isNotEmpty)
    return when {
        segments.size >= 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
        segments.size >= 2 && segments[0] == "document" -> segments[1]
        segments.size >= 2 && segments[0] == "tree" -> segments[1]
        else -> null
    }
}

private fun List<String>.documentIdFromSafPath(): String? {
    return when {
        size >= 4 && this[0] == "tree" && this[2] == "document" -> this[3]
        size >= 2 && this[0] == "document" -> this[1]
        else -> null
    }
}

private fun isFileEntryInParent(entry: File, parent: File): Boolean {
    val canonicalParent = runCatching { parent.canonicalFile }.getOrNull() ?: return false
    val canonicalEntry = runCatching { entry.canonicalFile }.getOrNull() ?: return false
    return canonicalEntry.parentFile == canonicalParent
}

internal fun resolveManagedFileSubdirectory(root: File, desiredName: String): File {
    val exactDirectory = File(root, desiredName)
    if (exactDirectory.isDirectory) {
        return exactDirectory
    }
    root.listFiles()
        ?.firstOrNull { child ->
            child.isDirectory && child.name.equals(desiredName, ignoreCase = true)
        }
        ?.let { return it }
    if (exactDirectory.exists()) {
        throw IOException("下载目录子目录不是目录: ${exactDirectory.path}")
    }
    if (!exactDirectory.mkdirs() && !exactDirectory.isDirectory) {
        throw IOException("无法创建下载目录子目录: ${exactDirectory.path}")
    }
    return exactDirectory
}
