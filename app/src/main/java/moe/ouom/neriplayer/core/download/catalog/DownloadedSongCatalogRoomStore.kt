package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedLibraryItemRoomStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.util.io.writeTextAtomically

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
            if (readCutoverState() == LEGACY_JSON_STATE) {
                val legacySongs = readLegacyCatalog(rootKey) ?: return null
                ManagedLibraryItemRoomStore.replacePreviews(context, legacySongs, database)
                markRoomPrimary(rootKey)
                return legacySongs
            }
            ManagedLibraryItemRoomStore.restore(context, database)?.let { return it }

            val legacySongs = readLegacyCatalog(rootKey) ?: return null
            ManagedLibraryItemRoomStore.replacePreviews(context, legacySongs, database)
            markRoomPrimary(rootKey)
            return legacySongs
        }
    }

    suspend fun persist(songs: List<DownloadedSong>) {
        persistCatalog(songs)
    }

    override suspend fun persistCatalog(songs: List<DownloadedSong>) {
        globalMutex.withLock {
            val rootKey = snapshotCacheKeyProvider(context)
            ManagedLibraryItemRoomStore.replacePreviews(context, songs, database)
            markRoomPrimary(rootKey)
        }
    }

    override suspend fun persistLegacyFallback(songs: List<DownloadedSong>) {
        globalMutex.withLock {
            val rootKey = snapshotCacheKeyProvider(context)
            writeLegacyCatalog(rootKey, songs)
            markLegacyJsonPrimary(rootKey)
        }
    }

    private suspend fun readCutoverState(): String? {
        return database.syncMetadataDao()
            .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
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
                expectedCacheKey = rootKey
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
