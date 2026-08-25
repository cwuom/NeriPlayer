package moe.ouom.neriplayer.core.download

import android.content.Context
import androidx.room.withTransaction
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
        metadataRevision: Long = System.currentTimeMillis(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        val stableKey = song.stableKey()
        val preview = ManagedLibraryItemEntity(
                rootKey = libraryId,
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
        database.withTransaction {
            val dao = database.managedLibraryItemDao()
            dao.insertIfAbsent(preview)
            dao.updatePreview(
                libraryId = libraryId,
                stableKey = stableKey,
                audioReference = audio.reference,
                audioName = audio.logicalName,
                fileSize = audio.sizeBytes.takeIf { it > 0L },
                downloadedAtMs = audio.lastModifiedMs.takeIf { it > 0L },
                metadataName = "${audio.logicalName}.npmeta.json",
                locatorHint = audio.reference,
                titlePreview = song.name,
                artistPreview = song.artist,
                coverKeyPreview = null,
                metadataRevision = metadataRevision
            )
        }
    }

    suspend fun upsertPreview(
        context: Context,
        song: DownloadedSong,
        state: String = "FINALIZED",
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        val stableKey = song.stableKey?.trim().takeIf { !it.isNullOrBlank() }
            ?: return
        val reference = song.mediaUri?.takeIf(String::isNotBlank)
            ?: song.filePath.takeIf(String::isNotBlank)
            ?: return
        val metadataRevision = System.currentTimeMillis()
        val preview = ManagedLibraryItemEntity(
                rootKey = libraryId,
                stableKey = stableKey,
                artifactId = "managed:$libraryId:$stableKey",
                state = state,
                audioReference = reference,
                audioName = song.filePath.substringAfterLast('/').takeIf(String::isNotBlank),
                fileSize = song.fileSize,
                updatedAtMs = System.currentTimeMillis(),
                needsReconcile = state != "FINALIZED",
                metadataName = null,
                locatorHint = reference,
                titlePreview = song.displayName(),
                artistPreview = song.displayArtist(),
                downloadedAtMs = song.downloadTime.takeIf { it > 0L },
                metadataRevision = metadataRevision
            )
        database.withTransaction {
            val dao = database.managedLibraryItemDao()
            dao.insertIfAbsent(preview)
            dao.updatePreview(
                libraryId = libraryId,
                stableKey = stableKey,
                audioReference = reference,
                audioName = preview.audioName,
                fileSize = song.fileSize.takeIf { it > 0L },
                downloadedAtMs = song.downloadTime.takeIf { it > 0L },
                metadataName = null,
                locatorHint = reference,
                titlePreview = song.displayName(),
                artistPreview = song.displayArtist(),
                coverKeyPreview = null,
                metadataRevision = metadataRevision
            )
        }
    }

    suspend fun restore(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<DownloadedSong>? {
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        val rows = database.managedLibraryItemDao()
            .findAll(libraryId)
        if (rows.isEmpty()) return null
        return rows.mapNotNull { row ->
            val reference = row.audioReference ?: row.locatorHint ?: return@mapNotNull null
            DownloadedSong(
                id = 0L,
                name = row.titlePreview ?: row.audioName ?: reference,
                artist = row.artistPreview.orEmpty(),
                album = "",
                filePath = reference,
                fileSize = row.fileSize ?: 0L,
                downloadTime = row.downloadedAtMs ?: row.updatedAtMs,
                mediaUri = reference.takeIf { it.startsWith("content://") },
                stableKey = row.stableKey
            )
        }.takeIf { it.isNotEmpty() }
    }

    suspend fun replacePreviews(
        context: Context,
        songs: List<DownloadedSong>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        // catalog snapshots are previews and must not delete active artifact leases
        songs.forEach { song -> upsertPreview(context, song, database = database) }
    }

    suspend fun delete(
        context: Context,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        database.managedLibraryItemDao()
            .delete(libraryId, stableKey)
    }
}
