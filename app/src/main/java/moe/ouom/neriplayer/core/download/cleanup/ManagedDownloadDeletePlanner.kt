package moe.ouom.neriplayer.core.download.cleanup

import android.content.Context
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.storage.operation.content.rootKeyForResolvedRoot
import moe.ouom.neriplayer.core.download.storage.operation.content.readDownloadedAudioMetadataEntriesDetailed
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.catalog.resolveDownloadedSongPlaybackReference
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.readTemporaryDirectoryEntries
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.logging.NPLogger

internal enum class ManagedDownloadFullDeleteBlockReason {
    INCOMPLETE_ENUMERATION,
    ROOT_CHANGED,
    METADATA_UNAVAILABLE,
    CONFLICTING_RECEIPT,
    UNRESOLVED_PENDING,
    RETAINED_OWNED_REFERENCE
}

internal data class ManagedDownloadFullDeletePlan(
    val requestedReferences: Set<String>,
    val snapshotComplete: Boolean,
    val unresolvedPendingReferences: Set<String> = emptySet(),
    val blockingReasonCounts: Map<ManagedDownloadFullDeleteBlockReason, Int> = emptyMap(),
    val rootEmptyConfirmationPending: Boolean = false
)

internal class ManagedDownloadDeletePlanner {

    suspend fun buildFullLibraryDeletePlan(
        context: Context,
        selectedSongs: Collection<DownloadedSong> = emptyList()
    ): ManagedDownloadFullDeletePlan {
        val root = ManagedDownloadStorage.resolveRootBlocking(context)
        val rootKey = ManagedDownloadStorage.rootKeyForResolvedRoot(root)
        val intent = PersistentDownloadedSongDeleteIntentStore.read(context)
        if (intent != null && intent.rootKey != rootKey) {
            return ManagedDownloadFullDeletePlan(emptySet(), false,
                blockingReasonCounts = mapOf(ManagedDownloadFullDeleteBlockReason.ROOT_CHANGED to 1))
        }
        val refresh = ManagedDownloadStorage.treeDirectories.refreshDownloadLibraryEntries(context, root)
        val temporary = ManagedDownloadStorage.readTemporaryDirectoryEntries(
            context, root, forceRefresh = true, rootAlreadyRefreshed = true
        )
        val enumerationComplete = refresh.rootEntriesComplete && refresh.sidecarEntriesComplete && temporary.isComplete
        val emptyConfirmationPending = refresh.rootEmptyConfirmationPending && temporary.isComplete
        if (!enumerationComplete) {
            return ManagedDownloadFullDeletePlan(
                requestedReferences = emptySet(),
                snapshotComplete = false,
                blockingReasonCounts = mapOf(ManagedDownloadFullDeleteBlockReason.INCOMPLETE_ENUMERATION to 1),
                rootEmptyConfirmationPending = emptyConfirmationPending
            )
        }
        val metadataEntries = (refresh.rootEntries + temporary.entries).filter {
            !it.isDirectory && ManagedDownloadTreeNaming.isMetadataName(it.name)
        }
        val plan = planOwnedFullLibraryDeletion(
            inventory = ManagedFullDeleteInventory(
                rootEntries = refresh.rootEntries,
                coverEntries = refresh.coverEntries,
                lyricEntries = refresh.lyricEntries,
                temporaryEntries = temporary.entries,
                metadataByReference = ManagedDownloadStorage.readDownloadedAudioMetadataEntriesDetailed(
                    context, metadataEntries, fullLibraryDelete = true
                ),
                enumerationComplete = true
            ),
            targets = intent?.targets.orEmpty(),
            selectedSongs = selectedSongs,
            persistedOwnedReferences = intent?.ownedReferences.orEmpty()
        )
        if (!plan.snapshotComplete) {
            NPLogger.w("ManagedDownloadDeletePlanner",
                "全选删除保留未确认引用: planned=${plan.requestedReferences.size}, " +
                    "blockingReasons=${plan.blockingReasonCounts}")
        }
        return plan.copy(
            rootEmptyConfirmationPending = emptyConfirmationPending
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
        val referenceIndex = ManagedDownloadDeleteReferenceIndex(snapshot.knownReferences, snapshot.artifactOwnerAudioNamesByReference)
        val uniqueAudioReferencesByName = readUniqueAudioReferencesByName(context)
        return deleteContexts.map { deleteContext ->
            val requestedReferences = ManagedDownloadArtifactPlanner.collectArtifactReferences(
                snapshot = snapshot,
                storedAudio = deleteContext.storedAudio,
                explicitReferences = deleteContext.explicitReferences,
                deletingAudioNames = deletingAudioNames,
                referenceIndex = referenceIndex,
                uniqueAudioReferencesByName = uniqueAudioReferencesByName
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
            explicitReferences = explicitReferences,
            uniqueAudioReferencesByName = if (useCachedSnapshotOnly) emptyMap()
                else readUniqueAudioReferencesByName(context)
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

    private fun readUniqueAudioReferencesByName(context: Context): Map<String, String> {
        val root = ManagedDownloadStorage.resolveRootBlocking(context)
        val inventory = ManagedDownloadStorage.treeDirectories.refreshRootEntries(context, root)
        if (!inventory.isComplete) return emptyMap()
        // snapshot 和名称缓存都可能折叠同名文档，必须使用本批次的原始完整查询
        return inventory.entries.filterNot { it.isDirectory }
            .groupBy { it.logicalName }
            .mapNotNull { (name, entries) ->
                entries.distinctBy { it.reference }.singleOrNull()?.let { name to it.reference }
            }.toMap()
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
