package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotRoomStore
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
        val originalFiles = snapshotFiles(
            context,
            "playback_stats_counters.json",
            "last_playlist.json",
            "managed_download_snapshot_v1.json",
            "keep_me.json"
        )
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
            setRoomPrimary(
                database = database,
                key = ManagedDownloadSnapshotRoomStore.CUTOVER_STATE_METADATA_KEY
            )

            val eligibleStatsFile = writeLegacyFile(context, "playback_stats_counters.json")
            val eligibleQueueFile = writeLegacyFile(context, "last_playlist.json")
            val eligibleSnapshotFile = writeLegacyFile(context, "managed_download_snapshot_v1.json")
            val unrelatedFile = writeLegacyFile(context, "keep_me.json")

            val coordinator = LegacyJsonCleanupCoordinator(context, database)
            val plan = coordinator.buildPlan()
            val result = coordinator.execute(plan, confirmed = true)

            assertEquals(LegacyJsonCleanupStatus.COMPLETED, result.status)
            assertFalse(eligibleStatsFile.exists())
            assertFalse(eligibleQueueFile.exists())
            assertFalse(eligibleSnapshotFile.exists())
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
            restoreFiles(context, originalFiles)
        }
    }

    @Test
    fun cleanupDeletesEligibleFilesEvenWhenOtherTargetsAreBlocked() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalFiles = snapshotFiles(
            context,
            "playback_stats_counters.json",
            "last_playback_state.json"
        )
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
            assertFalse(eligibleStatsFile.exists())
            assertTrue(blockedQueueFile.exists())

            val audit = database.syncMetadataDao().getMigrationMetadata(
                LegacyJsonCleanupCoordinator.CLEANUP_AUDIT_METADATA_KEY
            )
            assertNotNull(audit)
            assertTrue(audit?.value?.contains("status=BLOCKED") == true)
            assertTrue(audit?.value?.contains("playback_stats_counters.json") == true)
            assertTrue(audit?.value?.contains("last_playback_state.json") == true)
        } finally {
            database.close()
            restoreFiles(context, originalFiles)
        }
    }

    @Test
    fun cleanupKeepsLegacyJsonFallbackFilesInMixedUpgradeState() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalFiles = snapshotFiles(
            context,
            "local_playlists.json",
            "last_playlist.json",
            "play_history.json"
        )
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            setRoomPrimary(
                database = database,
                key = LocalPlaylistRoomStore.CUTOVER_STATE_METADATA_KEY
            )
            setLegacyJsonPrimary(
                database = database,
                key = PlaybackQueueRoomStore.CUTOVER_STATE_METADATA_KEY
            )

            val promotedPlaylistFile = writeLegacyFile(context, "local_playlists.json")
            val fallbackQueueFile = writeLegacyFile(context, "last_playlist.json")
            val missingMarkerHistoryFile = writeLegacyFile(context, "play_history.json")
            val fallbackQueueContent = fallbackQueueFile.readText()
            val missingMarkerHistoryContent = missingMarkerHistoryFile.readText()

            val coordinator = LegacyJsonCleanupCoordinator(context, database)
            val plan = coordinator.buildPlan()
            val result = coordinator.execute(plan, confirmed = true)

            assertEquals(LegacyJsonCleanupStatus.BLOCKED, result.status)
            assertFalse(promotedPlaylistFile.exists())
            assertTrue(fallbackQueueFile.exists())
            assertTrue(missingMarkerHistoryFile.exists())
            assertTrue(result.blockedFiles.contains("last_playlist.json"))
            assertTrue(result.blockedFiles.contains("play_history.json"))
            assertEquals(fallbackQueueContent, fallbackQueueFile.readText())
            assertEquals(missingMarkerHistoryContent, missingMarkerHistoryFile.readText())
        } finally {
            database.close()
            restoreFiles(context, originalFiles)
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

    private suspend fun setLegacyJsonPrimary(
        database: NeriUserDataDatabase,
        key: String
    ) {
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = key,
                value = PlaybackQueueRoomStore.LEGACY_JSON_STATE,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun writeLegacyFile(context: Context, fileName: String): File {
        return File(context.filesDir, fileName).apply {
            writeText("legacy")
        }
    }

    private fun snapshotFiles(context: Context, vararg fileNames: String): Map<String, String?> {
        return fileNames.associateWith { fileName ->
            File(context.filesDir, fileName).takeIf(File::exists)?.readText()
        }
    }

    private fun restoreFiles(context: Context, files: Map<String, String?>) {
        files.forEach { (fileName, content) ->
            val file = File(context.filesDir, fileName)
            if (content == null) {
                file.delete()
            } else {
                file.writeText(content)
            }
        }
    }
}
