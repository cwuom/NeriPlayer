package moe.ouom.neriplayer.data.sync.github

import android.content.Context
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.stats.TrackStat
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.model.SyncTrackStat

internal object SyncPlaybackStatMapper {
    fun shouldSync(stat: TrackStat, context: Context): Boolean {
        return stat.localFilePath.isNullOrBlank() &&
            !LocalSongSupport.isLocalSong(stat.album, stat.mediaUri, stat.albumId, context)
    }

    fun shouldSync(bucket: PlaybackStatBucket, context: Context): Boolean {
        return bucket.localFilePath.isNullOrBlank() &&
            !LocalSongSupport.isLocalSong(bucket.album, bucket.mediaUri, bucket.albumId, context)
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
            coverUrl = stat.coverUrl,
            durationMs = stat.durationMs,
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(stat.mediaUri),
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
            coverUrl = bucket.coverUrl,
            durationMs = bucket.durationMs,
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(bucket.mediaUri),
            id = bucket.id,
            albumId = bucket.albumId,
            counterBaseListenMs = counterBaseListenMs(bucket.totalListenMs, normalizedShards),
            counterBasePlayCount = counterBasePlayCount(bucket.playCount, normalizedShards),
            counterShards = normalizedShards
        )
    }

    fun sanitize(stat: SyncTrackStat, context: Context): SyncTrackStat? {
        if (stat.identityKey.isBlank()) return null
        if (LocalSongSupport.isLocalSong(stat.album, stat.mediaUri, stat.albumId, context)) {
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
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(stat.mediaUri),
            counterBaseListenMs = stat.counterBaseListenMs.coerceAtLeast(0L),
            counterBasePlayCount = stat.counterBasePlayCount.coerceAtLeast(0),
            counterShards = normalizeCounterShards(stat.counterShards)
        )
    }

    fun sanitize(bucket: SyncPlaybackStatBucket, context: Context): SyncPlaybackStatBucket? {
        if (bucket.identityKey.isBlank()) return null
        if (LocalSongSupport.isLocalSong(bucket.album, bucket.mediaUri, bucket.albumId, context)) {
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
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(bucket.mediaUri),
            counterBaseListenMs = bucket.counterBaseListenMs.coerceAtLeast(0L),
            counterBasePlayCount = bucket.counterBasePlayCount.coerceAtLeast(0),
            counterShards = normalizeCounterShards(bucket.counterShards)
        )
    }

    fun sameMetadata(a: SyncTrackStat, b: SyncTrackStat): Boolean {
        return a.identityKey == b.identityKey &&
            a.name == b.name &&
            a.artist == b.artist &&
            a.album == b.album &&
            a.totalListenMs == b.totalListenMs &&
            a.playCount == b.playCount &&
            a.lastPlayedAt == b.lastPlayedAt &&
            a.firstPlayedAt == b.firstPlayedAt &&
            a.coverUrl == b.coverUrl &&
            a.durationMs == b.durationMs &&
            a.mediaUri == b.mediaUri &&
            a.id == b.id &&
            a.albumId == b.albumId &&
            a.counterBaseListenMs == b.counterBaseListenMs &&
            a.counterBasePlayCount == b.counterBasePlayCount &&
            normalizeCounterShards(a.counterShards) == normalizeCounterShards(b.counterShards)
    }

    fun sameMetadata(a: SyncPlaybackStatBucket, b: SyncPlaybackStatBucket): Boolean {
        return a.dayStartAt == b.dayStartAt &&
            a.identityKey == b.identityKey &&
            a.name == b.name &&
            a.artist == b.artist &&
            a.album == b.album &&
            a.totalListenMs == b.totalListenMs &&
            a.playCount == b.playCount &&
            a.lastPlayedAt == b.lastPlayedAt &&
            a.firstPlayedAt == b.firstPlayedAt &&
            a.coverUrl == b.coverUrl &&
            a.durationMs == b.durationMs &&
            a.mediaUri == b.mediaUri &&
            a.id == b.id &&
            a.albumId == b.albumId &&
            a.counterBaseListenMs == b.counterBaseListenMs &&
            a.counterBasePlayCount == b.counterBasePlayCount &&
            normalizeCounterShards(a.counterShards) == normalizeCounterShards(b.counterShards)
    }

    private fun counterBaseListenMs(
        totalListenMs: Long,
        counterShards: List<SyncPlaybackCounterShard>
    ): Long {
        val shardTotal = counterShards.sumOf { it.totalListenMs.coerceAtLeast(0L) }
        return (totalListenMs.coerceAtLeast(0L) - shardTotal).coerceAtLeast(0L)
    }

    private fun counterBasePlayCount(
        playCount: Int,
        counterShards: List<SyncPlaybackCounterShard>
    ): Int {
        val shardTotal = counterShards.sumOf { it.playCount.coerceAtLeast(0) }
        return (playCount.coerceAtLeast(0) - shardTotal).coerceAtLeast(0)
    }

    internal fun normalizeCounterShards(
        shards: List<SyncPlaybackCounterShard?>?
    ): List<SyncPlaybackCounterShard> {
        return shards.orEmpty()
            .asSequence()
            .filterNotNull()
            .filter { it.deviceId.isNotBlank() }
            .map { shard ->
                val lastPlayedAt = shard.lastPlayedAt.coerceAtLeast(0L)
                val firstPlayedAt = shard.firstPlayedAt.coerceAtLeast(0L).let {
                    if (lastPlayedAt > 0L && (it == 0L || it > lastPlayedAt)) lastPlayedAt else it
                }
                shard.copy(
                    epochStartedAt = shard.epochStartedAt.coerceAtLeast(0L),
                    totalListenMs = shard.totalListenMs.coerceAtLeast(0L),
                    playCount = shard.playCount.coerceAtLeast(0),
                    firstPlayedAt = firstPlayedAt,
                    lastPlayedAt = lastPlayedAt
                )
            }
            .groupBy { it.deviceId to it.epochStartedAt }
            .map { (_, snapshots) ->
                snapshots.reduce(::mergeCounterShard)
            }
            .sortedWith(compareBy<SyncPlaybackCounterShard> { it.deviceId }.thenBy { it.epochStartedAt })
    }

    private fun mergeCounterShard(
        left: SyncPlaybackCounterShard,
        right: SyncPlaybackCounterShard
    ): SyncPlaybackCounterShard {
        return left.copy(
            totalListenMs = maxOf(left.totalListenMs, right.totalListenMs),
            playCount = maxOf(left.playCount, right.playCount),
            firstPlayedAt = minPositivePlayedAt(left.firstPlayedAt, right.firstPlayedAt),
            lastPlayedAt = maxOf(left.lastPlayedAt, right.lastPlayedAt)
        )
    }

    private fun minPositivePlayedAt(left: Long, right: Long): Long {
        return when {
            left <= 0L -> right
            right <= 0L -> left
            else -> minOf(left, right)
        }
    }
}
