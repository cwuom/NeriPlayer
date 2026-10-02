package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toSnapshotData
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatProjection
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackStatsMergePolicy
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackCounterArithmetic
import moe.ouom.neriplayer.data.sync.merge.stats.SyncCounterShardPolicy
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import java.io.IOException

internal class PlaybackStatsRoomDiffAccess(private val store: PlaybackStatsRoomStore) {
    private val database = store.database
    private val primary = database.playbackStatsDao()
    private val staged = database.playbackStatsSnapshotDao()
    private val trackChanges = ArrayList<TrackChange>()
    private val bucketChanges = ArrayList<BucketChange>()
    private val trackDeletions = ArrayList<PlaybackStatsSnapshotDeletedTrackEntity>()
    private val bucketDeletions = ArrayList<PlaybackStatsSnapshotDeletedBucketEntity>()

    suspend fun build(id: String, source: SyncPlaybackSource, context: Context) {
        val projection = SyncPlaybackStatMapper.bind(context)
        val header = checkNotNull(staged.getSnapshot(id))
        check(header.isDiff && !header.sealed) { "Playback diff must begin empty and unsealed" }
        val state = checkNotNull(store.readPrimaryState())
        val clearAdvanced = header.clearedAt > state.clearedAt
        source.openTracks().use { cursor ->
            val remote = OrderedRecordReader({ cursor.nextPage() }, Comparator<SyncTrackStat> { left, right ->
                SyncPlaybackKeyOrder.compare(left.identityKey, right.identityKey)
            })
            val local = OrderedRecordReader(::trackPage, Comparator<TrackRows> { left, right ->
                SyncPlaybackKeyOrder.compare(left.track.identityKey, right.track.identityKey)
            })
            mergeOrdered(local, remote, { left, right -> SyncPlaybackKeyOrder.compare(left.track.identityKey, right.identityKey) }) { previous, incoming ->
                val desired = when {
                    incoming != null && previous != null && !projection.shouldSync(previous.track.toDomain()) ->
                        SyncPlaybackStatsMergePolicy.mergeTrack(SyncPlaybackStatMapper.fromTrackStat(previous.track.toDomain(),
                            previous.counters.map { it.toDomain() }), incoming, header.clearedAt)
                    incoming != null -> incoming
                    previous == null -> null
                    projection.shouldSync(previous.track.toDomain()) -> null
                    clearAdvanced -> SyncPlaybackStatsMergePolicy.mergeTrack(null,
                        SyncPlaybackStatMapper.fromTrackStat(previous.track.toDomain(), previous.counters.map { it.toDomain() }), header.clearedAt)
                    else -> return@mergeOrdered
                }
                if (desired == null) {
                    previous?.let { trackDeletions.add(PlaybackStatsSnapshotDeletedTrackEntity(id, it.track.identityKey)) }
                } else {
                    val row = desired.toPrimaryEntity(previous?.track)
                    val counters = normalizeCounters(desired.counterShards).map { it.toTrackEntity(row.identityKey) }
                    if (previous == null || previous.track != row || previous.counters != counters) trackChanges.add(TrackChange(row, counters))
                }
                if (trackChanges.size + trackDeletions.size >= SYNC_PLAYBACK_PAGE_RECORDS) flushTracks(id)
            }
        }
        flushTracks(id)
        walkBuckets(source, SyncPlaybackBucketOrder.DAY_IDENTITY, header.clearedAt, clearAdvanced, projection) { previous, desired ->
            if (desired == null) {
                previous?.let { bucketDeletions.add(PlaybackStatsSnapshotDeletedBucketEntity(id, it.bucket.dayStartAt, it.bucket.identityKey)) }
            } else if (previous == null || desired != previous) bucketChanges.add(BucketChange(desired.bucket, desired.counters))
            if (bucketChanges.size + bucketDeletions.size >= SYNC_PLAYBACK_PAGE_RECORDS) flushBuckets(id)
        }
        flushBuckets(id)
        if (clearAdvanced) liftFinalBucketTotals(id, source, header.clearedAt, projection)
        currentCoroutineContext().ensureActive()
        staged.updateSnapshotState(id, sealed = true, clearedAt = header.clearedAt)
    }

    private suspend fun trackPage(after: TrackRows?): List<TrackRows> {
        val page = primary.identityPage(after?.track?.identityKey, SYNC_PLAYBACK_PAGE_RECORDS)
        if (page.isEmpty()) return emptyList()
        val shards = primary.trackCountersByKeys(page.map { it.identityKey }).groupBy { it.identityKey }
        return page.map { TrackRows(it, shards[it.identityKey].orEmpty()) }
    }

