package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedLibraryItemRoomStore
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.util.io.writeTextAtomically

internal const val MANAGED_LIBRARY_CATALOG_BACKUP_SUFFIX = ".managed-v1.json"

internal class DownloadedSongCatalogRoomStore(
    private val context: Context,
    private val database: NeriUserDataDatabase,
    private val cacheFileName: String,
    private val snapshotCacheKeyProvider: (Context) -> String,
    private val loggerTag: String
) : DownloadedSongCatalogPersistenceStore {
    suspend fun restore(): List<DownloadedSong>? {
        globalMutex.withLock {
            val rootKey = snapshotCacheKeyProvider(context)
            val storedRootKey = database.syncMetadataDao()
                .getMigrationMetadata(ROOT_KEY_METADATA_KEY)
                ?.value
            if (storedRootKey != null && storedRootKey != rootKey) {
                return null
            }
            val roomSongs = ManagedLibraryItemRoomStore.restore(context, database)
            readManagedCatalogBackup(rootKey)?.let { backupSongs ->
                return mergeCatalogBackupWithPreviews(backupSongs, roomSongs.orEmpty())
            }
            // Room 行只保存轻量预览，升级首次恢复时必须先读取旧完整目录
            readLegacyCatalog(rootKey)?.let { legacySongs ->
                val mergedSongs = mergeCatalogBackupWithPreviews(
                    backupSongs = legacySongs,
                    previewSongs = roomSongs.orEmpty()
                )
                val roomUpdated = try {
                    ManagedLibraryItemRoomStore.replacePreviews(
                        context = context,
                        songs = mergedSongs,
                        database = database
                    )
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    NPLogger.w(
                        loggerTag,
                        "旧下载歌曲目录写入 Room 失败，保留完整目录: ${error.message}"
                    )
                    false
                }
                val backupWritten = writeManagedCatalogBackup(rootKey, mergedSongs)
                // 任一步提升失败都不能标记 Room 主数据，避免清理器删掉唯一完整载荷
                if (roomUpdated && backupWritten) {
                    markRoomPrimary(rootKey)
                }
                return mergedSongs
            }
            return roomSongs
        }
    }

    suspend fun persist(songs: List<DownloadedSong>) {
        persistCatalog(songs)
    }

    override suspend fun persistCatalog(songs: List<DownloadedSong>) {
        globalMutex.withLock {
            val rootKey = snapshotCacheKeyProvider(context)
            ManagedLibraryItemRoomStore.replacePreviews(
                context = context,
                songs = songs,
                database = database,
                stateForSong = { song ->
                    if (song.filePath.contains(PENDING_AUDIO_WRITE_MARKER)) {
                        "CORE_COMMITTED"
                    } else {
                        "FINALIZED"
                    }
                }
            )
            if (writeManagedCatalogBackup(rootKey, songs)) {
                markRoomPrimary(rootKey)
            }
        }
    }

    override suspend fun persistLegacyFallback(songs: List<DownloadedSong>) {
        globalMutex.withLock {
            val rootKey = snapshotCacheKeyProvider(context)
            writeLegacyCatalog(rootKey, songs)
            writeManagedCatalogBackup(rootKey, songs)
            markLegacyJsonPrimary(rootKey)
        }
    }

    private fun managedCatalogBackupFile(): File {
        return File(
            context.applicationContext.filesDir,
            "$cacheFileName$MANAGED_LIBRARY_CATALOG_BACKUP_SUFFIX"
        )
    }

    private fun writeManagedCatalogBackup(
        rootKey: String,
        songs: List<DownloadedSong>
    ): Boolean {
        val file = managedCatalogBackupFile()
        val written = writeManagedCatalogBackupFile(
            file = file,
            rootKey = rootKey,
            songs = songs
        )
        if (!written) {
            NPLogger.w(
                loggerTag,
                "写入完整下载目录备份失败，保留 Room 目录: ${file.name}"
            )
        }
        return written
    }

    private fun readManagedCatalogBackup(rootKey: String): List<DownloadedSong>? {
        val file = managedCatalogBackupFile()
        if (!file.isFile) return null
        return readManagedCatalogBackupFile(file, rootKey)
            .also { songs ->
                if (songs == null) {
                    NPLogger.w(loggerTag, "完整下载目录备份无效，回退 Room 预览: ${file.name}")
                }
            }
    }

    private suspend fun markRoomPrimary(rootKey: String) {
        val nowMs = System.currentTimeMillis()
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = CUTOVER_STATE_METADATA_KEY,
                value = ROOM_PRIMARY_STATE,
                updatedAt = nowMs
            )
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = ROOT_KEY_METADATA_KEY,
                value = rootKey,
                updatedAt = nowMs
            )
        )
    }

    private suspend fun markLegacyJsonPrimary(rootKey: String) {
        val nowMs = System.currentTimeMillis()
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = CUTOVER_STATE_METADATA_KEY,
                value = LEGACY_JSON_STATE,
                updatedAt = nowMs
            )
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = ROOT_KEY_METADATA_KEY,
                value = rootKey,
                updatedAt = nowMs
            )
        )
    }

    private fun writeLegacyCatalog(rootKey: String, songs: List<DownloadedSong>) {
        File(context.filesDir, cacheFileName).writeTextAtomically(
            serializeDownloadedSongsCatalog(
                cacheKey = rootKey,
                songs = songs
            )
        )
    }

    private fun readLegacyCatalog(rootKey: String): List<DownloadedSong>? {
        val file = File(context.filesDir, cacheFileName)
        if (!file.exists()) {
            return null
        }
        val rawPayload = runCatching { file.readText(Charsets.UTF_8) }
            .onFailure { error ->
                NPLogger.w(loggerTag, "读取旧下载歌曲目录失败: ${error.message}")
            }
            .getOrNull()
            ?: return null
        if (rawPayload.isBlank()) {
            return null
        }
        return runCatching {
            deserializeDownloadedSongsCatalog(
                raw = rawPayload,
                expectedCacheKey = rootKey,
                includeOriginalLyrics = true
            )
        }.onFailure { error ->
            NPLogger.w(loggerTag, "解析旧下载歌曲目录失败，保留文件等待下次迁移: ${error.message}")
        }.getOrNull()
    }

    companion object {
        const val CUTOVER_STATE_METADATA_KEY = "managed_library_item_cutover_state"
        const val ROOT_KEY_METADATA_KEY = "managed_library_item_root_key"
        const val ROOM_PRIMARY_STATE = "room_primary"
        const val LEGACY_JSON_STATE = "legacy_json"
        private val globalMutex = Mutex()
    }
}

