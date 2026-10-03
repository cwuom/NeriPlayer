package moe.ouom.neriplayer.data.sync.mapping.stats

import moe.ouom.neriplayer.data.sync.merge.stats.SyncCounterShardPolicy
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackCounterArithmetic
import moe.ouom.neriplayer.data.sync.sanitize.SyncSanitizationHost
import moe.ouom.neriplayer.data.sync.policy.isLocalMediaUri
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.policy.sanitizeCoverUrlForSync

object SyncPlaybackStatMapping {
    fun shouldSync(stat: TrackStat, host: SyncSanitizationHost): Boolean {
        return stat.localFilePath.isNullOrBlank() &&
            !host.isLocalSong(stat.album, stat.mediaUri, stat.albumId)
    }

    fun shouldSync(bucket: PlaybackStatBucket, host: SyncSanitizationHost): Boolean {
        return bucket.localFilePath.isNullOrBlank() &&
            !host.isLocalSong(bucket.album, bucket.mediaUri, bucket.albumId)
    }

    fun fromTrackStat(
        stat: TrackStat,
        counterShards: List<SyncPlaybackCounterShard> = emptyList()
    ): SyncTrackStat {
        val normalizedShards = normalizeCounterShards(counterShards)
        return SyncTrackStat(
            identityKey = stat.identityKey,
            name = stat.name,
            artist = stat.artist,
            album = stat.album,
            totalListenMs = stat.totalListenMs,
            playCount = stat.playCount,
            lastPlayedAt = stat.lastPlayedAt,
            firstPlayedAt = stat.firstPlayedAt,
            coverUrl = sanitizeCoverUrlForSync(stat.coverUrl),
            durationMs = stat.durationMs,
            mediaUri = stat.mediaUri?.takeUnless(::isLocalMediaUri),
            id = stat.id,
            albumId = stat.albumId,
            counterBaseListenMs = counterBaseListenMs(stat.totalListenMs, normalizedShards),
            counterBasePlayCount = counterBasePlayCount(stat.playCount, normalizedShards),
            counterShards = normalizedShards
        )
    }

    fun fromPlaybackStatBucket(
        bucket: PlaybackStatBucket,
        counterShards: List<SyncPlaybackCounterShard> = emptyList()
    ): SyncPlaybackStatBucket {
        val normalizedShards = normalizeCounterShards(counterShards)
        return SyncPlaybackStatBucket(
            dayStartAt = bucket.dayStartAt,
            identityKey = bucket.identityKey,
            name = bucket.name,
            artist = bucket.artist,
            album = bucket.album,
            totalListenMs = bucket.totalListenMs,
            playCount = bucket.playCount,
            lastPlayedAt = bucket.lastPlayedAt,
            firstPlayedAt = bucket.firstPlayedAt,
            coverUrl = sanitizeCoverUrlForSync(bucket.coverUrl),
            durationMs = bucket.durationMs,
            mediaUri = bucket.mediaUri?.takeUnless(::isLocalMediaUri),
            id = bucket.id,
            albumId = bucket.albumId,
            counterBaseListenMs = counterBaseListenMs(bucket.totalListenMs, normalizedShards),
            counterBasePlayCount = counterBasePlayCount(bucket.playCount, normalizedShards),
            counterShards = normalizedShards
        )
    }

    fun sanitize(stat: SyncTrackStat, host: SyncSanitizationHost): SyncTrackStat? {
        if (stat.identityKey.isBlank()) return null
        if (host.isLocalSong(stat.album, stat.mediaUri, stat.albumId)) {
            return null
        }
        val lastPlayedAt = stat.lastPlayedAt.coerceAtLeast(0L)
        val firstPlayedAt = stat.firstPlayedAt.coerceAtLeast(0L).let {
            if (lastPlayedAt > 0L && (it == 0L || it > lastPlayedAt)) lastPlayedAt else it
        }
        return stat.copy(
            totalListenMs = stat.totalListenMs.coerceAtLeast(0L),
            playCount = stat.playCount.coerceAtLeast(0),
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt,
            durationMs = stat.durationMs.coerceAtLeast(0L),
            coverUrl = sanitizeCoverUrlForSync(stat.coverUrl),
            mediaUri = stat.mediaUri?.takeUnless(::isLocalMediaUri),
            counterBaseListenMs = stat.counterBaseListenMs.coerceAtLeast(0L),
            counterBasePlayCount = stat.counterBasePlayCount.coerceAtLeast(0),
            counterShards = normalizeCounterShards(stat.counterShards)
        )
    }

