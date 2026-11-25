package moe.ouom.neriplayer.data.local.database.store.stats

import java.time.Instant
import java.time.ZoneId
import androidx.sqlite.db.SimpleSQLiteQuery
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsTimeRange
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.local.database.entity.stats.MAX_PLAYBACK_LISTENING_INTERVAL_MS

internal class PlaybackStatsSqlQuery(private val query: PlaybackStatsQuery, hasBuckets: Boolean, hasLegacyStats: Boolean = !hasBuckets) {
    val range = query.resolvedRange
    val hourDates by lazy(LazyThreadSafetyMode.NONE) { range.completeCivilDates() }
    private val allTime = range.startInclusive == null && range.endExclusive == Long.MAX_VALUE
    val usesLegacyBreakdown = !allTime && hasLegacyStats
    private val arguments = mutableListOf<Any>()
    private val source: String = sourceSql(hasBuckets)
    private val sortColumn = query.sort.sqlColumn()
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

    fun hourlyListening(): SimpleSQLiteQuery {
        val start = range.startInclusive ?: Long.MIN_VALUE
        val end = range.endExclusive
        val dates = hourDates
        val scan = if (dates == null) (start.coerceAtLeast(0L) - MAX_PLAYBACK_LISTENING_INTERVAL_MS) to end
            else candidateDayBounds(start, end)
        val params = mutableListOf<Any>(start, end, scan.first, scan.second)
        val dateBounds = if (dates == null) "" else {
            params.add(dates.first)
            params.add(dates.second)
            ", ? AS first_day, ? AS until_day"
        }
        val tracks = if (query.minimumListenMs > 0 || query.requirePlayCount) {
            params.addAll(filteredArguments())
            " AND identity_key IN (SELECT identity_key FROM ($source) WHERE $filter)"
        } else ""
        fun selectRange(absolute: String): String = if (dates == null) absolute else
            "((recorded_local_date >= first_day AND recorded_local_date < until_day) OR (recorded_local_date IS NULL AND $absolute))"
        val clipped = "MIN(end_at, until_ms) - MAX(start_at, from_ms)"
        val duration = if (dates == null) clipped else
            "CASE WHEN recorded_local_date IS NULL THEN $clipped ELSE end_at - start_at END"
        val sql = """
            WITH bounds AS (SELECT ? AS from_ms, ? AS until_ms, ? AS scan_from, ? AS scan_until$dateBounds),
            slices AS (
                SELECT identity_key, local_hour, listened_ms FROM bounds CROSS JOIN playback_stat_hour
                WHERE hour_start_at >= scan_from AND hour_start_at < scan_until
                    AND ${selectRange("hour_start_at >= from_ms AND hour_start_at < until_ms")}
                UNION ALL
                SELECT identity_key, local_hour, $duration AS listened_ms FROM bounds CROSS JOIN playback_listening_interval
                WHERE start_at >= scan_from AND start_at < scan_until
                    AND ${selectRange("start_at < until_ms AND end_at > from_ms")}
            ),
            hours AS (
                SELECT local_hour, SUM(listened_ms >> 32) AS hi, SUM(listened_ms & 4294967295) AS lo
                FROM slices WHERE listened_ms > 0$tracks GROUP BY local_hour
            )
            SELECT local_hour AS localHour, ${saturatedSum()} AS listenedMs FROM hours
        """.trimIndent()
        return SimpleSQLiteQuery(sql, params.toTypedArray())
    }

    fun filteredTracksSql(): String = "SELECT * FROM ($source) WHERE $filter"

