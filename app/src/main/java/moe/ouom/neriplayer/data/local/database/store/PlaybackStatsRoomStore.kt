package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.entity.toEntity
import moe.ouom.neriplayer.data.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.stats.TrackStat
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard

internal data class PlaybackStatsRoomSnapshot(
    val stats: List<TrackStat>,
    val dailyStats: List<PlaybackStatBucket>,
    val counterSnapshot: PlaybackStatsSyncCounterSnapshot,
    val counterEpochStartedAt: Long,
    val clearedAt: Long
)

internal class PlaybackStatsRoomStore(
    private val database: NeriUserDataDatabase
) {
    suspend fun readIfRoomPrimary(): PlaybackStatsRoomSnapshot? {
        if (database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value != ROOM_PRIMARY_STATE
        ) {
            return null
        }
        return readSnapshot()
    }

    suspend fun importLegacyAndPromote(
        stats: List<TrackStat>,
        dailyStats: List<PlaybackStatBucket>,
        counterSnapshot: PlaybackStatsSyncCounterSnapshot,
        counterEpochStartedAt: Long,
        clearedAt: Long,
        now: Long = System.currentTimeMillis()
    ) {
        replaceAll(
            stats = stats,
            dailyStats = dailyStats,
            counterSnapshot = counterSnapshot,
            counterEpochStartedAt = counterEpochStartedAt,
            clearedAt = clearedAt,
            now = now
        )
    }

    suspend fun replaceAll(
        stats: List<TrackStat>,
        dailyStats: List<PlaybackStatBucket>,
        counterSnapshot: PlaybackStatsSyncCounterSnapshot,
        counterEpochStartedAt: Long,
        clearedAt: Long,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val dao = database.playbackStatsDao()
            dao.deleteAllDailyCounterShards()
            dao.deleteAllCounterShards()
            dao.deleteAllBuckets()
            dao.deleteAllStats()
            insertAll(
                stats = stats,
                dailyStats = dailyStats,
                counterSnapshot = counterSnapshot
            )
            markRoomPrimary(
                clearedAt = clearedAt,
                counterEpochStartedAt = counterEpochStartedAt,
                now = now
            )
        }
    }

    suspend fun writeIncremental(
        previousStats: List<TrackStat>,
        nextStats: List<TrackStat>,
        previousDailyStats: List<PlaybackStatBucket>,
        nextDailyStats: List<PlaybackStatBucket>,
        previousCounterSnapshot: PlaybackStatsSyncCounterSnapshot,
        counterSnapshot: PlaybackStatsSyncCounterSnapshot,
        counterEpochStartedAt: Long,
        clearedAt: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val previousByKey = previousStats.associateBy(TrackStat::identityKey)
        val nextByKey = nextStats.associateBy(TrackStat::identityKey)
        val previousDailyByKey = previousDailyStats.groupBy(PlaybackStatBucket::identityKey)
        val nextDailyByKey = nextDailyStats.groupBy(PlaybackStatBucket::identityKey)
        val previousTrackCounterKeys = previousCounterSnapshot.trackShardsByIdentity.keys
        val nextTrackCounterKeys = counterSnapshot.trackShardsByIdentity.keys
        val previousDailyCounterKeys = previousCounterSnapshot.dailyShardsByBucketKey.keys
        val nextDailyCounterKeys = counterSnapshot.dailyShardsByBucketKey.keys
        val counterChangedKeys = (
            previousTrackCounterKeys + nextTrackCounterKeys +
                previousDailyCounterKeys.map(::identityKeyFromDailyCounterKey) +
                nextDailyCounterKeys.map(::identityKeyFromDailyCounterKey)
            ).filter { key ->
                previousCounterSnapshot.trackShards(key) != counterSnapshot.trackShards(key) ||
                    dailyCounterKeysForIdentity(
                        previousCounterSnapshot,
                        key
                    ) != dailyCounterKeysForIdentity(counterSnapshot, key)
            }
            .toSet()
        val changedKeys = (previousByKey.keys + nextByKey.keys +
            previousDailyByKey.keys + nextDailyByKey.keys + counterChangedKeys)
            .filter { key ->
                previousByKey[key] != nextByKey[key] ||
                    previousDailyByKey[key] != nextDailyByKey[key] ||
                    key in counterChangedKeys
            }
            .toSet()

        database.withTransaction {
            val dao = database.playbackStatsDao()
            changedKeys.chunked(500).forEach { chunk ->
                if (chunk.isEmpty()) return@forEach
                dao.deleteDailyCounterShards(chunk)
                dao.deleteCounterShards(chunk)
                dao.deleteBuckets(chunk)
                dao.deleteStats(chunk.filter { it !in nextByKey })
            }
            changedKeys
                .filter { it in nextByKey }
                .toList()
                .chunked(500)
                .forEach { chunk ->
                    if (chunk.isEmpty()) return@forEach
                    val chunkStats = nextStats.filter { it.identityKey in chunk }
                    val chunkBuckets = nextDailyStats.filter { it.identityKey in chunk }
                    dao.upsertStats(chunkStats.map(TrackStat::toEntity))
                    dao.upsertBuckets(chunkBuckets.map(PlaybackStatBucket::toEntity))
                    dao.upsertCounterShards(
                        chunk.flatMap { key ->
                            counterSnapshot.trackShards(key).map { shard ->
                                shard.toTrackEntity(key)
                            }
                        }
                    )
                    dao.upsertDailyCounterShards(
                        chunkBuckets.flatMap { bucket ->
                            counterSnapshot.dailyShards(
                                dayStartAt = bucket.dayStartAt,
                                identityKey = bucket.identityKey
                            ).map { shard ->
                                shard.toDailyEntity(
                                    dayStartAt = bucket.dayStartAt,
                                    identityKey = bucket.identityKey
                                )
                            }
                        }
                    )
                }
            markRoomPrimary(
                clearedAt = clearedAt,
                counterEpochStartedAt = counterEpochStartedAt,
                now = now
            )
        }
    }

    suspend fun markLegacyJsonPrimary(now: Long = System.currentTimeMillis()) {
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, now)
        )
    }

    private suspend fun readSnapshot(): PlaybackStatsRoomSnapshot {
        val dao = database.playbackStatsDao()
        val stats = dao.getStats().map(PlaybackStatEntity::toDomain)
        val buckets = dao.getBuckets().map(PlaybackStatBucketEntity::toDomain)
        val trackShards = dao.getCounterShards()
            .groupBy(PlaybackStatCounterShardEntity::identityKey)
            .mapValues { (_, shards) ->
                shards.map(PlaybackStatCounterShardEntity::toDomain)
            }
        val dailyShards = dao.getDailyCounterShards()
            .groupBy { it.dayStartAt to it.identityKey }
            .mapKeys { (key, _) ->
                PlaybackStatsSyncCounterSnapshot.dailyCounterKey(
                    dayStartAt = key.first,
                    identityKey = key.second
                )
            }
            .mapValues { (_, shards) ->
                shards.map(PlaybackStatDailyCounterShardEntity::toDomain)
            }
        val clearedAt = database.syncMetadataDao()
            .getMigrationMetadata(CLEARED_AT_METADATA_KEY)
            ?.value
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
        val counterEpochStartedAt = database.syncMetadataDao()
            .getMigrationMetadata(COUNTER_EPOCH_METADATA_KEY)
            ?.value
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: clearedAt
        return PlaybackStatsRoomSnapshot(
            stats = stats,
            dailyStats = buckets,
            counterSnapshot = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = trackShards,
                dailyShardsByBucketKey = dailyShards
            ),
            counterEpochStartedAt = counterEpochStartedAt,
            clearedAt = clearedAt
        )
    }

    private suspend fun insertAll(
        stats: List<TrackStat>,
        dailyStats: List<PlaybackStatBucket>,
        counterSnapshot: PlaybackStatsSyncCounterSnapshot
    ) {
        val statKeys = stats.mapTo(mutableSetOf(), TrackStat::identityKey)
        val validBuckets = dailyStats.filter { it.identityKey in statKeys }
        val dao = database.playbackStatsDao()
        dao.upsertStats(stats.map(TrackStat::toEntity))
        dao.upsertBuckets(validBuckets.map(PlaybackStatBucket::toEntity))
        dao.upsertCounterShards(
            stats.flatMap { stat ->
                counterSnapshot.trackShards(stat.identityKey).map { shard ->
                    shard.toTrackEntity(stat.identityKey)
                }
            }
        )
        dao.upsertDailyCounterShards(
            validBuckets.flatMap { bucket ->
                counterSnapshot.dailyShards(
                    dayStartAt = bucket.dayStartAt,
                    identityKey = bucket.identityKey
                ).map { shard ->
                    shard.toDailyEntity(
                        dayStartAt = bucket.dayStartAt,
                        identityKey = bucket.identityKey
                    )
                }
            }
        )
    }

    private suspend fun markRoomPrimary(
        clearedAt: Long,
        counterEpochStartedAt: Long,
        now: Long
    ) {
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(IMPORT_SCHEMA_METADATA_KEY, "1", now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CLEARED_AT_METADATA_KEY, clearedAt.coerceAtLeast(0L).toString(), now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(
                COUNTER_EPOCH_METADATA_KEY,
                counterEpochStartedAt.coerceAtLeast(0L).toString(),
                now
            )
        )
    }

    private fun metadata(key: String, value: String, now: Long) =
        moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity(
            key = key,
            value = value,
            updatedAt = now
        )

    companion object {
        const val CUTOVER_STATE_METADATA_KEY = "playback_stats_cutover_state"
        const val IMPORT_SCHEMA_METADATA_KEY = "playback_stats_import_schema"
        const val CLEARED_AT_METADATA_KEY = "playback_stats_cleared_at"
        const val COUNTER_EPOCH_METADATA_KEY = "playback_stats_counter_epoch"
        const val ROOM_PRIMARY_STATE = "room_primary"
        const val LEGACY_JSON_STATE = "legacy_json"
    }
}

