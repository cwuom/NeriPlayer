package moe.ouom.neriplayer.core.download.storage.queue

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.CANCELLED_DOWNLOAD_KEYS_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.PENDING_DOWNLOAD_QUEUE_FILE_NAME
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadCancelledKeyEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadPendingQueueEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

internal class DownloadRecoveryRoomStore(
    private val context: Context,
    private val database: NeriUserDataDatabase
) {
    suspend fun upsertPendingDownloadQueue(
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val distinctSongs = songs.distinctBy(SongItem::stableKey)
        if (distinctSongs.isEmpty()) {
            return
        }
        globalMigrationMutex.withLock {
            ensurePendingQueueImported(nowMs)
            database.withTransaction {
                val dao = database.downloadRecoveryDao()
                val existingEntries = dao.getPendingQueue()
                    .associateBy(DownloadPendingQueueEntity::stableKey)
                var nextOrder = (existingEntries.values.maxOfOrNull { it.queueOrder } ?: -1) + 1
                dao.upsertPendingQueue(
                    distinctSongs.map { song ->
                        val stableKey = song.stableKey()
                        val existing = existingEntries[stableKey]
                        song.toEntity(
                            stableKey = stableKey,
                            queueOrder = existing?.queueOrder ?: nextOrder++,
                            queuedAtMs = existing?.queuedAtMs ?: nowMs
                        )
                    }
                )
            }
        }
    }

    suspend fun listPendingQueuedDownloads(): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        globalMigrationMutex.withLock {
            ensurePendingQueueImported()
            return database.downloadRecoveryDao()
                .getPendingQueue()
                .map { entity ->
                    ManagedDownloadStorage.PendingDownloadQueueEntry(
                        stableKey = entity.stableKey,
                        song = entity.toSong(),
                        order = entity.queueOrder,
                        queuedAtMs = entity.queuedAtMs
                    )
                }
        }
    }

    suspend fun removePendingDownloadQueueEntries(
        songKeys: Collection<String>
    ) {
        val keys = songKeys.filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) {
            return
        }
        globalMigrationMutex.withLock {
            ensurePendingQueueImported()
            keys.chunked(500).forEach { chunk ->
                database.downloadRecoveryDao().deletePendingQueue(chunk)
            }
        }
    }

    suspend fun clearPendingDownloadQueue() {
        globalMigrationMutex.withLock {
            ensurePendingQueueImported()
            database.downloadRecoveryDao().clearPendingQueue()
        }
    }

    suspend fun markCancelledDownloadKeys(
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val keys = songKeys.filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) {
            return
        }
        globalMigrationMutex.withLock {
            ensureCancelledKeysImported(nowMs)
            database.downloadRecoveryDao().insertCancelledKeys(
                keys.map { stableKey ->
                    DownloadCancelledKeyEntity(
                        stableKey = stableKey,
                        cancelledAtMs = nowMs
                    )
                }
            )
        }
    }

    suspend fun listCancelledDownloadKeys(): Set<String> {
        globalMigrationMutex.withLock {
            ensureCancelledKeysImported()
            return database.downloadRecoveryDao()
                .getCancelledKeys()
                .toSet()
        }
    }

    suspend fun removeCancelledDownloadKeys(songKeys: Collection<String>) {
        val keys = songKeys.filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) {
            return
        }
        globalMigrationMutex.withLock {
            ensureCancelledKeysImported()
            keys.chunked(500).forEach { chunk ->
                database.downloadRecoveryDao().deleteCancelledKeys(chunk)
            }
        }
    }

    suspend fun clearCancelledDownloadKeys() {
        globalMigrationMutex.withLock {
            ensureCancelledKeysImported()
            database.downloadRecoveryDao().clearCancelledKeys()
        }
    }

    private suspend fun ensurePendingQueueImported(
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (isRoomPrimary(PENDING_QUEUE_CUTOVER_STATE_KEY)) {
            return
        }
        val legacyEntries = readLegacyPendingQueue() ?: return
        database.withTransaction {
            if (isRoomPrimary(PENDING_QUEUE_CUTOVER_STATE_KEY)) {
                return@withTransaction
            }
            val dao = database.downloadRecoveryDao()
            dao.clearPendingQueue()
            dao.upsertPendingQueue(
                legacyEntries.map { entry ->
                    entry.song.toEntity(
                        stableKey = entry.stableKey,
                        queueOrder = entry.order,
                        queuedAtMs = entry.queuedAtMs
                    )
                }
            )
            markRoomPrimary(PENDING_QUEUE_CUTOVER_STATE_KEY, nowMs)
        }
    }

    private suspend fun ensureCancelledKeysImported(
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (isRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)) {
            return
        }
        val legacyKeys = readLegacyCancelledKeys() ?: return
        database.withTransaction {
            if (isRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)) {
                return@withTransaction
            }
            val dao = database.downloadRecoveryDao()
            dao.clearCancelledKeys()
            dao.insertCancelledKeys(
                legacyKeys.map { stableKey ->
                    DownloadCancelledKeyEntity(
                        stableKey = stableKey,
                        cancelledAtMs = nowMs
                    )
                }
            )
            markRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY, nowMs)
        }
    }

    private suspend fun isRoomPrimary(key: String): Boolean {
        return database.syncMetadataDao()
            .getMigrationMetadata(key)
            ?.value == ROOM_PRIMARY_STATE
    }

    private suspend fun markRoomPrimary(key: String, nowMs: Long) {
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = key,
                value = ROOM_PRIMARY_STATE,
                updatedAt = nowMs
            )
        )
    }

    private fun readLegacyPendingQueue(): List<ManagedDownloadStorage.PendingDownloadQueueEntry>? {
        val file = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        if (!file.exists()) {
            return emptyList()
        }
        val rawPayload = runCatching { file.readText(Charsets.UTF_8) }
            .onFailure { error ->
                NPLogger.w(TAG, "读取旧下载队列失败: ${error.message}")
            }
            .getOrNull()
            ?: return null
        if (rawPayload.isBlank()) {
            return emptyList()
        }
        return runCatching {
            ManagedDownloadStorageJsonCodec.parsePendingDownloadQueuePayload(rawPayload)
        }.onFailure { error ->
            NPLogger.w(TAG, "解析旧下载队列失败，保留文件等待下次迁移: ${error.message}")
        }.getOrNull()
    }

    private fun readLegacyCancelledKeys(): Set<String>? {
        val file = File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        if (!file.exists()) {
            return emptySet()
        }
        val rawPayload = runCatching { file.readText(Charsets.UTF_8) }
            .onFailure { error ->
                NPLogger.w(TAG, "读取旧下载取消标记失败: ${error.message}")
            }
            .getOrNull()
            ?: return null
        if (rawPayload.isBlank()) {
            return emptySet()
        }
        return runCatching {
            ManagedDownloadStorageJsonCodec.parseCancelledDownloadKeysPayload(rawPayload)
        }.onFailure { error ->
            NPLogger.w(TAG, "解析旧下载取消标记失败，保留文件等待下次迁移: ${error.message}")
        }.getOrNull()
    }

    companion object {
        const val PENDING_QUEUE_CUTOVER_STATE_KEY = "download_pending_queue_cutover_state"
        const val CANCELLED_KEYS_CUTOVER_STATE_KEY = "download_cancelled_keys_cutover_state"
        const val ROOM_PRIMARY_STATE = "room_primary"
        private const val TAG = "DownloadRecoveryRoomStore"
        private val globalMigrationMutex = Mutex()
    }
}

private fun SongItem.toEntity(
    stableKey: String,
    queueOrder: Int,
    queuedAtMs: Long
): DownloadPendingQueueEntity {
    return DownloadPendingQueueEntity(
        stableKey = stableKey,
        queueOrder = queueOrder,
        queuedAtMs = queuedAtMs,
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedLyricSource = matchedLyricSource?.name,
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId,
        sourceStableKey = sourceStableKey,
        streamUrl = streamUrl
    )
}

private fun DownloadPendingQueueEntity.toSong(): SongItem {
    return SongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedLyricSource = matchedLyricSource
            ?.let { value -> runCatching { MusicPlatform.valueOf(value) }.getOrNull() },
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId,
        sourceStableKey = sourceStableKey,
        streamUrl = streamUrl
    )
}
