package moe.ouom.neriplayer.data.stats

import android.content.Context
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomSnapshotAccess
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomCounterImportAccess
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toSnapshotData
import moe.ouom.neriplayer.data.local.database.store.stats.toTrackEntity
import moe.ouom.neriplayer.data.local.database.store.stats.toDailyEntity
import moe.ouom.neriplayer.data.local.database.store.stats.toDomain
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import java.io.File
import java.io.IOException

internal class PlaybackStatsLegacyImporter(private val context: Context, private val gson: Gson, private val store: PlaybackStatsRoomStore) {
    private val dao = store.database.playbackStatsSnapshotDao()
    private val counterWriter = PlaybackStatsRoomCounterImportAccess(store)
    private var snapshotId = ""
    private var clearedAt = 0L
    private var epoch = 0L

    suspend fun migrate() {
        val snapshot = store.beginLegacyImport()
        snapshotId = snapshot.id
        try {
            val committed = readMetadata()
            if (!committed) {
                readArrayFile("playback_stats.json", TrackStat::class.java) { rows -> writeTracks(rows) }
                val daily = File(context.filesDir, "playback_stats_daily.json")
                if (daily.exists()) readArrayFile(daily.name, PlaybackStatBucket::class.java) { rows -> writeBuckets(rows) }
                else buildLegacyBuckets()
                val counters = File(context.filesDir, "playback_stats_counters.json")
                if (counters.exists()) read(counters) { parseCounters(it, isSnapshot = false) }
            }
            PlaybackStatsRoomSnapshotAccess(store).liftBucketTotals(snapshotId)
            // 旧文件会保留已裁剪日桶的分片，等全部父行读完后再清理
            dao.deleteLegacyOrphanCounters(snapshotId)
            dao.deleteLegacyOrphanDailyCounters(snapshotId)
            dao.upsertSnapshot(snapshot.copy(clearedAt = clearedAt.coerceAtLeast(0), counterEpochStartedAt = epoch.coerceAtLeast(clearedAt), sealed = true))
            if (!store.commitFrozenSnapshot(snapshotId, snapshot.revision)) throw IOException("Playback legacy import changed before commit")
        } finally {
            withContext(NonCancellable) { store.releaseSnapshot(snapshotId) }
        }
    }

