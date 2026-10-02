@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.merge.dataset

import java.io.Closeable
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackBucketTotalsPolicy
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackStatsMergePolicy
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource

internal object SyncPlaybackNoOpVerifier {
    suspend fun matches(local: SyncPlaybackSource, remote: SyncPlaybackSource, clear: Long): Boolean {
        val days = verifyDays(local, remote, clear) ?: return false
        return verifyTracks(local, remote, clear, days)
    }

    private suspend fun verifyDays(local: SyncPlaybackSource, remote: SyncPlaybackSource, clear: Long): BucketSetDigest? {
        val records = SamePlaybackRecords({ local.openBuckets() }, { remote.openBuckets() })
        val digest = BucketSetDigest()
        var previous: SyncPlaybackStatBucket? = null
        records.use {
            while (true) {
                currentCoroutineContext().ensureActive()
                val bucket = records.peek()
                if (!records.same) return null
                if (bucket == null) return digest
                if (previous != null && compareDays(checkNotNull(previous), bucket) >= 0) return null
                if (SyncPlaybackStatsMergePolicy.mergeBucket(bucket, bucket, clear) != bucket) return null
                digest.add(bucket)
                previous = bucket
                records.takeSame()
            }
        }
    }

    private suspend fun verifyTracks(local: SyncPlaybackSource, remote: SyncPlaybackSource, clear: Long,
        expectedBuckets: BucketSetDigest): Boolean {
        val tracks = SamePlaybackRecords(local::openTracks, remote::openTracks)
        val buckets = SamePlaybackRecords({ local.openBuckets(SyncPlaybackBucketOrder.IDENTITY_DAY) },
            { remote.openBuckets(SyncPlaybackBucketOrder.IDENTITY_DAY) })
        val state = TrackProofState(clear)
        tracks.use { buckets.use {
            while (true) {
                currentCoroutineContext().ensureActive()
                val track = tracks.peek()
                val bucket = buckets.peek()
                if (!tracks.same || !buckets.same) return false
                if (track == null) return state.finish(bucket, expectedBuckets)
                if (!state.canStartTrack(track)) return false
                val fold = state.foldIdentityBuckets(track.identityKey, buckets) ?: return false
                if (!state.verifyTrack(track, fold)) return false
                tracks.takeSame()
            }
        } }
    }

    private class TrackProofState(private val clear: Long) {
        private val buckets = BucketSetDigest()
        private var previousTrack: String? = null
        private var previousBucket: SyncPlaybackStatBucket? = null

        fun canStartTrack(track: SyncTrackStat): Boolean =
            previousTrack?.let { SyncPlaybackKeyOrder.compare(it, track.identityKey) < 0 } ?: true

        suspend fun foldIdentityBuckets(identityKey: String,
            records: SamePlaybackRecords<SyncPlaybackStatBucket>): SyncPlaybackBucketTotalsPolicy.Fold? {
            val fold = SyncPlaybackBucketTotalsPolicy.Fold(identityKey)
            while (true) {
                currentCoroutineContext().ensureActive()
                val bucket = records.peek()
                if (!records.same) return null
                if (bucket == null) return fold
                val identityOrder = SyncPlaybackKeyOrder.compare(bucket.identityKey, identityKey)
                if (identityOrder < 0) return null
                if (identityOrder > 0) return fold
                if (!acceptBucket(bucket)) return null
                fold.add(bucket)
                records.takeSame()
            }
        }

        private fun acceptBucket(bucket: SyncPlaybackStatBucket): Boolean {
            if (previousBucket?.let { compareIdentities(it, bucket) >= 0 } == true) return false
            buckets.add(bucket)
            previousBucket = bucket
            return true
        }

        fun verifyTrack(track: SyncTrackStat, fold: SyncPlaybackBucketTotalsPolicy.Fold): Boolean {
            val normalized = SyncPlaybackStatsMergePolicy.mergeTrack(track, track, clear) ?: return false
            if (fold.lift(normalized) != track) return false
            previousTrack = track.identityKey
            return true
        }

        fun finish(remainingBucket: SyncPlaybackStatBucket?, expectedBuckets: BucketSetDigest): Boolean =
            remainingBucket == null && buckets.sameSet(expectedBuckets)
    }

    private fun compareDays(left: SyncPlaybackStatBucket, right: SyncPlaybackStatBucket): Int =
        left.dayStartAt.compareTo(right.dayStartAt).takeIf { it != 0 }
            ?: SyncPlaybackKeyOrder.compare(left.identityKey, right.identityKey)

    private fun compareIdentities(left: SyncPlaybackStatBucket, right: SyncPlaybackStatBucket): Int =
        SyncPlaybackKeyOrder.compare(left.identityKey, right.identityKey).takeIf { it != 0 }
            ?: left.dayStartAt.compareTo(right.dayStartAt)
}

private class SamePlaybackRecords<T>(left: () -> SyncPlaybackCursor<T>, right: () -> SyncPlaybackCursor<T>) : Closeable {
    private val first = PlaybackIterator(left)
    private val second = PlaybackIterator(right)
    var same = false
        private set

    suspend fun peek(): T? {
        val record = first.peek()
        same = record == second.peek()
        return record
    }

    suspend fun takeSame() {
        check(same) { "Playback proof requires matching decoded records" }
        first.take()
        second.take()
    }

    override fun close() { first.use { second.close() } }
}

private class BucketSetDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val sum = ByteArray(32)
    private var count = 0L

    fun add(bucket: SyncPlaybackStatBucket) {
        check(count < Long.MAX_VALUE) { "Playback bucket proof count exhausted" }
        count++
        val bytes = digest.digest(ProtoBuf.encodeToByteArray(SyncPlaybackStatBucket.serializer(), bucket))
        var carry = 0
        for (index in sum.lastIndex downTo 0) {
            val total = (sum[index].toInt() and 255) + (bytes[index].toInt() and 255) + carry
            sum[index] = total.toByte()
            carry = total ushr 8
        }
    }

    // 两个索引已逐记录验证并完整读尾，摘要只证明它们关联同一完整 payload 集合
    fun sameSet(other: BucketSetDigest): Boolean = count == other.count && sum.contentEquals(other.sum)
}

internal class VerifiedCapturedPlaybackSource(private val captured: SyncPlaybackSource,
    private val verifiedRemote: SyncPlaybackSource) : SyncPlaybackSource {
    private var closed = false

    fun matchesRemote(source: SyncPlaybackSource): Boolean = !closed && source === verifiedRemote

    override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> {
        check(!closed) { "Captured playback view is closed" }
        return captured.openTracks()
    }

    override fun openBuckets(order: SyncPlaybackBucketOrder): SyncPlaybackCursor<SyncPlaybackStatBucket> {
        check(!closed) { "Captured playback view is closed" }
        return captured.openBuckets(order)
    }

    // 会话保有捕获源直到应用完成，本视图只拥有自己的开放状态
    override fun close() { closed = true }
}
