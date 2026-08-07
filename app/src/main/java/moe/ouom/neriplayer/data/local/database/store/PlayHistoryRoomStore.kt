package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import moe.ouom.neriplayer.data.history.PlayedEntry
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase

internal enum class PlayHistoryRoomImportStatus {
    IMPORTED,
    SKIPPED_NOT_EQUIVALENT
}

internal data class PlayHistoryRoomImportResult(
    val status: PlayHistoryRoomImportStatus,
    val entryCount: Int
)

internal class PlayHistoryRoomStore(
    private val database: NeriUserDataDatabase
) {
    private val mapper = PlayHistoryRoomMapper()

    suspend fun readIfRoomPrimary(): List<PlayedEntry>? {
        val state = database.syncMetadataDao()
            .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
            ?.value
        if (state != ROOM_PRIMARY_STATE) return null
        return mapper.toDomain(database.playHistoryDao().getAll())
    }

    suspend fun importLegacyAndPromote(
        entries: List<PlayedEntry>,
        now: Long = System.currentTimeMillis()
    ): PlayHistoryRoomImportResult {
        if (!mapper.validateRoundTrip(entries)) {
            return PlayHistoryRoomImportResult(
                status = PlayHistoryRoomImportStatus.SKIPPED_NOT_EQUIVALENT,
                entryCount = entries.size
            )
        }
        replaceAll(entries, now)
        return PlayHistoryRoomImportResult(
            status = PlayHistoryRoomImportStatus.IMPORTED,
            entryCount = entries.size
        )
    }

    suspend fun writeIncremental(
        previous: List<PlayedEntry>,
        next: List<PlayedEntry>,
        now: Long = System.currentTimeMillis()
    ) {
        val previousByKey = previous.associateBy(::identityKey)
        val nextByKey = next.associateBy(::identityKey)
        val changedEntries = next.filter { entry ->
            previousByKey[identityKey(entry)] != entry
        }
        val removedKeys = previousByKey.keys - nextByKey.keys
        database.withTransaction {
            if (changedEntries.isNotEmpty()) {
                database.playHistoryDao().upsert(mapper.toEntities(changedEntries))
            }
            removedKeys.toList().chunked(500).forEach { chunk ->
                if (chunk.isNotEmpty()) {
                    database.playHistoryDao().deleteByIdentityKeys(chunk)
                }
            }
            markRoomPrimary(now)
        }
    }

    suspend fun replaceAll(
        entries: List<PlayedEntry>,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            database.playHistoryDao().deleteAll()
            database.playHistoryDao().upsert(mapper.toEntities(entries))
            markRoomPrimary(now)
        }
    }

    suspend fun clear(now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            database.playHistoryDao().deleteAll()
            markRoomPrimary(now)
        }
    }

    suspend fun markLegacyJsonPrimary(now: Long = System.currentTimeMillis()) {
        database.syncMetadataDao().upsertMigrationMetadata(
            migrationMetadata(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, now)
        )
    }

    private suspend fun markRoomPrimary(now: Long) {
        database.syncMetadataDao().upsertMigrationMetadata(
            migrationMetadata(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            migrationMetadata(
                IMPORT_SCHEMA_METADATA_KEY,
                PLAY_HISTORY_SCHEMA_VERSION.toString(),
                now
            )
        )
    }

    private fun identityKey(entry: PlayedEntry): String {
        return buildString {
            append(entry.id)
            append('|')
            append(entry.album)
            append('|')
            append(entry.localFilePath ?: entry.mediaUri.orEmpty())
        }
    }

    private fun migrationMetadata(
        key: String,
        value: String,
        now: Long
    ) = moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity(
        key = key,
        value = value,
        updatedAt = now
    )

    companion object {
        const val CUTOVER_STATE_METADATA_KEY = "play_history_cutover_state"
        const val IMPORT_SCHEMA_METADATA_KEY = "play_history_import_schema"
        const val ROOM_PRIMARY_STATE = "room_primary"
        const val LEGACY_JSON_STATE = "legacy_json"
        private const val PLAY_HISTORY_SCHEMA_VERSION = 1
    }
}
