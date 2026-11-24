package moe.ouom.neriplayer.data.local.database.store.stats

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsRankRow
import moe.ouom.neriplayer.data.stats.PlaybackStatsInsights
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRank
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary

internal suspend fun PlaybackStatsRoomStore.readInsights(query: PlaybackStatsQuery, rankLimit: Int): PlaybackStatsInsights {
    require(rankLimit in 1..256)
    return database.withTransaction {
        val dao = database.playbackStatsDao()
        val hasStats = dao.hasStats()
        if (!hasStats) return@withTransaction PlaybackStatsInsights()
        val sql = PlaybackStatsSqlQuery(query.copy(sort = PlaybackStatsSort.LISTEN_TIME), dao.hasBuckets(), dao.hasLegacyStats())
        val totals = dao.querySummary(sql.summary())
        val summary = PlaybackStatsSummary(totals.trackCount, totals.totalPlayCount, totals.totalListenMs, sql.usesLegacyBreakdown, true)
        val filtered = sql.filteredTracksSql()
        val args = sql.filteredArguments()
        val window = query.resolvedRange
        val bounds = dayBucketBounds(window.startInclusive ?: Long.MIN_VALUE, window.endExclusive)
        val artists = dao.queryRanking(insightRankingQuery(filtered, args, artist = true, limit = rankLimit))
        val albums = dao.queryRanking(insightRankingQuery(filtered, args, artist = false, limit = rankLimit))
        val weekdays = dao.queryWeekdays(weekdayQuery(filtered, args, bounds))
        val daySlots = MutableList(7) { 0L }
        weekdays.forEach { daySlots[it.weekday] = it.totalListenMs }
        val recordedMs = weekdays.fold(0L) { sum, row -> addSaturated(sum, row.totalListenMs) }
        val activeDays = weekdays.sumOf { it.activeDays }
        val earliest = dao.queryFirstListenAt(SimpleSQLiteQuery(
            "SELECT MIN(first_played_at) FROM ($filtered) WHERE first_played_at > 0", args.toTypedArray()
        ))
        PlaybackStatsInsights(
            summary = summary,
            topTrack = dao.queryStats(sql.page(null, 1)).firstOrNull()?.takeIf { it.totalListenMs > 0 }?.toDomain(),
            earliestListenAt = earliest,
            artists = artists.map { it.toRank(totals.totalListenMs) },
            albums = albums.map { it.toRank(totals.totalListenMs) },
            artistCount = artists.firstOrNull()?.groupCount ?: 0,
            albumCount = albums.firstOrNull()?.groupCount ?: 0,
            weekdayListenMs = daySlots,
            activeDays = activeDays,
            averageDailyListenMs = if (activeDays > 0) recordedMs / activeDays else 0,
            averageListenMsPerPlay = if (totals.totalPlayCount > 0) totals.totalListenMs / totals.totalPlayCount else 0
        )
    }
}

