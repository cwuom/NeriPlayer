package moe.ouom.neriplayer.data.local.database.store.stats

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.resolvePlaybackStatsTimeRange
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatsSqlQueryBindingTest {
    @Test
    fun `all time summary reads the primary table and binds only the listen threshold`() {
        val query = PlaybackStatsQuery(PlaybackStatsPeriod.ALL, minimumListenMs = 600_000, requirePlayCount = true, nowMillis = NOW)
        val sql = PlaybackStatsSqlQuery(query, hasBuckets = true, hasLegacyStats = true)

        val summary = sql.summary()

        assertFalse(sql.usesLegacyBreakdown)
        assertTrue(summary.sql.contains("FROM (SELECT * FROM playback_stat) WHERE total_listen_ms >= ? AND play_count > 0"))
        assertEquals(listOf<Any?>(600_000L), summary.boundValues())
    }

    @Test
    fun `bucket backed period binds the bucket window three times before the legacy window`() {
        val range = PlaybackStatsPeriod.WEEK.resolvePlaybackStatsTimeRange(NOW)
        val start = checkNotNull(range.startInclusive)
        val query = PlaybackStatsQuery(PlaybackStatsPeriod.WEEK, minimumListenMs = 5, nowMillis = NOW)

        val bucketOnly = PlaybackStatsSqlQuery(query, hasBuckets = true)
        val mixed = PlaybackStatsSqlQuery(query, hasBuckets = true, hasLegacyStats = true)
        val summary = mixed.summary()

        assertFalse(bucketOnly.usesLegacyBreakdown)
        assertTrue(mixed.usesLegacyBreakdown)
        assertTrue(summary.sql.contains("UNION ALL SELECT * FROM playback_stat WHERE"))
        assertTrue(summary.sql.contains("AND NOT EXISTS (SELECT 1 FROM playback_stat_bucket b WHERE b.identity_key = playback_stat.identity_key)"))
        assertEquals(
            listOf<Any?>(start, range.endExclusive, start, range.endExclusive, start, range.endExclusive, start, range.endExclusive, 5L),
            summary.boundValues()
        )
    }

    @Test
    fun `legacy only period filters by first play time inside one window`() {
        val range = PlaybackStatsPeriod.MONTH.resolvePlaybackStatsTimeRange(NOW)
        val sql = PlaybackStatsSqlQuery(PlaybackStatsQuery(PlaybackStatsPeriod.MONTH, nowMillis = NOW), hasBuckets = false)

        val summary = sql.summary()

        assertTrue(sql.usesLegacyBreakdown)
        assertFalse(summary.sql.contains("UNION ALL"))
        assertFalse(summary.sql.contains("NOT EXISTS"))
        assertTrue(summary.sql.contains("(CASE WHEN first_played_at > 0 THEN first_played_at ELSE last_played_at END) >= ? AND last_played_at < ?"))
        assertEquals(listOf<Any?>(range.startInclusive, range.endExclusive, 0L), summary.boundValues())
    }

    @Test
    fun `keyset pages continue on the sort column with an identity tie break in both directions`() {
        val recent = PlaybackStatsSqlQuery(PlaybackStatsQuery(sort = PlaybackStatsSort.RECENT, minimumListenMs = 1, nowMillis = NOW), hasBuckets = false)
        val cursor = PlaybackStatsCursor(300, "k")

        val next = recent.page(cursor, limit = 11)
        val previous = recent.page(cursor, limit = 11, before = true)
        val first = recent.page(null, limit = 11)

        assertTrue(next.sql.endsWith("AND (last_played_at < ? OR (last_played_at = ? AND identity_key > ?)) ORDER BY last_played_at DESC, identity_key ASC LIMIT ?"))
        assertEquals(listOf<Any?>(1L, 300L, 300L, "k", 11L), next.boundValues())
        assertTrue(previous.sql.endsWith("AND (last_played_at > ? OR (last_played_at = ? AND identity_key < ?)) ORDER BY last_played_at ASC, identity_key DESC LIMIT ?"))
        assertTrue(first.sql.endsWith("WHERE total_listen_ms >= ? ORDER BY last_played_at DESC, identity_key ASC LIMIT ?"))
        assertEquals(listOf<Any?>(1L, 11L), first.boundValues())

        val oldest = PlaybackStatsSqlQuery(PlaybackStatsQuery(sort = PlaybackStatsSort.FIRST_PLAYED, nowMillis = NOW), hasBuckets = false)
        assertTrue(oldest.page(cursor, limit = 2).sql.endsWith("AND (first_played_at > ? OR (first_played_at = ? AND identity_key > ?)) ORDER BY first_played_at ASC, identity_key ASC LIMIT ?"))
        assertTrue(oldest.page(cursor, limit = 2, before = true).sql.endsWith("ORDER BY first_played_at DESC, identity_key DESC LIMIT ?"))
    }

    @Test
    fun `hot ranking pages compare play count, listen time and recency before identity`() {
        val hot = PlaybackStatsSqlQuery(
            PlaybackStatsQuery(sort = PlaybackStatsSort.PLAY_COUNT, minimumListenMs = 60_000, requirePlayCount = true, nowMillis = NOW),
            hasBuckets = false
        )
        val cursor = PlaybackStatsCursor(sortValue = 9, identityKey = "k", secondaryValue = 7_000, tertiaryValue = 400)

        val next = hot.page(cursor, limit = 5)
        val previous = hot.page(cursor, limit = 5, before = true)

        assertTrue(next.sql.contains("AND (play_count < ? OR (play_count = ? AND total_listen_ms < ?) OR (play_count = ? AND total_listen_ms = ? AND last_played_at < ?) OR (play_count = ? AND total_listen_ms = ? AND last_played_at = ? AND identity_key > ?))"))
        assertTrue(next.sql.endsWith("ORDER BY play_count DESC, total_listen_ms DESC, last_played_at DESC, identity_key ASC LIMIT ?"))
        assertEquals(listOf<Any?>(60_000L, 9L, 9L, 7_000L, 9L, 7_000L, 400L, 9L, 7_000L, 400L, "k", 5L), next.boundValues())
        assertTrue(previous.sql.contains("(play_count = ? AND total_listen_ms = ? AND last_played_at = ? AND identity_key < ?)"))
        assertTrue(previous.sql.endsWith("ORDER BY play_count ASC, total_listen_ms ASC, last_played_at ASC, identity_key DESC LIMIT ?"))
    }

    @Test
    fun `cursor captures the value of the active sort with listen time and recency tie breakers`() {
        val stat = TrackStat(1, "song", "artist", "album", 0, null, 1, totalListenMs = 4_000, playCount = 6,
            lastPlayedAt = 900, firstPlayedAt = 100, null, null, null, null, null, null, "k")
        val expected = mapOf(
            PlaybackStatsSort.PLAY_COUNT to 6L,
            PlaybackStatsSort.LISTEN_TIME to 4_000L,
            PlaybackStatsSort.RECENT to 900L,
            PlaybackStatsSort.FIRST_PLAYED to 100L
        )

        for ((sort, value) in expected) {
            val cursor = PlaybackStatsSqlQuery(PlaybackStatsQuery(sort = sort, nowMillis = NOW), hasBuckets = false).cursor(stat)
            assertEquals(PlaybackStatsCursor(value, "k", 4_000, 900), cursor)
        }
    }

    private fun SupportSQLiteQuery.boundValues(): List<Any?> {
        val program = RecordingProgram()
        bindTo(program)
        assertEquals(argCount, program.values.size)
        return program.values.values.toList()
    }

    private class RecordingProgram : SupportSQLiteProgram {
        val values = sortedMapOf<Int, Any?>()
        override fun bindNull(index: Int) { values[index] = null }
        override fun bindLong(index: Int, value: Long) { values[index] = value }
        override fun bindDouble(index: Int, value: Double) { values[index] = value }
        override fun bindString(index: Int, value: String) { values[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { values[index] = value }
        override fun clearBindings() = values.clear()
        override fun close() = Unit
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
