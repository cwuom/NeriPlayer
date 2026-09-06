package moe.ouom.neriplayer.core.download.storage.lookup

import moe.ouom.neriplayer.core.download.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming

internal object ManagedDownloadManagedAudioPolicy {
    fun shouldTreatAudioAsManaged(
        audioName: String,
        metadataAudioNames: Set<String>,
        coverEntryNames: Set<String>,
        lyricEntryNames: Set<String>,
        allowMetadataLessAudio: Boolean
    ): Boolean {
        if (metadataAudioNames.any { metadataName ->
                matchesStoredName(audioName, metadataName)
            }
        ) {
            return true
        }
        if (allowMetadataLessAudio) {
            return true
        }
        val candidateBaseNames = candidateManagedDownloadBaseNames(
            audioName.substringBeforeLast('.', audioName)
        )
        val hasManagedCover = ManagedDownloadStorageNaming
            .buildSidecarCandidateNames(candidateBaseNames)
            .any { candidate ->
                coverEntryNames.any { storedName ->
                    matchesStoredName(storedName, candidate)
                }
            }
        if (hasManagedCover) {
            return true
        }
        return ManagedDownloadStorageNaming.LyricKind.entries.any { kind ->
            ManagedDownloadStorageNaming.buildLyricCandidateNames(
                songId = null,
                candidateBaseNames = candidateBaseNames,
                kind = kind
            ).any { candidate ->
                lyricEntryNames.any { storedName ->
                    matchesStoredName(storedName, candidate)
                }
            }
        }
    }

    private fun matchesStoredName(actualName: String, expectedName: String): Boolean {
        val canonicalActualName = ManagedDownloadTreeNaming.canonicalLookupName(actualName)
        val canonicalExpectedName = ManagedDownloadTreeNaming.canonicalLookupName(expectedName)
        return canonicalActualName == canonicalExpectedName ||
            ManagedDownloadTreeNaming.providerNumberedNameOrdinal(
                actualName = canonicalActualName,
                expectedName = canonicalExpectedName
            ) != null
    }
}
