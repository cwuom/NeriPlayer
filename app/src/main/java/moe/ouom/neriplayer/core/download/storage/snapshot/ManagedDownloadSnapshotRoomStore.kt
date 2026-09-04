package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import java.io.IOException

/** Compatibility adapter for code that still names the old Room store. */
internal class ManagedDownloadSnapshotRoomStore(
    private val context: Context,
    private val database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
) : ManagedDownloadSnapshotPersistenceStore {
    override suspend fun restore(
        expectedKey: String?
    ): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? {
        val restored = ManagedDownloadSnapshotDiskCache.restore(
            context.applicationContext,
            expectedKey
        ) ?: return null
        markRoomPrimary(restored.first)
        ManagedDownloadSnapshotDiskCache.delete(context.applicationContext)
        return restored
    }

    override suspend fun persist(
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): Boolean {
        val appContext = context.applicationContext
        val cacheFile = ManagedDownloadSnapshotDiskCache.cacheFile(appContext)
        val parent = cacheFile.parentFile
        if (
            parent != null &&
            !parent.isDirectory &&
            !parent.mkdirs() &&
            !parent.isDirectory
        ) {
            NPLogger.w(TAG, "无法创建下载索引缓存目录: ${parent.absolutePath}")
            return false
        }
        return try {
            cacheFile.writeText(
                ManagedDownloadSnapshotIndex.serializePayload(cacheKey, snapshot),
                Charsets.UTF_8
            )
            markRoomPrimary(cacheKey)
            true
        } catch (error: IOException) {
            NPLogger.w(TAG, "写入下载索引缓存失败: ${error.message}")
            false
        }
    }

    override suspend fun clear() {
        ManagedDownloadSnapshotDiskCache.delete(context.applicationContext)
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
        private const val TAG = "ManagedDownloadStorage"
        const val CUTOVER_STATE_METADATA_KEY = "managed_download_snapshot_cutover_state"
        const val ROOT_KEY_METADATA_KEY = "managed_download_snapshot_root_key"
        const val ROOM_PRIMARY_STATE = "room_primary"
    }
}
