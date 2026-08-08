package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.CoverUrlMappingEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity

internal class CoverUrlMappingRoomStore(
    private val database: NeriUserDataDatabase
) {
    suspend fun readIfRoomPrimary(): Map<String, String>? {
        val state = database.syncMetadataDao()
            .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
            ?.value
        if (!isReadableRoomState(state)) {
            return null
        }
        return database.coverUrlMappingDao()
            .getAll()
            .associate { entity -> entity.localUrl to entity.networkUrl }
    }

    suspend fun importLegacyAndPromote(
        mappings: Map<String, String>,
        cleanupEligible: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val cutoverState = database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value
            if (isReadableRoomState(cutoverState)) {
                return@withTransaction
            }
            val dao = database.coverUrlMappingDao()
            dao.deleteAll()
            dao.upsert(mappings.toEntities(now))
            markRoomPrimary(cleanupEligible, now)
        }
    }

    suspend fun upsert(
        localUrl: String,
        networkUrl: String,
        cleanupEligible: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val currentState = database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value
            database.coverUrlMappingDao().upsert(
                CoverUrlMappingEntity(
                    localUrl = localUrl,
                    networkUrl = networkUrl,
                    updatedAt = now
                )
            )
            markRoomPrimary(
                cleanupEligible = currentState == ROOM_PRIMARY_STATE || cleanupEligible,
                now = now
            )
        }
    }

    suspend fun delete(
        localUrls: Collection<String>,
        cleanupEligible: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        val normalizedUrls = localUrls.filter(String::isNotBlank)
        if (normalizedUrls.isEmpty()) return
        database.withTransaction {
            val currentState = database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value
            normalizedUrls.chunked(500).forEach { chunk ->
                database.coverUrlMappingDao().delete(chunk)
            }
            markRoomPrimary(
                cleanupEligible = currentState == ROOM_PRIMARY_STATE || cleanupEligible,
                now = now
            )
        }
    }

    private suspend fun markRoomPrimary(cleanupEligible: Boolean, now: Long) {
        val state = if (cleanupEligible) {
            ROOM_PRIMARY_STATE
        } else {
            ROOM_PRIMARY_WITH_LEGACY_IMPORT_FAILURE_STATE
        }
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CUTOVER_STATE_METADATA_KEY, state, now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(IMPORT_SCHEMA_METADATA_KEY, "1", now)
        )
    }

    private fun Map<String, String>.toEntities(now: Long): List<CoverUrlMappingEntity> {
        return entries.map { (localUrl, networkUrl) ->
            CoverUrlMappingEntity(
                localUrl = localUrl,
                networkUrl = networkUrl,
                updatedAt = now
            )
        }
    }

    private fun metadata(key: String, value: String, now: Long): MigrationMetadataEntity {
        return MigrationMetadataEntity(
            key = key,
            value = value,
            updatedAt = now
        )
    }

    private fun isReadableRoomState(state: String?): Boolean {
        return state == ROOM_PRIMARY_STATE
    }

    companion object {
        const val CUTOVER_STATE_METADATA_KEY = "cover_url_mapping_cutover_state"
        const val IMPORT_SCHEMA_METADATA_KEY = "cover_url_mapping_import_schema"
        const val ROOM_PRIMARY_STATE = "room_primary"
        const val ROOM_PRIMARY_WITH_LEGACY_IMPORT_FAILURE_STATE =
            "room_primary_legacy_import_failed"
    }
}
