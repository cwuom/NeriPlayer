package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.toEntity
import moe.ouom.neriplayer.data.playlist.usage.UsageEntry
import moe.ouom.neriplayer.data.playlist.usage.usageKey
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard

internal class PlaylistUsageRoomStore(
    private val database: NeriUserDataDatabase
) {
    suspend fun readIfRoomPrimary(): List<UsageEntry>? {
        if (database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value != ROOM_PRIMARY_STATE
        ) {
            return null
        }
        return readEntries()
    }

    suspend fun importLegacyAndPromote(
        entries: List<UsageEntry>,
        now: Long = System.currentTimeMillis()
    ) {
        replaceAll(entries, now)
    }

    suspend fun writeIncremental(
        previous: List<UsageEntry>,
        next: List<UsageEntry>,
        now: Long = System.currentTimeMillis()
    ) {
        val previousByKey = previous.associateBy(UsageEntry::usageKey)
        val nextByKey = next.associateBy(UsageEntry::usageKey)
        val changedKeys = (previousByKey.keys + nextByKey.keys)
            .filter { key -> previousByKey[key] != nextByKey[key] }
            .toSet()
        val removedKeys = changedKeys.filter { it !in nextByKey }
        val changedEntries = next.filter { it.usageKey() in changedKeys }
        database.withTransaction {
            deleteKeys(removedKeys)
            changedKeys
                .filter { it in nextByKey }
                .chunked(500)
                .forEach { chunk ->
                    database.playlistUsageDao().deleteCounterShards(chunk)
                }
            database.playlistUsageDao().upsertEntries(changedEntries.map(UsageEntry::toEntity))
            database.playlistUsageDao().upsertCounterShards(
                changedEntries.flatMap { entry ->
                    SyncPlaybackStatMapper.normalizeCounterShards(entry.counterShards)
                        .map { shard -> shard.toEntity(entry.usageKey()) }
                }
            )
            markRoomPrimary(now)
        }
    }

    suspend fun replaceAll(
        entries: List<UsageEntry>,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            database.playlistUsageDao().deleteAllCounterShards()
            database.playlistUsageDao().deleteAllEntries()
            database.playlistUsageDao().upsertEntries(entries.map(UsageEntry::toEntity))
            database.playlistUsageDao().upsertCounterShards(
                entries.flatMap { entry ->
                    SyncPlaybackStatMapper.normalizeCounterShards(entry.counterShards)
                        .map { shard -> shard.toEntity(entry.usageKey()) }
                }
            )
            markRoomPrimary(now)
        }
    }

    private suspend fun readEntries(): List<UsageEntry> {
        val shardsByKey = database.playlistUsageDao()
            .getCounterShards()
            .groupBy { it.usageKey }
            .mapValues { (_, shards) ->
                shards.map { shard ->
                    SyncPlaybackCounterShard(
                        deviceId = shard.deviceId,
                        epochStartedAt = shard.epochStartedAt,
                        playCount = shard.playCount,
                        firstPlayedAt = shard.firstPlayedAt,
                        lastPlayedAt = shard.lastPlayedAt
                    )
                }
            }
        return database.playlistUsageDao().getEntries().map { entry ->
            UsageEntry(
                id = entry.id,
                name = entry.name,
                picUrl = entry.picUrl,
                trackCount = entry.trackCount,
                source = entry.source,
                lastOpened = entry.lastOpened,
                openCount = entry.openCount,
                firstOpened = entry.firstOpened,
                counterBaseOpenCount = entry.counterBaseOpenCount,
                counterShards = shardsByKey[entry.usageKey].orEmpty(),
                fid = entry.fid,
                mid = entry.mid,
                browseId = entry.browseId,
                playlistId = entry.playlistId,
                subtype = entry.subtype,
                subtitle = entry.subtitle
            )
        }
    }

    private suspend fun deleteKeys(keys: List<String>) {
        keys.chunked(500).forEach { chunk ->
            if (chunk.isNotEmpty()) {
                database.playlistUsageDao().deleteCounterShards(chunk)
                database.playlistUsageDao().deleteEntries(chunk)
            }
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
        const val CUTOVER_STATE_METADATA_KEY = "playlist_usage_cutover_state"
        const val IMPORT_SCHEMA_METADATA_KEY = "playlist_usage_import_schema"
        const val ROOM_PRIMARY_STATE = "room_primary"
    }
}
