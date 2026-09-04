package moe.ouom.neriplayer.core.download.storage.lookup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.remoteDownloadIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport

internal data class ManagedDownloadAudioLookupResult(
    val entry: ManagedDownloadStorage.StoredEntry,
    val hitType: String
)

internal object ManagedDownloadStorageLookup {
    fun selectCanonicalAudioEntries(
        audioEntries: List<ManagedDownloadStorage.StoredEntry>,
        metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>
    ): List<ManagedDownloadStorage.StoredEntry> {
        val grouped = audioEntries
            .mapNotNull { entry ->
                metadataForEntry(metadataByAudioName, entry)
                    ?.stableKey
                    ?.takeIf(String::isNotBlank)
                    ?.let { stableKey -> stableKey to entry }
            }
            .groupBy({ it.first }, { it.second })
        val winners = grouped.values
            .filter { it.size > 1 }
            .mapTo(hashSetOf()) { entries ->
                entries.sortedWith(
                    compareByDescending<ManagedDownloadStorage.StoredEntry> { entry ->
                        isFinalizedDownloadedMetadata(metadataForEntry(metadataByAudioName, entry))
                    }
                        .thenBy { entry ->
                            if (entries.any { candidate ->
                                    candidate.name.substringBeforeLast('.') ==
                                        entry.name.substringBeforeLast('.')
                                }
                            ) {
                                providerNumberedOrdinal(entry, entries)
                            } else {
                                0
                            }
                        }
                        .thenByDescending { entry -> entry.sizeBytes }
                        .thenByDescending { entry -> entry.lastModifiedMs }
                        .thenBy { entry -> entry.name }
                ).first()
            }
        if (winners.isEmpty()) return audioEntries
        return audioEntries.filter { entry ->
            val stableKey = metadataForEntry(metadataByAudioName, entry)
                ?.stableKey
                ?.takeIf(String::isNotBlank)
            val groupSize = stableKey?.let { grouped[it]?.size ?: 0 } ?: 0
            stableKey == null || groupSize <= 1 || entry in winners
        }
    }

    private fun providerNumberedOrdinal(
        entry: ManagedDownloadStorage.StoredEntry,
        entries: List<ManagedDownloadStorage.StoredEntry>
    ): Int {
        return entries.asSequence()
            .mapNotNull { expected ->
                ManagedDownloadTreeNaming.providerNumberedNameOrdinal(
                    actualName = entry.name,
                    expectedName = expected.name
                )
            }
            .minOrNull() ?: 0
    }

    fun findAudioEntry(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        song: SongItem,
        fileNameTemplate: String?
    ): ManagedDownloadAudioLookupResult? {
        val identity = song.remoteDownloadIdentityOrNull() ?: song.identity()
        val stableKey = identity.stableKey()
        val remoteTrackKey = ManagedDownloadSnapshotIndex.buildRemoteTrackKey(
            song.channelId,
            song.audioId,
            song.subAudioId
        )
        val requiresVerifiedRemoteIdentity = song.requiresVerifiedRemoteDownloadIdentity(
            remoteTrackKey = remoteTrackKey
        )

        val localReferences = listOfNotNull(song.localFilePath, song.mediaUri)
            .filter { it.startsWith("/") || it.startsWith("content://", ignoreCase = true) }
            .distinct()
        localReferences.firstNotNullOfOrNull { reference ->
            snapshot.audioEntriesByLookupKey[reference]
        }?.let { return ManagedDownloadAudioLookupResult(it, "localReference") }

        if (requiresVerifiedRemoteIdentity) {
            snapshot.audioEntriesByStableKey[stableKey]
                ?.let { matches ->
                    pickBestAudioEntry(matches, song, fileNameTemplate, snapshot.metadataByAudioName)
                        ?.let { return ManagedDownloadAudioLookupResult(it, "stableKey") }
                }

            remoteTrackKey?.let { key ->
                snapshot.audioEntriesByRemoteTrackKey[key]
                    ?.let { matches ->
                        pickBestAudioEntry(matches, song, fileNameTemplate, snapshot.metadataByAudioName)
                            ?.let { return ManagedDownloadAudioLookupResult(it, "remoteTrackKey") }
                    }
            }
        }

        if (!requiresVerifiedRemoteIdentity) {
            snapshot.audioEntriesByStableKey[stableKey]
                ?.let { matches ->
                    pickBestAudioEntry(matches, song, fileNameTemplate, snapshot.metadataByAudioName)
                        ?.let { return ManagedDownloadAudioLookupResult(it, "stableKey") }
                }
        }

        if (!requiresVerifiedRemoteIdentity) {
            remoteTrackKey?.let { key ->
                snapshot.audioEntriesByRemoteTrackKey[key]
                    ?.let { matches ->
                        pickBestAudioEntry(matches, song, fileNameTemplate, snapshot.metadataByAudioName)
                            ?.let { return ManagedDownloadAudioLookupResult(it, "remoteTrackKey") }
                    }
            }
        }

        identity.mediaUri?.let { mediaUri ->
            snapshot.audioEntriesByMediaUri[mediaUri]
                ?.let { matches ->
                    pickBestAudioEntry(matches, song, fileNameTemplate, snapshot.metadataByAudioName)
                        ?.takeIf { entry ->
                            !requiresVerifiedRemoteIdentity || entryMatchesRemoteIdentity(
                                snapshot = snapshot,
                                entry = entry,
                                stableKey = stableKey,
                                remoteTrackKey = remoteTrackKey
                            )
                        }
                        ?.let { return ManagedDownloadAudioLookupResult(it, "mediaUri") }
                }
        }

        identity.id.takeIf { it > 0L }?.let { songId ->
            snapshot.audioEntriesBySongId[songId]
                ?.let { matches ->
                    pickBestAudioEntry(matches, song, fileNameTemplate, snapshot.metadataByAudioName)
                        ?.takeIf { entry ->
                            !requiresVerifiedRemoteIdentity || entryMatchesRemoteIdentity(
                                snapshot = snapshot,
                                entry = entry,
                                stableKey = stableKey,
                                remoteTrackKey = remoteTrackKey
                            )
                        }
                        ?.let { return ManagedDownloadAudioLookupResult(it, "songId") }
                }
        }

        if (requiresVerifiedRemoteIdentity) {
            findUniqueLegacyLocalAudioEntry(
                snapshot = snapshot,
                song = song,
                fileNameTemplate = fileNameTemplate,
                remoteTrackKey = remoteTrackKey
            )?.let { return ManagedDownloadAudioLookupResult(it, "legacyLocalEvidence") }
            return null
        }
        val baseNames = candidateManagedDownloadBaseNames(song, fileNameTemplate)
        return findAudioEntry(snapshot.audioEntriesWithoutMetadata, baseNames)
            ?.let { ManagedDownloadAudioLookupResult(it, "legacyNameFallback") }
    }

