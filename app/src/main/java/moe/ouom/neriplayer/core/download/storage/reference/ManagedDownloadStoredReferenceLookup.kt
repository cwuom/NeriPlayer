package moe.ouom.neriplayer.core.download.storage.reference

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle

internal object ManagedDownloadStoredReferenceLookup {
    suspend fun query(
        context: Context,
        root: ManagedDownloadRootHandle,
        reference: String,
        knownEntry: StoredEntry? = null
    ): StoredEntry? {
        return try {
            when (root) {
                is ManagedDownloadRootHandle.FileRoot -> queryFile(root, reference)
                is ManagedDownloadRootHandle.TreeRoot -> queryTree(context, root, reference, knownEntry)
            }?.takeIf { !it.isDirectory && (it.isPendingAudioWrite || it.extension in audioExtensions) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // 查询失败仍沿用 nullable 契约，缺失确认和删除授权由原有恢复流程另行检查
            null
        }
    }

    private suspend fun queryFile(
        root: ManagedDownloadRootHandle.FileRoot,
        reference: String
    ): StoredEntry? {
        val uri = reference.toUri()
        val path = when {
            reference.startsWith('/') -> reference
            uri.scheme.equals("file", ignoreCase = true) && uri.authority.isNullOrEmpty() -> uri.path
            else -> null
        } ?: return null
        val directory = root.dir.canonicalFile
        val file = File(path).absoluteFile
        val canonicalFile = file.canonicalFile
        if (!canonicalFile.path.startsWith("${directory.path}${File.separator}")) return null
        val stat = FileStorageBackend(directory).stat(
            StorageReference.FileRef(canonicalFile.relativeTo(directory).path)
        ) as? StorageLookupResult.Found ?: return null
        if (stat.value.isDirectory) return null
        return ManagedDownloadStoredEntryMapper.fromFile(file).copy(
            sizeBytes = stat.value.sizeBytes ?: 0L,
            lastModifiedMs = stat.value.lastModifiedMs ?: 0L,
            sizeKnown = stat.value.sizeBytes != null
        )
    }

    private suspend fun queryTree(
        context: Context,
        root: ManagedDownloadRootHandle.TreeRoot,
        reference: String,
        knownEntry: StoredEntry?
    ): StoredEntry? {
        val uri = reference.toUri()
        if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority != root.tree.uri.authority) {
            return null
        }
        val rootId = DocumentsContract.getDocumentId(root.tree.uri)
        val treeId = DocumentsContract.getTreeDocumentId(root.tree.uri)
        if (DocumentsContract.isTreeUri(uri) && DocumentsContract.getTreeDocumentId(uri) != treeId) {
            return null
        }
        val documentId = DocumentsContract.getDocumentId(uri)
        val scopedUri = DocumentsContract.buildDocumentUriUsingTree(root.tree.uri, documentId)
        val stat = SafStorageBackend(context).stat(StorageReference.SafRef(scopedUri))
            as? StorageLookupResult.Found ?: return null
        if (stat.value.isDirectory) return null
        val knownMember = knownEntry != null &&
            (knownEntry.reference == reference || knownEntry.mediaUri == reference)
        if (!knownMember) {
            // 冷引用只通过 Provider 路径确认归属，不从 opaque document id 猜文件名或目录
            val path = DocumentsContract.findDocumentPath(context.contentResolver, scopedUri)?.path
                ?: return null
            if (rootId !in path || path.lastOrNull() != documentId) return null
        }
        val name = stat.value.displayName.takeIf(String::isNotBlank)
            ?: knownEntry?.name?.takeIf { knownMember && it.isNotBlank() }
            ?: return null
        return ManagedDownloadStoredEntryMapper.fromTreeChild(
            name = name,
            documentReference = scopedUri.toString(),
            sizeBytes = stat.value.sizeBytes ?: 0L,
            lastModifiedMs = stat.value.lastModifiedMs ?: 0L,
            isDirectory = false,
            sizeKnown = stat.value.sizeBytes != null
        )
    }
}
