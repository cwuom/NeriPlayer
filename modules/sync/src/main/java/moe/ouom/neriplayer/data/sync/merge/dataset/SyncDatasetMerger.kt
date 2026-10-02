package moe.ouom.neriplayer.data.sync.merge.dataset

import moe.ouom.neriplayer.data.sync.runtime.dataset.*
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackPageWriter

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.change.SyncDataChangeDetector
import moe.ouom.neriplayer.data.sync.mapping.stats.SyncPlaybackStatMapping
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackBucketTotalsPolicy
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackStatsMergePolicy

class SyncDatasetMerger(private val merger: SyncDataMerger, private val store: SyncPlaybackDatasetStore) {
    suspend fun merge(local: SyncDataset, remote: SyncDataset?, lastSyncTime: Long): SyncDatasetMergeResult {
        val core = if (remote == null) merger.initial(local.data) else merger.merge(local.data, remote.data, lastSyncTime)
        val clear = core.mergedData.playbackStatsClearedAt
        if (remote != null && clear == local.data.playbackStatsClearedAt &&
            SyncPlaybackNoOpVerifier.matches(local.playback, remote.playback, clear)) {
            return SyncDatasetMergeResult(SyncDataset(core.mergedData,
                VerifiedCapturedPlaybackSource(local.playback, remote.playback), local.capturedPlaybackRevision, true), core.syncResult)
        }
        val source = mergePlayback(local.playback, remote?.playback, clear)
        try {
            val matches = matchesCaptured(local, clear, source)
            return SyncDatasetMergeResult(
                SyncDataset(core.mergedData, source, local.capturedPlaybackRevision, matches), core.syncResult
            )
        } catch (error: Exception) {
            try { source.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private suspend fun mergePlayback(local: SyncPlaybackSource, remote: SyncPlaybackSource?, clear: Long): SyncPlaybackSource {
        val mergedBuckets = store.newOrderedSink().use { sink ->
            mergeBuckets(local, remote, clear, sink)
            sink.seal()
        }
        try {
            val mergedTracks = store.newOrderedSink().use { sink ->
                mergeTracks(local, remote, mergedBuckets, clear, sink)
                sink.seal()
            }
            return MergedPlaybackSource(mergedTracks, mergedBuckets)
        } catch (error: Exception) {
            try { mergedBuckets.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private suspend fun matchesCaptured(local: SyncDataset, clear: Long, source: SyncPlaybackSource): Boolean {
        if (clear != local.data.playbackStatsClearedAt) return false
        if (different({ local.playback.openTracks() }, { source.openTracks() }, SyncPlaybackStatMapping::sameMetadata)) return false
        return !different({ local.playback.openBuckets() }, { source.openBuckets() }, SyncPlaybackStatMapping::sameMetadata)
    }

    suspend fun changed(remote: SyncDataset?, merged: SyncDataset, requiresMigration: Boolean): Boolean {
        if (remote == null || requiresMigration || SyncDataChangeDetector.hasDataChanged(remote.data, merged.data)) return true
        if ((merged.playback as? VerifiedCapturedPlaybackSource)?.matchesRemote(remote.playback) == true) return false
        if (different({ remote.playback.openTracks() }, { merged.playback.openTracks() }, SyncPlaybackStatMapping::sameMetadata)) return true
        return different({ remote.playback.openBuckets() }, { merged.playback.openBuckets() }, SyncPlaybackStatMapping::sameMetadata)
    }

    private suspend fun mergeBuckets(local: SyncPlaybackSource, remote: SyncPlaybackSource?, clear: Long, sink: SyncPlaybackSink) {
        val left = PlaybackIterator { local.openBuckets() }
        val right = PlaybackIterator { remote?.openBuckets() }
        val page = SyncPlaybackPageWriter(SyncPlaybackStatBucket.serializer(), sink::appendBuckets)
        try {
            while (true) {
                val seed = smaller(left.peek(), right.peek(), ::compareBuckets) ?: break
                val localMerged = foldBuckets(left, seed, null, clear)
                val merged = foldBuckets(right, seed, localMerged, clear)
                merged?.let { page.add(it) }
            }
            page.finish()
        } finally { left.close(); right.close() }
    }

    private suspend fun mergeTracks(
        local: SyncPlaybackSource, remote: SyncPlaybackSource?, buckets: SyncPlaybackSource,
        clear: Long, sink: SyncPlaybackSink
    ) {
        val left = PlaybackIterator { local.openTracks() }
        val right = PlaybackIterator { remote?.openTracks() }
        val days = PlaybackIterator { buckets.openBuckets(SyncPlaybackBucketOrder.IDENTITY_DAY) }
        val page = SyncPlaybackPageWriter(SyncTrackStat.serializer(), sink::appendTracks)
        try {
            while (true) {
                val identity = nextIdentity(left, right, days) ?: break
                val localMerged = foldTracks(left, identity, null, clear)
                val merged = foldTracks(right, identity, localMerged, clear)
                val fold = SyncPlaybackBucketTotalsPolicy.Fold(identity)
                foldBucketTotals(days, identity, fold)
                fold.lift(merged)?.let { page.add(it) }
            }
            page.finish()
        } finally { left.close(); right.close(); days.close() }
    }

    private suspend fun foldBuckets(
        records: PlaybackIterator<SyncPlaybackStatBucket>, seed: SyncPlaybackStatBucket,
        initial: SyncPlaybackStatBucket?, clear: Long
    ): SyncPlaybackStatBucket? {
        var result = initial
        while (records.peek()?.let { compareBuckets(it, seed) == 0 } == true) {
            result = SyncPlaybackStatsMergePolicy.mergeBucket(result, checkNotNull(records.take()), clear)
        }
        return result
    }

    private suspend fun nextIdentity(
        left: PlaybackIterator<SyncTrackStat>, right: PlaybackIterator<SyncTrackStat>,
        days: PlaybackIterator<SyncPlaybackStatBucket>
    ): String? = listOfNotNull(left.peek()?.identityKey, right.peek()?.identityKey, days.peek()?.identityKey)
        .minWithOrNull(SyncPlaybackKeyOrder)

    private suspend fun foldTracks(
        records: PlaybackIterator<SyncTrackStat>, identity: String,
        initial: SyncTrackStat?, clear: Long
    ): SyncTrackStat? {
        var result = initial
        while (records.peek()?.identityKey == identity) {
            result = SyncPlaybackStatsMergePolicy.mergeTrack(result, checkNotNull(records.take()), clear)
        }
        return result
    }

    private suspend fun foldBucketTotals(
        records: PlaybackIterator<SyncPlaybackStatBucket>, identity: String,
        totals: SyncPlaybackBucketTotalsPolicy.Fold
    ) {
        while (records.peek()?.identityKey == identity) totals.add(checkNotNull(records.take()))
    }

    private fun compareBuckets(left: SyncPlaybackStatBucket, right: SyncPlaybackStatBucket): Int =
        left.dayStartAt.compareTo(right.dayStartAt).takeIf { it != 0 }
            ?: SyncPlaybackKeyOrder.compare(left.identityKey, right.identityKey)

    private fun <T> smaller(left: T?, right: T?, order: (T, T) -> Int): T? = when {
        left == null -> right
        right == null -> left
        order(left, right) <= 0 -> left
        else -> right
    }

    private suspend fun <T> different(left: () -> SyncPlaybackCursor<T>, right: () -> SyncPlaybackCursor<T>, same: (T, T) -> Boolean): Boolean {
        val first = PlaybackIterator(left)
        val second = PlaybackIterator(right)
        try {
            while (true) {
                val a = first.take()
                val b = second.take()
                if (a == null || b == null) return a != b
                if (!same(a, b)) return true
            }
        } finally { first.close(); second.close() }
    }
}

internal class PlaybackIterator<T>(private val open: () -> SyncPlaybackCursor<T>?) : java.io.Closeable {
    private var cursor: SyncPlaybackCursor<T>? = null
    private var opened = false
    private var page: List<T> = emptyList()
    private var index = 0
    private var ended = false
    suspend fun peek(): T? {
        openIfNeeded()
        if (index == page.size && !ended) readPage()
        return page.getOrNull(index)
    }
    private fun openIfNeeded() {
        if (!opened) {
            cursor = open()
            opened = true
            ended = cursor == null
        }
    }
    private suspend fun readPage() {
        page = openedCursor().nextPage()
        require(page.size <= SYNC_PLAYBACK_PAGE_RECORDS) { "Playback cursor exceeds page budget" }
        index = 0
        ended = page.isEmpty()
    }
    private fun openedCursor(): SyncPlaybackCursor<T> = checkNotNull(cursor) { "Playback cursor was not opened" }
    suspend fun take(): T? = peek()?.also { index++ }
    override fun close() { cursor?.close() }
}

private class MergedPlaybackSource(
    private val tracks: SyncPlaybackSource, private val buckets: SyncPlaybackSource
) : SyncPlaybackSource {
    private var closed = false

    override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> {
        check(!closed) { "Merged playback dataset is closed" }
        return tracks.openTracks()
    }

    override fun openBuckets(order: SyncPlaybackBucketOrder): SyncPlaybackCursor<SyncPlaybackStatBucket> {
        check(!closed) { "Merged playback dataset is closed" }
        return buckets.openBuckets(order)
    }

    override fun close() {
        if (closed) return
        closed = true
        // 两个不可变文件集直接复用，关闭失败仍须释放另一个集合
        tracks.use { buckets.close() }
    }
}
