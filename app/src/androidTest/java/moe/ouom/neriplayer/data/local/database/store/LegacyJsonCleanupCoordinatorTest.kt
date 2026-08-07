package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyJsonCleanupCoordinatorTest {
    @Test
    fun cleanupDeletesEligibleFilesAndKeepsUnrelatedFiles() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            setRoomPrimary(
                database = database,
                key = PlaybackStatsRoomStore.CUTOVER_STATE_METADATA_KEY
            )
            setRoomPrimary(
                database = database,
                key = PlaybackQueueRoomStore.CUTOVER_STATE_METADATA_KEY
            )

            val eligibleStatsFile = writeLegacyFile(context, "playback_stats_counters.json")
            val eligibleQueueFile = writeLegacyFile(context, "last_playlist.json")
            val unrelatedFile = writeLegacyFile(context, "keep_me.json")

            val coordinator = LegacyJsonCleanupCoordinator(context, database)
            val plan = coordinator.buildPlan()
            val result = coordinator.execute(plan, confirmed = true)

            assertEquals(LegacyJsonCleanupStatus.COMPLETED, result.status)
            assertFalse(eligibleStatsFile.exists())
            assertFalse(eligibleQueueFile.exists())
            assertTrue(unrelatedFile.exists())

            val audit = database.syncMetadataDao().getMigrationMetadata(
                LegacyJsonCleanupCoordinator.CLEANUP_AUDIT_METADATA_KEY
            )
            assertNotNull(audit)
            assertTrue(audit?.value?.contains("status=COMPLETED") == true)
            assertTrue(audit?.value?.contains("playback_stats_counters.json") == true)
            assertTrue(audit?.value?.contains("last_playlist.json") == true)
        } finally {
            database.close()
            cleanupFiles(
                context,
                "playback_stats_counters.json",
                "last_playlist.json",
                "keep_me.json"
            )
        }
    }

    @Test
    fun cleanupBlocksWhenATargetHasNotPromotedToRoomYet() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            setRoomPrimary(
                database = database,
                key = PlaybackStatsRoomStore.CUTOVER_STATE_METADATA_KEY
            )

            val eligibleStatsFile = writeLegacyFile(context, "playback_stats_counters.json")
            val blockedQueueFile = writeLegacyFile(context, "last_playback_state.json")

            val coordinator = LegacyJsonCleanupCoordinator(context, database)
            val plan = coordinator.buildPlan()
            val result = coordinator.execute(plan, confirmed = true)

            assertEquals(LegacyJsonCleanupStatus.BLOCKED, result.status)
            assertTrue(result.blockedFiles.contains("last_playback_state.json"))
            assertTrue(eligibleStatsFile.exists())
            assertTrue(blockedQueueFile.exists())

            val audit = database.syncMetadataDao().getMigrationMetadata(
                LegacyJsonCleanupCoordinator.CLEANUP_AUDIT_METADATA_KEY
            )
            assertNotNull(audit)
            assertTrue(audit?.value?.contains("status=BLOCKED") == true)
            assertTrue(audit?.value?.contains("last_playback_state.json") == true)
        } finally {
            database.close()
            cleanupFiles(
                context,
                "playback_stats_counters.json",
                "last_playback_state.json"
            )
        }
    }

    private suspend fun setRoomPrimary(
        database: NeriUserDataDatabase,
        key: String
    ) {
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = key,
                value = LegacyJsonCleanupCoordinator.ROOM_PRIMARY_STATE,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun writeLegacyFile(context: Context, fileName: String): File {
        return File(context.filesDir, fileName).apply {
            writeText("legacy")
        }
    }

    private fun cleanupFiles(context: Context, vararg fileNames: String) {
        fileNames.forEach { fileName ->
            File(context.filesDir, fileName).delete()
        }
    }
}
