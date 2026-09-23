package moe.ouom.neriplayer.core.download.manager.catalog

import android.content.Context
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongDeleteIntent
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadDeleteReferenceIndex
import moe.ouom.neriplayer.core.download.model.DownloadedSong

private suspend fun remainingDeleteRecoverySongs(
    context: Context,
    songs: List<DownloadedSong>,
    intent: DownloadedSongDeleteIntent,
    deletedReferences: Set<String>
): List<DownloadedSong> {
    val deletedIndex = ManagedDownloadDeleteReferenceIndex(deletedReferences)
    val remainingSongs = songs.filterNot { deletedIndex.resolve(it.deletionIdentity()) != null }
    val targetIndex = ManagedDownloadDeleteReferenceIndex(intent.targets.map { it.deletionIdentity })
    val targets = remainingSongs.filter { targetIndex.resolve(it.deletionIdentity()) != null }
    val missing = findConfirmedMissingDownloadedSongs(context, targets).toSet()
    return remainingSongs.filterNot { it in missing }
}

internal suspend fun filterMissingDeleteRecoveryPreview(
    context: Context,
    songs: List<DownloadedSong>
): List<DownloadedSong> {
    val intent = PersistentDownloadedSongDeleteIntentStore.read(context) ?: return songs
    if (intent.rootKey != ManagedDownloadStorage.currentSnapshotCacheKey(context)) return songs
    val remainingSongs = remainingDeleteRecoverySongs(context, songs, intent, emptySet())
    return if (isDeleteRecoveryIntentCurrent(context, intent)) remainingSongs else songs
}

// 清理未决侧载不能阻止已删除音频退出可见列表，恢复记录仍由完整删除事务收尾
internal suspend fun GlobalDownloadManager.publishConfirmedDeleteRecoveryCatalog(
    context: Context,
    intent: DownloadedSongDeleteIntent,
    deletedReferences: Set<String>
) {
    if (!isDeleteRecoveryIntentCurrent(context, intent)) return
    val (songs, catalogRevision, metadataRevision) = synchronized(downloadedSongCatalogMutationLock) {
        Triple(
            downloadedSongsMutable.value,
            downloadedSongCatalogPersistenceRevision.get(),
            downloadedSongMetadataRevision.get()
        )
    }
    val remainingSongs = remainingDeleteRecoverySongs(context, songs, intent, deletedReferences)
    if (remainingSongs.size == songs.size) return
    downloadedSongMetadataSyncMutex.withLock {
        if (isDeleteRecoveryIntentCurrent(context, intent)) {
            publishScannedDownloadedSongsIfCurrent(
                context = context,
                songs = remainingSongs,
                scanRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context),
                expectedCatalogRevision = catalogRevision,
                expectedMetadataRevision = metadataRevision
            )
        }
    }
}

private fun isDeleteRecoveryIntentCurrent(context: Context, intent: DownloadedSongDeleteIntent): Boolean {
    if (intent.rootKey != ManagedDownloadStorage.currentSnapshotCacheKey(context)) return false
    val currentIntent = PersistentDownloadedSongDeleteIntentStore.read(context) ?: return false
    return currentIntent.rootKey == intent.rootKey && currentIntent.requestedAtMs == intent.requestedAtMs
}
