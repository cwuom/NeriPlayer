package moe.ouom.neriplayer.data.sync.github

internal object SyncPlaybackStatsMergePolicy {
    fun merge(
        local: List<SyncTrackStat>,
        remote: List<SyncTrackStat>,
        playbackStatsClearedAt: Long
    ): List<SyncTrackStat> {
        val merged = linkedMapOf<String, SyncTrackStat>()
        for (stat in (local + remote).mapNotNull { normalizeAfterClear(it, playbackStatsClearedAt) }) {
            val existing = merged[stat.identityKey]
            merged[stat.identityKey] = if (existing == null) {
                stat
            } else {
                mergeStat(existing, stat)
            }
        }
        return merged.values.toList()
    }

    fun mergeBuckets(
        local: List<SyncPlaybackStatBucket>,
        remote: List<SyncPlaybackStatBucket>,
        playbackStatsClearedAt: Long
    ): List<SyncPlaybackStatBucket> {
        val merged = linkedMapOf<Pair<Long, String>, SyncPlaybackStatBucket>()
        for (bucket in (local + remote).mapNotNull {
            normalizeBucketAfterClear(it, playbackStatsClearedAt)
        }) {
            val key = bucket.dayStartAt to bucket.identityKey
            val existing = merged[key]
            merged[key] = if (existing == null) {
                bucket
            } else {
                mergeBucket(existing, bucket)
            }
        }
        return merged.values.toList()
    }

    fun shouldKeepAfterClear(stat: SyncTrackStat, playbackStatsClearedAt: Long): Boolean {
        if (playbackStatsClearedAt <= 0L) return true
        return stat.lastPlayedAt >= playbackStatsClearedAt
    }

    fun shouldKeepAfterClear(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): Boolean {
        if (playbackStatsClearedAt <= 0L) return true
        return bucket.lastPlayedAt >= playbackStatsClearedAt
    }

    private fun normalizeAfterClear(
        stat: SyncTrackStat,
        playbackStatsClearedAt: Long
    ): SyncTrackStat? {
        if (!shouldKeepAfterClear(stat, playbackStatsClearedAt)) return null
        if (playbackStatsClearedAt <= 0L) return stat

        val normalizedFirstPlayedAt = stat.firstPlayedAt
            .takeIf { it >= playbackStatsClearedAt && it <= stat.lastPlayedAt }
            ?: stat.lastPlayedAt
        return if (normalizedFirstPlayedAt == stat.firstPlayedAt) {
            stat
        } else {
            stat.copy(firstPlayedAt = normalizedFirstPlayedAt)
        }
    }

    private fun normalizeBucketAfterClear(
        bucket: SyncPlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): SyncPlaybackStatBucket? {
        if (!shouldKeepAfterClear(bucket, playbackStatsClearedAt)) return null
        if (playbackStatsClearedAt <= 0L) return bucket

        val normalizedFirstPlayedAt = bucket.firstPlayedAt
            .takeIf { it >= playbackStatsClearedAt && it <= bucket.lastPlayedAt }
            ?: bucket.lastPlayedAt
        return if (normalizedFirstPlayedAt == bucket.firstPlayedAt) {
            bucket
        } else {
            bucket.copy(firstPlayedAt = normalizedFirstPlayedAt)
        }
    }

    private fun mergeStat(existing: SyncTrackStat, stat: SyncTrackStat): SyncTrackStat {
        val newer = if (stat.lastPlayedAt >= existing.lastPlayedAt) stat else existing
        return SyncTrackStat(
            identityKey = stat.identityKey,
            name = newer.name,
            artist = newer.artist,
            album = newer.album,
            totalListenMs = maxOf(stat.totalListenMs, existing.totalListenMs),
            playCount = maxOf(stat.playCount, existing.playCount),
            lastPlayedAt = maxOf(stat.lastPlayedAt, existing.lastPlayedAt),
            firstPlayedAt = minOf(stat.firstPlayedAt, existing.firstPlayedAt),
            coverUrl = newer.coverUrl,
            durationMs = newer.durationMs,
            mediaUri = newer.mediaUri,
            id = newer.id,
            albumId = newer.albumId
        )
    }

    private fun mergeBucket(
        existing: SyncPlaybackStatBucket,
        bucket: SyncPlaybackStatBucket
    ): SyncPlaybackStatBucket {
        val newer = if (bucket.lastPlayedAt >= existing.lastPlayedAt) bucket else existing
        return SyncPlaybackStatBucket(
            dayStartAt = existing.dayStartAt,
            identityKey = existing.identityKey,
            name = newer.name,
            artist = newer.artist,
            album = newer.album,
            totalListenMs = maxOf(bucket.totalListenMs, existing.totalListenMs),
            playCount = maxOf(bucket.playCount, existing.playCount),
            lastPlayedAt = maxOf(bucket.lastPlayedAt, existing.lastPlayedAt),
            firstPlayedAt = minOf(bucket.firstPlayedAt, existing.firstPlayedAt),
            coverUrl = newer.coverUrl,
            durationMs = newer.durationMs,
            mediaUri = newer.mediaUri,
            id = newer.id,
            albumId = newer.albumId
        )
    }
}
