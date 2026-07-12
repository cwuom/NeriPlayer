package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming

internal data class ManagedMigrationEntryRef(
    val subdirectory: String?,
    val entry: ManagedDownloadStorage.StoredEntry
)

internal data class ManagedMigrationTargetIndex(
    val rootEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>,
    val coverEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>,
    val lyricEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>
) {
    fun namesFor(subdirectory: String?): Set<String> {
        return when (subdirectory) {
            null -> rootEntriesByName.keys
            COVER_SUBDIRECTORY -> coverEntriesByName.keys
            LYRIC_SUBDIRECTORY -> lyricEntriesByName.keys
            else -> emptySet()
        }
    }

    fun entryFor(subdirectory: String?, name: String): ManagedDownloadStorage.StoredEntry? {
        return when (subdirectory) {
            null -> rootEntriesByName[name]
            COVER_SUBDIRECTORY -> coverEntriesByName[name]
            LYRIC_SUBDIRECTORY -> lyricEntriesByName[name]
            else -> null
        }
    }
}

internal data class ManagedMigrationNamePlan(
    val targetNamesByReference: Map<String, String>
) {
    fun targetNameFor(entry: ManagedMigrationEntryRef): String {
        return targetNamesByReference[entry.entry.reference] ?: entry.entry.name
    }
}

internal object ManagedDownloadMigrationNamePlanner {
    fun buildNamePlan(
        entries: List<ManagedMigrationEntryRef>,
        targetIndex: ManagedMigrationTargetIndex
    ): ManagedMigrationNamePlan {
        val plannedNames = mutableMapOf<String, String>()
        val reservedRootNames = targetIndex.rootEntriesByName.keys.toMutableSet()
        val audioEntriesByName = entries
            .filter { it.subdirectory == null && it.entry.extension in audioExtensions }
            .associateBy { it.entry.name }

        audioEntriesByName.values
            .sortedBy { it.entry.name }
            .forEach { audioEntry ->
                val targetName = resolvePlannedMigrationName(
                    desiredName = audioEntry.entry.name,
                    sourceEntry = audioEntry.entry,
                    targetEntry = targetIndex.entryFor(null, audioEntry.entry.name),
                    reservedNames = reservedRootNames
                )
                plannedNames[audioEntry.entry.reference] = targetName
                val metadataName = audioEntry.entry.name + METADATA_SUFFIX
                val metadataTargetName = targetName + METADATA_SUFFIX
                entries.firstOrNull { candidate ->
                    candidate.subdirectory == null && candidate.entry.name == metadataName
                }?.let { metadataEntry ->
                    plannedNames[metadataEntry.entry.reference] = metadataTargetName
                    reservedRootNames += metadataTargetName
                }
            }

        return ManagedMigrationNamePlan(targetNamesByReference = plannedNames)
    }

    fun isEquivalentMigrationTarget(
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetEntry: ManagedDownloadStorage.StoredEntry
    ): Boolean {
        return sourceEntry.reference.isNotBlank() && sourceEntry.reference == targetEntry.reference
    }

    private fun resolvePlannedMigrationName(
        desiredName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        reservedNames: MutableSet<String>
    ): String {
        if (targetEntry == null && desiredName !in reservedNames) {
            reservedNames += desiredName
            return desiredName
        }
        if (targetEntry != null && isEquivalentMigrationTarget(sourceEntry, targetEntry)) {
            return desiredName
        }
        val resolvedName = ManagedDownloadStorageNaming.createUniqueName(reservedNames, desiredName)
        reservedNames += resolvedName
        return resolvedName
    }
}
