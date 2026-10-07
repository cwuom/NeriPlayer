package moe.ouom.neriplayer.data.local.database.store.stats

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsSnapshotDao
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsEventReceiptEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDeletedTrackEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackEntity

/**
 * Snapshot staging tables kept in memory with the key and ordering rules of the Room queries.
 *
 * Only staging, paging, tombstone and counter-candidate queries are modelled; publishing, receipts
 * and pending deltas belong to the primary store and fail loudly here. Raw counter-candidate
 * lookups are answered from their bound arguments: each requested key tuple, then the snapshot id.
 */
internal class InMemoryPlaybackStatsSnapshotDao : PlaybackStatsSnapshotDao {
    val snapshots = linkedMapOf<String, PlaybackStatsSnapshotEntity>()
    val deletedTracks = linkedSetOf<PlaybackStatsSnapshotDeletedTrackEntity>()
    val deletedBuckets = linkedSetOf<PlaybackStatsSnapshotDeletedBucketEntity>()
    val candidateQueries = mutableListOf<SupportSQLiteQuery>()
    private val tracks = linkedMapOf<List<Any>, PlaybackStatsSnapshotTrackEntity>()
    private val buckets = linkedMapOf<List<Any>, PlaybackStatsSnapshotBucketEntity>()
    private val counters = linkedMapOf<List<Any>, PlaybackStatsSnapshotCounterEntity>()
    private val dailyCounters = linkedMapOf<List<Any>, PlaybackStatsSnapshotDailyCounterEntity>()

    fun storedTracks(id: String): List<PlaybackStatsSnapshotTrackData> = trackRows(id).map { it.stat }

    fun storedBuckets(id: String): List<PlaybackStatsSnapshotBucketData> = dayOrderedBuckets(id).map { it.bucket }

    fun storedCounters(id: String): List<PlaybackStatsSnapshotCounterData> =
        counters.values.filter { it.snapshotId == id }.map { it.shard }
            .sortedWith(compareBy({ it.identityKey }, { it.deviceId }, { it.epochStartedAt }))

    fun storedDailyCounters(id: String): List<PlaybackStatsSnapshotDailyCounterData> =
        dailyCounters.values.filter { it.snapshotId == id }.map { it.shard }
            .sortedWith(compareBy({ it.dayStartAt }, { it.identityKey }, { it.deviceId }, { it.epochStartedAt }))

    override suspend fun upsertSnapshot(snapshot: PlaybackStatsSnapshotEntity) {
        snapshots[snapshot.id] = snapshot
    }

    override suspend fun getSnapshot(id: String): PlaybackStatsSnapshotEntity? = snapshots[id]

    override suspend fun deleteSnapshot(id: String) {
        snapshots.remove(id)
    }

    override suspend fun updateSnapshotState(id: String, sealed: Boolean, clearedAt: Long) {
        val snapshot = snapshots[id] ?: return
        snapshots[id] = snapshot.copy(
            sealed = sealed,
            clearedAt = clearedAt,
            counterEpochStartedAt = maxOf(snapshot.counterEpochStartedAt, clearedAt)
        )
    }

    override suspend fun upsertDeletedTracks(rows: List<PlaybackStatsSnapshotDeletedTrackEntity>) {
        deletedTracks += rows
    }

    override suspend fun upsertDeletedBuckets(rows: List<PlaybackStatsSnapshotDeletedBucketEntity>) {
        deletedBuckets += rows
    }

    override suspend fun deletedTrackKeys(id: String, keys: List<String>): List<String> =
        keys.filter { PlaybackStatsSnapshotDeletedTrackEntity(id, it) in deletedTracks }

    override suspend fun restoreDeletedTracks(id: String, keys: List<String>) {
        keys.forEach { deletedTracks.remove(PlaybackStatsSnapshotDeletedTrackEntity(id, it)) }
    }

    override suspend fun deleteTrackTombstones(id: String) {
        deletedTracks.removeAll { it.snapshotId == id }
    }

    override suspend fun deleteBucketTombstones(id: String) {
        deletedBuckets.removeAll { it.snapshotId == id }
    }

    override suspend fun firstTrackPage(id: String, limit: Int): List<PlaybackStatsSnapshotTrackEntity> =
        trackRows(id).take(limit)

    override suspend fun nextTrackPage(id: String, after: String, limit: Int): List<PlaybackStatsSnapshotTrackEntity> =
        trackRows(id).filter { it.stat.identityKey > after }.take(limit)

