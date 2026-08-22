package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.REMOTE_COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal data class ManagedMigrationEntryRef(
    val subdirectory: String?,
    val entry: ManagedDownloadStorage.StoredEntry
)

internal data class ManagedMigrationTargetIndex(
    val rootEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>,
    val coverEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>,
    val lyricEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry>,
    val remoteCoverEntriesByName: Map<String, ManagedDownloadStorage.StoredEntry> = emptyMap(),
    val metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata> = emptyMap()
) {
    fun namesFor(subdirectory: String?): Set<String> {
        return when (subdirectory) {
            null -> rootEntriesByName.keys
            COVER_SUBDIRECTORY -> coverEntriesByName.keys
            REMOTE_COVER_SUBDIRECTORY -> remoteCoverEntriesByName.keys
            LYRIC_SUBDIRECTORY -> lyricEntriesByName.keys
            else -> emptySet()
        }
    }

    fun entryFor(subdirectory: String?, name: String): ManagedDownloadStorage.StoredEntry? {
        return when (subdirectory) {
            null -> rootEntriesByName[name]
            COVER_SUBDIRECTORY -> coverEntriesByName[name]
            REMOTE_COVER_SUBDIRECTORY -> remoteCoverEntriesByName[name]
            LYRIC_SUBDIRECTORY -> lyricEntriesByName[name]
            else -> null
        }
    }

    fun entryByReference(reference: String?): ManagedDownloadStorage.StoredEntry? {
        val normalized = reference?.trim()?.takeIf(String::isNotBlank) ?: return null
        return sequenceOf(
            rootEntriesByName,
            coverEntriesByName,
            remoteCoverEntriesByName,
            lyricEntriesByName
        )
            .flatMap { it.values.asSequence() }
            .firstOrNull { entry ->
                entry.reference == normalized ||
                    entry.mediaUri == normalized ||
                    entry.localFilePath == normalized
            }
    }

    fun metadataEntryForAudioName(audioName: String): ManagedDownloadStorage.StoredEntry? {
        return rootEntriesByName.values
            .asSequence()
            .filter { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name)
                    ?.equals(audioName, ignoreCase = true) == true
            }
            .minWithOrNull(
                compareBy<ManagedDownloadStorage.StoredEntry>(
                    {
                        ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, audioName)
                            ?: Int.MAX_VALUE
                    },
                    ManagedDownloadStorage.StoredEntry::name
                )
            )
    }
}

internal data class ManagedMigrationNamePlan(
    val targetNamesByReference: Map<String, String>,
    val reusedTargetsByReference: Map<String, ManagedDownloadStorage.StoredEntry> = emptyMap()
) {
    fun targetNameFor(entry: ManagedMigrationEntryRef): String {
        return targetNamesByReference[entry.entry.reference] ?: entry.entry.name
    }

    fun reusedTargetFor(entry: ManagedMigrationEntryRef): ManagedDownloadStorage.StoredEntry? {
        return reusedTargetsByReference[entry.entry.reference]
    }
}

