package moe.ouom.neriplayer.core.download

import android.content.Context
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.ManagedLibraryItemEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

internal object ManagedLibraryItemRoomStore {
    suspend fun upsert(
        context: Context,
        song: SongItem,
        audio: ManagedDownloadStorage.StoredEntry,
        state: String,
        metadataRevision: Long = System.currentTimeMillis()
    ) {
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        val stableKey = song.stableKey()
        NeriUserDataDatabase.getInstance(context).managedLibraryItemDao().upsert(
            ManagedLibraryItemEntity(
                libraryId = libraryId,
                stableKey = stableKey,
                artifactId = "managed:$libraryId:$stableKey",
                state = state,
                audioName = audio.logicalName,
                metadataName = "${audio.logicalName}.npmeta.json",
                locatorHint = audio.reference,
                titlePreview = song.name,
                artistPreview = song.artist,
                coverKeyPreview = null,
                downloadedAtMs = audio.lastModifiedMs.takeIf { it > 0L },
                metadataRevision = metadataRevision
            )
        )
    }

    suspend fun delete(context: Context, stableKey: String) {
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        NeriUserDataDatabase.getInstance(context).managedLibraryItemDao()
            .delete(libraryId, stableKey)
    }
}
