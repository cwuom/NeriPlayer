package moe.ouom.neriplayer.data.local.database.store.stats

import androidx.sqlite.db.SimpleSQLiteQuery
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.resolvePlaybackStatsTimeRange
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort

internal class PlaybackStatsSqlQuery(private val query: PlaybackStatsQuery, hasBuckets: Boolean, hasLegacyStats: Boolean = !hasBuckets) {
    val usesLegacyBreakdown = query.period != PlaybackStatsPeriod.ALL && hasLegacyStats
    private val arguments = mutableListOf<Any>()
    private val source: String = when {
        query.period == PlaybackStatsPeriod.ALL -> "SELECT * FROM playback_stat"
        hasBuckets -> bucketSource() + " UNION ALL " + legacySource(onlyWithoutBuckets = true)
        else -> legacySource()
    }
    private val sortColumn = when (query.sort) {
        PlaybackStatsSort.PLAY_COUNT -> "play_count"
        PlaybackStatsSort.LISTEN_TIME -> "total_listen_ms"
        PlaybackStatsSort.RECENT -> "last_played_at"
        PlaybackStatsSort.FIRST_PLAYED -> "first_played_at"
    }
    private val ascending = query.sort == PlaybackStatsSort.FIRST_PLAYED
    private val hotOrder = query.requirePlayCount && query.sort == PlaybackStatsSort.PLAY_COUNT
    private val filter = "total_listen_ms >= ?" + if (query.requirePlayCount) " AND play_count > 0" else ""

    fun summary(): SimpleSQLiteQuery = SimpleSQLiteQuery(
        "SELECT trackCount, totalPlayCount, ${saturatedSum()} AS totalListenMs FROM " +
            "(SELECT COUNT(*) AS trackCount, COALESCE(SUM(play_count), 0) AS totalPlayCount, " +
            "COALESCE(SUM(MAX(total_listen_ms, 0) >> 32), 0) AS hi, " +
            "COALESCE(SUM(MAX(total_listen_ms, 0) & 4294967295), 0) AS lo FROM ($source) WHERE $filter)",
        (arguments + query.minimumListenMs).toTypedArray()
    )

    fun page(after: PlaybackStatsCursor?, limit: Int, before: Boolean = false): SimpleSQLiteQuery {
        val params = (arguments + query.minimumListenMs).toMutableList()
        val cursor = if (after == null) "" else if (hotOrder) {
            params.addAll(listOf(after.sortValue, after.sortValue, after.secondaryValue, after.sortValue,
                after.secondaryValue, after.tertiaryValue, after.sortValue, after.secondaryValue, after.tertiaryValue, after.identityKey))
            val comparison = if (before) ">" else "<"
            val identityComparison = if (before) "<" else ">"
            " AND (play_count $comparison ? OR (play_count = ? AND total_listen_ms $comparison ?) OR " +
                "(play_count = ? AND total_listen_ms = ? AND last_played_at $comparison ?) OR " +
                "(play_count = ? AND total_listen_ms = ? AND last_played_at = ? AND identity_key $identityComparison ?))"
        } else {
            params.addAll(listOf(after.sortValue, after.sortValue, after.identityKey))
            " AND ($sortColumn ${if (ascending != before) ">" else "<"} ? OR ($sortColumn = ? AND identity_key ${if (before) "<" else ">"} ?))"
        }
        params.add(limit)
        val direction = if (ascending != before) "ASC" else "DESC"
        val identityDirection = if (before) "DESC" else "ASC"
        val order = if (hotOrder) {
            val hotDirection = if (before) "ASC" else "DESC"
            "play_count $hotDirection, total_listen_ms $hotDirection, last_played_at $hotDirection, identity_key $identityDirection"
        } else "$sortColumn $direction, identity_key $identityDirection"
        return SimpleSQLiteQuery("SELECT * FROM ($source) WHERE $filter$cursor ORDER BY $order LIMIT ?", params.toTypedArray())
    }

    fun cursor(stat: TrackStat): PlaybackStatsCursor = PlaybackStatsCursor(
        when (query.sort) {
            PlaybackStatsSort.PLAY_COUNT -> stat.playCount.toLong()
            PlaybackStatsSort.LISTEN_TIME -> stat.totalListenMs
            PlaybackStatsSort.RECENT -> stat.lastPlayedAt
            PlaybackStatsSort.FIRST_PLAYED -> stat.firstPlayedAt
        }, stat.identityKey, stat.totalListenMs, stat.lastPlayedAt
    )

    private fun legacySource(onlyWithoutBuckets: Boolean = false): String {
        val range = query.period.resolvePlaybackStatsTimeRange(query.nowMillis)
        arguments.add(checkNotNull(range.startInclusive))
        arguments.add(range.endExclusive)
        val withoutBuckets = if (onlyWithoutBuckets) " AND NOT EXISTS (SELECT 1 FROM playback_stat_bucket b WHERE b.identity_key = playback_stat.identity_key)" else ""
        return "SELECT * FROM playback_stat WHERE (CASE WHEN first_played_at > 0 THEN first_played_at ELSE last_played_at END) >= ? AND last_played_at < ?$withoutBuckets"
    }

    // 两个整数 limb 分开聚合，在千万行和 Long 上限输入下仍保持精度
    private fun saturatedSum(): String = "CASE WHEN hi + (lo >> 32) > 2147483647 THEN 9223372036854775807 ELSE ((hi + (lo >> 32)) << 32) + (lo & 4294967295) END"

    private fun bucketSource(): String {
        val range = query.period.resolvePlaybackStatsTimeRange(query.nowMillis)
        val start = checkNotNull(range.startInclusive)
        repeat(3) { arguments.add(start); arguments.add(range.endExclusive) }
        return "WITH bucket_sums AS (SELECT identity_key, SUM(MAX(total_listen_ms, 0) >> 32) AS hi, " +
            "SUM(MAX(total_listen_ms, 0) & 4294967295) AS lo, MIN(SUM(play_count), 2147483647) AS play_count, " +
            "MIN(CASE WHEN first_played_at > 0 THEN first_played_at END) AS first_played_at, " +
            "MAX(last_played_at) AS last_played_at FROM playback_stat_bucket WHERE day_start_at >= ? AND day_start_at < ? GROUP BY identity_key), " +
            "totals AS (SELECT identity_key, ${saturatedSum()} AS total_listen_ms, play_count, first_played_at, last_played_at FROM bucket_sums) " +
            "SELECT b.identity_key, b.id, b.name, b.artist, b.album, b.album_id, b.cover_url, b.duration_ms, " +
            "t.total_listen_ms, t.play_count, t.last_played_at, COALESCE(t.first_played_at, 0) AS first_played_at, " +
            "b.media_uri, b.local_file_path, b.local_file_name, b.custom_name, b.custom_artist, b.custom_cover_url " +
            "FROM totals t JOIN playback_stat_bucket b ON b.identity_key = t.identity_key " +
            "WHERE b.day_start_at >= ? AND b.day_start_at < ? AND NOT EXISTS " +
            "(SELECT 1 FROM playback_stat_bucket newer WHERE newer.identity_key = b.identity_key " +
            "AND newer.day_start_at >= ? AND newer.day_start_at < ? AND " +
            "(newer.last_played_at > b.last_played_at OR (newer.last_played_at = b.last_played_at AND newer.day_start_at > b.day_start_at)))"
    }
}
