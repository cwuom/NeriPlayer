package moe.ouom.neriplayer.data.stats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.Build
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsReadOnlyCaptureTest {
    @Test
    fun publicReadOnlyTransactionKeepsAllFamiliesWhileRoomWriterCommits() {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        verifyConcurrentSnapshot(publicTransaction = true)
    }

    @Test
    fun privateReadOnlySavepointKeepsAllFamiliesWhileRoomWriterCommits() {
        verifyConcurrentSnapshot(publicTransaction = false)
    }

    private fun verifyConcurrentSnapshot(publicTransaction: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stats-read-only-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        val writer = Executors.newSingleThreadExecutor()
        try {
            runBlocking {
                PlaybackStatsRoomStore(database).importLegacyAndPromote(
                    emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0
                )
            }
            val sql = database.openHelper.writableDatabase
            sql.beginTransactionNonExclusive()
            try {
                sql.execSQL("INSERT INTO playback_stat VALUES ('snapshot',1,'song','artist','netease',0,NULL,180000,1000,1,200,100,NULL,NULL,NULL,NULL,NULL,NULL)")
                sql.execSQL("INSERT INTO playback_stat_counter_shard SELECT identity_key,'actor',0,total_listen_ms,play_count,first_played_at,last_played_at FROM playback_stat")
                sql.execSQL("INSERT INTO playback_stat_bucket SELECT 0,identity_key,id,name,artist,album,album_id,cover_url,duration_ms,total_listen_ms,play_count,last_played_at,first_played_at,media_uri,local_file_path,local_file_name,custom_name,custom_artist,custom_cover_url FROM playback_stat")
                sql.execSQL("INSERT INTO playback_stat_daily_counter_shard SELECT 0,identity_key,'actor',0,total_listen_ms,play_count,first_played_at,last_played_at FROM playback_stat")
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }

            val params = SQLiteDatabase.OpenParams.Builder().setOpenFlags(SQLiteDatabase.OPEN_READONLY)
                .setErrorHandler { throw SQLiteDatabaseCorruptException("Read-only capture must preserve the primary database") }
                .build()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name), params).use { reader ->
                assertTrue(reader.isReadOnly)
                reader.rawQuery("PRAGMA journal_mode", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("wal", it.getString(0))
                }
                begin(reader, publicTransaction)
                try {
                    val revision = revision(reader)
                    assertFamilies(reader, 1_000)
                    val committed = writer.submit {
                        val primary = database.openHelper.writableDatabase
                        primary.beginTransactionNonExclusive()
                        try {
                            FAMILIES.forEach { primary.execSQL("UPDATE $it SET total_listen_ms=2000") }
                            primary.execSQL("UPDATE migration_metadata SET value=CAST(value AS INTEGER)+1 WHERE key='playback_stats_revision'")
                            primary.setTransactionSuccessful()
                        } finally { primary.endTransaction() }
                    }
                    committed.get(10, TimeUnit.SECONDS)
                    assertFamilies(reader, 1_000)
                    assertEquals(revision, revision(reader))
                } finally { end(reader, publicTransaction) }
                assertFamilies(reader, 2_000)
            }
        } finally {
            writer.shutdownNow()
            assertTrue("Task writer must terminate before its database is removed", writer.awaitTermination(10, TimeUnit.SECONDS))
            database.close()
            assertTrue(context.deleteDatabase(name))
        }
    }

    private fun begin(reader: SQLiteDatabase, publicTransaction: Boolean) {
        if (!publicTransaction) reader.execSQL("SAVEPOINT stats_capture")
        else if (Build.VERSION.SDK_INT >= 35) reader.beginTransactionReadOnly()
        else error("Public read-only transactions require API 35")
    }

    private fun end(reader: SQLiteDatabase, publicTransaction: Boolean) {
        if (publicTransaction) reader.endTransaction()
        else reader.execSQL("RELEASE stats_capture")
    }

    private fun revision(reader: SQLiteDatabase): Long =
        reader.rawQuery("SELECT value FROM migration_metadata WHERE key='playback_stats_revision'", null).use {
            check(it.moveToFirst())
            it.getString(0).toLong()
        }

    private fun assertFamilies(reader: SQLiteDatabase, expected: Long) {
        for (table in FAMILIES) reader.rawQuery("SELECT total_listen_ms FROM $table", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(expected, it.getLong(0))
            assertTrue(!it.moveToNext())
        }
    }

    private companion object {
        val FAMILIES = listOf("playback_stat", "playback_stat_counter_shard", "playback_stat_bucket", "playback_stat_daily_counter_shard")
    }
}