    private suspend fun bucketPage(after: BucketRows?, order: SyncPlaybackBucketOrder): List<BucketRows> {
        val page = when (order) {
            SyncPlaybackBucketOrder.DAY_IDENTITY -> primary.bucketPage(after?.bucket?.dayStartAt, after?.bucket?.identityKey, SYNC_PLAYBACK_PAGE_RECORDS)
            SyncPlaybackBucketOrder.IDENTITY_DAY -> primary.bucketIdentityPage(after?.bucket?.identityKey, after?.bucket?.dayStartAt, SYNC_PLAYBACK_PAGE_RECORDS)
        }
        if (page.isEmpty()) return emptyList()
        val shards = page.groupBy { it.dayStartAt }.flatMap { (day, rows) -> primary.dailyCountersByKeys(day, rows.map { it.identityKey }) }
            .groupBy { it.dayStartAt to it.identityKey }
        return page.map { BucketRows(it, shards[it.dayStartAt to it.identityKey].orEmpty()) }
    }

    private suspend fun walkBuckets(source: SyncPlaybackSource, order: SyncPlaybackBucketOrder, clearedAt: Long,
        clearAdvanced: Boolean, projection: SyncPlaybackStatProjection, consume: suspend (BucketRows?, BucketRows?) -> Unit) {
        source.openBuckets(order).use { cursor ->
            val remote = OrderedRecordReader({ cursor.nextPage() }, Comparator<SyncPlaybackStatBucket> { left, right ->
                compareBucketKeys(left.dayStartAt, left.identityKey, right.dayStartAt, right.identityKey, order)
            })
            val local = OrderedRecordReader({ after -> bucketPage(after, order) }, Comparator<BucketRows> { left, right ->
                compareBucketKeys(left.bucket.dayStartAt, left.bucket.identityKey, right.bucket.dayStartAt, right.bucket.identityKey, order)
            })
            mergeOrdered(local, remote, { left, right -> compareBucketKeys(left.bucket.dayStartAt, left.bucket.identityKey, right.dayStartAt, right.identityKey, order) }) { previous, incoming ->
                val desired = when {
                    incoming != null && previous != null && !projection.shouldSync(previous.bucket.toDomain()) ->
                        SyncPlaybackStatsMergePolicy.mergeBucket(SyncPlaybackStatMapper.fromPlaybackStatBucket(previous.bucket.toDomain(),
                            previous.counters.map { it.toDomain() }), incoming, clearedAt)
                    incoming != null -> incoming
                    previous == null -> null
                    projection.shouldSync(previous.bucket.toDomain()) -> null
                    clearAdvanced -> SyncPlaybackStatsMergePolicy.mergeBucket(null,
                        SyncPlaybackStatMapper.fromPlaybackStatBucket(previous.bucket.toDomain(), previous.counters.map { it.toDomain() }), clearedAt)
                    else -> { consume(previous, previous); return@mergeOrdered }
                }
                val rows = desired?.let { value ->
                    val row = value.toPrimaryEntity(previous?.bucket)
                    BucketRows(row, normalizeCounters(value.counterShards).map { it.toDailyEntity(row.dayStartAt, row.identityKey) })
                }
                consume(previous, rows)
            }
        }
    }

    private suspend fun flushTracks(id: String) {
        if (trackChanges.isEmpty() && trackDeletions.isEmpty()) return
        database.withTransaction {
            if (trackDeletions.isNotEmpty()) staged.upsertDeletedTracks(trackDeletions)
            if (trackChanges.isNotEmpty()) {
                val keys = trackChanges.map { it.track.identityKey }
                staged.restoreDeletedTracks(id, keys)
                staged.deleteCountersByKeys(id, keys)
                staged.upsertTracks(trackChanges.map { PlaybackStatsSnapshotTrackEntity(id, it.track.toSnapshotData()) })
                trackChanges.asSequence().flatMap { it.counters.asSequence() }.chunked(500).forEach { rows ->
                    staged.upsertCounters(rows.map { PlaybackStatsSnapshotCounterEntity(id, it.toSnapshotData()) })
                }
            }
        }
        trackChanges.clear()
        trackDeletions.clear()
    }

