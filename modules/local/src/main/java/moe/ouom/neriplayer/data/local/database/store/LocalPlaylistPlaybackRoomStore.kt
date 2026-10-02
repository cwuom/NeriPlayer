package moe.ouom.neriplayer.data.local.database.store


import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.stats.LocalPlaylistPlaybackCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsEventReceiptEntity
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlayBucket
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import java.io.IOException

internal class LocalPlaylistPlaybackEventConflictException : IOException("Local playlist event identity reused with different content")

internal class LocalPlaylistPlaybackRoomStore(
    private val database: NeriUserDataDatabase
) {
    suspend fun readIfRoomPrimary(): List<LocalPlaylistPlaybackStat>? {
        if (database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value != ROOM_PRIMARY_STATE
        ) {
            return null
        }
        return readStats()
    }

    suspend fun importLegacyAndPromote(
        stats: List<LocalPlaylistPlaybackStat>,
        now: Long = System.currentTimeMillis()
    ) {
        replaceAll(stats, now)
    }

    suspend fun replaceAll(
        stats: List<LocalPlaylistPlaybackStat>,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            database.localPlaylistPlaybackDao().deleteAllCounterShards()
            database.localPlaylistPlaybackDao().deleteAllBuckets()
            database.localPlaylistPlaybackDao().deleteAllStats()
            insertStats(stats)
            markRoomPrimary(now)
        }
    }

    suspend fun markLegacyJsonPrimary(now: Long = System.currentTimeMillis()) {
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, now)
        )
    }

    suspend fun writeIncremental(
        previous: List<LocalPlaylistPlaybackStat>,
        next: List<LocalPlaylistPlaybackStat>,
        now: Long = System.currentTimeMillis()
    ) {
        val previousById = previous.associateBy(LocalPlaylistPlaybackStat::playlistId)
        val nextById = next.associateBy(LocalPlaylistPlaybackStat::playlistId)
        val changedIds = (previousById.keys + nextById.keys)
            .filter { id -> previousById[id] != nextById[id] }
            .toSet()
        val removedIds = changedIds.filter { it !in nextById }
        database.withTransaction {
            removedIds.chunked(500).forEach { chunk ->
                if (chunk.isNotEmpty()) {
                    database.localPlaylistPlaybackDao().deleteCounterShards(chunk)
                    database.localPlaylistPlaybackDao().deleteBuckets(chunk)
                    database.localPlaylistPlaybackDao().deleteStats(chunk)
                }
            }
            changedIds
                .filter { it in nextById }
                .toList()
                .chunked(500)
                .forEach { chunk ->
                    database.localPlaylistPlaybackDao().deleteCounterShards(chunk)
                    database.localPlaylistPlaybackDao().deleteBuckets(chunk)
                }
            insertStats(next.filter { it.playlistId in changedIds })
            markRoomPrimary(now)
        }
    }

    suspend fun writeIncrementalOnce(
        previous: List<LocalPlaylistPlaybackStat>,
        next: List<LocalPlaylistPlaybackStat>,
        eventId: String,
        payloadHash: String,
        now: Long
    ): Boolean = database.withTransaction {
        val receiptId = "local-playlist-play:$eventId"
        val dao = database.playbackStatsSnapshotDao()
        val receipt = dao.receipt(receiptId)
        if (receipt != null) {
            if (receipt.payloadHash != payloadHash) throw LocalPlaylistPlaybackEventConflictException()
            return@withTransaction false
        }
        // 回执和递增共用事务，提交后的取消及进程重启都不会重复计数
        writeIncremental(previous, next, now)
        dao.insertReceipt(PlaybackStatsEventReceiptEntity(receiptId, now, payloadHash))
        dao.pruneCompletedReceipts(PlaybackStatsRoomStore.RETAINED_EVENT_RECEIPTS)
        true
    }

    private suspend fun insertStats(stats: List<LocalPlaylistPlaybackStat>) {
        database.localPlaylistPlaybackDao().upsertStats(
            stats.map(LocalPlaylistPlaybackStat::toEntity)
        )
        database.localPlaylistPlaybackDao().upsertBuckets(
            stats.flatMap { stat ->
                stat.dailyPlayBuckets.orEmpty().map { bucket ->
                    bucket.toEntity(stat.playlistId)
                }
            }
        )
        database.localPlaylistPlaybackDao().upsertCounterShards(
            stats.flatMap { stat ->
                buildList {
                    addAll(
                        stat.counterShards.orEmpty().map { shard ->
                            shard.toEntity(stat.playlistId, dayStartAt = 0L)
                        }
                    )
                    stat.dailyPlayBuckets.orEmpty().forEach { bucket ->
                        addAll(
                            bucket.counterShards.orEmpty().map { shard ->
                                shard.toEntity(stat.playlistId, bucket.dayStartAt)
                            }
                        )
                    }
                }
            }
        )
    }

    private suspend fun readStats(): List<LocalPlaylistPlaybackStat> {
        val bucketsByPlaylist = database.localPlaylistPlaybackDao()
            .getBuckets()
            .groupBy { it.playlistId }
        val shardsByScope = database.localPlaylistPlaybackDao()
            .getCounterShards()
            .groupBy { it.playlistId to it.dayStartAt }
        return database.localPlaylistPlaybackDao().getStats().map { stat ->
            LocalPlaylistPlaybackStat(
                playlistId = stat.playlistId,
                totalPlayCount = stat.totalPlayCount,
                firstPlayedAt = stat.firstPlayedAt,
                lastPlayedAt = stat.lastPlayedAt,
                counterBasePlayCount = stat.counterBasePlayCount,
                counterShards = shardsByScope[stat.playlistId to 0L]
                    .orEmpty()
                    .map(::toCounterShard),
                dailyPlayBuckets = bucketsByPlaylist[stat.playlistId]
                    .orEmpty()
                    .map { bucket ->
                        LocalPlaylistPlayBucket(
                            dayStartAt = bucket.dayStartAt,
                            playCount = bucket.playCount,
                            firstPlayedAt = bucket.firstPlayedAt,
                            lastPlayedAt = bucket.lastPlayedAt,
                            counterBasePlayCount = bucket.counterBasePlayCount,
                            counterShards = shardsByScope[
                                stat.playlistId to bucket.dayStartAt
                            ].orEmpty().map(::toCounterShard)
                        )
                    }
            )
        }
    }

    private fun toCounterShard(
        shard: LocalPlaylistPlaybackCounterShardEntity
    ): SyncPlaybackCounterShard {
        return SyncPlaybackCounterShard(
            deviceId = shard.deviceId,
            epochStartedAt = shard.epochStartedAt,
            playCount = shard.playCount,
            firstPlayedAt = shard.firstPlayedAt,
            lastPlayedAt = shard.lastPlayedAt
        )
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
        const val CUTOVER_STATE_METADATA_KEY = "local_playlist_playback_cutover_state"
        const val IMPORT_SCHEMA_METADATA_KEY = "local_playlist_playback_import_schema"
        const val ROOM_PRIMARY_STATE = "room_primary"
        const val LEGACY_JSON_STATE = "legacy_json"
    }
}