internal object ManagedDownloadMigrationNamePlanner {
    fun buildNamePlan(
        entries: List<ManagedMigrationEntryRef>,
        targetIndex: ManagedMigrationTargetIndex,
        sourceMetadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata> = emptyMap()
    ): ManagedMigrationNamePlan {
        val plannedNames = mutableMapOf<String, String>()
        val reusedTargets = mutableMapOf<String, ManagedDownloadStorage.StoredEntry>()
        val reservedRootNames = targetIndex.rootEntriesByName.keys.toMutableSet()
        val audioEntriesByName = entries
            .filter { it.subdirectory == null && it.entry.extension in audioExtensions }
            .associateBy { it.entry.name }

        audioEntriesByName.values
            .sortedBy { it.entry.name }
            .forEach { audioEntry ->
                val sourceMetadata = sourceMetadataByAudioName[audioEntry.entry.name]
                val duplicateTarget = sourceMetadata?.let { metadata ->
                    targetIndex.metadataByAudioName
                        .asSequence()
                        .filter { (_, targetMetadata) -> sameSong(metadata, targetMetadata) }
                        .mapNotNull { (targetAudioName, _) ->
                            targetIndex.rootEntriesByName[targetAudioName]
                        }
                        .sortedBy(ManagedDownloadStorage.StoredEntry::name)
                        .firstOrNull()
                }
                if (duplicateTarget != null) {
                    plannedNames[audioEntry.entry.reference] = duplicateTarget.name
                    reusedTargets[audioEntry.entry.reference] = duplicateTarget
                    metadataEntryForAudio(entries, audioEntry.entry.name)?.let { metadataEntry ->
                        metadataEntryForAudio(
                            targetIndex.rootEntriesByName.values.toList(),
                            duplicateTarget.name
                        )?.let { targetMetadataEntry ->
                            plannedNames[metadataEntry.entry.reference] = targetMetadataEntry.name
                            reusedTargets[metadataEntry.entry.reference] = targetMetadataEntry
                        }
                    }
                    reserveDuplicateSidecars(
                        sourceMetadata = sourceMetadata,
                        targetMetadata = targetIndex.metadataByAudioName[duplicateTarget.name],
                        entries = entries,
                        targetIndex = targetIndex,
                        plannedNames = plannedNames,
                        reusedTargets = reusedTargets
                    )
                    return@forEach
                }
                val targetName = resolvePlannedMigrationName(
                    desiredName = audioEntry.entry.name,
                    sourceEntry = audioEntry.entry,
                    targetEntry = targetIndex.entryFor(null, audioEntry.entry.name),
                    reservedNames = reservedRootNames
                )
                plannedNames[audioEntry.entry.reference] = targetName
                metadataEntryForAudio(entries, audioEntry.entry.name)?.let { metadataEntry ->
                    val existingMetadata = targetIndex.metadataEntryForAudioName(targetName)
                    if (existingMetadata != null) {
                        plannedNames[metadataEntry.entry.reference] = existingMetadata.name
                        reusedTargets[metadataEntry.entry.reference] = existingMetadata
                    } else {
                        val metadataTargetName = targetName + METADATA_SUFFIX
                        plannedNames[metadataEntry.entry.reference] = metadataTargetName
                        reservedRootNames += metadataTargetName
                    }
                }
            }

        entries.asSequence()
            .filter { entry ->
                entry.subdirectory == null &&
                    ManagedDownloadTreeNaming.isMetadataName(entry.entry.name) &&
                    entry.entry.reference !in plannedNames
            }
            .sortedBy { entry -> entry.entry.name }
            .forEach { metadataEntry ->
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(metadataEntry.entry.name)
                    ?: return@forEach
                val existingMetadata = targetIndex.metadataEntryForAudioName(audioName)
                if (existingMetadata != null) {
                    plannedNames[metadataEntry.entry.reference] = existingMetadata.name
                    reusedTargets[metadataEntry.entry.reference] = existingMetadata
                } else {
                    plannedNames[metadataEntry.entry.reference] = resolvePlannedMigrationName(
                        desiredName = metadataEntry.entry.name,
                        sourceEntry = metadataEntry.entry,
                        targetEntry = targetIndex.entryFor(null, metadataEntry.entry.name),
                        reservedNames = reservedRootNames
                    )
                }
            }

        return ManagedMigrationNamePlan(
            targetNamesByReference = plannedNames,
            reusedTargetsByReference = reusedTargets
        )
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

    private fun metadataEntryForAudio(
        entries: Collection<ManagedMigrationEntryRef>,
        audioName: String
    ): ManagedMigrationEntryRef? {
        return metadataEntryForAudio(
            entries.asSequence()
                .filter { it.subdirectory == null }
                .map(ManagedMigrationEntryRef::entry)
                .toList(),
            audioName
        )?.let { entry ->
            entries.firstOrNull { candidate -> candidate.entry.reference == entry.reference }
        }
    }

    private fun metadataEntryForAudio(
        entries: Collection<ManagedDownloadStorage.StoredEntry>,
        audioName: String
    ): ManagedDownloadStorage.StoredEntry? {
        return entries.asSequence()
            .filter { entry ->
                ManagedDownloadTreeNaming.metadataAudioName(entry.name) == audioName
            }
            .minWithOrNull(
                compareBy<ManagedDownloadStorage.StoredEntry>(
                    { ManagedDownloadTreeNaming.metadataNameOrdinal(it.name, audioName) ?: Int.MAX_VALUE },
                    { it.name }
                )
            )
    }

    private fun reserveDuplicateSidecars(
        sourceMetadata: ManagedDownloadStorage.DownloadedAudioMetadata,
        targetMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        entries: List<ManagedMigrationEntryRef>,
        targetIndex: ManagedMigrationTargetIndex,
        plannedNames: MutableMap<String, String>,
        reusedTargets: MutableMap<String, ManagedDownloadStorage.StoredEntry>
    ) {
        val sourcePaths = sidecarPaths(sourceMetadata)
        val targetPaths = sidecarPaths(targetMetadata)
        entries
            .filter { candidate ->
                candidate.subdirectory != null &&
                    (candidate.entry.name in sourcePaths ||
                        sourcePaths.any { path -> candidate.entry.reference == path })
            }
            .forEach { candidate ->
                val targetEntry = targetPaths.asSequence()
                    .mapNotNull(targetIndex::entryByReference)
                    .firstOrNull { entry ->
                        entry.name == candidate.entry.name ||
                            entry.nameWithoutExtension == candidate.entry.nameWithoutExtension
                    }
                if (targetEntry != null) {
                    plannedNames[candidate.entry.reference] = targetEntry.name
                    reusedTargets[candidate.entry.reference] = targetEntry
                }
            }
    }

    private fun sidecarPaths(metadata: ManagedDownloadStorage.DownloadedAudioMetadata?): Set<String> {
        if (metadata == null) return emptySet()
        return buildSet {
            metadata.coverPath?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            metadata.lyricPath?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            metadata.translatedLyricPath?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            metadata.romanizedLyricPath?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun sameSong(
        source: ManagedDownloadStorage.DownloadedAudioMetadata,
        target: ManagedDownloadStorage.DownloadedAudioMetadata
    ): Boolean {
        val sourceStableKey = source.stableKey?.trim()?.takeIf(String::isNotBlank)
        val targetStableKey = target.stableKey?.trim()?.takeIf(String::isNotBlank)
        if (sourceStableKey != null && sourceStableKey == targetStableKey) {
            return true
        }
        val sourceSongId = source.songId?.takeIf { it > 0L }
        val targetSongId = target.songId?.takeIf { it > 0L }
        val sourceAlbum = source.identityAlbum?.trim()?.takeIf(String::isNotBlank)
        val targetAlbum = target.identityAlbum?.trim()?.takeIf(String::isNotBlank)
        if (
            sourceSongId != null && sourceSongId == targetSongId &&
            sourceAlbum != null && sourceAlbum == targetAlbum
        ) {
            return true
        }
        val sourceMediaUri = source.mediaUri?.trim()?.takeIf(String::isNotBlank)
        val targetMediaUri = target.mediaUri?.trim()?.takeIf(String::isNotBlank)
        return sourceMediaUri != null && sourceMediaUri == targetMediaUri
    }
}
