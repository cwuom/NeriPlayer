package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.TrafficStatsBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.toDomain
import moe.ouom.neriplayer.data.local.database.entity.toEntity
import moe.ouom.neriplayer.data.traffic.TrafficStatsBucket

internal class TrafficStatsRoomStore(
    private val database: NeriUserDataDatabase
) {
    suspend fun readIfRoomPrimary(): List<TrafficStatsBucket>? {
        if (database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value != ROOM_PRIMARY_STATE
        ) {
            return null
        }
        return database.trafficStatsDao().getAll().map(TrafficStatsBucketEntity::toDomain)
    }

    suspend fun importLegacyAndPromote(
        buckets: List<TrafficStatsBucket>,
        now: Long = System.currentTimeMillis()
    ) {
        replaceAll(buckets, now)
    }

    suspend fun replaceAll(
        buckets: List<TrafficStatsBucket>,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            database.trafficStatsDao().deleteAll()
            database.trafficStatsDao().upsert(buckets.map(TrafficStatsBucket::toEntity))
            markRoomPrimary(now)
        }
    }

    suspend fun writeIncremental(
        previous: List<TrafficStatsBucket>,
        next: List<TrafficStatsBucket>,
        now: Long = System.currentTimeMillis()
    ) {
        val previousByDay = previous.associateBy(TrafficStatsBucket::dayStartAt)
        val nextByDay = next.associateBy(TrafficStatsBucket::dayStartAt)
        val changedDays = (previousByDay.keys + nextByDay.keys)
            .filter { day -> previousByDay[day] != nextByDay[day] }
            .toSet()
        database.withTransaction {
            val dao = database.trafficStatsDao()
            changedDays.toList().chunked(500).forEach { chunk ->
                if (chunk.isNotEmpty()) dao.delete(chunk)
            }
            dao.upsert(
                next.filter { it.dayStartAt in changedDays }
                    .map(TrafficStatsBucket::toEntity)
            )
            markRoomPrimary(now)
        }
    }

    suspend fun clear(now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            database.trafficStatsDao().deleteAll()
            markRoomPrimary(now)
        }
    }

    private suspend fun markRoomPrimary(now: Long) {
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(IMPORT_SCHEMA_METADATA_KEY, "1", now)
        )
    }

    private fun metadata(key: String, value: String, now: Long) =
        moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity(
            key = key,
            value = value,
            updatedAt = now
        )

    companion object {
        const val CUTOVER_STATE_METADATA_KEY = "traffic_stats_cutover_state"
        const val IMPORT_SCHEMA_METADATA_KEY = "traffic_stats_import_schema"
        const val ROOM_PRIMARY_STATE = "room_primary"
    }
}
