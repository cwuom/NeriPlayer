package moe.ouom.neriplayer.data.local.database.migration.library

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackHourTotalsCodec
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHourTotal

internal object PlaybackHourTotalsMigration : Migration(23, 24) {
    private const val HOUR_MS = 3_600_000L
    private const val DAY_SEARCH_MS = 48 * HOUR_MS

    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("playback_stat_bucket", "playback_stat_snapshot_bucket")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN hourly_totals TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE $table ADD COLUMN local_hourly_totals TEXT NOT NULL DEFAULT '[]'")
        }
        addRecordedDate(db, "playback_stat_hour", "hour_start_at", "listened_ms > 0")
        addRecordedDate(db, "playback_listening_interval", "start_at", "end_at > start_at")
        copyObservedHours(db, historicalSource(db))
    }

    private fun addRecordedDate(db: SupportSQLiteDatabase, table: String, timestamp: String, measured: String) {
        db.execSQL("ALTER TABLE $table ADD COLUMN recorded_local_date TEXT")
        val date = matchingBucketValue("recorded_local_date", "$table.identity_key", "$table.$timestamp")
        db.execSQL("UPDATE $table SET recorded_local_date = ($date) WHERE $measured AND epoch_started_at >= 0 " +
            "AND $timestamp >= 0 AND local_hour BETWEEN 0 AND 23")
    }

    private fun matchingBucketValue(column: String, identity: String, observedAt: String) =
        "SELECT k.$column FROM playback_stat_bucket k WHERE k.identity_key = $identity " +
            "AND k.day_start_at BETWEEN $observedAt - $DAY_SEARCH_MS AND $observedAt " +
            "AND k.last_played_at >= $observedAt ORDER BY k.day_start_at DESC LIMIT 1"

    // 已参与同步的数据库副本共享这个标识，可识别两台设备上相同的历史明细
    private fun historicalSource(db: SupportSQLiteDatabase): String {
        val previousInstance = db.query("SELECT value FROM migration_metadata WHERE key = 'playback_stats_capture_database_instance'").use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val instance = previousInstance?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: UUID.randomUUID()
        return "legacy-hour-$instance"
    }

    private fun copyObservedHours(db: SupportSQLiteDatabase, device: String) {
        db.query(observedHours()).use { cursor ->
            val totals = mutableListOf<SyncPlaybackHourTotal>()
            var day = 0L
            var identity = ""
            fun saveBucket() {
                if (totals.isEmpty()) return
                val json = PlaybackHourTotalsCodec.encode(totals)
                db.execSQL(
                    "UPDATE playback_stat_bucket SET hourly_totals = ?, local_hourly_totals = ? " +
                        "WHERE day_start_at = ? AND identity_key = ?", arrayOf<Any>(json, json, day, identity)
                )
                totals.clear()
            }
            while (cursor.moveToNext()) {
                val nextDay = cursor.getLong(0)
                val nextIdentity = cursor.getString(1)
                if (day != nextDay || identity != nextIdentity) {
                    saveBucket()
                    day = nextDay
                    identity = nextIdentity
                }
                totals.add(SyncPlaybackHourTotal(device, cursor.getLong(2), cursor.getLong(3),
                    cursor.getInt(4), cursor.getLong(5)))
            }
            saveBucket()
        }
    }

    // 旧区间没有时区，日桶午夜只用于区分回拨前后的小时，记录时间取自实际观测
    private fun observedHours() = """
        WITH observations(identity_key, epoch, observed_at, local_hour, duration, is_interval) AS (
            SELECT identity_key, epoch_started_at, hour_start_at, local_hour, listened_ms, 0
            FROM playback_stat_hour WHERE listened_ms > 0
            UNION ALL
            SELECT identity_key, epoch_started_at, start_at, local_hour, end_at - start_at, 1
            FROM playback_listening_interval WHERE end_at > start_at
        ), matched AS (
            SELECT b.day_start_at AS day, o.identity_key, o.epoch, o.local_hour, o.duration, o.observed_at,
                CASE WHEN o.is_interval = 1 THEN o.observed_at - ((o.observed_at - b.day_start_at) % $HOUR_MS)
                     ELSE o.observed_at END AS hour_partition
            FROM observations o JOIN playback_stat_bucket b ON b.identity_key = o.identity_key
                AND b.day_start_at = (${matchingBucketValue("day_start_at", "o.identity_key", "o.observed_at")})
            WHERE o.epoch >= 0 AND o.observed_at >= 0 AND o.local_hour BETWEEN 0 AND 23
        ), totals AS (
            SELECT day, identity_key, epoch, MIN(observed_at) AS hour_start, local_hour,
                SUM(duration >> 32) AS hi, SUM(duration & 4294967295) AS lo
            FROM matched GROUP BY day, identity_key, epoch, hour_partition, local_hour
        )
        SELECT day, identity_key, epoch, hour_start, local_hour,
            CASE WHEN hi + (lo >> 32) > 2147483647 THEN 9223372036854775807
                 ELSE ((hi + (lo >> 32)) << 32) + (lo & 4294967295) END
        FROM totals ORDER BY day, identity_key, hour_start, local_hour, epoch
    """.trimIndent()
}
