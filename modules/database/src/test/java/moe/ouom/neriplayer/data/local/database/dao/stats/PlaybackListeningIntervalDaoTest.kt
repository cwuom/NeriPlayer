package moe.ouom.neriplayer.data.local.database.dao.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackListeningIntervalEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackListeningIntervalDaoTest {
    @Test
    fun adjacentIntervalsMergeOnlyWhenTheirRecordingDatesMatch() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val dao = db.playbackStatsDao()
            val dates = listOf(null, null, "2026-11-22", "2026-11-22", "2026-11-23", null)
            for ((index, date) in dates.withIndex()) {
                val start = index * 1000L
                dao.appendListeningInterval(PlaybackListeningIntervalEntity(
                    identityKey = "song", startAt = start, endAt = start + 1000, localHour = 12,
                    epochStartedAt = 0, recordedLocalDate = date
                ))
            }
            db.openHelper.readableDatabase.query(
                "SELECT start_at, end_at, recorded_local_date FROM playback_listening_interval ORDER BY start_at"
            ).use { cursor ->
                val intervals = buildList {
                    while (cursor.moveToNext()) {
                        val date = if (cursor.isNull(2)) null else cursor.getString(2)
                        add(Triple(cursor.getLong(0), cursor.getLong(1), date))
                    }
                }
                assertEquals(listOf(Triple(0L, 2000L, null), Triple(2000L, 4000L, "2026-11-22"),
                    Triple(4000L, 5000L, "2026-11-23"), Triple(5000L, 6000L, null)), intervals)
            }
        } finally {
            db.close()
        }
    }
}