    fun findAudioEntry(
        audioEntries: List<ManagedDownloadStorage.StoredEntry>,
        baseNames: List<String>
    ): ManagedDownloadStorage.StoredEntry? {
        val exactCandidates = buildSet {
            baseNames.forEach { baseName ->
                audioExtensions.forEach { ext -> add("$baseName.$ext") }
            }
        }
        val patternCandidates = baseNames.map { baseName ->
            Regex("^${Regex.escape(baseName)}(?: \\(\\d+\\))?\\.[A-Za-z0-9]+$")
        }

        return audioEntries
            .filterNot(ManagedDownloadStorage.StoredEntry::isDirectory)
            .filter { entry ->
                entry.extension in audioExtensions && (
                    entry.name in exactCandidates ||
                        patternCandidates.any { it.matches(entry.name) }
                    )
            }
            .minWithOrNull(
                compareBy(
                    { entry -> if (entry.name in exactCandidates) 0 else 1 },
                    { entry ->
                        baseNames.asSequence()
                            .flatMap { baseName ->
                                audioExtensions.asSequence().mapNotNull { extension ->
                                    ManagedDownloadTreeNaming.providerNumberedNameOrdinal(
                                        actualName = entry.name,
                                        expectedName = "$baseName.$extension"
                                    )
                                }
                            }
                            .minOrNull() ?: Int.MAX_VALUE
                    },
                    { entry -> -entry.sizeBytes },
                    { entry -> entry.name }
                )
            )
    }

    /**
     * pending 文件的实际扩展名是 .pending, 但匹配应使用其逻辑音频名
     */
    fun findPendingAudioEntry(
        audioEntries: List<ManagedDownloadStorage.StoredEntry>,
        baseNames: List<String>
    ): ManagedDownloadStorage.StoredEntry? {
        val normalizedBaseNames = baseNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedBaseNames.isEmpty()) return null

        fun matchOrdinal(logicalName: String): Int? {
            return normalizedBaseNames.asSequence()
                .flatMap { baseName ->
                    audioExtensions.asSequence().mapNotNull { extension ->
                        val expectedName = "$baseName.$extension"
                        when {
                            logicalName.equals(expectedName, ignoreCase = true) -> 0
                            else -> ManagedDownloadTreeNaming.providerNumberedNameOrdinal(
                                actualName = logicalName,
                                expectedName = expectedName
                            )
                                ?.plus(1)
                        }
                    }
                }
                .minOrNull()
        }

