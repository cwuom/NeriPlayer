package moe.ouom.neriplayer.core.download.storage.lookup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey

internal data class ManagedDownloadAudioLookupResult(
    val entry: ManagedDownloadStorage.StoredEntry,
    val hitType: String
)

internal object ManagedDownloadStorageLookup {
    fun findAudioEntry(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        song: SongItem,
        fileNameTemplate: String?
    ): ManagedDownloadAudioLookupResult? {
        val identity = song.identity()
        val stableKey = identity.stableKey()
        val remoteTrackKey = ManagedDownloadSnapshotIndex.buildRemoteTrackKey(
            song.channelId,
            song.audioId,
            song.subAudioId
        )

        snapshot.audioEntriesByStableKey[stableKey]
            ?.let { matches ->
                pickBestAudioEntry(matches, song, fileNameTemplate)
                    ?.let { return ManagedDownloadAudioLookupResult(it, "stableKey") }
            }

        remoteTrackKey?.let { key ->
            snapshot.audioEntriesByRemoteTrackKey[key]
                ?.let { matches ->
                    pickBestAudioEntry(matches, song, fileNameTemplate)
                        ?.let { return ManagedDownloadAudioLookupResult(it, "remoteTrackKey") }
                }
        }

        identity.mediaUri?.let { mediaUri ->
            snapshot.audioEntriesByMediaUri[mediaUri]
                ?.let { matches ->
                    pickBestAudioEntry(matches, song, fileNameTemplate)
                        ?.let { return ManagedDownloadAudioLookupResult(it, "mediaUri") }
                }
        }

        identity.id.takeIf { it > 0L }?.let { songId ->
            snapshot.audioEntriesBySongId[songId]
                ?.let { matches ->
                    pickBestAudioEntry(matches, song, fileNameTemplate)
                        ?.let { return ManagedDownloadAudioLookupResult(it, "songId") }
                }
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
            .firstOrNull { entry ->
                entry.extension in audioExtensions && (
                    entry.name in exactCandidates ||
                        patternCandidates.any { it.matches(entry.name) }
                    )
            }
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
        fileNameTemplate: String?
    ): ManagedDownloadStorage.StoredEntry? {
        if (audioEntries.isEmpty()) return null
        val baseNames = candidateManagedDownloadBaseNames(song, fileNameTemplate)
        return findAudioEntry(audioEntries, baseNames)
            ?: audioEntries.maxByOrNull(ManagedDownloadStorage.StoredEntry::lastModifiedMs)
    }
}
