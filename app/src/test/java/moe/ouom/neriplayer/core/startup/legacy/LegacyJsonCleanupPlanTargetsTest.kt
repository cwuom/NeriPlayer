package moe.ouom.neriplayer.core.startup.legacy

import android.content.Context
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.store.RepositoryCutoverKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class LegacyJsonCleanupPlanTargetsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `existing files are eligible only behind a room primary marker`() = runTest {
        val plan = planFor(
            existingFiles = listOf("local_playlists.json", "playlist_usage.json", "favorite_playlists.json"),
            cutoverStates = mapOf(
                RepositoryCutoverKeys.LOCAL_PLAYLIST to LegacyJsonCleanupCoordinator.ROOM_PRIMARY_STATE,
                RepositoryCutoverKeys.FAVORITE_PLAYLIST to "json_primary"
            )
        )

        assertEquals(listOf("local_playlists.json"), plan.existingEligibleTargets.map { it.fileName })
        assertEquals(
            listOf(
                "playlist_usage.json" to "Room primary marker is missing",
                "favorite_playlists.json" to "Room primary marker is json_primary"
            ),
            plan.blockedTargets.map { it.fileName to it.reason }
        )
        assertEquals("json_primary", plan.target("favorite_playlists.json").cutoverState)
        assertNull(plan.target("playlist_usage.json").cutoverState)
    }

    @Test
    fun `missing files are never blocked and keep the marker they would need`() = runTest {
        val plan = planFor(
            existingFiles = emptyList(),
            cutoverStates = mapOf(
                RepositoryCutoverKeys.PLAY_HISTORY to LegacyJsonCleanupCoordinator.ROOM_PRIMARY_STATE,
                RepositoryCutoverKeys.FAVORITE_PLAYLIST to "json_primary"
            )
        )

        val playHistory = plan.target("play_history.json")
        assertFalse(playHistory.exists)
        assertTrue(playHistory.eligible)
        assertNull(playHistory.reason)
        val favorites = plan.target("favorite_playlists.json")
        assertFalse(favorites.eligible)
        assertNull(favorites.reason)
        assertEquals(emptyList<LegacyJsonCleanupTarget>(), plan.blockedTargets)
        assertEquals(emptyList<LegacyJsonCleanupTarget>(), plan.existingEligibleTargets)
    }

    @Test
    fun `a marker row without a value counts as missing`() = runTest {
        val plan = planFor(
            existingFiles = listOf("play_history.json"),
            cutoverStates = mapOf(RepositoryCutoverKeys.PLAY_HISTORY to null)
        )

        val playHistory = plan.target("play_history.json")
        assertFalse(playHistory.eligible)
        assertEquals("Room primary marker is missing", playHistory.reason)
    }

    private suspend fun planFor(
        existingFiles: List<String>,
        cutoverStates: Map<String, String?>
    ): LegacyJsonCleanupPlan {
        val filesDir = temporaryFolder.newFolder("files")
        existingFiles.forEach { File(filesDir, it).writeText("[]") }
        val context = mock(Context::class.java).also { `when`(it.filesDir).thenReturn(filesDir) }
        val database = mock(NeriUserDataDatabase::class.java).also {
            `when`(it.syncMetadataDao()).thenReturn(CutoverStateDao(cutoverStates))
        }
        return LegacyJsonCleanupCoordinator(context, database).buildPlan()
    }

    private fun LegacyJsonCleanupPlan.target(fileName: String): LegacyJsonCleanupTarget =
        targets.single { it.fileName == fileName }

    private class CutoverStateDao(
        private val states: Map<String, String?>
    ) : SyncMetadataDao by mock(SyncMetadataDao::class.java) {
        override suspend fun getMigrationMetadata(key: String): MigrationMetadataEntity? =
            if (key in states) MigrationMetadataEntity(key = key, value = states[key], updatedAt = 0L) else null
    }
}