    fun syncedHourBuckets(): SimpleSQLiteQuery {
        val bounds = candidateDayBounds(range.startInclusive ?: Long.MIN_VALUE, range.endExclusive)
        val params = mutableListOf<Any>(bounds.first, bounds.second)
        val tracks = if (query.minimumListenMs > 0 || query.requirePlayCount) {
            params.addAll(filteredArguments())
            " AND identity_key IN (SELECT identity_key FROM ($source) WHERE $filter)"
        } else ""
        return SimpleSQLiteQuery(
            "SELECT hourly_totals, local_hourly_totals, day_start_at, identity_key, recorded_local_date " +
                "FROM playback_stat_bucket WHERE day_start_at >= ? AND day_start_at < ? AND hourly_totals != '[]' AND hourly_totals != local_hourly_totals$tracks",
            params.toTypedArray()
        )
    }

    fun differingHourShardDates(): SimpleSQLiteQuery {
        val bounds = candidateDayBounds(range.startInclusive ?: Long.MIN_VALUE, range.endExclusive)
        return SimpleSQLiteQuery("""
            SELECT c.day_start_at, c.identity_key, c.device_id, c.epoch_started_at, c.recorded_local_date
            FROM playback_stat_daily_counter_shard c JOIN playback_stat_bucket b USING (day_start_at, identity_key)
            WHERE c.day_start_at >= ? AND c.day_start_at < ?
                AND c.recorded_local_date IS NOT b.recorded_local_date AND b.hourly_totals != b.local_hourly_totals
        """.trimIndent(), arrayOf(bounds.first, bounds.second))
    }

    fun filteredArguments(): List<Any> = arguments + query.minimumListenMs

    fun page(after: PlaybackStatsCursor?, limit: Int, before: Boolean = false): SimpleSQLiteQuery {
        val params = (arguments + query.minimumListenMs).toMutableList()
        val cursor = if (after == null) "" else if (hotOrder) hotCursorClause(after, before, params) else sortedCursorClause(after, before, params)
        params.add(limit)
        return SimpleSQLiteQuery("SELECT * FROM ($source) WHERE $filter$cursor ORDER BY ${orderClause(before)} LIMIT ?", params.toTypedArray())
    }

    private fun hotCursorClause(after: PlaybackStatsCursor, before: Boolean, params: MutableList<Any>): String {
        params.addAll(listOf(after.sortValue, after.sortValue, after.secondaryValue, after.sortValue,
            after.secondaryValue, after.tertiaryValue, after.sortValue, after.secondaryValue, after.tertiaryValue, after.identityKey))
        val comparison = if (before) ">" else "<"
        val identityComparison = if (before) "<" else ">"
        return " AND (play_count $comparison ? OR (play_count = ? AND total_listen_ms $comparison ?) OR " +
            "(play_count = ? AND total_listen_ms = ? AND last_played_at $comparison ?) OR " +
            "(play_count = ? AND total_listen_ms = ? AND last_played_at = ? AND identity_key $identityComparison ?))"
    }

    private fun sortedCursorClause(after: PlaybackStatsCursor, before: Boolean, params: MutableList<Any>): String {
        // Row values need one direction for every key; only FIRST_PLAYED sorts identity_key the same way.
        if (ascending) {
            params.addAll(listOf(after.sortValue, after.identityKey))
            return " AND ($sortColumn, identity_key) ${if (before) "<" else ">"} (?, ?)"
        }
        params.addAll(listOf(after.sortValue, after.sortValue, after.identityKey))
        return " AND ($sortColumn ${if (ascending != before) ">" else "<"} ? OR ($sortColumn = ? AND identity_key ${if (before) "<" else ">"} ?))"
    }

    private fun orderClause(before: Boolean): String {
        val identityDirection = if (before) "DESC" else "ASC"
        if (!hotOrder) return "$sortColumn ${if (ascending != before) "ASC" else "DESC"}, identity_key $identityDirection"
        val hotDirection = if (before) "ASC" else "DESC"
        return "play_count $hotDirection, total_listen_ms $hotDirection, last_played_at $hotDirection, identity_key $identityDirection"
    }