    fun sanitize(bucket: SyncPlaybackStatBucket, host: SyncSanitizationHost): SyncPlaybackStatBucket? {
        if (bucket.identityKey.isBlank()) return null
        if (host.isLocalSong(bucket.album, bucket.mediaUri, bucket.albumId)) {
            return null
        }
        val lastPlayedAt = bucket.lastPlayedAt.coerceAtLeast(0L)
        val firstPlayedAt = bucket.firstPlayedAt.coerceAtLeast(0L).let {
            if (lastPlayedAt > 0L && (it == 0L || it > lastPlayedAt)) lastPlayedAt else it
        }
        return bucket.copy(
            dayStartAt = bucket.dayStartAt.coerceAtLeast(0L),
            totalListenMs = bucket.totalListenMs.coerceAtLeast(0L),
            playCount = bucket.playCount.coerceAtLeast(0),
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt,
            durationMs = bucket.durationMs.coerceAtLeast(0L),
            coverUrl = sanitizeCoverUrlForSync(bucket.coverUrl),
            mediaUri = bucket.mediaUri?.takeUnless(::isLocalMediaUri),
            counterBaseListenMs = bucket.counterBaseListenMs.coerceAtLeast(0L),
            counterBasePlayCount = bucket.counterBasePlayCount.coerceAtLeast(0),
            counterShards = normalizeCounterShards(bucket.counterShards)
        )
    }

    fun sameMetadata(a: SyncTrackStat, b: SyncTrackStat): Boolean =
        sameTrackIdentity(a, b) &&
            sameTrackDescription(a, b) &&
            sameTrackCounters(a, b) &&
            normalizeCounterShards(a.counterShards) == normalizeCounterShards(b.counterShards)

    private fun sameTrackIdentity(a: SyncTrackStat, b: SyncTrackStat): Boolean =
        a.identityKey == b.identityKey && a.id == b.id && a.albumId == b.albumId &&
            a.mediaUri == b.mediaUri

    private fun sameTrackDescription(a: SyncTrackStat, b: SyncTrackStat): Boolean =
        a.name == b.name && a.artist == b.artist && a.album == b.album &&
            a.coverUrl == b.coverUrl && a.durationMs == b.durationMs

    private fun sameTrackCounters(a: SyncTrackStat, b: SyncTrackStat): Boolean =
        a.totalListenMs == b.totalListenMs && a.playCount == b.playCount && a.lastPlayedAt == b.lastPlayedAt &&
            a.firstPlayedAt == b.firstPlayedAt && a.counterBaseListenMs == b.counterBaseListenMs && a.counterBasePlayCount == b.counterBasePlayCount

    fun sameMetadata(a: SyncPlaybackStatBucket, b: SyncPlaybackStatBucket): Boolean =
        a.dayStartAt == b.dayStartAt &&
            sameBucketIdentity(a, b) &&
            sameBucketDescription(a, b) &&
            sameBucketCounters(a, b) &&
            normalizeCounterShards(a.counterShards) == normalizeCounterShards(b.counterShards)

    private fun sameBucketIdentity(a: SyncPlaybackStatBucket, b: SyncPlaybackStatBucket): Boolean =
        a.identityKey == b.identityKey && a.id == b.id && a.albumId == b.albumId &&
            a.mediaUri == b.mediaUri

    private fun sameBucketDescription(a: SyncPlaybackStatBucket, b: SyncPlaybackStatBucket): Boolean =
        a.name == b.name && a.artist == b.artist && a.album == b.album &&
            a.coverUrl == b.coverUrl && a.durationMs == b.durationMs

    private fun sameBucketCounters(a: SyncPlaybackStatBucket, b: SyncPlaybackStatBucket): Boolean =
        a.totalListenMs == b.totalListenMs && a.playCount == b.playCount && a.lastPlayedAt == b.lastPlayedAt &&
            a.firstPlayedAt == b.firstPlayedAt && a.counterBaseListenMs == b.counterBaseListenMs && a.counterBasePlayCount == b.counterBasePlayCount

    private fun counterBaseListenMs(
        totalListenMs: Long,
        counterShards: List<SyncPlaybackCounterShard>
    ): Long {
        val shardTotal = counterShards.fold(0L) { total, shard -> SyncPlaybackCounterArithmetic.add(total, shard.totalListenMs) }
        return (totalListenMs.coerceAtLeast(0L) - shardTotal).coerceAtLeast(0L)
    }

    private fun counterBasePlayCount(
        playCount: Int,
        counterShards: List<SyncPlaybackCounterShard>
    ): Int {
        val shardTotal = counterShards.fold(0) { total, shard -> SyncPlaybackCounterArithmetic.add(total, shard.playCount) }
        return (playCount.coerceAtLeast(0) - shardTotal).coerceAtLeast(0)
    }

    fun normalizeCounterShards(
        shards: List<SyncPlaybackCounterShard?>?
    ): List<SyncPlaybackCounterShard> = SyncCounterShardPolicy.normalizeCounterShards(shards)
}
