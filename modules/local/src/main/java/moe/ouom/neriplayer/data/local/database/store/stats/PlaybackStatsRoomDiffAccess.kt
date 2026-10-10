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
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotEntity
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
        val header = requireOpenDiff(id)
        val state = checkNotNull(store.readPrimaryState())
        val clearAdvanced = header.clearedAt > state.clearedAt
        walkTracks(id, source, header.clearedAt, clearAdvanced, projection)
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

    private suspend fun requireOpenDiff(id: String): PlaybackStatsSnapshotEntity {
        val header = checkNotNull(staged.getSnapshot(id))
        check(header.isDiff && !header.sealed) { "Playback diff must begin empty and unsealed" }
        return header
    }

    private suspend fun walkTracks(id: String, source: SyncPlaybackSource, clearedAt: Long, clearAdvanced: Boolean,
        projection: SyncPlaybackStatProjection) {
        source.openTracks().use { cursor ->
            val remote = OrderedRecordReader({ cursor.nextPage() }, Comparator<SyncTrackStat> { left, right ->
                SyncPlaybackKeyOrder.compare(left.identityKey, right.identityKey)
            })
            val local = OrderedRecordReader(::trackPage, Comparator<TrackRows> { left, right ->
                SyncPlaybackKeyOrder.compare(left.track.identityKey, right.track.identityKey)
            })
            mergeOrdered(local, remote, { left, right -> SyncPlaybackKeyOrder.compare(left.track.identityKey, right.identityKey) }) { previous, incoming ->
                val target = playbackDiffTarget(previous, incoming, { projection.shouldSync(it.track.toDomain()) }, clearAdvanced,
                    mergeWithIncoming = { local, remote -> SyncPlaybackStatsMergePolicy.mergeTrack(local.toSync(), remote, clearedAt) },
                    trimLocal = { local -> SyncPlaybackStatsMergePolicy.mergeTrack(null, local.toSync(), clearedAt) })
                if (target is PlaybackDiffTarget.Write) stageTrack(id, previous, target.value)
            }
        }
        flushTracks(id)
    }

    private suspend fun stageTrack(id: String, previous: TrackRows?, desired: SyncTrackStat?) {
        if (desired == null) {
            previous?.let { trackDeletions.add(PlaybackStatsSnapshotDeletedTrackEntity(id, it.track.identityKey)) }
        } else {
            stageTrackChange(previous, desired)
        }
        if (trackChanges.size + trackDeletions.size >= SYNC_PLAYBACK_PAGE_RECORDS) flushTracks(id)
    }

    private fun stageTrackChange(previous: TrackRows?, desired: SyncTrackStat) {
        val row = desired.toPrimaryEntity(previous?.track)
        val counters = normalizeCounters(desired.counterShards).map { it.toTrackEntity(row.identityKey) }
        if (previous != TrackRows(row, counters)) trackChanges.add(TrackChange(row, counters))
    }

    private suspend fun trackPage(after: TrackRows?): List<TrackRows> {
        val page = primary.identityPage(after?.track?.identityKey, SYNC_PLAYBACK_PAGE_RECORDS)
        if (page.isEmpty()) return emptyList()
        val shards = primary.trackCountersByKeys(page.map { it.identityKey }).groupBy { it.identityKey }
        return page.map { TrackRows(it, shards[it.identityKey].orEmpty()) }
    }

    private suspend fun bucketPage(after: BucketRows?, order: SyncPlaybackBucketOrder): List<BucketRows> {
        val page = primaryBucketPage(after?.bucket, order)
        if (page.isEmpty()) return emptyList()
        val shards = page.groupBy { it.dayStartAt }.flatMap { (day, rows) -> primary.dailyCountersByKeys(day, rows.map { it.identityKey }) }
            .groupBy { it.dayStartAt to it.identityKey }
        return page.map { BucketRows(it, shards[it.dayStartAt to it.identityKey].orEmpty()) }
    }

    private suspend fun primaryBucketPage(after: PlaybackStatBucketEntity?, order: SyncPlaybackBucketOrder) = when (order) {
        SyncPlaybackBucketOrder.DAY_IDENTITY -> primary.bucketPage(after?.dayStartAt, after?.identityKey, SYNC_PLAYBACK_PAGE_RECORDS)
        SyncPlaybackBucketOrder.IDENTITY_DAY -> primary.bucketIdentityPage(after?.identityKey, after?.dayStartAt, SYNC_PLAYBACK_PAGE_RECORDS)
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
                val target = playbackDiffTarget(previous, incoming, { projection.shouldSync(it.bucket.toDomain()) }, clearAdvanced,
                    mergeWithIncoming = { local, remote -> SyncPlaybackStatsMergePolicy.mergeBucket(local.toSync(), remote, clearedAt) },
                    trimLocal = { local -> SyncPlaybackStatsMergePolicy.mergeBucket(null, local.toSync(), clearedAt) })
                when (target) {
                    PlaybackDiffTarget.Keep -> consume(previous, previous)
                    is PlaybackDiffTarget.Write -> consume(previous, target.value?.toBucketRows(previous?.bucket))
                }
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
        val page = ArrayList<DiffBucketTotals>()
        val totals = DiffBucketTotalsAccumulator { total ->
            page.add(total)
            if (page.size == SYNC_PLAYBACK_PAGE_RECORDS) { liftTotalsPage(id, page); page.clear() }
        }
        walkBuckets(source, SyncPlaybackBucketOrder.IDENTITY_DAY, clearedAt, clearAdvanced = true, projection) { _, desired ->
            if (desired != null) totals.add(desired.bucket)
        }
        totals.finish()
        if (page.isNotEmpty()) liftTotalsPage(id, page)
        flushTracks(id)
    }

    private suspend fun liftTotalsPage(id: String, totals: List<DiffBucketTotals>) {
        val keys = totals.map { it.latest.identityKey }
        val sources = LiftedTrackSources(
            changes = staged.tracksByKeys(id, keys).associateBy { it.stat.identityKey },
            deleted = staged.deletedTrackKeys(id, keys).toSet(),
            originals = primary.tracksByKeys(keys).associateBy { it.identityKey },
            stagedCounters = staged.trackCounters(id, keys).groupBy { it.shard.identityKey },
            originalCounters = primary.trackCountersByKeys(keys).groupBy { it.identityKey }
        )
        totals.mapNotNullTo(trackChanges) { sources.liftedChange(it) }
        flushTracks(id)
    }
}