private fun addSaturated(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

private fun PlaybackStatsRankRow.toRank(totalMs: Long) = PlaybackStatsRank(
    name = name, trackCount = trackCount, totalListenMs = totalListenMs, totalPlayCount = totalPlayCount,
    share = if (totalMs > 0) (totalListenMs.toDouble() / totalMs).coerceIn(0.0, 1.0) else 0.0, coverUrl = coverUrl
)

// only the canonical spaced separator denotes separate credits, so names such as AC/DC remain intact
private const val ARTIST_MEMBERS_SQL = """
    credits(identity_key, name, rest) AS (
        SELECT identity_key, '', trim(COALESCE(NULLIF(trim(custom_artist), ''), artist, '')) || ' / ' FROM selected
        UNION ALL
        SELECT identity_key, trim(substr(rest, 1, instr(rest, ' / ') - 1)), substr(rest, instr(rest, ' / ') + 3)
        FROM credits WHERE rest <> ''
    ),
    members AS (
        SELECT DISTINCT identity_key, name COLLATE NOCASE AS name, lower(name) AS groupKey
        FROM credits WHERE lower(name) NOT IN ('', 'unknown', 'unknown artist', '未知艺术家', '未知歌手')
    )
"""

private const val ALBUM_MEMBERS_SQL = """
    albumSources AS (
        SELECT identity_key, album_id, trim(album) AS rawName,
            trim(COALESCE(NULLIF(trim(custom_artist), ''), artist, '')) AS credits,
            CASE WHEN instr(lower(identity_key), '|netease|') > 0 THEN 'netease'
                 WHEN instr(media_uri, 'neri-server://') = 1 THEN substr(media_uri, 1, instr(media_uri, '/song/') - 1)
                 WHEN instr(lower(identity_key), '|youtube_music|') > 0 OR media_uri LIKE 'ytmusic://%' THEN 'youtube'
                 WHEN instr(lower(identity_key), '|bilibili|') > 0 THEN 'bilibili'
                 WHEN local_file_path IS NOT NULL OR local_file_name IS NOT NULL OR media_uri LIKE 'content://%' OR media_uri LIKE 'file://%' THEN 'local'
                 ELSE 'legacy' END AS source
        FROM selected
    ),
    albums AS (
        SELECT identity_key, album_id, source, trim(substr(credits, 1, instr(credits || ' / ', ' / ') - 1)) AS primaryArtist,
            CASE WHEN rawName LIKE 'Bilibili|%' THEN ''
                 WHEN rawName LIKE 'Netease%' AND source = 'netease' THEN trim(substr(rawName, 8), ' -:_|')
                 ELSE rawName END AS name
        FROM albumSources
    ),
    members AS (
        SELECT identity_key, name,
            CASE WHEN (source = 'netease' AND album_id > 0) OR (source LIKE 'neri-server://%' AND album_id <> 0)
                 THEN source || ':id:' || album_id
                 ELSE source || ':name:' || quote(lower(name)) || ':artist:' ||
                      quote(COALESCE(NULLIF(lower(primaryArtist), ''), identity_key)) END AS groupKey
        FROM albums WHERE lower(name) NOT IN ('', 'netease', 'bilibili', 'youtube music', 'youtubemusic',
            'local files', 'local music', 'local_files', '__local_files__', 'qq music', 'unknown album', '本地音乐', '本地文件', '未知专辑')
    )
"""

private fun insightRankingQuery(filtered: String, args: List<Any>, artist: Boolean, limit: Int): SimpleSQLiteQuery {
    val members = if (artist) ARTIST_MEMBERS_SQL else ALBUM_MEMBERS_SQL
    val query = """
        WITH RECURSIVE selected AS ($filtered), $members,
        grouped AS (
            SELECT m.groupKey, MIN(m.name) AS name, COUNT(*) AS trackCount, SUM(s.play_count) AS totalPlayCount,
                SUM(MAX(s.total_listen_ms, 0) >> 32) AS hi, SUM(MAX(s.total_listen_ms, 0) & 4294967295) AS lo,
                MIN(COALESCE(NULLIF(s.custom_cover_url, ''), NULLIF(s.cover_url, ''))) AS coverUrl
            FROM members m JOIN selected s USING (identity_key) GROUP BY m.groupKey
        )
        SELECT name, trackCount, totalPlayCount, coverUrl, ${saturatedSum()} AS totalListenMs,
            (SELECT COUNT(*) FROM grouped) AS groupCount
        FROM grouped ORDER BY totalListenMs DESC, totalPlayCount DESC, name COLLATE NOCASE, groupKey LIMIT ?
    """.trimIndent()
    return SimpleSQLiteQuery(query, (args + limit).toTypedArray())
}

private fun weekdayQuery(filtered: String, args: List<Any>, bounds: List<Any>): SimpleSQLiteQuery {
    val query = """
        WITH selected AS ($filtered), ${dayBucketEventsSql()},
        events AS (
            SELECT b.day, b.total_listen_ms, b.play_count FROM day_events b JOIN selected s USING (identity_key)
            UNION ALL
            SELECT date(last_played_at / 1000, 'unixepoch', 'localtime') AS day, total_listen_ms, play_count
            FROM selected s WHERE first_played_at > 0
                AND date(first_played_at / 1000, 'unixepoch', 'localtime') = date(last_played_at / 1000, 'unixepoch', 'localtime')
                AND NOT EXISTS (SELECT 1 FROM playback_stat_bucket b WHERE b.identity_key = s.identity_key)
        ),
        slots AS (
            SELECT (CAST(strftime('%w', day) AS INTEGER) + 6) % 7 AS weekday,
                COUNT(DISTINCT day) AS activeDays,
                SUM(MAX(total_listen_ms, 0) >> 32) AS hi, SUM(MAX(total_listen_ms, 0) & 4294967295) AS lo
            FROM events WHERE day IS NOT NULL AND (total_listen_ms > 0 OR play_count > 0) GROUP BY weekday
        )
        SELECT weekday, activeDays, ${saturatedSum()} AS totalListenMs FROM slots
    """.trimIndent()
    return SimpleSQLiteQuery(query, (args + bounds).toTypedArray())
}
