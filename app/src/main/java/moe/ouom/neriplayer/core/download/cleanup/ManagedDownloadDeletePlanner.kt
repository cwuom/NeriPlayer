package moe.ouom.neriplayer.core.download.cleanup

import android.content.Context
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

internal class ManagedDownloadDeletePlanner {

    suspend fun buildDeletePlans(
        context: Context,
        songs: List<DownloadedSong>
    ): List<ManagedDownloadSongDeletePlan> {
        if (songs.isEmpty()) {
            return emptyList()
        }
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
            ?: ManagedDownloadStorage.emptyDownloadLibrarySnapshot()
        val deleteContexts = songs.map { song ->
            ManagedDownloadArtifactPlanner.buildDeleteContext(
                song = song,
                snapshot = snapshot
            )
        }
        val deletingAudioNames = deleteContexts.mapNotNullTo(mutableSetOf()) {
            it.storedAudio?.name
        }
        return deleteContexts.map { deleteContext ->
            val requestedReferences = ManagedDownloadArtifactPlanner.collectArtifactReferences(
                snapshot = snapshot,
                storedAudio = deleteContext.storedAudio,
                songId = deleteContext.song.id,
                candidateBaseNames = deleteContext.candidateBaseNames,
                explicitReferences = deleteContext.explicitReferences,
                deletingAudioNames = deletingAudioNames
            )
            ManagedDownloadSongDeletePlan(
                song = deleteContext.song,
                requestedReferences = requestedReferences,
                requiredReferences = deleteContext.requiredReferences
            )
        }
    }

    suspend fun removeArtifacts(
        context: Context,
        songName: String,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        songId: Long,
        candidateBaseNames: List<String>,
        explicitReferences: List<String> = emptyList(),
        useCachedSnapshotOnly: Boolean = false,
        logger: (String) -> Unit = {}
    ): ManagedDownloadArtifactRemovalResult {
        var snapshot = if (useCachedSnapshotOnly) {
            ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
                ?: ManagedDownloadStorage.emptyDownloadLibrarySnapshot()
        } else {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = false
            )
        }
        if (
            !useCachedSnapshotOnly &&
            storedAudio != null &&
            snapshot.audioEntriesByLookupKey[storedAudio.reference] == null
        ) {
            snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
        }

        val referencesToDelete = ManagedDownloadArtifactPlanner.collectArtifactReferences(
            snapshot = snapshot,
            storedAudio = storedAudio,
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            explicitReferences = explicitReferences
        )
        referencesToDelete.forEach { reference ->
            if (storedAudio?.reference == reference) {
                logger("删除下载音频: song=$songName, reference=$reference")
            } else {
                logger("删除下载关联文件: song=$songName, reference=$reference")
            }
        }
        val deletedReferences = if (referencesToDelete.isNotEmpty()) {
            ManagedDownloadStorage.deleteReferences(context, referencesToDelete)
        } else {
            emptySet()
        }
        if (referencesToDelete.isEmpty()) {
            return ManagedDownloadArtifactRemovalResult()
        }
        return ManagedDownloadArtifactRemovalResult(
            requestedReferences = referencesToDelete,
            deletedReferences = deletedReferences
        )
    }
}