    private suspend fun flushBuckets(id: String) {
        if (bucketChanges.isEmpty() && bucketDeletions.isEmpty()) return
        database.withTransaction {
            if (bucketDeletions.isNotEmpty()) staged.upsertDeletedBuckets(bucketDeletions)
            for ((day, rows) in bucketChanges.groupBy { it.bucket.dayStartAt }) {
                staged.deleteDailyCountersByKeys(id, day, rows.map { it.bucket.identityKey })
                staged.upsertBuckets(rows.map { PlaybackStatsSnapshotBucketEntity(id, it.bucket.toSnapshotData()) })
                rows.asSequence().flatMap { it.counters.asSequence() }.chunked(500).forEach { shards ->
                    staged.upsertDailyCounters(shards.map { PlaybackStatsSnapshotDailyCounterEntity(id, it.toSnapshotData()) })
                }
            }
        }
        bucketChanges.clear()
        bucketDeletions.clear()
    }

    private suspend fun liftFinalBucketTotals(id: String, source: SyncPlaybackSource, clearedAt: Long, projection: SyncPlaybackStatProjection) {
        var totals: DiffBucketTotals? = null
        val page = ArrayList<DiffBucketTotals>()
        suspend fun completed(total: DiffBucketTotals) {
            page.add(total)
            if (page.size == SYNC_PLAYBACK_PAGE_RECORDS) { liftTotalsPage(id, page); page.clear() }
        }
        walkBuckets(source, SyncPlaybackBucketOrder.IDENTITY_DAY, clearedAt, clearAdvanced = true, projection) { _, desired ->
            if (desired != null) {
                val row = desired.bucket
                if (totals != null && totals?.latest?.identityKey != row.identityKey) {
                    completed(checkNotNull(totals))
                    totals = null
                }
                totals = totals?.plus(row) ?: DiffBucketTotals(row.totalListenMs, row.playCount, row.firstPlayedAt, row.lastPlayedAt, row)
            }
        }
        totals?.let { completed(it) }
        if (page.isNotEmpty()) liftTotalsPage(id, page)
        flushTracks(id)
    }

    private suspend fun liftTotalsPage(id: String, totals: List<DiffBucketTotals>) {
        val keys = totals.map { it.latest.identityKey }
        val changes = staged.tracksByKeys(id, keys).associateBy { it.stat.identityKey }
        val deleted = staged.deletedTrackKeys(id, keys).toSet()
        val originals = primary.tracksByKeys(keys).associateBy { it.identityKey }
        val stagedCounters = staged.trackCounters(id, keys).groupBy { it.shard.identityKey }
        val originalCounters = primary.trackCountersByKeys(keys).groupBy { it.identityKey }
        for (total in totals) {
            val latest = total.latest
            val changed = changes[latest.identityKey]?.stat?.toEntity()
            val existing = changed ?: originals[latest.identityKey]?.takeUnless { it.identityKey in deleted }
            val desired = existing?.copy(totalListenMs = maxOf(existing.totalListenMs, total.listenMs), playCount = maxOf(existing.playCount, total.playCount))
                ?: PlaybackStatEntity(latest.identityKey, latest.id, latest.name, latest.artist, latest.album, latest.albumId,
                    latest.coverUrl, latest.durationMs, total.listenMs, total.playCount, total.lastPlayedAt, total.firstPlayedAt,
                    latest.mediaUri, originals[latest.identityKey]?.localFilePath ?: latest.localFilePath,
                    originals[latest.identityKey]?.localFileName ?: latest.localFileName,
                    originals[latest.identityKey]?.customName ?: latest.customName,
                    originals[latest.identityKey]?.customArtist ?: latest.customArtist,
                    originals[latest.identityKey]?.customCoverUrl ?: latest.customCoverUrl)
            if (existing != desired) {
                val counters = when {
                    changed != null -> stagedCounters[latest.identityKey].orEmpty().map { it.shard.toEntity() }
                    existing != null -> originalCounters[latest.identityKey].orEmpty()
                    else -> emptyList()
                }
                trackChanges.add(TrackChange(desired, counters))
            }
        }
        flushTracks(id)
    }
}

private data class TrackRows(val track: PlaybackStatEntity, val counters: List<PlaybackStatCounterShardEntity>)
private data class BucketRows(val bucket: PlaybackStatBucketEntity, val counters: List<PlaybackStatDailyCounterShardEntity>)
private data class TrackChange(val track: PlaybackStatEntity, val counters: List<PlaybackStatCounterShardEntity>)
private data class BucketChange(val bucket: PlaybackStatBucketEntity, val counters: List<PlaybackStatDailyCounterShardEntity>)

