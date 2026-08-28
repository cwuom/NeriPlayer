package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafParentDocumentCache
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend.SafQueryResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.StorageTargetChangedException
import moe.ouom.neriplayer.core.download.storage.backend.StorageRenameResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationException
import moe.ouom.neriplayer.core.download.storage.migration.CopiedMigrationEntry
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementPlan

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

    /**
     * Restores a preserved target when verification fails. A missing backup is
     * treated as already restored so retries stay idempotent.
     */
    fun restoreMigrationReplacement(
        context: Context,
        root: ManagedDownloadRootHandle,
        copied: CopiedMigrationEntry
    ): Boolean {
        val backup = copied.replacementBackup ?: return true
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                val backupFile = backup.localFilePath?.let(::File)
                    ?: File(backup.reference)
                val targetFile = File(root.dir, copied.copiedEntry.name)
                if (!backupFile.exists()) return targetFile.exists()
                if (targetFile.exists() && !targetFile.delete()) return false
                runCatching {
                    java.nio.file.Files.move(backupFile.toPath(), targetFile.toPath())
                    targetFile.exists()
                }.getOrDefault(false)
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                val backupUri = runCatching { backup.mediaUri.toUri() }.getOrNull()
                    ?: return false
                val backupRef = StorageReference.SafRef(backupUri)
                val backend = SafStorageBackend(
                    context,
                    parentDocumentCache = safParentDocumentCache
                )
                val target = root.tree.findFile(copied.copiedEntry.name)
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
                        return false
                    }
                }
                val renamed = runBlocking(Dispatchers.IO) {
                    backend.rename(
                        moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef(
                            reference = backupRef,
                            externalReference = backupUri.toString()
                        ),
                        copied.copiedEntry.name
                    )
                }
                renamed is StorageRenameResult.Renamed
            }
        }
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
                val entry = writeSafEntry(
                    context = context,
                    parent = directory,
                    displayName = displayName,
                    mimeType = mimeType,
                    expectedSizeBytes = bytes.size.toLong()
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
                writeSafEntry(
                    context = context,
                    parent = root.tree,
                    displayName = displayName,
                    mimeType = "application/json",
                    expectedSizeBytes = encoded.size.toLong(),
                    expectedAbsent = expectedAbsent,
                    knownExistingReference = knownTargetEntry?.reference
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
        plan: ManagedMigrationReplacementPlan,
        onProgress: ((Long) -> Unit)?
    ): StoredWriteResult {
        if (plan.targetName != displayName) {
            throw ManagedDownloadMigrationException.targetChanged(
                "迁移替换计划目标名发生变化: $displayName"
            )
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
            sourceAuthoritative = true
        )
    }

    private fun writeTreeReplacementStream(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
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
        if (target != null && backupEntry != null) {
            val targetEntry = ManagedDownloadStoredEntryMapper.fromDocumentFile(target)
                ?: throw ManagedDownloadMigrationException.transient(
                    "SAF 迁移替换目标无法读取: $displayName"
                )
            return StoredWriteResult(
                entry = targetEntry,
                createdNew = false,
                replacementBackup = backupEntry,
                sourceAuthoritative = true
            )
        }
        val result = runBlocking(Dispatchers.IO) {
            backend.writeCreateOnlyRecoverable(
                target = StorageTarget.SafTarget(
                    parent = StorageReference.SafRef(parent.uri),
                    displayName = displayName,
                    mimeType = mimeType
                )
            ) { output ->
                ManagedDownloadCommitIo.copyStreamWithProgress(
                    input = input,
                    output = output,
                    bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
                    onProgress = onProgress
                )
            }
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
        val expectedSize = sourceEntry.sizeBytes.takeIf { it > 0L }
            ?: stat.sizeBytes
            ?: 0L
        val actualSize = stat.sizeBytes ?: expectedSize
        if (actualSize != expectedSize) {
            throw ManagedDownloadMigrationException.transient(
                "SAF 迁移替换大小不匹配: $displayName, expected=$expectedSize, actual=$actualSize"
            )
        }
        val entry = stat.toStoredEntry().copy(sizeBytes = actualSize)
        treeChildRegistry.rememberTreeChild(parent, entry)
        return StoredWriteResult(
            entry = entry,
            createdNew = backupEntry == null,
            replacementBackup = backupEntry,
            sourceAuthoritative = true
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
            createdNew = true
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
        writer: suspend (java.io.OutputStream) -> Unit
    ): ManagedDownloadStorage.StoredEntry {
        val backend = moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend(
            context,
            parentDocumentCache = safParentDocumentCache
        )
        val result = runBlocking(Dispatchers.IO) {
            val target = StorageTarget.SafTarget(
                parent = StorageReference.SafRef(parent.uri),
                displayName = displayName,
                mimeType = mimeType
            )
            if (expectedAbsent) {
                backend.writeCreateOnlyRecoverable(target = target, writer = writer)
            } else if (!knownExistingReference.isNullOrBlank()) {
                backend.replaceKnownRecoverable(
                    target = target,
                    existingReference = StorageReference.SafRef(
                        knownExistingReference.toUri()
                    ),
                    writer = writer
                )
            } else {
                backend.writeRecoverable(target = target, writer = writer)
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
        val verifiedSize = expectedSizeBytes?.let { expected ->
            val measured = stat.sizeBytes ?: runBlocking(Dispatchers.IO) {
                val measuredResult = backend.read(stat.reference) { input ->
                    ManagedDownloadCommitIo.countInputStreamBytes(
                        input,
                        STREAM_COPY_BUFFER_SIZE_BYTES
                    )
                }
                when (measuredResult) {
                    is moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult.Found -> {
                        measuredResult.value
                    }
                    else -> null
                }
            }
            if (measured != null && measured != expected) {
                throw IOException(
                    "SAF 写入大小不匹配: $displayName, expected=$expected, actual=$measured"
                )
            }
            measured ?: expected
        }
        val entry = stat.toStoredEntry().copy(sizeBytes = verifiedSize ?: stat.sizeBytes ?: 0L)
        treeChildRegistry.rememberTreeChild(parent, entry)
        return entry
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
            createdNew = true
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
        val expectedSize = sourceEntry.sizeBytes.takeIf { it > 0L } ?: copiedBytes
        if (stat.sizeBytes != null && stat.sizeBytes != expectedSize) {
            throw IOException(
                "SAF 迁移大小不匹配: $description, expected=$expectedSize, actual=${stat.sizeBytes}"
            )
        }
        val entry = stat.toStoredEntry().copy(
            sizeBytes = stat.sizeBytes ?: copiedBytes,
            // saf providers own the physical timestamp; source time stays in metadata
            lastModifiedMs = stat.lastModifiedMs ?: sourceEntry.lastModifiedMs
        )
        treeChildRegistry.rememberTreeChild(parent, entry)
        return StoredWriteResult(entry = entry, createdNew = true)
    }

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
