package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import java.io.File

internal object ManagedDownloadMigrationTargetResolver {
    fun resolveFileTarget(
        parent: File,
        displayName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        readExistingEntry: (File) -> ManagedDownloadStorage.StoredEntry?,
        reserveName: (String) -> String,
        onReuseMetadata: (ManagedDownloadStorage.StoredEntry) -> Unit,
        onReuseFile: (ManagedDownloadStorage.StoredEntry) -> Unit,
        preloadedMetadataCandidates: Collection<ManagedDownloadStorage.StoredEntry>? = null,
        preloadedExistingEntry: ManagedDownloadStorage.StoredEntry? = null
    ): StoredWriteResult {
        val existing = File(parent, displayName)
        val alternateMetadataEntry = if (preloadedMetadataCandidates != null) {
            findAlternateMetadataEntry(
                sourceEntry = sourceEntry,
                displayName = displayName,
                candidateEntries = preloadedMetadataCandidates
            )
        } else {
            // metadata alternates are relevant only for metadata entries. Avoid
            // touching every target file for ordinary audio and sidecar files
            val sourceAudioName = ManagedDownloadTreeNaming.metadataAudioName(sourceEntry.name)
            if (sourceAudioName == null) {
                null
            } else {
                findAlternateMetadataEntry(
                    sourceEntry = sourceEntry,
                    displayName = displayName,
                    candidateEntries = targetNames.asSequence()
                        .filter { name -> !name.equals(displayName, ignoreCase = true) }
                        .filter { name ->
                            ManagedDownloadTreeNaming.metadataAudioName(name)
                                ?.equals(sourceAudioName, ignoreCase = true) == true
                        }
                        .mapNotNull { name -> readExistingEntry(File(parent, name)) }
                        .toList()
                )
            }
        }
        if (displayName in targetNames || existing.exists() || alternateMetadataEntry != null) {
            reusedMetadataTarget(sourceEntry, targetEntry)
                ?.let { existingEntry ->
                    onReuseMetadata(existingEntry)
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            alternateMetadataEntry?.let { existingEntry ->
                onReuseMetadata(existingEntry)
                return StoredWriteResult(entry = existingEntry, createdNew = false)
            }
            reusedEquivalentTarget(
                sourceEntry,
                targetEntry,
                preloadedExistingEntry ?: readExistingEntry(existing)
            )
                ?.let { existingEntry ->
                    onReuseFile(existingEntry)
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            return plannedWriteResult(reserveName(displayName))
        }
        return plannedWriteResult(reserveName(displayName))
    }

    fun resolveTreeTarget(
        displayName: String,
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetNames: Set<String>,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        existingChildEntry: ManagedDownloadStorage.StoredEntry?,
        reserveName: (String) -> String,
        onReuseMetadata: (ManagedDownloadStorage.StoredEntry) -> Unit,
        onReuseFile: (ManagedDownloadStorage.StoredEntry) -> Unit
    ): StoredWriteResult {
        val alternateMetadataEntry = findAlternateMetadataEntry(
            sourceEntry = sourceEntry,
            displayName = displayName,
            candidateEntries = listOfNotNull(existingChildEntry)
        )
        if (displayName in targetNames || existingChildEntry != null) {
            reusedMetadataTarget(sourceEntry, targetEntry)
                ?.let { existingEntry ->
                    onReuseMetadata(existingEntry)
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            alternateMetadataEntry?.let { existingEntry ->
                onReuseMetadata(existingEntry)
                return StoredWriteResult(entry = existingEntry, createdNew = false)
            }
            reusedEquivalentTarget(sourceEntry, targetEntry, existingChildEntry)
                ?.let { existingEntry ->
                    onReuseFile(existingEntry)
                    return StoredWriteResult(entry = existingEntry, createdNew = false)
                }
            return plannedWriteResult(reserveName(displayName))
        }
        return plannedWriteResult(reserveName(displayName))
    }

    private fun reusedMetadataTarget(
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetEntry: ManagedDownloadStorage.StoredEntry?
    ): ManagedDownloadStorage.StoredEntry? {
        if (!ManagedDownloadTreeNaming.isMetadataName(sourceEntry.name)) {
            return null
        }
        return targetEntry?.takeIf { target ->
            sourceEntry.reference.isNotBlank() && sourceEntry.reference == target.reference
        }
    }

    private fun reusedEquivalentTarget(
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        targetEntry: ManagedDownloadStorage.StoredEntry?,
        existingEntry: ManagedDownloadStorage.StoredEntry?
    ): ManagedDownloadStorage.StoredEntry? {
        return targetEntry
            ?.takeIf { entry -> ManagedDownloadMigrationNamePlanner.isEquivalentMigrationTarget(sourceEntry, entry) }
            ?: existingEntry
                ?.takeIf { entry -> ManagedDownloadMigrationNamePlanner.isEquivalentMigrationTarget(sourceEntry, entry) }
    }

    private fun findAlternateMetadataEntry(
        sourceEntry: ManagedDownloadStorage.StoredEntry,
        displayName: String,
        candidateEntries: Collection<ManagedDownloadStorage.StoredEntry>
    ): ManagedDownloadStorage.StoredEntry? {
        val sourceAudioName = ManagedDownloadTreeNaming.metadataAudioName(sourceEntry.name)
            ?: return null
        return candidateEntries
            .asSequence()
            .filter { entry -> !entry.name.equals(displayName, ignoreCase = true) }
            .filter { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                    ?.equals(sourceAudioName, ignoreCase = true) == true
            }
            .minWithOrNull(
                compareBy<ManagedDownloadStorage.StoredEntry>(
                    {
                        ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, sourceAudioName)
                            ?: Int.MAX_VALUE
                    },
                    ManagedDownloadStorage.StoredEntry::name
                )
            )
    }

    private fun plannedWriteResult(displayName: String): StoredWriteResult {
        return StoredWriteResult(
            entry = ManagedDownloadStorage.StoredEntry(
                name = displayName,
                reference = "",
                mediaUri = "",
                localFilePath = null,
                sizeBytes = 0L,
                lastModifiedMs = 0L,
                isDirectory = false
            ),
            createdNew = true
        )
    }
}