    override suspend fun firstBucketPage(id: String, limit: Int): List<PlaybackStatsSnapshotBucketEntity> =
        dayOrderedBuckets(id).take(limit)

    override suspend fun nextBucketPage(
        id: String,
        afterDay: Long,
        afterIdentity: String,
        limit: Int
    ): List<PlaybackStatsSnapshotBucketEntity> = dayOrderedBuckets(id).filter {
        it.bucket.dayStartAt > afterDay || (it.bucket.dayStartAt == afterDay && it.bucket.identityKey > afterIdentity)
    }.take(limit)

    override suspend fun firstBucketIdentityPage(id: String, limit: Int): List<PlaybackStatsSnapshotBucketEntity> =
        identityOrderedBuckets(id).take(limit)

    override suspend fun nextBucketIdentityPage(
        id: String,
        afterIdentity: String,
        afterDay: Long,
        limit: Int
    ): List<PlaybackStatsSnapshotBucketEntity> = identityOrderedBuckets(id).filter {
        it.bucket.identityKey > afterIdentity || (it.bucket.identityKey == afterIdentity && it.bucket.dayStartAt > afterDay)
    }.take(limit)

    override suspend fun trackCounters(id: String, keys: List<String>): List<PlaybackStatsSnapshotCounterEntity> =
        counters.values.filter { it.snapshotId == id && it.shard.identityKey in keys }
            .sortedWith(compareBy({ it.shard.identityKey }, { it.shard.deviceId }, { it.shard.epochStartedAt }))

    override suspend fun dailyCounters(id: String, day: Long, keys: List<String>): List<PlaybackStatsSnapshotDailyCounterEntity> =
        dailyCounters.values.filter { it.snapshotId == id && it.shard.dayStartAt == day && it.shard.identityKey in keys }
            .sortedWith(compareBy({ it.shard.identityKey }, { it.shard.deviceId }, { it.shard.epochStartedAt }))

    override suspend fun tracksByKeys(id: String, keys: List<String>): List<PlaybackStatsSnapshotTrackEntity> =
        trackRows(id).filter { it.stat.identityKey in keys }

    override suspend fun bucketsByKeys(id: String, day: Long, keys: List<String>): List<PlaybackStatsSnapshotBucketEntity> =
        dayOrderedBuckets(id).filter { it.bucket.dayStartAt == day && it.bucket.identityKey in keys }

    override suspend fun upsertTracks(rows: List<PlaybackStatsSnapshotTrackEntity>) {
        rows.forEach { tracks[listOf(it.snapshotId, it.stat.identityKey)] = it }
    }

    override suspend fun upsertBuckets(rows: List<PlaybackStatsSnapshotBucketEntity>) {
        rows.forEach { buckets[listOf(it.snapshotId, it.bucket.dayStartAt, it.bucket.identityKey)] = it }
    }

    override suspend fun upsertCounters(rows: List<PlaybackStatsSnapshotCounterEntity>) {
        rows.forEach { counters[listOf(it.snapshotId, it.shard.identityKey, it.shard.deviceId, it.shard.epochStartedAt)] = it }
    }

    override suspend fun upsertDailyCounters(rows: List<PlaybackStatsSnapshotDailyCounterEntity>) {
        rows.forEach {
            dailyCounters[listOf(it.snapshotId, it.shard.dayStartAt, it.shard.identityKey, it.shard.deviceId, it.shard.epochStartedAt)] = it
        }
    }

    override suspend fun deleteCountersByKeys(id: String, keys: List<String>) {
        counters.values.removeAll { it.snapshotId == id && it.shard.identityKey in keys }
    }

    override suspend fun deleteDailyCountersByKeys(id: String, day: Long, keys: List<String>) {
        dailyCounters.values.removeAll { it.snapshotId == id && it.shard.dayStartAt == day && it.shard.identityKey in keys }
    }

    override suspend fun deleteTracksByKeys(id: String, keys: List<String>) {
        tracks.values.removeAll { it.snapshotId == id && it.stat.identityKey in keys }
    }

    override suspend fun deleteBucketsByKeys(id: String, day: Long, keys: List<String>) {
        buckets.values.removeAll { it.snapshotId == id && it.bucket.dayStartAt == day && it.bucket.identityKey in keys }
    }

    override suspend fun queryCounterCandidates(query: SupportSQLiteQuery): List<PlaybackStatsSnapshotCounterEntity> {
        candidateQueries += query
        return requestedKeys(query, columns = 3).mapNotNull(counters::get)
    }

