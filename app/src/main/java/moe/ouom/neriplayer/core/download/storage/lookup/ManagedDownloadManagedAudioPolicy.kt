package moe.ouom.neriplayer.core.download.storage.lookup

import moe.ouom.neriplayer.core.download.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming

internal object ManagedDownloadManagedAudioPolicy {
    fun shouldTreatAudioAsManaged(
        audioName: String,
        metadataAudioNames: Set<String>,
        coverEntryNames: Set<String>,
        lyricEntryNames: Set<String>,
        allowMetadataLessAudio: Boolean
    ): Boolean {
        if (audioName in metadataAudioNames) {
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
            .any(coverEntryNames::contains)
        if (hasManagedCover) {
            return true
        }
        return ManagedDownloadStorageNaming.buildLyricCandidateNames(
            songId = null,
            candidateBaseNames = candidateBaseNames,
            translated = false
        ).any(lyricEntryNames::contains) ||
            ManagedDownloadStorageNaming.buildLyricCandidateNames(
                songId = null,
                candidateBaseNames = candidateBaseNames,
                translated = true
            ).any(lyricEntryNames::contains)
    }
}
