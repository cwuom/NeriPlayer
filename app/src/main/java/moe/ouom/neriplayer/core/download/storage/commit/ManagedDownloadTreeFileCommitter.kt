package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadTreeFileCommitter(
    private val treeChildRegistry: ManagedDownloadTreeChildRegistry,
    private val tag: String,
    private val verifyDocumentCommittedLength: (Context, android.net.Uri, Long, String) -> Long
) {
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
