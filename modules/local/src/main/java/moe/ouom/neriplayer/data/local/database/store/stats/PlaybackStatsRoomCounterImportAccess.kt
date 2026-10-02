package moe.ouom.neriplayer.data.local.database.store.stats

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toSnapshotData
import moe.ouom.neriplayer.data.sync.merge.stats.SyncCounterShardPolicy
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS

internal class PlaybackStatsRoomCounterImportAccess(private val store: PlaybackStatsRoomStore) {
    private val dao = store.database.playbackStatsSnapshotDao()

    suspend fun writeTracks(id: String, rows: List<PlaybackStatsSnapshotCounterEntity>) {
        require(rows.size <= SYNC_PLAYBACK_PAGE_RECORDS && rows.all { it.snapshotId == id })
        val incoming = normalizeTracks(id, rows)
        if (incoming.isEmpty()) return
        store.database.withTransaction {
            val existing = dao.queryCounterCandidates(lookup(id, "playback_stat_snapshot_counter",
                listOf("identity_key", "device_id", "epoch_started_at"), incoming.map {
                    listOf(it.shard.identityKey, it.shard.deviceId, it.shard.epochStartedAt)
                }))
            dao.upsertCounters(normalizeTracks(id, incoming + existing))
        }
    }

    suspend fun writeDaily(id: String, rows: List<PlaybackStatsSnapshotDailyCounterEntity>) {
        require(rows.size <= SYNC_PLAYBACK_PAGE_RECORDS && rows.all { it.snapshotId == id })
        val incoming = normalizeDaily(id, rows)
        if (incoming.isEmpty()) return
        store.database.withTransaction {
            // 日桶候选多一列，分两次查询让旧 SQLite 的绑定参数始终小于 999
            val existing = incoming.chunked(128).flatMap { page ->
                dao.queryDailyCounterCandidates(lookup(id, "playback_stat_snapshot_daily_counter",
                    listOf("day_start_at", "identity_key", "device_id", "epoch_started_at"), page.map {
                        listOf(it.shard.dayStartAt, it.shard.identityKey, it.shard.deviceId, it.shard.epochStartedAt)
                    }))
            }
            dao.upsertDailyCounters(normalizeDaily(id, incoming + existing))
        }
    }

    private fun normalizeTracks(id: String, rows: List<PlaybackStatsSnapshotCounterEntity>): List<PlaybackStatsSnapshotCounterEntity> =
        rows.groupBy { it.shard.identityKey }.flatMap { (identity, shards) ->
            SyncCounterShardPolicy.normalizeCounterShards(shards.map { it.shard.toEntity().toDomain() }).map {
                PlaybackStatsSnapshotCounterEntity(id, it.toTrackEntity(identity).toSnapshotData())
            }
        }

    private fun normalizeDaily(id: String, rows: List<PlaybackStatsSnapshotDailyCounterEntity>): List<PlaybackStatsSnapshotDailyCounterEntity> =
        rows.groupBy { it.shard.dayStartAt to it.shard.identityKey }.flatMap { (key, shards) ->
            SyncCounterShardPolicy.normalizeCounterShards(shards.map { it.shard.toEntity().toDomain() }).map {
                PlaybackStatsSnapshotDailyCounterEntity(id, it.toDailyEntity(key.first, key.second).toSnapshotData())
            }
        }

    private fun lookup(id: String, table: String, columns: List<String>, keys: List<List<Any>>): SimpleSQLiteQuery {
        require(keys.isNotEmpty())
        val values = keys.joinToString(",") { "(" + columns.joinToString(",") { "?" } + ")" }
        val matches = columns.joinToString(" AND ") { "s.$it = requested.$it" }
        // 小候选必须作为外层，不能退化成按 snapshot_id 扫描全部历史分片
        return SimpleSQLiteQuery("WITH requested(" + columns.joinToString(",") + ") AS (VALUES $values) " +
            "SELECT s.* FROM requested CROSS JOIN $table s WHERE s.snapshot_id = ? AND $matches",
            (keys.flatten() + id).toTypedArray())
    }
}
