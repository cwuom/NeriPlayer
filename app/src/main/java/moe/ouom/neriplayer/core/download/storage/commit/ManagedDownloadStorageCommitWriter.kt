package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
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
    private val migrationPendingWriteNames = ManagedDownloadPendingAudioWriteNames()

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
                val dir = resolveManagedFileSubdirectory(root.dir, subdirectory)
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
                val dir = resolveManagedFileSubdirectory(root.dir, subdirectory)
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
                val output = runCatching {
                    context.contentResolver.openOutputStream(target.uri, "rwt")
                }.getOrElse {
                    context.contentResolver.openOutputStream(target.uri, "wt")
                } ?: throw IOException("无法写入元数据文件: $displayName")
                output.use { it.write(encoded) }
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
        onProgress: ((Long) -> Unit)?
    ): StoredWriteResult {
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
        val requestedPendingName = migrationPendingWriteNames.buildPendingAudioWriteName(finalName)
        var pendingTarget: DocumentFile? = null
        var pendingName = requestedPendingName
        try {
            val writtenAtMs = System.currentTimeMillis()
            val pending = treeFileCommitter.createRootFile(
                context = context,
                parent = parent,
                desiredName = requestedPendingName,
                mimeType = mimeType,
                replace = false
            )
            pendingTarget = pending
            pendingName = treeFileCommitter.resolvedTreeStoredName(
                pending,
                requestedPendingName
            )
            var copiedBytes = 0L
            context.contentResolver.openOutputStream(pending.uri, "w")?.use { output ->
                copiedBytes = ManagedDownloadCommitIo.copyStreamWithProgress(
                    input = input,
                    output = output,
                    bufferSizeBytes = STREAM_COPY_BUFFER_SIZE_BYTES,
                    onProgress = onProgress
                )
            } ?: throw IOException("无法写入 SAF 迁移临时文件: $finalName")
            val expectedSize = sourceEntry.sizeBytes.takeIf { it > 0L }
                ?: copiedBytes.coerceAtLeast(0L)
            treeFileCommitter.verifiedTreeStoredEntry(
                context = context,
                target = pending,
                expectedName = pendingName,
                expectedSizeBytes = expectedSize,
                fallbackLastModifiedMs = writtenAtMs,
                description = "迁移临时文件: $description"
            )
            if (!pending.renameTo(finalName)) {
                throw IOException("SAF 提供方不支持安全提交迁移文件: $finalName")
            }
            val committedTarget = DocumentFile.fromSingleUri(context, pending.uri) ?: pending
            val entry = treeFileCommitter.verifiedTreeStoredEntry(
                context = context,
                target = committedTarget,
                expectedName = finalName,
                expectedSizeBytes = expectedSize,
                fallbackLastModifiedMs = writtenAtMs,
                description = description
            )
            if (entry.name != finalName) {
                throw IOException("SAF 迁移提交后的文件名异常: expected=$finalName, actual=${entry.name}")
            }
            treeChildRegistry.forgetTreeChildName(parent, requestedPendingName)
            if (pendingName != requestedPendingName) {
                treeChildRegistry.forgetTreeChildName(parent, pendingName)
            }
            treeChildRegistry.rememberTreeChild(parent, entry)
            restoreLastModified(context, committedTarget.uri, sourceEntry.lastModifiedMs)
            return StoredWriteResult(
                entry = entry.copy(
                    lastModifiedMs = sourceEntry.lastModifiedMs.takeIf { it > 0L }
                        ?: entry.lastModifiedMs
                ),
                createdNew = true
            )
        } catch (error: Throwable) {
            pendingTarget?.let { target ->
                treeFileCommitter.discardTreeFile(
                    context = context,
                    parent = parent,
                    target = target,
                    expectedName = pendingName
                )
            }
            treeChildRegistry.forgetTreeChildName(parent, requestedPendingName)
            throw error
        }
    }

    private fun restoreLastModified(context: Context, uri: android.net.Uri, lastModifiedMs: Long) {
        if (lastModifiedMs <= 0L) {
            return
        }
        runCatching {
            context.contentResolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED, lastModifiedMs)
                },
                null,
                null
            )
        }
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