    fun cursor(stat: TrackStat): PlaybackStatsCursor = PlaybackStatsCursor(
        when (query.sort) {
            PlaybackStatsSort.PLAY_COUNT -> stat.playCount.toLong()
            PlaybackStatsSort.LISTEN_TIME -> stat.totalListenMs
            PlaybackStatsSort.RECENT -> stat.lastPlayedAt
            PlaybackStatsSort.FIRST_PLAYED -> stat.firstPlayedAt
        }, stat.identityKey, stat.totalListenMs, stat.lastPlayedAt
    )

    private fun sourceSql(hasBuckets: Boolean): String = when {
        allTime -> "SELECT * FROM playback_stat"
        hasBuckets -> bucketSource() + " UNION ALL " + legacySource(onlyWithoutBuckets = true)
        else -> legacySource()
    }

    private fun legacySource(onlyWithoutBuckets: Boolean = false): String {
        arguments.add(range.startInclusive ?: Long.MIN_VALUE)
        arguments.add(range.endExclusive)
        val withoutBuckets = if (onlyWithoutBuckets) " AND NOT EXISTS (SELECT 1 FROM playback_stat_bucket b WHERE b.identity_key = playback_stat.identity_key)" else ""
        return "SELECT * FROM playback_stat WHERE (CASE WHEN first_played_at > 0 THEN first_played_at ELSE last_played_at END) >= ? AND last_played_at < ?$withoutBuckets"
    }

    private fun bucketSource(): String {
        arguments.addAll(dayBucketBounds(range.startInclusive ?: Long.MIN_VALUE, range.endExclusive))
        return """
            WITH ${dayBucketEventsSql()},
            bucket_sums AS (
                SELECT identity_key, SUM(MAX(total_listen_ms, 0) >> 32) AS hi,
                    SUM(MAX(total_listen_ms, 0) & 4294967295) AS lo, MIN(SUM(play_count), 2147483647) AS play_count,
                    MIN(CASE WHEN first_played_at > 0 THEN first_played_at END) AS first_played_at,
                    MAX(last_played_at) AS last_played_at FROM day_events GROUP BY identity_key
            ),
            totals AS (
                SELECT identity_key, ${saturatedSum()} AS total_listen_ms, play_count, first_played_at, last_played_at FROM bucket_sums
            ),
            latest AS (
                SELECT e.identity_key, MAX(e.day_start_at) AS day_start_at FROM day_events e
                JOIN totals t ON e.identity_key = t.identity_key AND e.last_played_at = t.last_played_at GROUP BY e.identity_key
            )
            SELECT b.identity_key, b.id, b.name, b.artist, b.album, b.album_id, b.cover_url, b.duration_ms,
                t.total_listen_ms, t.play_count, t.last_played_at, COALESCE(t.first_played_at, 0) AS first_played_at,
                b.media_uri, b.local_file_path, b.local_file_name, b.custom_name, b.custom_artist, b.custom_cover_url
            FROM latest JOIN playback_stat_bucket b USING (day_start_at, identity_key) JOIN totals t USING (identity_key)
        """.trimIndent()
    }
}

private fun PlaybackStatsSort.sqlColumn(): String = when (this) {
    PlaybackStatsSort.PLAY_COUNT -> "play_count"
    PlaybackStatsSort.LISTEN_TIME -> "total_listen_ms"
    PlaybackStatsSort.RECENT -> "last_played_at"
    PlaybackStatsSort.FIRST_PLAYED -> "first_played_at"
}

// 两端设备的当地午夜最多相差一天多，先用时间戳索引限流，再筛选录制日期
private const val DATE_LINE_MARGIN_MS = 2 * 24 * 60 * 60 * 1000L

private fun candidateDayBounds(start: Long, end: Long): Pair<Long, Long> =
    (start.coerceAtLeast(Long.MIN_VALUE + DATE_LINE_MARGIN_MS) - DATE_LINE_MARGIN_MS) to
        (end.coerceAtMost(Long.MAX_VALUE - DATE_LINE_MARGIN_MS) + DATE_LINE_MARGIN_MS)

