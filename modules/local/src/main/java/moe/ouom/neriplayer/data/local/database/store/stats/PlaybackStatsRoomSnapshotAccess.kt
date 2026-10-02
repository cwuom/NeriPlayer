package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toSnapshotData
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatProjection
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackStatsMergePolicy
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackCounterArithmetic
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSink
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS

internal class PlaybackStatsRoomSnapshotAccess(private val store: PlaybackStatsRoomStore) {
    private val database = store.database
    private val dao = database.playbackStatsSnapshotDao()

    suspend fun mergeLegacyBackup(id: String, tracks: List<SyncTrackStat>, buckets: List<SyncPlaybackStatBucket>, clearedAt: Long, respectLocalClear: Boolean) {
        val barrier = beginLegacyMerge(id, clearedAt, respectLocalClear)
        for (page in tracks.chunked(SYNC_PLAYBACK_PAGE_RECORDS)) mergeTrackPage(id, page, barrier)
        for (page in buckets.chunked(SYNC_PLAYBACK_PAGE_RECORDS)) mergeBucketPage(id, page, barrier)
        finishLegacyMerge(id, barrier)
    }

    suspend fun mergeLegacyBackup(id: String, source: SyncPlaybackSource, clearedAt: Long, respectLocalClear: Boolean, context: Context) {
        val barrier = beginLegacyMerge(id, clearedAt, respectLocalClear)
        source.openTracks().use { cursor ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = cursor.nextPage()
                if (page.isEmpty()) break
                require(page.size <= SYNC_PLAYBACK_PAGE_RECORDS) { "Playback backup page exceeds record budget" }
                mergeTrackPage(id, page.mapNotNull { SyncPlaybackStatMapper.sanitize(it, context) }, barrier)
            }
        }
        source.openBuckets().use { cursor ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = cursor.nextPage()
                if (page.isEmpty()) break
                require(page.size <= SYNC_PLAYBACK_PAGE_RECORDS) { "Playback backup page exceeds record budget" }
                mergeBucketPage(id, page.mapNotNull { SyncPlaybackStatMapper.sanitize(it, context) }, barrier)
            }
        }
        finishLegacyMerge(id, barrier)
    }

    private suspend fun beginLegacyMerge(id: String, clearedAt: Long, respectLocalClear: Boolean): Long {
        val previous = checkNotNull(dao.getSnapshot(id))
        val barrier = if (respectLocalClear) maxOf(previous.clearedAt, clearedAt) else clearedAt.coerceAtLeast(0)
        dao.updateSnapshotState(id, sealed = false, clearedAt = barrier)
        if (barrier > previous.clearedAt) normalizeClear(id, barrier)
        return barrier
    }

    private suspend fun mergeTrackPage(id: String, page: List<SyncTrackStat>, barrier: Long) {
        if (page.isEmpty()) return
        val keys = page.map { it.identityKey }
        val existing = dao.tracksByKeys(id, keys).associateBy { it.stat.identityKey }
        val counters = dao.trackCounters(id, keys).groupBy { it.shard.identityKey }
        val merged = page.mapNotNull { incoming ->
            val local = existing[incoming.identityKey]?.stat?.toEntity()?.toDomain()?.let {
                SyncPlaybackStatMapper.fromTrackStat(it, counters[it.identityKey].orEmpty().map { row -> row.shard.toEntity().toDomain() })
            }
            SyncPlaybackStatsMergePolicy.mergeTrack(local, incoming, barrier)
        }
        writeTracks(id, merged)
    }

    private suspend fun mergeBucketPage(id: String, page: List<SyncPlaybackStatBucket>, barrier: Long) {
        if (page.isEmpty()) return
        val existing = page.groupBy { it.dayStartAt }.flatMap { (day, rows) -> dao.bucketsByKeys(id, day, rows.map { it.identityKey }) }
            .associateBy { it.bucket.dayStartAt to it.bucket.identityKey }
        val counters = page.groupBy { it.dayStartAt }.flatMap { (day, rows) -> dao.dailyCounters(id, day, rows.map { it.identityKey }) }
            .groupBy { it.shard.dayStartAt to it.shard.identityKey }
        val merged = page.mapNotNull { incoming ->
            val local = existing[incoming.dayStartAt to incoming.identityKey]?.bucket?.toEntity()?.toDomain()?.let {
                SyncPlaybackStatMapper.fromPlaybackStatBucket(it, counters[it.dayStartAt to it.identityKey].orEmpty().map { row -> row.shard.toEntity().toDomain() })
            }
            SyncPlaybackStatsMergePolicy.mergeBucket(local, incoming, barrier)
        }
        writeBuckets(id, merged)
    }

    private suspend fun finishLegacyMerge(id: String, barrier: Long) {
        liftBucketTotals(id)
        dao.updateSnapshotState(id, sealed = true, clearedAt = barrier)
    }

    suspend fun export(id: String, sink: SyncPlaybackSink, context: Context) = export(id, context, sink::appendTracks, sink::appendBuckets)

    suspend fun export(id: String, context: Context, writeTracks: suspend (List<SyncTrackStat>) -> Unit,
        writeBuckets: suspend (List<SyncPlaybackStatBucket>) -> Unit, suppliedProjection: SyncPlaybackStatProjection? = null) {
        val projection = suppliedProjection ?: SyncPlaybackStatMapper.bind(context)
        var after: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.trackPage(id, after, SYNC_PLAYBACK_PAGE_RECORDS)
            if (page.isEmpty()) break
            val shards = dao.trackCounters(id, page.map { it.stat.identityKey }).groupBy { it.shard.identityKey }
            val tracks = page.mapNotNull { row ->
                val track = row.stat.toEntity().toDomain()
                if (!projection.shouldSync(track)) null
                else SyncPlaybackStatMapper.fromTrackStat(track, shards[track.identityKey].orEmpty().map { it.shard.toEntity().toDomain() })
            }
            writeTracks(tracks)
            after = page.last().stat.identityKey
        }
        var afterDay: Long? = null
        var afterIdentity: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.bucketPage(id, afterDay, afterIdentity, SYNC_PLAYBACK_PAGE_RECORDS)
            if (page.isEmpty()) break
            val counters = page.groupBy { it.bucket.dayStartAt }.flatMap { (day, rows) ->
                dao.dailyCounters(id, day, rows.map { it.bucket.identityKey })
            }.groupBy { it.shard.dayStartAt to it.shard.identityKey }
            val buckets = page.mapNotNull { row ->
                val bucket = row.bucket.toEntity().toDomain()
                if (!projection.shouldSync(bucket)) null
                else SyncPlaybackStatMapper.fromPlaybackStatBucket(bucket, counters[bucket.dayStartAt to bucket.identityKey].orEmpty().map { it.shard.toEntity().toDomain() })
            }
            writeBuckets(buckets)
            afterDay = page.last().bucket.dayStartAt
            afterIdentity = page.last().bucket.identityKey
        }
    }

    internal suspend fun writeTracks(id: String, tracks: List<SyncTrackStat>) = database.withTransaction {
        val keys = tracks.map { it.identityKey }
        val existing = dao.tracksByKeys(id, keys).associateBy { it.stat.identityKey }
        dao.deleteCountersByKeys(id, keys)
        dao.upsertTracks(tracks.map { incoming ->
            PlaybackStatsSnapshotTrackEntity(id, incoming.toEntity(existing[incoming.identityKey]?.stat?.toEntity()).toSnapshotData())
        })
        tracks.asSequence().flatMap { track -> track.counterShards.asSequence().map { shard ->
            PlaybackStatsSnapshotCounterEntity(id, shard.toTrackEntity(track.identityKey).toSnapshotData())
        } }.chunked(500).forEach { dao.upsertCounters(it) }
    }

    internal suspend fun writeBuckets(id: String, buckets: List<SyncPlaybackStatBucket>) = database.withTransaction {
        for ((day, page) in buckets.groupBy { it.dayStartAt }) {
            val keys = page.map { it.identityKey }
            val existing = dao.bucketsByKeys(id, day, keys).associateBy { it.bucket.identityKey }
            dao.deleteDailyCountersByKeys(id, day, keys)
            dao.upsertBuckets(page.map { incoming ->
                PlaybackStatsSnapshotBucketEntity(id, incoming.toEntity(existing[incoming.identityKey]?.bucket?.toEntity()).toSnapshotData())
            })
            page.asSequence().flatMap { bucket -> bucket.counterShards.asSequence().map { shard ->
                PlaybackStatsSnapshotDailyCounterEntity(id, shard.toDailyEntity(day, bucket.identityKey).toSnapshotData())
            } }.chunked(500).forEach { dao.upsertDailyCounters(it) }
        }
    }

    private suspend fun normalizeClear(id: String, clearedAt: Long) {
        var after: String? = null
        while (true) {
            val page = dao.trackPage(id, after, SYNC_PLAYBACK_PAGE_RECORDS)
            if (page.isEmpty()) break
            val shards = dao.trackCounters(id, page.map { it.stat.identityKey }).groupBy { it.shard.identityKey }
            val normalized = page.mapNotNull { row ->
                val track = row.stat.toEntity().toDomain()
                SyncPlaybackStatsMergePolicy.mergeTrack(null, SyncPlaybackStatMapper.fromTrackStat(track,
                    shards[track.identityKey].orEmpty().map { it.shard.toEntity().toDomain() }), clearedAt)
            }
            val dropped = page.map { it.stat.identityKey }.toSet() - normalized.map { it.identityKey }.toSet()
            database.withTransaction {
                dao.deleteTracksByKeys(id, dropped.toList())
                dao.deleteCountersByKeys(id, dropped.toList())
            }
            writeTracks(id, normalized)
            after = page.last().stat.identityKey
        }
        var day: Long? = null
        var identity: String? = null
        while (true) {
            val page = dao.bucketPage(id, day, identity, SYNC_PLAYBACK_PAGE_RECORDS)
            if (page.isEmpty()) break
            val counters = page.groupBy { it.bucket.dayStartAt }.flatMap { (at, rows) ->
                dao.dailyCounters(id, at, rows.map { it.bucket.identityKey })
            }.groupBy { it.shard.dayStartAt to it.shard.identityKey }
            val normalized = page.mapNotNull { row ->
                val bucket = row.bucket.toEntity().toDomain()
                SyncPlaybackStatsMergePolicy.mergeBucket(null, SyncPlaybackStatMapper.fromPlaybackStatBucket(bucket,
                    counters[bucket.dayStartAt to bucket.identityKey].orEmpty().map { it.shard.toEntity().toDomain() }), clearedAt)
            }
            val retained = normalized.map { it.dayStartAt to it.identityKey }.toSet()
            database.withTransaction {
                page.filter { it.bucket.dayStartAt to it.bucket.identityKey !in retained }.groupBy { it.bucket.dayStartAt }.forEach { (at, rows) ->
                    val keys = rows.map { it.bucket.identityKey }
                    dao.deleteBucketsByKeys(id, at, keys)
                    dao.deleteDailyCountersByKeys(id, at, keys)
                }
            }
            writeBuckets(id, normalized)
            day = page.last().bucket.dayStartAt
            identity = page.last().bucket.identityKey
        }
    }

    internal suspend fun liftBucketTotals(id: String) {
        var identity: String? = null
        var day: Long? = null
        var totals: PlaybackBucketTotals? = null
        while (true) {
            val page = dao.bucketIdentityPage(id, identity, day, SYNC_PLAYBACK_PAGE_RECORDS)
            if (page.isEmpty()) break
            for (row in page) {
                val bucket = row.bucket.toEntity()
                if (totals != null && totals.identityKey != bucket.identityKey) {
                    publishTotals(id, totals)
                    totals = null
                }
                totals = totals?.plus(bucket) ?: PlaybackBucketTotals(bucket.identityKey, bucket.totalListenMs, bucket.playCount, bucket.firstPlayedAt, bucket.lastPlayedAt, bucket)
            }
            identity = page.last().bucket.identityKey
            day = page.last().bucket.dayStartAt
        }
        totals?.let { publishTotals(id, it) }
    }

    private suspend fun publishTotals(id: String, totals: PlaybackBucketTotals) {
        val existing = dao.tracksByKeys(id, listOf(totals.identityKey)).firstOrNull()?.stat?.toEntity()
        val latest = totals.latest
        val stat = existing?.copy(totalListenMs = maxOf(existing.totalListenMs, totals.listenMs), playCount = maxOf(existing.playCount, totals.playCount))
            ?: PlaybackStatEntity(latest.identityKey, latest.id, latest.name, latest.artist, latest.album, latest.albumId, latest.coverUrl, latest.durationMs,
                totals.listenMs, totals.playCount, totals.lastPlayedAt, totals.firstPlayedAt, latest.mediaUri, latest.localFilePath, latest.localFileName,
                latest.customName, latest.customArtist, latest.customCoverUrl)
        dao.upsertTracks(listOf(PlaybackStatsSnapshotTrackEntity(id, stat.toSnapshotData())))
    }
}

