package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.MIGRATION_COPY_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_DELETE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_REWRITE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_TREE_COPY_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_TREE_DELETE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_TREE_REWRITE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming

internal object ManagedDownloadMigrationPolicy {
    fun mimeTypeFor(entry: ManagedMigrationEntryRef): String {
        return if (entry.subdirectory == null && entry.entry.name.endsWith(METADATA_SUFFIX)) {
            "application/json"
        } else {
            ManagedDownloadStorageNaming.mimeTypeFromName(entry.entry.name, null)
        }
    }

    fun copyParallelism(usesTreeRoot: Boolean): Int {
        return if (usesTreeRoot) {
            MIGRATION_TREE_COPY_PARALLELISM
        } else {
            MIGRATION_COPY_PARALLELISM
        }
    }

    fun rewriteParallelism(usesTreeRoot: Boolean): Int {
        return if (usesTreeRoot) {
            MIGRATION_TREE_REWRITE_PARALLELISM
        } else {
            MIGRATION_REWRITE_PARALLELISM
        }
    }

    fun deleteParallelism(usesTreeRoot: Boolean): Int {
        return if (usesTreeRoot) {
            MIGRATION_TREE_DELETE_PARALLELISM
        } else {
            MIGRATION_DELETE_PARALLELISM
        }
    }
}
