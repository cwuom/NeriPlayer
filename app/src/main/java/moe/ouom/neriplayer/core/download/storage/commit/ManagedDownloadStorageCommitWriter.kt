package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
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
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.logging.NPLogger

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
                writeFileEntry(root = root.dir, target = target, displayName = displayName) { output ->
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
                var copiedBytes = 0L
                val entry = writeSafEntry(
                    context = context,
                    parent = directory,
                    displayName = displayName,
                    mimeType = mimeType,
                    expectedSizeBytes = null
                ) { output ->
                    copiedBytes = input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                }
                entry.takeIf { it.sizeBytes > 0L || copiedBytes <= 0L }
                    ?: entry.copy(sizeBytes = copiedBytes)
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
                    expectedSizeBytes = encoded.size.toLong()
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

    private fun writeSafEntry(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long?,
        writer: suspend (java.io.OutputStream) -> Unit
    ): ManagedDownloadStorage.StoredEntry {
        val backend = moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend(context)
        val result = runBlocking(Dispatchers.IO) {
            backend.writeRecoverable(
                target = StorageTarget.SafTarget(
                    parent = StorageReference.SafRef(parent.uri),
                    displayName = displayName,
                    mimeType = mimeType
                ),
                writer = writer
            )
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
        var copiedBytes = 0L
        val backend = moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend(context)
        val result = runBlocking(Dispatchers.IO) {
            backend.writeRecoverable(
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
            is StorageWriteResult.ProviderFailure -> throw IOException(
                "SAF 迁移写入失败: $description",
                result.error
            )
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