    override suspend fun queryDailyCounterCandidates(query: SupportSQLiteQuery): List<PlaybackStatsSnapshotDailyCounterEntity> {
        candidateQueries += query
        return requestedKeys(query, columns = 4).mapNotNull(dailyCounters::get)
    }

    override suspend fun hasDiffRows(id: String): Boolean = unsupported()
    override suspend fun applyTrackTombstones(id: String): Unit = unsupported()
    override suspend fun applyBucketTombstones(id: String): Unit = unsupported()
    override suspend fun deleteReplacedCounters(id: String): Unit = unsupported()
    override suspend fun deleteReplacedDailyCounters(id: String): Unit = unsupported()
    override suspend fun updateDiffTracks(id: String): Unit = unsupported()
    override suspend fun insertDiffTracks(id: String): Unit = unsupported()
    override suspend fun updateDiffBuckets(id: String): Unit = unsupported()
    override suspend fun insertDiffBuckets(id: String): Unit = unsupported()
    override suspend fun abandonedSnapshotIds(owner: String, limit: Int): List<String> = unsupported()
    override suspend fun freezeTrack(id: String): Unit = unsupported()
    override suspend fun publishTrack(id: String): Unit = unsupported()
    override suspend fun deleteTrack(id: String): Unit = unsupported()
    override suspend fun freezeBucket(id: String): Unit = unsupported()
    override suspend fun publishBucket(id: String): Unit = unsupported()
    override suspend fun deleteBucket(id: String): Unit = unsupported()
    override suspend fun freezeCounter(id: String): Unit = unsupported()
    override suspend fun publishCounter(id: String): Unit = unsupported()
    override suspend fun deleteCounter(id: String): Unit = unsupported()
    override suspend fun deleteLegacyOrphanCounters(id: String): Unit = unsupported()
    override suspend fun freezeDailyCounter(id: String): Unit = unsupported()
    override suspend fun publishDailyCounter(id: String): Unit = unsupported()
    override suspend fun deleteDailyCounter(id: String): Unit = unsupported()
    override suspend fun deleteLegacyOrphanDailyCounters(id: String): Unit = unsupported()
    override suspend fun insertReceipt(receipt: PlaybackStatsEventReceiptEntity): Unit = unsupported()
    override suspend fun receipt(id: String): PlaybackStatsEventReceiptEntity? = unsupported()
    override suspend fun pruneCompletedReceipts(retainedCount: Int): Unit = unsupported()
    override suspend fun insertPendingDelta(delta: PlaybackStatsPendingDeltaEntity): Unit = unsupported()
    override suspend fun pendingDelta(id: String): PlaybackStatsPendingDeltaEntity? = unsupported()
    override suspend fun pendingDeltas(limit: Int): List<PlaybackStatsPendingDeltaEntity> = unsupported()
    override suspend fun pendingDeltaCount(): Long = unsupported()
    override suspend fun deletePendingDelta(id: String): Unit = unsupported()
    override suspend fun deleteAllPendingDeltas(): Unit = unsupported()

    private fun trackRows(id: String) = tracks.values.filter { it.snapshotId == id }.sortedBy { it.stat.identityKey }

    private fun dayOrderedBuckets(id: String) = buckets.values.filter { it.snapshotId == id }
        .sortedWith(compareBy({ it.bucket.dayStartAt }, { it.bucket.identityKey }))

    private fun identityOrderedBuckets(id: String) = buckets.values.filter { it.snapshotId == id }
        .sortedWith(compareBy({ it.bucket.identityKey }, { it.bucket.dayStartAt }))

    private fun requestedKeys(query: SupportSQLiteQuery, columns: Int): List<List<Any>> {
        val arguments = query.boundArguments()
        val id = checkNotNull(arguments.last())
        return arguments.dropLast(1).chunked(columns).map { key -> listOf(id) + key.map { checkNotNull(it) } }
    }

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Snapshot access tests only exercise staging tables")
}

/** Arguments bound by [SupportSQLiteQuery.bindTo], in placeholder order. */
internal fun SupportSQLiteQuery.boundArguments(): List<Any?> {
    val values = sortedMapOf<Int, Any?>()
    bindTo(object : SupportSQLiteProgram {
        override fun bindNull(index: Int) { values[index] = null }
        override fun bindLong(index: Int, value: Long) { values[index] = value }
        override fun bindDouble(index: Int, value: Double) { values[index] = value }
        override fun bindString(index: Int, value: String) { values[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { values[index] = value }
        override fun clearBindings() = values.clear()
        override fun close() = Unit
    })
    check(values.size == argCount) { "Query bound ${values.size} of $argCount arguments" }
    return values.values.toList()
}
