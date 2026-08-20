package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationTargetResolver
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import moe.ouom.neriplayer.core.logging.NPLogger

internal class ManagedDownloadCommitMigrationTargetResolver(
    private val treeChildRegistry: ManagedDownloadTreeChildRegistry,
    private val tag: String
) {
    fun resolveFileTarget(
        parent: File,
        displayName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry? = null
    ): StoredWriteResult {
        return ManagedDownloadMigrationTargetResolver.resolveFileTarget(
            parent = parent,
            displayName = displayName,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry,
            readExistingEntry = { existing ->
                existing.takeIf(File::isFile)?.let(ManagedDownloadStoredEntryMapper::fromFile)
            },
            reserveName = { reservedName -> treeChildRegistry.reserveUniqueFileChildName(parent, reservedName) },
            onReuseMetadata = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标 metadata: ${existingEntry.name}")
            },
            onReuseFile = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标文件: ${existingEntry.name}")
            }
        )
    }

    fun resolveTreeTarget(
        context: Context,
        parent: DocumentFile,
        displayName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry? = null
    ): StoredWriteResult {
        val metadataAudioName = ManagedDownloadTreeNaming.metadataAudioName(displayName)
        val existingChildEntry = treeChildRegistry.treeChildrenForWrite(context, parent)
            .children
            .asSequence()
            .filterNot(QueriedTreeChild::isDirectory)
            .filter { child ->
                child.name.equals(displayName, ignoreCase = true) ||
                    metadataAudioName?.let { audioName ->
                        ManagedDownloadTreeNaming.metadataAudioName(child.name)
                            ?.equals(audioName, ignoreCase = true) == true
                    } == true
            }
            .minWithOrNull(
                compareBy<QueriedTreeChild>(
                    { if (it.name.equals(displayName, ignoreCase = true)) 0 else 1 },
                    {
                        metadataAudioName?.let { audioName ->
                            ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, audioName)
                                ?: Int.MAX_VALUE
                        } ?: Int.MAX_VALUE
                    },
                    QueriedTreeChild::name
                )
            )
            ?.let(ManagedDownloadStoredEntryMapper::fromTreeChild)
        return ManagedDownloadMigrationTargetResolver.resolveTreeTarget(
            displayName = displayName,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry,
            existingChildEntry = existingChildEntry,
            reserveName = { reservedName -> treeChildRegistry.reserveUniqueTreeChildName(context, parent, reservedName) },
            onReuseMetadata = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标 SAF metadata: ${existingEntry.name}")
            },
            onReuseFile = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标 SAF 文件: ${existingEntry.name}")
            }
        )
    }
}
