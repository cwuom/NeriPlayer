package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationTargetResolver
import moe.ouom.neriplayer.core.download.storage.migration.StoredWriteResult
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
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
            rememberName = { childName -> treeChildRegistry.rememberFileChildName(parent, childName) },
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
        val existingChildEntry = if (displayName in targetNames) {
            treeChildRegistry.cachedTreeChild(context, parent, displayName)
                ?.takeUnless(QueriedTreeChild::isDirectory)
                ?.let(ManagedDownloadStoredEntryMapper::fromTreeChild)
        } else {
            null
        }
        return ManagedDownloadMigrationTargetResolver.resolveTreeTarget(
            displayName = displayName,
            sourceEntry = sourceEntry,
            targetNames = targetNames,
            targetEntry = targetEntry,
            existingChildEntry = existingChildEntry,
            reserveName = { reservedName -> treeChildRegistry.reserveUniqueTreeChildName(context, parent, reservedName) },
            rememberName = { childName ->
                treeChildRegistry.rememberTreeChildName(parent, childName, isReservation = true)
            },
            onReuseMetadata = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标 SAF metadata: ${existingEntry.name}")
            },
            onReuseFile = { existingEntry ->
                NPLogger.d(tag, "迁移复用目标 SAF 文件: ${existingEntry.name}")
            }
        )
    }
}