private fun identityKeyFromDailyCounterKey(key: String): String {
    return key.substringAfter('|', missingDelimiterValue = key)
}

private fun dailyCounterKeysForIdentity(
    snapshot: PlaybackStatsSyncCounterSnapshot,
    identityKey: String
): Map<String, List<SyncPlaybackCounterShard>> {
    return snapshot.dailyShardsByBucketKey
        .filterKeys { key -> identityKeyFromDailyCounterKey(key) == identityKey }
}

private fun PlaybackStatEntity.toDomain(): TrackStat {
    return TrackStat(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        coverUrl = coverUrl,
        durationMs = durationMs,
        totalListenMs = totalListenMs,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        localFileName = localFileName,
        customName = customName,
        customArtist = customArtist,
        customCoverUrl = customCoverUrl,
        identityKey = identityKey
    )
}

private fun PlaybackStatBucketEntity.toDomain(): PlaybackStatBucket {
    return PlaybackStatBucket(
        dayStartAt = dayStartAt,
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        coverUrl = coverUrl,
        durationMs = durationMs,
        totalListenMs = totalListenMs,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        localFileName = localFileName,
        customName = customName,
        customArtist = customArtist,
        customCoverUrl = customCoverUrl,
        identityKey = identityKey
    )
}

private fun PlaybackStatCounterShardEntity.toDomain(): SyncPlaybackCounterShard {
    return SyncPlaybackCounterShard(
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        totalListenMs = totalListenMs,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}

private fun PlaybackStatDailyCounterShardEntity.toDomain(): SyncPlaybackCounterShard {
    return SyncPlaybackCounterShard(
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        totalListenMs = totalListenMs,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}

private fun SyncPlaybackCounterShard.toTrackEntity(
    identityKey: String
): PlaybackStatCounterShardEntity {
    return PlaybackStatCounterShardEntity(
        identityKey = identityKey,
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        totalListenMs = totalListenMs,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}

private fun SyncPlaybackCounterShard.toDailyEntity(
    dayStartAt: Long,
    identityKey: String
): PlaybackStatDailyCounterShardEntity {
    return PlaybackStatDailyCounterShardEntity(
        dayStartAt = dayStartAt,
        identityKey = identityKey,
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        totalListenMs = totalListenMs,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}
