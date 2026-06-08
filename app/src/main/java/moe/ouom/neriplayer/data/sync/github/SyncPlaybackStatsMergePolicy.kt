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

    fun shouldKeepAfterClear(stat: SyncTrackStat, playbackStatsClearedAt: Long): Boolean {
        if (playbackStatsClearedAt <= 0L) return true
        return stat.lastPlayedAt >= playbackStatsClearedAt
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
}