    private suspend fun readMetadata(): Boolean {
        val file = File(context.filesDir, "playback_stats_meta.json")
        if (!file.exists()) return false
        var hasSnapshot = false
        var outerClearedAt = 0L
        read(file) { reader ->
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "clearedAt" -> outerClearedAt = reader.nextLong()
                "snapshot" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else {
                    if (hasSnapshot) throw IOException("Duplicate playback snapshot")
                    parseSnapshot(reader)
                    hasSnapshot = true
                }
                else -> reader.skipValue()
            }
            reader.endObject()
        }
        if (!hasSnapshot) clearedAt = outerClearedAt.coerceAtLeast(0)
        return hasSnapshot
    }

    private suspend fun parseSnapshot(reader: JsonReader) {
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            if (!seen.add(field)) throw IOException("Duplicate playback snapshot field")
            when (field) {
                "stats" -> readArray(reader, TrackStat::class.java) { writeTracks(it) }
                "dailyStats" -> readArray(reader, PlaybackStatBucket::class.java) { writeBuckets(it) }
                "counterSnapshot" -> parseCounters(reader, isSnapshot = true)
                "counterEpochStartedAt" -> epoch = reader.nextLong()
                "clearedAt" -> clearedAt = reader.nextLong()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (!seen.containsAll(listOf("stats", "dailyStats", "counterSnapshot", "counterEpochStartedAt", "clearedAt"))) throw IOException("Incomplete committed playback snapshot")
    }

    private suspend fun parseCounters(reader: JsonReader, isSnapshot: Boolean) {
        reader.beginObject()
        while (reader.hasNext()) when (reader.nextName()) {
            "epochStartedAt" -> if (isSnapshot) reader.skipValue() else epoch = reader.nextLong()
            "trackShardsByIdentity" -> readCounterMap(reader, daily = false)
            "dailyShardsByBucketKey" -> readCounterMap(reader, daily = true)
            else -> reader.skipValue()
        }
        reader.endObject()
    }

    private suspend fun readCounterMap(reader: JsonReader, daily: Boolean) {
        val tracks = CounterPageBuffer<PlaybackStatsSnapshotCounterEntity> { counterWriter.writeTracks(snapshotId, it) }
        val buckets = CounterPageBuffer<PlaybackStatsSnapshotDailyCounterEntity> { counterWriter.writeDaily(snapshotId, it) }
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (daily) readDailyShards(reader, key, buckets) else readTrackShards(reader, key, tracks)
        }
        reader.endObject()
        tracks.flush()
        buckets.flush()
    }

    private suspend fun readTrackShards(reader: JsonReader, identity: String, rows: CounterPageBuffer<PlaybackStatsSnapshotCounterEntity>) {
        requireCounterIdentity(identity)
        readShards(reader) { shard -> rows.add(PlaybackStatsSnapshotCounterEntity(snapshotId, shard.toTrackEntity(identity).toSnapshotData())) }
    }

    private suspend fun readDailyShards(reader: JsonReader, key: String, rows: CounterPageBuffer<PlaybackStatsSnapshotDailyCounterEntity>) {
        val day = key.substringBefore('|').toLongOrNull() ?: throw IOException("Invalid playback daily counter key")
        val identity = requireCounterIdentity(key.substringAfter('|', missingDelimiterValue = ""))
        readShards(reader) { shard ->
            rows.add(PlaybackStatsSnapshotDailyCounterEntity(snapshotId, shard.toDailyEntity(day, identity).toSnapshotData()))
        }
    }

    private fun requireCounterIdentity(identity: String): String {
        if (identity.isBlank()) throw IOException("Invalid playback counter identity")
        return identity
    }

    private suspend fun readShards(reader: JsonReader, consume: suspend (SyncPlaybackCounterShard) -> Unit) {
        reader.beginArray()
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            consume(gson.fromJson<SyncPlaybackCounterShard>(reader, SyncPlaybackCounterShard::class.java) ?: throw IOException("Null playback counter"))
        }
        reader.endArray()
    }

    private suspend fun buildLegacyBuckets() {
        var after: String? = null
        while (true) {
            val rows = dao.trackPage(snapshotId, after, 256)
            if (rows.isEmpty()) break
            writeBuckets(buildLegacyDailyStats(rows.map { it.stat.toEntity().toDomain() }, clearedAt))
            after = rows.last().stat.identityKey
        }
    }

    private suspend fun writeTracks(rows: List<TrackStat>) {
        if (rows.any { it.identityKey.isBlank() }) throw IOException("Playback track has no identity")
        dao.upsertTracks(rows.map { PlaybackStatsSnapshotTrackEntity(snapshotId, it.toEntity().toSnapshotData()) })
    }

    private suspend fun writeBuckets(rows: List<PlaybackStatBucket>) {
        if (rows.any { it.identityKey.isBlank() }) throw IOException("Playback bucket has no identity")
        dao.upsertBuckets(rows.map { PlaybackStatsSnapshotBucketEntity(snapshotId, it.toEntity().toSnapshotData()) })
    }

    private suspend fun <T> readArrayFile(name: String, type: Class<T>, consume: suspend (List<T>) -> Unit) {
        val file = File(context.filesDir, name)
        if (file.exists()) read(file) { reader -> readArray(reader, type, consume) }
    }

    private suspend fun <T> readArray(reader: JsonReader, type: Class<T>, consume: suspend (List<T>) -> Unit) {
        val page = ArrayList<T>(256)
        reader.beginArray()
        while (reader.hasNext()) {
            currentCoroutineContext().ensureActive()
            page.add(gson.fromJson<T>(reader, type) ?: throw IOException("Null playback record"))
            if (page.size == 256) {
                consume(page.toList())
                page.clear()
            }
        }
        reader.endArray()
        if (page.isNotEmpty()) consume(page)
    }

    private suspend fun read(file: File, consume: suspend (JsonReader) -> Unit) {
        file.reader(Charsets.UTF_8).use { input ->
            JsonReader(input).use { reader ->
                consume(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing playback legacy content")
            }
        }
    }
}

/** Buffers counter rows and hands them to [write] a page of 256 at a time. */
private class CounterPageBuffer<T>(private val write: suspend (List<T>) -> Unit) {
    private val rows = ArrayList<T>(256)

    suspend fun add(row: T) {
        rows.add(row)
        if (rows.size == 256) flush()
    }

    suspend fun flush() {
        if (rows.isEmpty()) return
        write(rows)
        rows.clear()
    }
}
