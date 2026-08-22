package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity

internal class ManagedDownloadSnapshotRoomStore(
    private val context: Context,
    private val database: NeriUserDataDatabase =
        NeriUserDataDatabase.getInstance(context.applicationContext)
) : ManagedDownloadSnapshotPersistenceStore {
    override suspend fun restore(
        expectedKey: String?
    ): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? {
        return globalMutex.withLock {
            runCatching {
                restoreLocked(expectedKey)
            }.onFailure { error ->
                NPLogger.w(TAG, "读取下载索引数据库缓存失败: ${error.message}")
            }.getOrNull()
        }
    }

    override suspend fun persist(
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): Boolean {
        return globalMutex.withLock {
            persistLocked(cacheKey, snapshot)
        }
    }

    override suspend fun clear() {
        globalMutex.withLock {
            runCatching {
                database.withTransaction {
                    database.downloadSnapshotDao().clearEntries()
                    database.downloadSnapshotDao().clearMetadata()
                    database.syncMetadataDao().deleteMigrationMetadata(
                        listOf(CUTOVER_STATE_METADATA_KEY, ROOT_KEY_METADATA_KEY)
                    )
                }
            }.onFailure { error ->
                NPLogger.w(TAG, "清理下载索引数据库缓存失败: ${error.message}")
            }
        }
        ManagedDownloadSnapshotDiskCache.delete(context.applicationContext)
    }

    private suspend fun restoreLocked(
        expectedKey: String?
    ): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? {
        if (isRoomPrimary()) {
            val rootKey = readRootKey() ?: return null
            if (expectedKey != null && expectedKey != rootKey) {
                return null
            }
            return rootKey to readSnapshot(rootKey)
        }

        val legacy = ManagedDownloadSnapshotDiskCache.restore(
            context = context.applicationContext,
            expectedKey = expectedKey
        ) ?: return null
        if (persistLocked(legacy.first, legacy.second)) {
            ManagedDownloadSnapshotDiskCache.delete(context.applicationContext)
        }
        return legacy
    }

    private suspend fun persistLocked(
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): Boolean {
        return runCatching {
            database.withTransaction {
                val dao = database.downloadSnapshotDao()
                val entries = ManagedDownloadSnapshotRoomMapper.toEntryEntities(cacheKey, snapshot)
                ManagedDownloadSnapshotRoomMapper.BUCKETS.forEach { bucket ->
                    val bucketEntries = entries.filter { it.bucket == bucket }
                    val nextKeys = bucketEntries.mapTo(HashSet(bucketEntries.size)) {
                        it.entryKey
                    }
                    val staleKeys = dao.getEntryKeys(cacheKey, bucket)
                        .filterNot(nextKeys::contains)
                    if (staleKeys.isNotEmpty()) {
                        dao.deleteEntries(cacheKey, bucket, staleKeys)
                    }
                    if (bucketEntries.isNotEmpty()) {
                        dao.upsertEntries(bucketEntries)
                    }
                }

                val metadata = ManagedDownloadSnapshotRoomMapper.toMetadataEntities(cacheKey, snapshot)
                val nextAudioNames = metadata.mapTo(HashSet(metadata.size)) {
                    it.audioName
                }
                val staleAudioNames = dao.getMetadataAudioNames(cacheKey)
                    .filterNot(nextAudioNames::contains)
                if (staleAudioNames.isNotEmpty()) {
                    dao.deleteMetadata(cacheKey, staleAudioNames)
                }
                if (metadata.isNotEmpty()) {
                    dao.upsertMetadata(metadata)
                }
                markRoomPrimary(cacheKey)
            }
            true
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载索引数据库缓存失败: ${error.message}")
        }.getOrDefault(false)
    }

    private suspend fun readSnapshot(rootKey: String): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val dao = database.downloadSnapshotDao()
        return ManagedDownloadSnapshotRoomMapper.toSnapshot(
            audioEntries = dao.getEntries(rootKey, ManagedDownloadSnapshotRoomMapper.BUCKET_AUDIO),
            metadataEntries = dao.getEntries(rootKey, ManagedDownloadSnapshotRoomMapper.BUCKET_METADATA),
            metadata = dao.getMetadata(rootKey),
            coverEntries = dao.getEntries(rootKey, ManagedDownloadSnapshotRoomMapper.BUCKET_COVER),
            lyricEntries = dao.getEntries(rootKey, ManagedDownloadSnapshotRoomMapper.BUCKET_LYRIC)
        )
    }

    private suspend fun isRoomPrimary(): Boolean {
        return database.syncMetadataDao()
            .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
            ?.value == ROOM_PRIMARY_STATE
    }

    private suspend fun readRootKey(): String? {
        return database.syncMetadataDao()
            .getMigrationMetadata(ROOT_KEY_METADATA_KEY)
            ?.value
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

    companion object {
        const val CUTOVER_STATE_METADATA_KEY = "managed_download_snapshot_cutover_state"
        const val ROOT_KEY_METADATA_KEY = "managed_download_snapshot_root_key"
        const val ROOM_PRIMARY_STATE = "room_primary"
        private const val TAG = "ManagedDownloadStorage"
        private val globalMutex = Mutex()
    }
}
