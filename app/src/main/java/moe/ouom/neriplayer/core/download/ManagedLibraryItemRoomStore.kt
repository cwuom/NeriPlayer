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
            upsertPreviewInTransaction(
                database = database,
                item = preview,
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
        metadataRevision: Long = System.currentTimeMillis(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        val stableKey = song.stableKey?.trim().takeIf { !it.isNullOrBlank() }
            ?: return
        val reference = song.mediaUri?.takeIf(String::isNotBlank)
            ?: song.filePath.takeIf(String::isNotBlank)
            ?: return
        val preview = ManagedLibraryItemEntity(
                rootKey = libraryId,
                stableKey = stableKey,
                artifactId = "managed:$libraryId:$stableKey",
                state = state,
                audioReference = reference,
                audioName = song.filePath.substringAfterLast('/').takeIf(String::isNotBlank),
                fileSize = song.fileSize,
                updatedAtMs = metadataRevision,
                needsReconcile = state != "FINALIZED",
                metadataName = null,
                locatorHint = reference,
                titlePreview = song.displayName(),
                artistPreview = song.displayArtist(),
                downloadedAtMs = song.downloadTime.takeIf { it > 0L },
                metadataRevision = metadataRevision
            )
        database.withTransaction {
            upsertPreviewInTransaction(
                database = database,
                item = preview,
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
        return rows.asSequence()
            .filter { row -> shouldRestoreManagedLibraryItem(row.state) }
            .mapNotNull { row ->
                val reference = preferredManagedLibraryRestoreReference(
                    audioReference = row.audioReference,
                    locatorHint = row.locatorHint
                ) ?: return@mapNotNull null
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
            }.toList()
    }

    suspend fun replacePreviews(
        context: Context,
        songs: List<DownloadedSong>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        stateForSong: (DownloadedSong) -> String = { "FINALIZED" },
        metadataRevision: Long = System.currentTimeMillis()
    ) {
        // catalog snapshots are previews and must not delete active artifact leases
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        database.withTransaction {
            songs.forEach { song ->
                val stableKey = song.stableKey?.trim().takeIf { !it.isNullOrBlank() }
                    ?: return@forEach
                val reference = song.mediaUri?.takeIf(String::isNotBlank)
                    ?: song.filePath.takeIf(String::isNotBlank)
                    ?: return@forEach
                val audioName = song.filePath.substringAfterLast('/')
                    .takeIf(String::isNotBlank)
                val state = stateForSong(song)
                val preview = ManagedLibraryItemEntity(
                    rootKey = libraryId,
                    stableKey = stableKey,
                    artifactId = "managed:$libraryId:$stableKey",
                    state = state,
                    audioReference = reference,
                    audioName = audioName,
                    fileSize = song.fileSize,
                    updatedAtMs = metadataRevision,
                    needsReconcile = state != "FINALIZED",
                    metadataName = null,
                    locatorHint = reference,
                    titlePreview = song.displayName(),
                    artistPreview = song.displayArtist(),
                    downloadedAtMs = song.downloadTime.takeIf { it > 0L },
                    metadataRevision = metadataRevision
                )
                upsertPreviewInTransaction(
                    database = database,
                    item = preview,
                    audioReference = reference,
                    audioName = audioName,
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
    }

    private suspend fun upsertPreviewInTransaction(
        database: NeriUserDataDatabase,
        item: ManagedLibraryItemEntity,
        audioReference: String?,
        audioName: String?,
        fileSize: Long?,
        downloadedAtMs: Long?,
        metadataName: String?,
        locatorHint: String?,
        titlePreview: String,
        artistPreview: String,
        coverKeyPreview: String?,
        metadataRevision: Long
    ) {
        val dao = database.managedLibraryItemDao()
        dao.insertIfAbsent(item)
        dao.updatePreview(
            libraryId = item.rootKey,
            stableKey = item.stableKey,
            audioReference = audioReference,
            audioName = audioName,
            fileSize = fileSize,
            downloadedAtMs = downloadedAtMs,
            metadataName = metadataName,
            locatorHint = locatorHint,
            titlePreview = titlePreview,
            artistPreview = artistPreview,
            coverKeyPreview = coverKeyPreview,
            metadataRevision = metadataRevision
        )
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

internal fun preferredManagedLibraryRestoreReference(
    audioReference: String?,
    locatorHint: String?
): String? {
    return locatorHint
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: audioReference
            ?.trim()
            ?.takeIf(String::isNotBlank)
}

internal fun shouldRestoreManagedLibraryItem(state: String): Boolean {
    return state in setOf(
        "FINALIZED",
        "COMPLETE",
        "COMPLETED",
        // core 音频已完成并校验，资产收尾可在下次启动继续
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
}