private data class PlaybackBucketTotals(val identityKey: String, val listenMs: Long, val playCount: Int, val firstPlayedAt: Long, val lastPlayedAt: Long, val latest: PlaybackStatBucketEntity) {
    fun plus(bucket: PlaybackStatBucketEntity) = copy(
        listenMs = SyncPlaybackCounterArithmetic.add(listenMs, bucket.totalListenMs),
        playCount = SyncPlaybackCounterArithmetic.add(playCount, bucket.playCount),
        firstPlayedAt = when { firstPlayedAt <= 0 -> bucket.firstPlayedAt; bucket.firstPlayedAt <= 0 -> firstPlayedAt; else -> minOf(firstPlayedAt, bucket.firstPlayedAt) },
        lastPlayedAt = maxOf(lastPlayedAt, bucket.lastPlayedAt),
        latest = if (bucket.lastPlayedAt > latest.lastPlayedAt || (bucket.lastPlayedAt == latest.lastPlayedAt && bucket.dayStartAt > latest.dayStartAt)) bucket else latest
    )
}

private fun SyncTrackStat.toEntity(local: PlaybackStatEntity?) = PlaybackStatEntity(
    identityKey, id, name, artist, album, albumId, coverUrl, durationMs, totalListenMs, playCount, lastPlayedAt, firstPlayedAt,
    mediaUri, local?.localFilePath, local?.localFileName, local?.customName, local?.customArtist, local?.customCoverUrl
)

private fun SyncPlaybackStatBucket.toEntity(local: PlaybackStatBucketEntity?) = PlaybackStatBucketEntity(
    dayStartAt, identityKey, id, name, artist, album, albumId, coverUrl, durationMs, totalListenMs, playCount, lastPlayedAt, firstPlayedAt,
    mediaUri, local?.localFilePath, local?.localFileName, local?.customName, local?.customArtist, local?.customCoverUrl
)