/** Track rows a final-totals lift reads: rows already staged in this diff win over the primary copy unless it was deleted. */
private class LiftedTrackSources(
    val changes: Map<String, PlaybackStatsSnapshotTrackEntity>,
    val deleted: Set<String>,
    val originals: Map<String, PlaybackStatEntity>,
    val stagedCounters: Map<String, List<PlaybackStatsSnapshotCounterEntity>>,
    val originalCounters: Map<String, List<PlaybackStatCounterShardEntity>>
) {
    fun liftedChange(total: DiffBucketTotals): TrackChange? {
        val key = total.latest.identityKey
        val changed = changes[key]?.let { it.stat.toEntity() }
        val existing = changed ?: originals[key]?.takeUnless { it.identityKey in deleted }
        val desired = if (existing != null) existing.liftedTo(total) else total.toTrackEntity(originals[key])
        if (existing == desired) return null
        return TrackChange(desired, countersFor(key, changed, existing))
    }

    private fun countersFor(key: String, changed: PlaybackStatEntity?, existing: PlaybackStatEntity?): List<PlaybackStatCounterShardEntity> = when {
        changed != null -> stagedCounters[key].orEmpty().map { it.shard.toEntity() }
        existing != null -> originalCounters[key].orEmpty()
        else -> emptyList()
    }
}

private fun PlaybackStatEntity.liftedTo(total: DiffBucketTotals) =
    copy(totalListenMs = maxOf(totalListenMs, total.listenMs), playCount = maxOf(playCount, total.playCount))

private fun DiffBucketTotals.toTrackEntity(original: PlaybackStatEntity?): PlaybackStatEntity {
    val lifted = PlaybackStatEntity(latest.identityKey, latest.id, latest.name, latest.artist, latest.album, latest.albumId,
        latest.coverUrl, latest.durationMs, listenMs, playCount, lastPlayedAt, firstPlayedAt, latest.mediaUri, latest.localFilePath,
        latest.localFileName, latest.customName, latest.customArtist, latest.customCoverUrl)
    return if (original == null) lifted else lifted.withDeviceFieldsFrom(original)
}

