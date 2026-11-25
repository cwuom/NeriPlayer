package moe.ouom.neriplayer.data.local.database.migration

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackHourTotalsCodec
import moe.ouom.neriplayer.data.local.database.migration.library.PlaybackHourTotalsMigration
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHourTotal
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackHourTotalsMigrationTest {
    @Test
    fun capturedDatabaseCopiesProduceTheSameMergeableHourlyHistory() {
        val captureId = UUID.randomUUID().toString()
        val buckets = migratedBuckets(captureId)
        val fromCopy = migratedBuckets(captureId)
        assertEquals(buckets, fromCopy)
        assertEquals(listOf(10L, 15L), buckets[0].map { it.totalListenMs })
        assertEquals(listOf(20L), buckets[1].map { it.totalListenMs })
        assertEquals(listOf(30L), buckets[2].map { it.totalListenMs })
        assertEquals(86408000L, buckets[2].single().hourStartAt)
        assertEquals(emptyList<SyncPlaybackHourTotal>(), buckets[3])
        assertEquals(setOf("legacy-hour-$captureId"), buckets.flatten().map { it.deviceId }.toSet())
        buckets.forEachIndexed { index, rows -> assertEquals(rows, SyncPlaybackHours.merge(rows, fromCopy[index])) }
    }

    @Test
    fun missingAndMalformedCaptureIdsEachMintOneOriginForTheWholeDatabase() {
        fun source(instance: String?): UUID {
            val buckets = migratedBuckets(instance)
            val origins = buckets.flatten().map { it.deviceId }.distinct()
            return UUID.fromString(origins.single().removePrefix("legacy-hour-"))
        }
        assertNotEquals(source(null), source("invalid-uuid"))
    }

    @Test
    fun lordHoweSpringForwardKeepsObservedListeningInsideItsCivilHour() {
        val day = LocalDate.of(2024, 10, 6)
        val zone = ZoneId.of("Australia/Lord_Howe")
        fun at(hour: Int, minute: Int) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        val hours = migratedBuckets(null) { db ->
            db.execSQL("INSERT INTO playback_stat_bucket VALUES (?,'a',?,90)", arrayOf(at(0, 0), at(4, 0)))
            fun addInterval(hour: Int, minute: Int, duration: Long) {
                val start = at(hour, minute)
                db.execSQL("INSERT INTO playback_listening_interval VALUES ('a',0,?,?,?)", arrayOf<Any>(start, start + duration, hour))
            }
            addInterval(2, 40, 10)
            addInterval(3, 20, 30)
            addInterval(3, 5, 50)
        }.single()
        assertEquals(listOf(at(2, 40), at(3, 5)), hours.map { it.hourStartAt })
        assertEquals(listOf(2, 3), hours.map { it.localHour })
        assertEquals(80L, hours.filter { it.hourStartAt in at(3, 0) until at(4, 0) }.sumOf { it.totalListenMs })
    }

    private fun migratedBuckets(
        instance: String?,
        populate: (SupportSQLiteDatabase) -> Unit = ::addExampleHistory
    ): List<List<SyncPlaybackHourTotal>> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).callback(object : SupportSQLiteOpenHelper.Callback(23) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE migration_metadata (key TEXT PRIMARY KEY, value TEXT)")
                db.execSQL("CREATE TABLE playback_stat_bucket (day_start_at INTEGER, identity_key TEXT, last_played_at INTEGER, total_listen_ms INTEGER)")
                db.execSQL("CREATE TABLE playback_stat_snapshot_bucket (snapshot_id TEXT)")
                db.execSQL("CREATE TABLE playback_stat_hour (identity_key TEXT, epoch_started_at INTEGER, hour_start_at INTEGER, local_hour INTEGER, listened_ms INTEGER)")
                db.execSQL("CREATE TABLE playback_listening_interval (identity_key TEXT, epoch_started_at INTEGER, start_at INTEGER, end_at INTEGER, local_hour INTEGER)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("Unexpected fixture upgrade from $oldVersion to $newVersion")
            }
        }).build()
        return FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            if (instance != null) db.execSQL("INSERT INTO migration_metadata VALUES ('playback_stats_capture_database_instance', ?)", arrayOf(instance))
            populate(db)
            PlaybackHourTotalsMigration.migrate(db)
            db.query("SELECT hourly_totals, local_hourly_totals, total_listen_ms FROM playback_stat_bucket ORDER BY day_start_at, identity_key").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        assertEquals(cursor.getString(0), cursor.getString(1))
                        assertEquals(90L, cursor.getLong(2))
                        add(PlaybackHourTotalsCodec.decode(cursor.getString(0)))
                    }
                }
            }
        }
    }

    private fun addExampleHistory(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO playback_stat_bucket VALUES (0,'a',4000000,90),(0,'b',5000,90),(86400000,'a',86409000,90),(86400000,'c',86409000,90)")
        db.execSQL("INSERT INTO playback_stat_hour VALUES ('a',0,0,0,10),('a',0,3600000,1,15),('b',0,0,0,20)")
        db.execSQL("INSERT INTO playback_listening_interval VALUES ('a',0,86408000,86408030,0)")
    }
}
