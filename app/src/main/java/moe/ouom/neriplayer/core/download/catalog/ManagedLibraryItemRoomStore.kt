package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.resolvedLocalFileName
import android.content.Context
import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.ManagedLibraryItemEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.json.JSONArray

internal object ManagedLibraryItemRoomStore {
    suspend fun clearPreviews(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        libraryId: String = ManagedDownloadStorage.currentSnapshotCacheKey(context)
    ): Int {
        return database.managedLibraryItemDao().deleteAll(libraryId)
    }

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
            updateDeletedStableKeys(database, libraryId, restored = setOf(stableKey))
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
                audioName = song.resolvedLocalFileName(),
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
            updateDeletedStableKeys(database, libraryId, restored = setOf(stableKey))
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
                    stableKey = row.stableKey,
                    localFileName = row.audioName
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
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        val incomingStableKeys = songs.mapNotNullTo(linkedSetOf()) { song ->
            val stableKey = song.stableKey?.trim().takeIf { !it.isNullOrBlank() }
                ?: return@mapNotNullTo null
            val hasReference = !song.mediaUri.isNullOrBlank() || song.filePath.isNotBlank()
            stableKey.takeIf { hasReference }
        }
        val dao = database.managedLibraryItemDao()
        database.withTransaction {
            val removedStableKeys = dao.findAll(libraryId)
                .filter { row ->
                    shouldRemoveMissingCatalogPreview(
                        state = row.state,
                        leaseId = row.leaseId,
                        needsReconcile = row.needsReconcile,
                        presentInSnapshot = row.stableKey in incomingStableKeys
                    )
                }
                .mapTo(linkedSetOf()) { row -> row.stableKey }
            // 删除事实与预览同事务提交，完整备份尚未覆盖时也不会在重启后复活
            updateDeletedStableKeys(
                database,
                libraryId,
                removed = removedStableKeys,
                restored = incomingStableKeys
            )
            removedStableKeys.forEach { stableKey -> dao.delete(libraryId, stableKey) }
            songs.forEach { song ->
                val stableKey = song.stableKey?.trim().takeIf { !it.isNullOrBlank() }
                    ?: return@forEach
                val reference = song.mediaUri?.takeIf(String::isNotBlank)
                    ?: song.filePath.takeIf(String::isNotBlank)
                    ?: return@forEach
                val audioName = song.resolvedLocalFileName()
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

    /**
     * 在一个 Room 事务中应用 catalog 增量，避免每个完成条目各自开启事务
     */
    suspend fun applyPreviewDelta(
        context: Context,
        upserts: List<DownloadedSong>,
        removedStableKeys: Set<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        stateForSong: (DownloadedSong) -> String = { "FINALIZED" },
        metadataRevision: Long = System.currentTimeMillis()
    ) {
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        val dao = database.managedLibraryItemDao()
        database.withTransaction {
            val restoredStableKeys = upserts.mapNotNullTo(linkedSetOf()) { song ->
                song.stableKey?.trim()?.takeIf { key ->
                    key.isNotBlank() &&
                        (!song.mediaUri.isNullOrBlank() || song.filePath.isNotBlank())
                }
            }
            updateDeletedStableKeys(
                database,
                libraryId,
                removed = removedStableKeys,
                restored = restoredStableKeys
            )
            removedStableKeys.forEach { stableKey ->
                dao.delete(libraryId, stableKey)
            }
            upserts.forEach { song ->
                val stableKey = song.stableKey?.trim().takeIf { !it.isNullOrBlank() }
                    ?: return@forEach
                val reference = song.mediaUri?.takeIf(String::isNotBlank)
                    ?: song.filePath.takeIf(String::isNotBlank)
                    ?: return@forEach
                val audioName = song.resolvedLocalFileName()
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
        database.withTransaction {
            updateDeletedStableKeys(database, libraryId, removed = setOf(stableKey))
            database.managedLibraryItemDao().delete(libraryId, stableKey)
        }
    }

    suspend fun deletedStableKeys(
        database: NeriUserDataDatabase,
        libraryId: String
    ): Set<String> {
        val raw = database.syncMetadataDao()
            .getMigrationMetadata(deletionMetadataKey(libraryId))?.value ?: return emptySet()
        val values = JSONArray(raw)
        return buildSet {
            for (index in 0 until values.length()) {
                values.getString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    suspend fun retainDeletedStableKeysPresentInBackups(
        database: NeriUserDataDatabase,
        libraryId: String,
        backupStableKeys: Set<String>
    ) {
        database.withTransaction {
            val current = deletedStableKeys(database, libraryId)
            val retained = current.intersect(backupStableKeys)
            if (retained != current) writeDeletedStableKeys(database, libraryId, retained)
        }
    }

    private suspend fun updateDeletedStableKeys(
        database: NeriUserDataDatabase,
        libraryId: String,
        removed: Set<String> = emptySet(),
        restored: Set<String> = emptySet()
    ) {
        if (removed.isEmpty() && restored.isEmpty()) return
        val current = deletedStableKeys(database, libraryId)
        val updated = (current + removed) - restored
        if (updated != current) writeDeletedStableKeys(database, libraryId, updated)
    }

    private suspend fun writeDeletedStableKeys(
        database: NeriUserDataDatabase,
        libraryId: String,
        stableKeys: Set<String>
    ) {
        val key = deletionMetadataKey(libraryId)
        if (stableKeys.isEmpty()) {
            database.syncMetadataDao().deleteMigrationMetadata(listOf(key))
        } else {
            database.syncMetadataDao().upsertMigrationMetadata(
                MigrationMetadataEntity(
                    key = key,
                    value = JSONArray(stableKeys.sorted()).toString(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun deletionMetadataKey(libraryId: String): String {
        return "managed_library_item_deleted_keys:$libraryId"
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
        "COMPLETED"
    )
}

internal fun shouldRemoveMissingCatalogPreview(
    state: String,
    leaseId: String?,
    needsReconcile: Boolean,
    presentInSnapshot: Boolean
): Boolean {
    return !presentInSnapshot &&
        leaseId.isNullOrBlank() &&
        !needsReconcile &&
        shouldRestoreManagedLibraryItem(state)
}