        return audioEntries.asSequence()
            .filterNot(ManagedDownloadStorage.StoredEntry::isDirectory)
            .filter(ManagedDownloadStorage.StoredEntry::isPendingAudioWrite)
            .mapNotNull { entry ->
                matchOrdinal(entry.logicalName)?.let { ordinal -> entry to ordinal }
            }
            .minWithOrNull(
                compareBy<Pair<ManagedDownloadStorage.StoredEntry, Int>> { it.second }
                    .thenByDescending { it.first.sizeBytes }
                    .thenBy { it.first.name }
            )
            ?.first
    }

    fun findIndexedEntryByNames(
        names: List<String>,
        entriesByName: Map<String, ManagedDownloadStorage.StoredEntry>
    ): ManagedDownloadStorage.StoredEntry? {
        return names.firstNotNullOfOrNull(entriesByName::get)
    }

    private fun pickBestAudioEntry(
        audioEntries: List<ManagedDownloadStorage.StoredEntry>,
        song: SongItem,
        fileNameTemplate: String?,
        metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>
    ): ManagedDownloadStorage.StoredEntry? {
        if (audioEntries.isEmpty()) return null
        val baseNames = candidateManagedDownloadBaseNames(song, fileNameTemplate)
        return findAudioEntry(audioEntries, baseNames)
            ?: audioEntries
                .sortedWith(
                    compareByDescending<ManagedDownloadStorage.StoredEntry> { entry ->
                        isFinalizedDownloadedMetadata(metadataForEntry(metadataByAudioName, entry))
                    }
                        .thenByDescending { entry -> entry.sizeBytes }
                        .thenByDescending { entry -> entry.lastModifiedMs }
                        .thenBy { entry -> entry.name }
                )
                .firstOrNull()
    }

    private fun SongItem.requiresVerifiedRemoteDownloadIdentity(remoteTrackKey: String?): Boolean {
        val sourceIdentity = sourceStableKey?.trim()?.takeIf(String::isNotBlank)
        if (sourceIdentity != null || remoteTrackKey != null) {
            return true
        }
        val sourceChannel = channelId
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
        return sourceChannel != null && id > 0L
    }

    private fun entryMatchesRemoteIdentity(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        entry: ManagedDownloadStorage.StoredEntry,
        stableKey: String,
        remoteTrackKey: String?
    ): Boolean {
        val metadata = metadataForEntry(snapshot.metadataByAudioName, entry) ?: return false
        if (metadata.stableKey == stableKey) {
            return true
        }
        val entryRemoteTrackKey = ManagedDownloadSnapshotIndex.buildRemoteTrackKey(
            metadata.channelId,
            metadata.audioId,
            metadata.subAudioId
        )
        return remoteTrackKey != null && remoteTrackKey == entryRemoteTrackKey
    }

    private fun metadataForEntry(
        metadataByAudioName: Map<String, ManagedDownloadStorage.DownloadedAudioMetadata>,
        entry: ManagedDownloadStorage.StoredEntry
    ): ManagedDownloadStorage.DownloadedAudioMetadata? {
        return metadataByAudioName[entry.name]
            ?: metadataByAudioName[entry.logicalName]
    }

    private fun findUniqueLegacyLocalAudioEntry(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        song: SongItem,
        fileNameTemplate: String?,
        remoteTrackKey: String?
    ): ManagedDownloadStorage.StoredEntry? {
        if (!LocalSongSupport.isLocalSong(song, null)) return null
        val baseNames = candidateManagedDownloadBaseNames(song, fileNameTemplate)
        if (baseNames.isEmpty()) return null
        val candidates = snapshot.audioEntries
            .asSequence()
            .filterNot(ManagedDownloadStorage.StoredEntry::isDirectory)
            .filterNot(ManagedDownloadStorage.StoredEntry::isPendingAudioWrite)
            .filter { entry ->
                baseNames.any { baseName ->
                    audioExtensions.any { extension ->
                        val expectedName = "$baseName.$extension"
                        listOf(entry.name, entry.logicalName).any { actualName ->
                            actualName.equals(expectedName, ignoreCase = true) ||
                                ManagedDownloadTreeNaming.providerNumberedNameOrdinal(
                                    actualName = actualName,
                                    expectedName = expectedName
                                ) != null
                        }
                    }
                }
            }
            .distinctBy(ManagedDownloadStorage.StoredEntry::reference)
            .toList()
        if (candidates.size != 1) return null

        val candidate = candidates.single()
        val metadata = metadataForEntry(snapshot.metadataByAudioName, candidate)
            ?: return candidate
        val expectedStableKeys = setOfNotNull(
            song.stableKey().takeIf(String::isNotBlank),
            song.sourceStableKey?.trim()?.takeIf(String::isNotBlank),
            song.remoteDownloadIdentityOrNull()?.stableKey()?.takeIf(String::isNotBlank)
        )
        val metadataStableKey = metadata.stableKey?.trim()?.takeIf(String::isNotBlank)
        val metadataTrackKey = ManagedDownloadSnapshotIndex.buildRemoteTrackKey(
            metadata.channelId,
            metadata.audioId,
            metadata.subAudioId
        )
        if (
            remoteTrackKey != null && metadataTrackKey != null &&
                metadataTrackKey != remoteTrackKey
        ) {
            return null
        }
        if (metadataStableKey != null && metadataStableKey !in expectedStableKeys) {
            if (remoteTrackKey == null || metadataTrackKey != remoteTrackKey) {
                return null
            }
        }
        return candidate
    }
}
