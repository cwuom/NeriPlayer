package moe.ouom.neriplayer.core.download.cleanup

import android.content.Context
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.catalog.resolveDownloadedSongPlaybackReference
import moe.ouom.neriplayer.core.download.readTemporaryDirectoryEntries
import moe.ouom.neriplayer.core.download.resolveRootBlocking
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_TEMPORARY_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle

internal data class ManagedDownloadFullDeletePlan(
    val requestedReferences: Set<String>,
    val snapshotComplete: Boolean
)

internal class ManagedDownloadDeletePlanner {

    suspend fun buildFullLibraryDeletePlan(
        context: Context
    ): ManagedDownloadFullDeletePlan {
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
            context = context,
            forceRefresh = true
        )
        val root = ManagedDownloadStorage.resolveRootBlocking(context)
        val temporaryEntries = ManagedDownloadStorage.readTemporaryDirectoryEntries(
            context = context,
            root = root,
            forceRefresh = false,
            rootAlreadyRefreshed = true
        )
        val canCompactManagedDirectories =
            snapshot.rootEntriesComplete &&
                snapshot.sidecarEntriesComplete &&
                temporaryEntries.isComplete
        fun managedDirectoryRoots(subdirectory: String): List<ManagedDownloadRootHandle> {
            return if (canCompactManagedDirectories) {
                ManagedDownloadStorage.treeDirectories.findSubdirectories(
                    context = context,
                    root = root,
                    desiredName = subdirectory
                )
            } else {
                emptyList()
            }
        }
        val coverDirectoryRoots = managedDirectoryRoots(COVER_SUBDIRECTORY)
        val lyricDirectoryRoots = managedDirectoryRoots(LYRIC_SUBDIRECTORY)
        val temporaryDirectoryRoots = managedDirectoryRoots(DOWNLOAD_TEMPORARY_DIR_NAME)
        val compactedDirectoryRoots =
            coverDirectoryRoots + lyricDirectoryRoots + temporaryDirectoryRoots
        val compactedDirectoryReferences = compactedDirectoryRoots
            .mapTo(linkedSetOf(), ::rootReference)
        val compactedChildReferences = buildSet {
            if (coverDirectoryRoots.isNotEmpty()) {
                snapshot.coverEntriesByName.values.forEach { entry -> add(entry.reference) }
            }
            if (lyricDirectoryRoots.isNotEmpty()) {
                snapshot.lyricEntriesByName.values.forEach { entry -> add(entry.reference) }
            }
            if (temporaryDirectoryRoots.isNotEmpty()) {
                temporaryEntries.entries.forEach { entry -> add(entry.reference) }
            }
        }
        val requestedReferences = if (compactedDirectoryReferences.isEmpty()) {
            ManagedDownloadArtifactPlanner.collectFullLibraryArtifactReferences(snapshot)
        } else {
            buildSet {
                addAll(
                    ManagedDownloadArtifactPlanner.collectFullLibraryArtifactReferences(snapshot) -
                        compactedChildReferences
                )
                addAll(compactedDirectoryReferences)
            }
        }
        return ManagedDownloadFullDeletePlan(
            requestedReferences = requestedReferences,
            snapshotComplete = snapshot.rootEntriesComplete &&
                snapshot.sidecarEntriesComplete &&
                temporaryEntries.isComplete
        )
    }

    suspend fun buildDeletePlans(
        context: Context,
        songs: List<DownloadedSong>
    ): List<ManagedDownloadSongDeletePlan> {
        if (songs.isEmpty()) {
            return emptyList()
        }
        var snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)
            ?: ManagedDownloadStorage.buildDownloadLibrarySnapshot(context)
        if (requiresManagedDownloadDeleteSnapshotRefresh(snapshot, songs)) {
            snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
        }
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

private fun rootReference(root: ManagedDownloadRootHandle): String {
    return when (root) {
        is ManagedDownloadRootHandle.FileRoot -> root.dir.absolutePath
        is ManagedDownloadRootHandle.TreeRoot -> root.tree.uri.toString()
    }
}

internal fun requiresManagedDownloadDeleteSnapshotRefresh(
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
    songs: List<DownloadedSong>
): Boolean {
    return songs.any { song ->
        val playbackReference = resolveDownloadedSongPlaybackReference(song)
            ?.takeIf(String::isNotBlank)
            ?: return@any false
        snapshot.audioEntriesByLookupKey[playbackReference] == null
    }
}