internal fun writeManagedCatalogBackupFile(
    file: File,
    rootKey: String,
    songs: List<DownloadedSong>,
    writeAtomically: (File, String) -> Unit = { target, payload ->
        target.writeTextAtomically(payload)
    }
): Boolean {
    return runCatching {
        writeAtomically(
            file,
            serializeDownloadedSongsCatalog(
                cacheKey = rootKey,
                songs = songs,
                includeOriginalLyrics = true
            )
        )
        true
    }.getOrElse { false }
}

internal fun readManagedCatalogBackupFile(
    file: File,
    rootKey: String
): List<DownloadedSong>? {
    if (!file.isFile) return null
    return runCatching {
        deserializeDownloadedSongsCatalog(
            raw = file.readText(Charsets.UTF_8),
            expectedCacheKey = rootKey,
            includeOriginalLyrics = true
        )
    }.getOrNull()
}

internal fun mergeCatalogBackupWithPreviews(
    backupSongs: List<DownloadedSong>,
    previewSongs: List<DownloadedSong>
): List<DownloadedSong> {
    if (backupSongs.isEmpty()) return previewSongs
    val previewsByStableKey = linkedMapOf<String, Int>()
    val previewsByReference = linkedMapOf<String, Int>()
    previewSongs.forEachIndexed { index, preview ->
        preview.stableKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { key -> previewsByStableKey[key] = index }
        preview.filePath
            .trim()
            .takeIf(String::isNotBlank)
            ?.let { reference -> previewsByReference[reference] = index }
        preview.mediaUri
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { reference -> previewsByReference[reference] = index }
    }

    val consumedPreviewIndices = BooleanArray(previewSongs.size)
    val merged = ArrayList<DownloadedSong>(backupSongs.size + previewSongs.size)
    val mergedStableKeys = hashSetOf<String>()
    val mergedReferences = hashSetOf<String>()

    fun addMergedIdentity(song: DownloadedSong) {
        song.stableKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(mergedStableKeys::add)
        song.filePath
            .trim()
            .takeIf(String::isNotBlank)
            ?.let(mergedReferences::add)
        song.mediaUri
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(mergedReferences::add)
    }

    backupSongs.forEach { backup ->
        val stableKey = backup.stableKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val previewIndex = stableKey
            ?.let(previewsByStableKey::get)
            ?.takeUnless(consumedPreviewIndices::get)
            ?: previewsByReference[backup.filePath.trim()]
                ?.takeUnless(consumedPreviewIndices::get)
            ?: backup.mediaUri
                ?.trim()
                ?.let(previewsByReference::get)
                ?.takeUnless(consumedPreviewIndices::get)
        val preview = previewIndex?.let { index ->
            consumedPreviewIndices[index] = true
            previewSongs[index]
        }
        val mergedSong = if (preview == null) {
            backup
        } else {
            backup.copy(
                filePath = preview.filePath.takeIf(String::isNotBlank) ?: backup.filePath,
                fileSize = preview.fileSize.takeIf { it > 0L } ?: backup.fileSize,
                downloadTime = preview.downloadTime.takeIf { it > 0L } ?: backup.downloadTime,
                name = backup.name.ifBlank { preview.name },
                artist = backup.artist.ifBlank { preview.artist },
                album = backup.album.ifBlank { preview.album },
                coverPath = preview.coverPath?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf(::isResolvableLocalReference)
                    ?: backup.coverPath,
                mediaUri = preview.mediaUri ?: backup.mediaUri
            )
        }
        merged += mergedSong
        addMergedIdentity(mergedSong)
    }

    previewSongs.forEachIndexed { index, preview ->
        if (consumedPreviewIndices[index]) return@forEachIndexed
        val stableKey = preview.stableKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val alreadyMerged = (stableKey != null && stableKey in mergedStableKeys) ||
            preview.filePath.trim() in mergedReferences ||
            (preview.mediaUri?.trim()?.takeIf(String::isNotBlank) in mergedReferences)
        if (alreadyMerged) return@forEachIndexed
        merged += preview
        addMergedIdentity(preview)
    }
    return merged
}
