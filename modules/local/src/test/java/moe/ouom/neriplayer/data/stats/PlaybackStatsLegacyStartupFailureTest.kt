package moe.ouom.neriplayer.data.stats

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomState
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsSnapshotHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlaybackStatsLegacyStartupFailureTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val room = mock(PlaybackStatsRoomStore::class.java)

    @Test
    fun `an empty primary store whose legacy import cannot start stays unavailable`() = runTest {
        val database = PlaybackStatsSnapshotHarness().room.database
        `when`(room.database).thenReturn(database)
        `when`(room.readPrimaryState()).thenReturn(null)
        doAnswer { throw IOException("legacy import unavailable") }.`when`(room).beginLegacyImport()
        val legacy = File(temporaryFolder.root, "playback_stats.json").apply { writeText("[]") }
        val repository = PlaybackStatsRepository(context(), room, backgroundScope) { "device" }

        assertFalse(repository.awaitInitialized())
        val failure = runCatching { repository.getStatForTrack("netease:7") }.exceptionOrNull()

        assertEquals("Playback statistics unavailable", (failure as IOException).message)
        assertEquals("legacy import unavailable", generateSequence<Throwable>(failure) { it.cause }.last().message)
        assertEquals("[]", legacy.readText())
        verify(room, never()).commitFrozenSnapshot(anyString(), anyLong())
    }

    @Test
    fun `a startup load cancelled midway is loaded again on the next wait`() = runTest {
        var reads = 0
        `when`(room.readPrimaryState()).thenAnswer {
            reads += 1
            if (reads == 1) throw CancellationException("startup cancelled")
            PlaybackStatsRoomState(revision = 4, clearedAt = 75, counterEpochStartedAt = 75)
        }
        `when`(room.pendingDeltas()).thenReturn(emptyList())
        `when`(room.clearedAtFlow).thenReturn(emptyFlow())
        val repository = PlaybackStatsRepository(context(), room, backgroundScope) { "device" }

        assertTrue(repository.awaitInitialized())

        assertEquals(2, reads)
        assertEquals(75L, repository.statsClearedAtFlow.value)
        assertFalse(repository.hasPendingWrites())
    }

    private fun context(): Context = mock(Context::class.java).also { context ->
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
    }
}
