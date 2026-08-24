package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.backend.StorageWriteResult
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadTreeFileCommitter(
    private val treeChildRegistry: ManagedDownloadTreeChildRegistry,
    private val tag: String,
    private val deleteTrustedReference: (Context, TrustedManagedRef) -> StorageMutationResult,
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
            if (
                !refresh.isComplete &&
                    !ManagedDownloadTreeNaming.canCreateWhenChildrenQueryIsIncomplete(parent.uri)
            ) {
                if (!replace) {
                    throw IOException("SAF 子项枚举不完整，拒绝创建文件: $desiredName")
                }
                throw IOException("SAF 子项枚举不完整，拒绝创建文件: $desiredName")
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
                if (!shouldRetryCreateAfterFailure(error)) {
                    throw IOException("SAF 创建文件失败，Provider 状态未知: $finalName", error)
                }
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
                    val existing = treeChildRegistry
                        .refreshTreeChildrenWithStatus(context, parent)
                        .children
                        .firstOrNull { child ->
                            !child.isDirectory &&
                                ManagedDownloadTreeNaming.isExactTreeStoredName(
                                    child.name,
                                    finalName
                                )
                        }
                        ?.let { child ->
                            treeChildRegistry.toDocumentFile(context, parent, child)
                        }
                    existing ?: throw IOException("无法在下载目录创建文件: $finalName")
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
                    if (!deleteConfirmed(context, resolvedCreated.uri)) {
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
                    if (!deleteConfirmed(context, resolvedCreated.uri)) {
                        throw IOException("无法清理 SAF 覆写副本: $storedName")
                    }
                    treeChildRegistry.forgetTreeChildName(parent, storedName)
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
            deleteConfirmed(context, target.uri)
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
            val result = runBlocking(Dispatchers.IO) {
                SafStorageBackend(context).writeRecoverable(
                    target = StorageTarget.SafTarget(
                        parent = StorageReference.SafRef(parent.uri),
                        displayName = finalName,
                        mimeType = mimeType
                    )
                ) { output ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(output, STREAM_COPY_BUFFER_SIZE_BYTES)
                    }
                }
            }
            val stat = when (result) {
                is StorageWriteResult.Written -> result.stat
                StorageWriteResult.Missing -> throw IOException("SAF 目标不存在: $finalName")
                StorageWriteResult.OutOfScope -> throw IOException("SAF 目标越界: $finalName")
                StorageWriteResult.PermissionLost -> throw SecurityException("SAF 写入权限丢失: $finalName")
                is StorageWriteResult.ProviderFailure -> throw IOException(
                    "SAF 回退写入失败: $finalName",
                    result.error
                )
                is StorageWriteResult.Unsupported -> throw IOException(
                    "SAF 不支持回退写入: $finalName (${result.operation})"
                )
            }
            val targetUri = (stat.reference as? StorageReference.SafRef)?.uri
                ?: throw IOException("SAF 回退写入未返回文档 URI: $finalName")
            val target = DocumentFile.fromSingleUri(context, targetUri)
                ?: throw IOException("无法访问回退写入后的文件: $finalName")
            val storedName = stat.displayName
            if (stat.sizeBytes != null && stat.sizeBytes != actualSizeBytes) {
                throw IOException("SAF 回退写入大小不匹配: $finalName")
            }
            if (storedName != finalName) {
                treeChildRegistry.forgetTreeChildName(parent, finalName)
            }
            val entry = ManagedDownloadStoredEntryMapper.fromDocumentFile(
                documentFile = target,
                knownName = storedName,
                knownSizeBytes = stat.sizeBytes ?: actualSizeBytes,
                knownLastModifiedMs = stat.lastModifiedMs ?: committedAtMs,
                knownIsDirectory = false
            ) ?: throw IOException("无法读取回退写入后的文件: $finalName")
            treeChildRegistry.rememberTreeChild(parent, entry)
            entry
        } finally {
            deleteConfirmed(context, pendingTarget.uri)
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

    private fun deleteConfirmed(context: Context, uri: android.net.Uri): Boolean {
        val reference = TrustedManagedRef(
            reference = StorageReference.SafRef(uri),
            externalReference = uri.toString()
        )
        return when (deleteTrustedReference(context, reference)) {
            StorageMutationResult.Deleted,
            StorageMutationResult.Missing -> true
            StorageMutationResult.OutOfScope,
            StorageMutationResult.PermissionLost,
            is StorageMutationResult.ProviderFailure,
            is StorageMutationResult.Unsupported -> false
        }
    }
}

internal fun shouldRetryCreateAfterFailure(error: Throwable): Boolean {
    return ManagedDownloadReferenceIo.isMissingDocumentFailure(error)
}