internal fun dayBucketBounds(start: Long, end: Long): List<Any> {
    val zone = ZoneId.systemDefault()
    val bounds = candidateDayBounds(start, end)
    return listOf(bounds.first, bounds.second, start, end, civilMidnightBound(start, zone), civilMidnightBound(end, zone))
}

private fun PlaybackStatsTimeRange.completeCivilDates(): Pair<String, String>? {
    val start = startInclusive ?: return null
    if (start >= endExclusive) return null
    val zone = ZoneId.systemDefault()
    fun midnightDate(millis: Long): String? {
        val instant = Instant.ofEpochMilli(millis)
        val day = instant.atZone(zone).toLocalDate()
        return day.toString().takeIf { day.atStartOfDay(zone).toInstant() == instant }
    }
    return (midnightDate(start) ?: return null) to (midnightDate(endExclusive) ?: return null)
}

private fun civilMidnightBound(millis: Long, zone: ZoneId): String {
    val instant = Instant.ofEpochMilli(millis)
    val day = instant.atZone(zone).toLocalDate()
    val firstMidnight = if (day.atStartOfDay(zone).toInstant() < instant) day.plusDays(1) else day
    return when {
        firstMidnight.year < 0 -> "0000-01-01"
        firstMidnight.year > 9999 -> "9999-12-32"
        else -> firstMidnight.toString()
    }
}

internal fun dayBucketEventsSql() = """
    candidates AS (
        SELECT day_start_at, identity_key, recorded_local_date, total_listen_ms, play_count, first_played_at, last_played_at
        FROM playback_stat_bucket WHERE day_start_at >= ? AND day_start_at < ?
    ),
    split_buckets AS (
        SELECT b.day_start_at, b.identity_key FROM candidates b WHERE EXISTS (
            SELECT 1 FROM playback_stat_daily_counter_shard c WHERE c.day_start_at = b.day_start_at AND c.identity_key = b.identity_key
                AND c.recorded_local_date IS NOT b.recorded_local_date
        )
    ),
    shard_events AS (
        SELECT c.day_start_at, c.identity_key, c.recorded_local_date, c.total_listen_ms, c.play_count, c.first_played_at, c.last_played_at
        FROM split_buckets JOIN playback_stat_daily_counter_shard c USING (day_start_at, identity_key)
    ),
    moved_sums AS (
        SELECT day_start_at, identity_key, SUM(total_listen_ms >> 32) AS hi, SUM(total_listen_ms & 4294967295) AS lo,
            SUM(play_count) AS play_count FROM shard_events GROUP BY day_start_at, identity_key
    ),
    moved AS (
        SELECT day_start_at, identity_key, ${saturatedSum()} AS total_listen_ms, play_count FROM moved_sums
    ),
    contributions AS (
        SELECT b.day_start_at, b.identity_key, b.recorded_local_date, MAX(0, b.total_listen_ms - COALESCE(m.total_listen_ms, 0)) AS total_listen_ms,
            MAX(0, b.play_count - COALESCE(m.play_count, 0)) AS play_count, b.first_played_at, b.last_played_at
        FROM candidates b LEFT JOIN moved m USING (day_start_at, identity_key)
        WHERE m.identity_key IS NULL OR b.total_listen_ms > m.total_listen_ms OR b.play_count > m.play_count
        UNION ALL SELECT * FROM shard_events
    ),
    day_events AS (
        SELECT *, COALESCE(recorded_local_date, date(day_start_at / 1000, 'unixepoch', 'localtime')) AS day FROM contributions
        WHERE (recorded_local_date IS NULL AND day_start_at >= ? AND day_start_at < ?)
            OR (recorded_local_date >= ? AND recorded_local_date < ?)
    )
""".trimIndent()

// 两个整数 limb 分开聚合，在千万行和 Long 上限输入下仍保持精度
internal fun saturatedSum(): String = "CASE WHEN hi + (lo >> 32) > 2147483647 THEN 9223372036854775807 ELSE ((hi + (lo >> 32)) << 32) + (lo & 4294967295) END"
