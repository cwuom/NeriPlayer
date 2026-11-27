package moe.ouom.neriplayer.data.local.database.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackHourTotalsCodec
import moe.ouom.neriplayer.data.local.database.migration.legacy.legacyCursor
import moe.ouom.neriplayer.data.local.database.migration.library.PlaybackHourTotalsMigration
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackHourTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

class PlaybackHourTotalsMigrationWritesTest {
    private val observations = listOf(
        observation(0, "a", 0, 600_000, 0, 30),
        observation(0, "a", 7, 14_500_000, 4, 40),
        observation(0, "b", 7, 14_550_000, 4, 50),
        observation(86_400_000, "b", 7, 89_900_000, 1, 60)
    )

    @Test
    fun writesOneCompleteUpdatePerTrackAndDayUsingTheExistingCaptureId() {
        val id = UUID.randomUUID().toString()
        val source = "legacy-hour-$id"
        val buckets = migrate(id, observations)
        assertEquals(
            mapOf(
                (0L to "a") to listOf(
                    SyncPlaybackHourTotal(source, 0, 600_000, 0, 30),
                    SyncPlaybackHourTotal(source, 7, 14_500_000, 4, 40)
                ),
                (0L to "b") to listOf(SyncPlaybackHourTotal(source, 7, 14_550_000, 4, 50)),
                (86_400_000L to "b") to listOf(SyncPlaybackHourTotal(source, 7, 89_900_000, 1, 60))
            ),
            buckets
        )
        assertEquals(emptyMap<Pair<Long, String>, List<SyncPlaybackHourTotal>>(), migrate(id, emptyList()))
    }

    @Test
    fun missingOrInvalidCaptureIdCreatesOneUuidPerDatabase() {
        fun source(instance: String?): UUID {
            val ids = migrate(instance, observations).values.flatten().map { it.deviceId }.distinct()
            return UUID.fromString(ids.single().removePrefix("legacy-hour-"))
        }
        assertNotEquals(source(null), source("bad-uuid"))
    }

    private fun observation(day: Long, track: String, epoch: Long, hourStart: Long, hour: Int, duration: Long) =
        linkedMapOf<String, Any?>("day" to day, "identity" to track, "epoch" to epoch, "hourStart" to hourStart,
            "hour" to hour, "duration" to duration)

    private fun migrate(
        instance: String?,
        observations: List<Map<String, Any?>>
    ): Map<Pair<Long, String>, List<SyncPlaybackHourTotal>> {
        val db = mock(SupportSQLiteDatabase::class.java)
        val buckets = mutableMapOf<Pair<Long, String>, List<SyncPlaybackHourTotal>>()
        doAnswer { call ->
            val sql = call.getArgument<String>(0)
            when {
                sql.startsWith("SELECT value FROM migration_metadata") ->
                    legacyCursor(listOfNotNull(instance?.let { mapOf("value" to it) }))
                sql.startsWith("WITH observations") -> legacyCursor(observations)
                else -> error("Unexpected query: $sql")
            }
        }.`when`(db).query(anyString())
        doAnswer { call ->
            val sql = call.getArgument<String>(0)
            check(sql.startsWith("UPDATE playback_stat_bucket SET hourly_totals = ?, local_hourly_totals = ?"))
            val args = call.getArgument<Array<Any>>(1)
            assertEquals(args[0], args[1])
            val bucket = (args[2] as Long) to (args[3] as String)
            assertNull(buckets.put(bucket, PlaybackHourTotalsCodec.decode(args[0] as String)))
            null
        }.`when`(db).execSQL(anyString(), any<Array<Any?>>() ?: emptyArray())
        PlaybackHourTotalsMigration.migrate(db)
        return buckets
    }
}
