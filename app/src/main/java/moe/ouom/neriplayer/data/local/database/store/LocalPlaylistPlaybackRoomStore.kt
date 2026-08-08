package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.toEntity
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlayBucket
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard

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
        database.withTransaction {
            val cutoverState = database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value
            if (cutoverState == ROOM_PRIMARY_STATE) {
                return@withTransaction
            }
            database.localPlaylistPlaybackDao().deleteAllCounterShards()
            database.localPlaylistPlaybackDao().deleteAllBuckets()
            database.localPlaylistPlaybackDao().deleteAllStats()
            insertStats(stats)
            markRoomPrimary(now)
        }
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
        shard: moe.ouom.neriplayer.data.local.database.entity
            .LocalPlaylistPlaybackCounterShardEntity
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
    }
}
