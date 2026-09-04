package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.storage.MIGRATION_COPY_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_DELETE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_REWRITE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_TREE_COPY_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_TREE_DELETE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.MIGRATION_TREE_REWRITE_PARALLELISM
import moe.ouom.neriplayer.core.download.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal enum class ManagedDownloadDirectoryChangeDecision {
    APPLY_DIRECTLY,
    REATTACH_EXISTING_TARGET,
    CONFIRM_MIGRATION,
    CONFIRM_MIGRATION_WITH_NON_EMPTY_TARGET
}

internal object ManagedDownloadMigrationPolicy {
    fun requiresExplicitConfirmation(
        fromDirectoryUri: String?,
        toDirectoryUri: String?
    ): Boolean {
        return !ManagedDownloadDirectoryIdentity.areEquivalentDirectoryUris(
            fromDirectoryUri,
            toDirectoryUri
        ) && !(fromDirectoryUri.isNullOrBlank() && toDirectoryUri.isNullOrBlank())
    }

    fun shouldReattachExistingManagedDirectory(
        fromDirectoryUri: String?,
        toDirectoryUri: String?,
        sourceHasManagedEntries: Boolean?,
        targetHasManagedEntries: Boolean?
    ): Boolean {
        return fromDirectoryUri.isNullOrBlank() &&
            !toDirectoryUri.isNullOrBlank() &&
            sourceHasManagedEntries != true &&
            targetHasManagedEntries == true
    }

    suspend fun shouldReattachExistingManagedDirectoryAfterProbes(
        fromDirectoryUri: String?,
        toDirectoryUri: String?,
        probeSourceHasManagedEntries: suspend () -> Boolean,
        probeTargetHasManagedEntries: suspend () -> Boolean
    ): Boolean {
        if (!fromDirectoryUri.isNullOrBlank() || toDirectoryUri.isNullOrBlank()) {
            return false
        }
        val sourceHasManagedEntries = probeSourceHasManagedEntries()
        if (sourceHasManagedEntries) {
            return false
        }
        return shouldReattachExistingManagedDirectory(
            fromDirectoryUri = fromDirectoryUri,
            toDirectoryUri = toDirectoryUri,
            sourceHasManagedEntries = false,
            targetHasManagedEntries = probeTargetHasManagedEntries()
        )
    }

    suspend fun resolveDirectoryChangeAfterProbes(
        fromDirectoryUri: String?,
        toDirectoryUri: String?,
        probeSourceHasManagedEntries: suspend () -> Boolean,
        probeTargetHasManagedEntries: suspend () -> Boolean,
        probeTargetNonEmpty: suspend () -> Boolean = probeTargetHasManagedEntries
    ): ManagedDownloadDirectoryChangeDecision {
        if (ManagedDownloadDirectoryIdentity.areEquivalentDirectoryUris(
                fromDirectoryUri,
                toDirectoryUri
            )
        ) {
            return ManagedDownloadDirectoryChangeDecision.APPLY_DIRECTLY
        }

        if (probeSourceHasManagedEntries()) {
            val targetNonEmpty = probeTargetNonEmpty()
            return if (targetNonEmpty) {
                ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION_WITH_NON_EMPTY_TARGET
            } else {
                ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION
            }
        }

        if (fromDirectoryUri.isNullOrBlank() && probeTargetHasManagedEntries()) {
            return ManagedDownloadDirectoryChangeDecision.REATTACH_EXISTING_TARGET
        }

        return ManagedDownloadDirectoryChangeDecision.APPLY_DIRECTLY
    }

    fun mimeTypeFor(entry: ManagedMigrationEntryRef): String {
        return if (entry.subdirectory == null && ManagedDownloadTreeNaming.isMetadataName(entry.entry.name)) {
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