// 正式 apply 的 source 必须已合并为唯一键；异常或并发变化只影响独立 diff，提交仍由 revision 检查保护
private class OrderedRecordReader<T>(private val read: suspend (T?) -> List<T>, private val order: Comparator<T>) {
    private var page: List<T> = emptyList()
    private var index = 0
    private var previous: T? = null
    private var ended = false

    suspend fun next(): T? {
        currentCoroutineContext().ensureActive()
        if (index == page.size) {
            if (ended) return null
            page = read(previous)
            index = 0
            if (page.isEmpty()) { ended = true; return null }
            require(page.size <= SYNC_PLAYBACK_PAGE_RECORDS) { "Playback page exceeds its record budget" }
            var last = previous
            for (value in page) {
                if (last != null && order.compare(last, value) >= 0) throw IOException("Playback source is not strictly ordered and unique")
                last = value
            }
        }
        return page[index++].also { previous = it }
    }
}

private suspend fun <L, R> mergeOrdered(local: OrderedRecordReader<L>, remote: OrderedRecordReader<R>,
    compare: (L, R) -> Int, consume: suspend (L?, R?) -> Unit) {
    var left = local.next()
    var right = remote.next()
    while (left != null || right != null) {
        val order = when { left == null -> 1; right == null -> -1; else -> compare(left, right) }
        when {
            order < 0 -> { consume(left, null); left = local.next() }
            order > 0 -> { consume(null, right); right = remote.next() }
            else -> { consume(left, right); left = local.next(); right = remote.next() }
        }
    }
}

private fun compareBucketKeys(leftDay: Long, leftIdentity: String, rightDay: Long, rightIdentity: String, order: SyncPlaybackBucketOrder): Int =
    when (order) {
        SyncPlaybackBucketOrder.DAY_IDENTITY -> leftDay.compareTo(rightDay).takeIf { it != 0 } ?: SyncPlaybackKeyOrder.compare(leftIdentity, rightIdentity)
        SyncPlaybackBucketOrder.IDENTITY_DAY -> SyncPlaybackKeyOrder.compare(leftIdentity, rightIdentity).takeIf { it != 0 } ?: leftDay.compareTo(rightDay)
    }

private fun normalizeCounters(rows: List<SyncPlaybackCounterShard>): List<SyncPlaybackCounterShard> =
    SyncCounterShardPolicy.normalizeCounterShards(rows).sortedWith { left, right ->
        SyncPlaybackKeyOrder.compare(left.deviceId, right.deviceId).takeIf { it != 0 } ?: left.epochStartedAt.compareTo(right.epochStartedAt)
    }

private fun SyncTrackStat.toPrimaryEntity(local: PlaybackStatEntity?) = PlaybackStatEntity(identityKey, id, name, artist, album,
    albumId, coverUrl, durationMs, totalListenMs, playCount, lastPlayedAt, firstPlayedAt, mediaUri,
    local?.localFilePath, local?.localFileName, local?.customName, local?.customArtist, local?.customCoverUrl)

private fun SyncPlaybackStatBucket.toPrimaryEntity(local: PlaybackStatBucketEntity?) = PlaybackStatBucketEntity(dayStartAt, identityKey,
    id, name, artist, album, albumId, coverUrl, durationMs, totalListenMs, playCount, lastPlayedAt, firstPlayedAt, mediaUri,
    local?.localFilePath, local?.localFileName, local?.customName, local?.customArtist, local?.customCoverUrl)

private data class DiffBucketTotals(val listenMs: Long, val playCount: Int, val firstPlayedAt: Long, val lastPlayedAt: Long, val latest: PlaybackStatBucketEntity) {
    fun plus(row: PlaybackStatBucketEntity) = copy(
        listenMs = SyncPlaybackCounterArithmetic.add(listenMs, row.totalListenMs),
        playCount = SyncPlaybackCounterArithmetic.add(playCount, row.playCount),
        firstPlayedAt = when { firstPlayedAt <= 0 -> row.firstPlayedAt; row.firstPlayedAt <= 0 -> firstPlayedAt; else -> minOf(firstPlayedAt, row.firstPlayedAt) },
        lastPlayedAt = maxOf(lastPlayedAt, row.lastPlayedAt),
        latest = if (row.lastPlayedAt > latest.lastPlayedAt || (row.lastPlayedAt == latest.lastPlayedAt && row.dayStartAt > latest.dayStartAt)) row else latest
    )
}
