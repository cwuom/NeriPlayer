package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories

internal class ManagedDownloadStorageCommitWriter(
    private val treeChildRegistry: ManagedDownloadTreeChildRegistry,
    private val treeDirectories: ManagedDownloadTreeDirectories,
    private val treeFileCommitter: ManagedDownloadTreeFileCommitter,
    private val tag: String
) {
    private val migrationTargetResolver = ManagedDownloadCommitMigrationTargetResolver(
        treeChildRegistry = treeChildRegistry,
        tag = tag
    )

    fun writeMigrationRootStream(
        context: Context,
        root: ManagedDownloadRootHandle,
        displayName: String,
        mimeType: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry? = null,
        onProgress: ((Long) -> Unit)? = null
    ): StoredWriteResult {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> writeMigrationFileRootStream(
                root = root,
                displayName = displayName,
                input = input,
                sourceEntry = sourceEntry,
                targetNames = targetNames,
                targetEntry = targetEntry,
                onProgress = onProgress
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
                onProgress = onProgress
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
                val dir = File(root.dir, subdirectory).apply { mkdirs() }
                treeDirectories.ensureManagedMediaScanIsolation(subdirectory, dir)
                val target = File(dir, displayName)
                target.outputStream().use { it.write(bytes) }
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
                val target = treeFileCommitter.createRootFile(
                    context = context,
                    parent = directory,
                    desiredName = displayName,
                    mimeType = mimeType,
                    replace = true
                )
                val writtenAtMs = System.currentTimeMillis()
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(bytes)
                } ?: throw IOException("无法写入目录文件: $displayName")
                treeFileCommitter.verifiedTreeStoredEntry(
                    context = context,
                    target = target,
                    expectedName = displayName,
                    expectedSizeBytes = bytes.size.toLong(),
                    fallbackLastModifiedMs = writtenAtMs,
                    description = displayName
                ).also { entry -> treeChildRegistry.rememberTreeChild(directory, entry) }
            }
        }
    }

    fun writeSubdirectoryFile(
        context: Context,
        root: ManagedDownloadRootHandle,
        subdirectory: String,
        displayName: String,
        sourceFile: File,
        mimeType: String
    ): ManagedDownloadStorage.StoredEntry? {
        if (!sourceFile.exists()) {
            return null
        }
        sourceFile.inputStream().use { input ->
            return writeSubdirectoryStream(
                context = context,
                root = root,
                subdirectory = subdirectory,
                displayName = displayName,
                mimeType = mimeType,
                input = input
            )
        }
    }

    fun writeSubdirectoryStream(
        context: Context,
        root: ManagedDownloadRootHandle,
        subdirectory: String,
        displayName: String,
        mimeType: String,
        input: InputStream
    ): ManagedDownloadStorage.StoredEntry {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                val dir = File(root.dir, subdirectory).apply { mkdirs() }
                treeDirectories.ensureManagedMediaScanIsolation(subdirectory, dir)
                val target = File(dir, displayName)
                var copiedBytes = 0L
                target.outputStream().use { output ->
                    copiedBytes = input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                }
                val verifiedSize = ManagedDownloadCommitIo.verifyFileCommittedLength(
                    target = target,
                    expectedSizeBytes = copiedBytes,
                    description = displayName
                )
                ManagedDownloadStoredEntryMapper.fromFile(target).copy(sizeBytes = verifiedSize)
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                val directory = treeDirectories.findOrCreateDirectory(context, root.tree, subdirectory)
                    ?: throw IOException("无法创建目录: $subdirectory")
                treeDirectories.ensureManagedMediaScanIsolation(context, subdirectory, directory)
                val target = treeFileCommitter.createRootFile(
                    context = context,
                    parent = directory,
                    desiredName = displayName,
                    mimeType = mimeType,
                    replace = true
                )
                val writtenAtMs = System.currentTimeMillis()
                var copiedBytes = 0L
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    copiedBytes = input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                } ?: throw IOException("无法写入目录文件: $displayName")
                val entry = treeFileCommitter.verifiedTreeStoredEntry(
                    context = context,
                    target = target,
                    expectedName = displayName,
                    expectedSizeBytes = copiedBytes.coerceAtLeast(0L),
                    fallbackLastModifiedMs = writtenAtMs,
                    description = displayName
                )
                treeChildRegistry.rememberTreeChild(directory, entry)
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
        onProgress: ((Long) -> Unit)? = null
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
                onProgress = onProgress
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
                onProgress = onProgress
            )
        }
    }

    fun writeRootText(
        context: Context,
        root: ManagedDownloadRootHandle,
        displayName: String,
        content: String
    ): ManagedDownloadStorage.StoredEntry? {
        return when (root) {
            is ManagedDownloadRootHandle.FileRoot -> {
                val target = File(root.dir, displayName)
                val encoded = content.toByteArray(Charsets.UTF_8)
                target.writeBytes(encoded)
                val verifiedSize = ManagedDownloadCommitIo.verifyFileCommittedLength(
                    target = target,
                    expectedSizeBytes = encoded.size.toLong(),
                    description = displayName
                )
                ManagedDownloadStoredEntryMapper.fromFile(target).copy(sizeBytes = verifiedSize)
            }

            is ManagedDownloadRootHandle.TreeRoot -> {
                val target = treeFileCommitter.createRootFile(
                    context = context,
                    parent = root.tree,
                    desiredName = displayName,
                    mimeType = "application/json",
                    replace = true
                )
                val writtenAtMs = System.currentTimeMillis()
                val encoded = content.toByteArray(Charsets.UTF_8)
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(encoded)
                } ?: throw IOException("无法写入元数据文件: $displayName")
                val entry = treeFileCommitter.verifiedTreeStoredEntry(
                    context = context,
                    target = target,
                    expectedName = displayName,
                    expectedSizeBytes = encoded.size.toLong(),
                    fallbackLastModifiedMs = writtenAtMs,
                    description = displayName
                )
                treeChildRegistry.rememberTreeChild(root.tree, entry)
                entry
            }
        }
    }

    private fun writeMigrationFileRootStream(
        root: ManagedDownloadRootHandle.FileRoot,
        displayName: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        onProgress: ((Long) -> Unit)?
    ): StoredWriteResult {
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
        var copiedBytes = 0L
        targetFile.outputStream().use { output ->
            copiedBytes = ManagedDownloadCommitIo.copyStreamWithProgress(
                input = input,
                output = output,
                bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
                onProgress = onProgress
            )
        }
        val verifiedSize = ManagedDownloadCommitIo.verifyFileCommittedLength(
            target = targetFile,
            expectedSizeBytes = copiedBytes,
            description = target.entry.name
        )
        return StoredWriteResult(
            entry = ManagedDownloadStoredEntryMapper.fromFile(targetFile).copy(sizeBytes = verifiedSize),
            createdNew = true
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
        onProgress: ((Long) -> Unit)?
    ): StoredWriteResult {
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
        val target = treeFileCommitter.createRootFile(context, root.tree, targetPlan.entry.name, mimeType, replace = true)
        val writtenAtMs = System.currentTimeMillis()
        var copiedBytes = 0L
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            copiedBytes = ManagedDownloadCommitIo.copyStreamWithProgress(
                input = input,
                output = output,
                bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
                onProgress = onProgress
            )
        } ?: throw IOException("无法写入根目录文件: ${targetPlan.entry.name}")
        val expectedSize = if (sourceEntry.sizeBytes > 0L) {
            sourceEntry.sizeBytes
        } else {
            copiedBytes.coerceAtLeast(0L)
        }
        val entry = treeFileCommitter.verifiedTreeStoredEntry(
            context = context,
            target = target,
            expectedName = targetPlan.entry.name,
            expectedSizeBytes = expectedSize,
            fallbackLastModifiedMs = writtenAtMs,
            description = displayName
        )
        treeChildRegistry.rememberTreeChild(root.tree, entry)
        return StoredWriteResult(entry = entry, createdNew = true)
    }

    private fun writeMigrationFileSubdirectoryStream(
        root: ManagedDownloadRootHandle.FileRoot,
        subdirectory: String,
        displayName: String,
        input: InputStream,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        onProgress: ((Long) -> Unit)?
    ): StoredWriteResult {
        val dir = File(root.dir, subdirectory).apply { mkdirs() }
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
        var copiedBytes = 0L
        targetFile.outputStream().use { output ->
            copiedBytes = ManagedDownloadCommitIo.copyStreamWithProgress(
                input = input,
                output = output,
                bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
                onProgress = onProgress
            )
        }
        val verifiedSize = ManagedDownloadCommitIo.verifyFileCommittedLength(
            target = targetFile,
            expectedSizeBytes = copiedBytes,
            description = target.entry.name
        )
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
        onProgress: ((Long) -> Unit)?
    ): StoredWriteResult {
        val directory = treeDirectories.findOrCreateDirectory(context, root.tree, subdirectory)
            ?: throw IOException("无法创建目录: $subdirectory")
        treeDirectories.ensureManagedMediaScanIsolation(context, subdirectory, directory)
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
        val target = treeFileCommitter.createRootFile(context, directory, targetPlan.entry.name, mimeType, replace = true)
        val writtenAtMs = System.currentTimeMillis()
        var copiedBytes = 0L
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            copiedBytes = ManagedDownloadCommitIo.copyStreamWithProgress(
                input = input,
                output = output,
                bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
                onProgress = onProgress
            )
        } ?: throw IOException("无法写入目录文件: ${targetPlan.entry.name}")
        val entry = treeFileCommitter.verifiedTreeStoredEntry(
            context = context,
            target = target,
            expectedName = targetPlan.entry.name,
            expectedSizeBytes = copiedBytes.coerceAtLeast(0L),
            fallbackLastModifiedMs = writtenAtMs,
            description = displayName
        )
        treeChildRegistry.rememberTreeChild(directory, entry)
        return StoredWriteResult(entry = entry, createdNew = true)
    }

}
