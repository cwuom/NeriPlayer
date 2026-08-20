package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadTreeFileCommitter(
    private val treeChildRegistry: ManagedDownloadTreeChildRegistry,
    private val tag: String,
    private val deleteContentReference: (Context, String, android.net.Uri) -> Boolean,
    private val verifyDocumentCommittedLength: (Context, android.net.Uri, Long, String) -> Long
) {
    fun createRootFile(
        context: Context,
        parent: DocumentFile,
        desiredName: String,
        mimeType: String,
        replace: Boolean
    ): DocumentFile {
        return ManagedDownloadTreeMutationLocks.withLock(parent.uri) {
            val refresh = treeChildRegistry.treeChildrenForWrite(context, parent)
            val existingChild = findExistingChildForReplace(refresh.children, desiredName)
            if (replace && existingChild != null) {
                if (existingChild.isDirectory) {
                    throw IOException("下载目录同名项不是文件: ${existingChild.name}")
                }
                return@withLock treeChildRegistry.toDocumentFile(context, parent, existingChild)
                    ?: throw IOException("无法访问已存在的下载文件: ${existingChild.name}")
            }
            if (!refresh.isComplete) {
                throw IOException("SAF 子项枚举不完整，拒绝创建文件: $desiredName")
            }

            val childNames = refresh.children.mapTo(mutableSetOf(), QueriedTreeChild::name)
            val finalName = if (replace) {
                desiredName
            } else {
                ManagedDownloadStorageNaming.createUniqueName(childNames, desiredName)
            }
            val created = parent.createFile(
                ManagedDownloadTreeNaming.documentCreateMimeType(finalName, mimeType),
                finalName
            ) ?: throw IOException("无法在下载目录创建文件: $finalName")
            val storedName = resolvedTreeStoredName(created, finalName)
            if (replace && !ManagedDownloadTreeNaming.isExactTreeStoredName(created.name, finalName)) {
                if (!deleteContentReference(context, created.uri.toString(), created.uri)) {
                    runCatching { created.delete() }
                }
                treeChildRegistry.forgetTreeChildName(parent, finalName)
                throw IOException("SAF 覆写目标被自动改名，拒绝生成副本: $finalName")
            }
            created.also {
                treeChildRegistry.rememberTreeChild(
                    parent = parent,
                    child = QueriedTreeChild(
                        name = storedName,
                        documentUri = created.uri,
                        sizeBytes = 0L,
                        lastModifiedMs = System.currentTimeMillis(),
                        isDirectory = false
                    )
                )
            }
        }
    }

    private fun findExistingChildForReplace(
        children: Collection<QueriedTreeChild>,
        desiredName: String
    ): QueriedTreeChild? {
        return children.asSequence()
            .filter { child ->
                child.name.equals(desiredName, ignoreCase = true) ||
                    ManagedDownloadTreeNaming.matchesProviderNumberedName(child.name, desiredName)
            }
            .minWithOrNull(
                compareBy<QueriedTreeChild>(
                    { if (it.name.equals(desiredName, ignoreCase = true)) 0 else 1 },
                    { ManagedDownloadTreeNaming.providerNumberedNameOrdinal(it.name, desiredName) ?: Int.MAX_VALUE },
                    QueriedTreeChild::name
                )
            )
    }

    fun verifiedTreeStoredEntry(
        context: Context,
        target: DocumentFile,
        expectedName: String,
        expectedSizeBytes: Long,
        fallbackLastModifiedMs: Long,
        description: String
    ): ManagedDownloadStorage.StoredEntry {
        val storedName = resolvedTreeStoredName(target, expectedName)
        val verifiedSize = verifyDocumentCommittedLength(
            context,
            target.uri,
            expectedSizeBytes,
            description
        )
        return ManagedDownloadStoredEntryMapper.fromDocumentFile(
            documentFile = target,
            knownName = storedName,
            knownSizeBytes = verifiedSize,
            knownLastModifiedMs = target.lastModified().takeIf { it > 0L } ?: fallbackLastModifiedMs,
            knownIsDirectory = false
        ) ?: throw IOException("无法读取已写入的目录文件: $description")
    }

    fun discardTreeFile(
        context: Context,
        parent: DocumentFile,
        target: DocumentFile,
        expectedName: String
    ) {
        runCatching {
            deleteContentReference(context, target.uri.toString(), target.uri)
        }
        treeChildRegistry.forgetTreeChildName(parent, expectedName)
    }

    fun commitTreeAudioAfterRenameFailure(
        context: Context,
        parent: DocumentFile,
        pendingTarget: DocumentFile,
        pendingName: String,
        finalName: String,
        mimeType: String,
        tempFile: File,
        actualSizeBytes: Long,
        committedAtMs: Long
    ): ManagedDownloadStorage.StoredEntry {
        NPLogger.w(tag, "SAF 重命名失败，回退为直接写入最终文件: $finalName")
        return try {
            val target = parent.createFile(
                ManagedDownloadTreeNaming.documentCreateMimeType(finalName, mimeType),
                finalName
            ) ?: throw IOException("无法在下载目录创建文件: $finalName")
            val storedName = resolvedTreeStoredName(target, finalName)

            try {
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                    }
                } ?: throw IOException("无法打开下载目录输出流")
            } catch (error: Throwable) {
                deleteContentReference(context, target.uri.toString(), target.uri)
                throw error
            }

            if (storedName != finalName) {
                treeChildRegistry.forgetTreeChildName(parent, finalName)
            }
            val entry = verifiedTreeStoredEntry(
                context = context,
                target = target,
                expectedName = storedName,
                expectedSizeBytes = actualSizeBytes,
                fallbackLastModifiedMs = committedAtMs,
                description = finalName
            )
            treeChildRegistry.rememberTreeChild(parent, entry)
            entry
        } finally {
            deleteContentReference(context, pendingTarget.uri.toString(), pendingTarget.uri)
            treeChildRegistry.forgetTreeChildName(parent, pendingName)
        }
    }

    fun resolvedTreeStoredName(target: DocumentFile, expectedName: String): String {
        val resolvedName = ManagedDownloadTreeNaming.resolveTreeStoredName(target.name, expectedName)
        if (resolvedName != expectedName) {
            NPLogger.w(
                tag,
                "SAF 文件名与预期不一致: expected=$expectedName, actual=$resolvedName, uri=${target.uri}"
            )
        }
        return resolvedName
    }
}
