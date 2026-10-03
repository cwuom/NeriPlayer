package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat

object SyncPlaybackBucketTotalsPolicy {
    private data class BucketTotals(
        val totalListenMs: Long,
        val playCount: Long,
        val firstPlayedAt: Long,
        val lastPlayedAt: Long,
        val latestBucket: SyncPlaybackStatBucket
    )

    class Fold(private val identityKey: String) {
        private var totals: BucketTotals? = null

        fun add(bucket: SyncPlaybackStatBucket) {
            require(bucket.identityKey == identityKey)
            totals = mergeBucketTotals(totals, bucket)
        }

        fun lift(stat: SyncTrackStat?): SyncTrackStat? {
            require(stat == null || stat.identityKey == identityKey)
            return if (stat == null) totals?.toTrackStat(identityKey) else liftTrack(stat, totals)
        }
    }

    fun liftStatsToBucketTotals(
        stats: List<SyncTrackStat>,
        buckets: List<SyncPlaybackStatBucket>
    ): List<SyncTrackStat> {
        if (buckets.isEmpty()) return stats
        val bucketTotalsByKey = LinkedHashMap<String, BucketTotals>()
        for (bucket in buckets) {
            val key = bucket.identityKey
            val current = bucketTotalsByKey[key]
            bucketTotalsByKey[key] = mergeBucketTotals(current, bucket)
        }

        val statKeys = stats.mapTo(HashSet()) { it.identityKey }
        return buildList(stats.size + bucketTotalsByKey.size) {
            stats.forEach { stat ->
                add(liftTrack(stat, bucketTotalsByKey[stat.identityKey]))
            }
            bucketTotalsByKey.forEach { (identityKey, bucketTotals) ->
                if (identityKey !in statKeys) {
                    add(bucketTotals.toTrackStat(identityKey))
                }
            }
        }
    }

    private fun mergeBucketTotals(current: BucketTotals?, bucket: SyncPlaybackStatBucket): BucketTotals {
        if (current == null) return BucketTotals(
            bucket.totalListenMs.coerceAtLeast(0L), bucket.playCount.coerceAtLeast(0).toLong(),
            bucket.firstPlayedAt, bucket.lastPlayedAt, bucket
        )
        return current.copy(
            totalListenMs = saturatedNonNegativeSum(current.totalListenMs, bucket.totalListenMs),
            playCount = saturatedNonNegativeSum(current.playCount, bucket.playCount.toLong()),
            firstPlayedAt = minPositivePlayedAt(current.firstPlayedAt, bucket.firstPlayedAt),
            lastPlayedAt = maxOf(current.lastPlayedAt, bucket.lastPlayedAt),
            latestBucket = latestBucket(current.latestBucket, bucket)
        )
    }

    private fun latestBucket(current: SyncPlaybackStatBucket, incoming: SyncPlaybackStatBucket): SyncPlaybackStatBucket =
        if (incoming.lastPlayedAt > current.lastPlayedAt ||
            (incoming.lastPlayedAt == current.lastPlayedAt && incoming.dayStartAt > current.dayStartAt)) incoming else current

    private fun liftTrack(stat: SyncTrackStat, totals: BucketTotals?): SyncTrackStat {
        val listen = maxOf(stat.totalListenMs, totals?.totalListenMs ?: 0L)
        val play = maxOf(stat.playCount.toLong(), totals?.playCount ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (listen == stat.totalListenMs && play == stat.playCount) stat
            else stat.copy(totalListenMs = listen, playCount = play)
    }

    private fun BucketTotals.toTrackStat(identityKey: String): SyncTrackStat {
        return SyncTrackStat(
            identityKey = identityKey,
            name = latestBucket.name,
            artist = latestBucket.artist,
            album = latestBucket.album,
            totalListenMs = totalListenMs,
            playCount = playCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt,
            coverUrl = latestBucket.coverUrl,
            durationMs = latestBucket.durationMs,
            mediaUri = latestBucket.mediaUri,
            id = latestBucket.id,
            albumId = latestBucket.albumId
        )
    }

    private fun saturatedNonNegativeSum(left: Long, right: Long): Long {
        val normalizedLeft = left.coerceAtLeast(0L)
        val normalizedRight = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - normalizedLeft < normalizedRight) {
            Long.MAX_VALUE
        } else {
            normalizedLeft + normalizedRight
        }
    }

    private fun minPositivePlayedAt(left: Long, right: Long): Long {
        return when {
            left <= 0L -> right
            right <= 0L -> left
            else -> minOf(left, right)
        }
    }
}