/** Device-only file and custom fields of the primary copy win over those carried by the remote bucket. */
private fun PlaybackStatEntity.withDeviceFieldsFrom(original: PlaybackStatEntity) = copy(
    localFilePath = preferred(original.localFilePath, localFilePath),
    localFileName = preferred(original.localFileName, localFileName),
    customName = preferred(original.customName, customName),
    customArtist = preferred(original.customArtist, customArtist),
    customCoverUrl = preferred(original.customCoverUrl, customCoverUrl)
)

private fun preferred(value: String?, fallback: String?): String? = value ?: fallback

/** Sums the buckets of one identity at a time; rows must arrive grouped by identity. */
private class DiffBucketTotalsAccumulator(private val completed: suspend (DiffBucketTotals) -> Unit) {
    private var current: DiffBucketTotals? = null

    suspend fun add(row: PlaybackStatBucketEntity) {
        val totals = current
        current = when {
            totals == null -> DiffBucketTotals.of(row)
            totals.latest.identityKey != row.identityKey -> { completed(totals); DiffBucketTotals.of(row) }
            else -> totals.plus(row)
        }
    }

    suspend fun finish() {
        current?.let { completed(it) }
        current = null
    }
}

internal sealed interface PlaybackDiffTarget<out T> {
    data object Keep : PlaybackDiffTarget<Nothing>
    /** A null [value] removes the local record from the diff. */
    data class Write<T>(val value: T?) : PlaybackDiffTarget<T>
}

/**
 * Decides what the diff holds for one key: remote records win unless the local record is private to this device, local
 * records the remote dropped are removed, and private local records are only rewritten when a newer clear trims them.
 */
internal fun <L : Any, R : Any> playbackDiffTarget(previous: L?, incoming: R?, isSynced: (L) -> Boolean, clearAdvanced: Boolean,
    mergeWithIncoming: (L, R) -> R?, trimLocal: (L) -> R?): PlaybackDiffTarget<R> = when {
    incoming != null && previous != null && !isSynced(previous) -> PlaybackDiffTarget.Write(mergeWithIncoming(previous, incoming))
    incoming != null -> PlaybackDiffTarget.Write(incoming)
    previous == null || isSynced(previous) -> PlaybackDiffTarget.Write(null)
    clearAdvanced -> PlaybackDiffTarget.Write(trimLocal(previous))
    else -> PlaybackDiffTarget.Keep
}

private data class TrackRows(val track: PlaybackStatEntity, val counters: List<PlaybackStatCounterShardEntity>) {
    fun toSync() = SyncPlaybackStatMapper.fromTrackStat(track.toDomain(), counters.map { it.toDomain() })
}

private data class BucketRows(val bucket: PlaybackStatBucketEntity, val counters: List<PlaybackStatDailyCounterShardEntity>) {
    fun toSync() = SyncPlaybackStatMapper.fromPlaybackStatBucket(bucket.toDomain(), counters.map { it.toDomain() })
}

private fun SyncPlaybackStatBucket.toBucketRows(local: PlaybackStatBucketEntity?): BucketRows {
    val row = toPrimaryEntity(local)
    return BucketRows(row, normalizeCounters(counterShards).map { it.toDailyEntity(row.dayStartAt, row.identityKey) })
}

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
            requireOrderedPage()
        }
        return page[index++].also { previous = it }
    }

    private fun requireOrderedPage() {
        require(page.size <= SYNC_PLAYBACK_PAGE_RECORDS) { "Playback page exceeds its record budget" }
        var last = previous
        for (value in page) {
            if (last != null && order.compare(last, value) >= 0) throw IOException("Playback source is not strictly ordered and unique")
            last = value
        }
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

    companion object {
        fun of(row: PlaybackStatBucketEntity) = DiffBucketTotals(row.totalListenMs, row.playCount, row.firstPlayedAt, row.lastPlayedAt, row)
    }
}
