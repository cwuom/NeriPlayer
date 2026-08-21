package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import android.provider.DocumentsContract
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
            if (replace) {
                treeChildRegistry.findCanonicalExternalStorageChild(
                    context = context,
                    parent = parent,
                    displayName = desiredName,
                    isDirectory = false
                )?.let { canonical ->
                    return@withLock treeChildRegistry.toTreeDocumentFile(
                        context = context,
                        parent = parent,
                        child = canonical
                    ) ?: canonical
                }
            }
            if (
                !refresh.isComplete &&
                    !ManagedDownloadTreeNaming.canCreateWhenChildrenQueryIsIncomplete(parent.uri)
            ) {
                if (!replace) {
                    throw IOException("SAF 子项枚举不完整，拒绝创建文件: $desiredName")
                }
                when (
                    val probe = treeChildRegistry.probeCanonicalExternalStorageChild(
                        context = context,
                        parent = parent,
                        displayName = desiredName,
                        isDirectory = false
                    )
                ) {
                    is ManagedDownloadTreeChildRegistry.CanonicalExternalStorageChildProbe.Found -> {
                        return@withLock treeChildRegistry.toTreeDocumentFile(
                            context = context,
                            parent = parent,
                            child = probe.document
                        ) ?: probe.document
                    }

                    ManagedDownloadTreeChildRegistry.CanonicalExternalStorageChildProbe.Missing -> {
                        // a direct URI probe confirmed that the canonical target is absent
                    }

                    ManagedDownloadTreeChildRegistry.CanonicalExternalStorageChildProbe.Unknown -> {
                        throw IOException("SAF 子项枚举不完整，拒绝创建文件: $desiredName")
                    }
                }
            }

            val childNames = refresh.children.mapTo(mutableSetOf(), QueriedTreeChild::name)
            val finalName = if (replace) {
                desiredName
            } else {
                ManagedDownloadStorageNaming.createUniqueName(childNames, desiredName)
            }
            val createdUri = try {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parent.uri,
                    ManagedDownloadTreeNaming.documentCreateMimeType(finalName, mimeType),
                    finalName
                )
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(
                    tag,
                    "SAF 创建文件失败: name=$finalName, parent=${parent.uri}, " +
                        "error=${error.message}",
                    error
                )
                null
            }
            val resolvedCreated = createdUri
                ?.let { DocumentFile.fromSingleUri(context, it) }
                ?.let { createdFile ->
                    treeChildRegistry.toTreeDocumentFile(
                        context = context,
                        parent = parent,
                        child = createdFile
                    ) ?: createdFile
                }
                ?: run {
                    // provider may have committed an existing target before returning null
                    if (!replace) {
                        throw IOException("无法在下载目录创建文件: $finalName")
                    }
                    val existing = try {
                        parent.findFile(finalName)
                    } catch (security: SecurityException) {
                        throw security
                    } catch (_: Exception) {
                        null
                    }
                    existing?.takeIf { it.isFile }
                        ?: throw IOException("无法在下载目录创建文件: $finalName")
                }
            val storedName = resolvedTreeStoredName(resolvedCreated, finalName)
            if (!ManagedDownloadTreeNaming.isExactTreeStoredName(resolvedCreated.name, finalName)) {
                val canonicalChild = treeChildRegistry.refreshTreeChildrenWithStatus(
                    context = context,
                    parent = parent
                ).children.firstOrNull { child ->
                    !child.isDirectory &&
                        ManagedDownloadTreeNaming.isExactTreeStoredName(
                            child.name,
                            finalName
                        ) &&
                        child.documentUri != resolvedCreated.uri
                }
                if (canonicalChild != null) {
                    if (!deleteContentReference(context, resolvedCreated.uri.toString(), resolvedCreated.uri)) {
                        throw IOException("无法清理 SAF 重复文件: $storedName")
                    }
                    treeChildRegistry.forgetTreeChildName(parent, storedName)
                    return@withLock treeChildRegistry.toDocumentFile(
                        context = context,
                        parent = parent,
                        child = canonicalChild
                    ) ?: throw IOException("无法访问已存在的下载文件: ${canonicalChild.name}")
                }
                if (replace) {
                    val canonicalTarget = treeChildRegistry.findCanonicalExternalStorageChild(
                        context = context,
                        parent = parent,
                        displayName = finalName,
                        isDirectory = false
                    )
                    if (!deleteContentReference(context, resolvedCreated.uri.toString(), resolvedCreated.uri)) {
                        throw IOException("无法清理 SAF 覆写副本: $storedName")
                    }
                    treeChildRegistry.forgetTreeChildName(parent, storedName)
                    canonicalTarget?.let { target ->
                        return@withLock target
                    }
                    throw IOException(
                        "SAF 提供方拒绝使用目标文件名，无法安全覆写: " +
                            "expected=$finalName, actual=$storedName"
                    )
                }
                NPLogger.w(
                    tag,
                    "SAF 提供方返回了实际文件名称: expected=$finalName, actual=$storedName"
                )
            }
            resolvedCreated.also {
                treeChildRegistry.rememberTreeChild(
                    parent = parent,
                    child = QueriedTreeChild(
                        name = storedName,
                        documentUri = resolvedCreated.uri,
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
            .filter { child -> ManagedDownloadTreeNaming.isExactTreeStoredName(child.name, desiredName) }
            .minWithOrNull(
                compareBy<QueriedTreeChild>(
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
        try {
            deleteContentReference(context, target.uri.toString(), target.uri)
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(tag, "删除 SAF 临时文件失败: ${error.message}", error)
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
            val target = createRootFile(
                context = context,
                parent = parent,
                desiredName = finalName,
                mimeType = mimeType,
                replace = true
            )
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
