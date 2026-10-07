package moe.ouom.neriplayer.data.stats

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomState
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlaybackStatsDeltaRecordingGuardTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val room = mock(PlaybackStatsRoomStore::class.java)
    private val song = SongItem(7, "song", "artist", "netease", 0, 180_000, null)

    @Test
    fun `deltas without listening time or plays never reach the journal`() = runTest {
        val repository = initializedRepository(backgroundScope) { "phone" }

        repository.recordListenDeltaNow(song, listenedMs = 0, playCountIncrement = 0, scheduleSync = false, eventId = "idle")
        repository.recordListenDeltaNow(song, listenedMs = -5, playCountIncrement = -1, scheduleSync = false, eventId = "rewind")

        verifyNothingEnqueued()
    }

    @Test
    fun `blank event ids are rejected before the journal is touched`() = runTest {
        val repository = initializedRepository(backgroundScope) { "phone" }

        val failure = runCatching {
            repository.recordListenDeltaNow(song, listenedMs = 100, playCountIncrement = 1, scheduleSync = false, eventId = " ")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        verifyNothingEnqueued()
    }

    @Test
    fun `a blank device identity is rejected before the journal is touched`() = runTest {
        val repository = initializedRepository(backgroundScope) { " " }

        val failure = runCatching {
            repository.recordListenDeltaNow(song, listenedMs = 100, playCountIncrement = 1, scheduleSync = false, eventId = "event")
        }.exceptionOrNull()

        assertEquals("Playback counter device identity unavailable", (failure as IllegalStateException).message)
        verifyNothingEnqueued()
    }

    @Test
    fun `a replayed event refused by the journal still settles pending writes`() = runTest {
        doReturn(false).`when`(room).enqueueDelta(anyString(), anyString(), anyLong(), any(), anyLong(), anyString(), any(), anyBoolean())
        val repository = initializedRepository(backgroundScope) { "phone" }

        repository.recordListenDeltaNow(
            song,
            listenedMs = 100,
            playCountIncrement = -3,
            scheduleSync = false,
            eventId = "replayed",
            playedAt = 40,
            observedClearedAt = 30
        )

        verify(room).enqueueDelta(sameString("replayed"), anyString(), eq(100L), eq(0), eq(40L), sameString("phone"), eq(30L), eq(true))
        assertFalse(repository.hasPendingWrites())
    }

    private fun sameString(value: String): String = eq(value) ?: value

    private suspend fun verifyNothingEnqueued() {
        verify(room, never()).enqueueDelta(anyString(), anyString(), anyLong(), any(), anyLong(), anyString(), any(), anyBoolean())
    }

    private suspend fun initializedRepository(scope: CoroutineScope, device: () -> String): PlaybackStatsRepository {
        `when`(room.readPrimaryState()).thenReturn(PlaybackStatsRoomState(revision = 1, clearedAt = 50, counterEpochStartedAt = 50))
        `when`(room.pendingDeltas()).thenReturn(emptyList())
        `when`(room.clearedAtFlow).thenReturn(emptyFlow())
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        return PlaybackStatsRepository(context, room, scope, device).also { repository ->
            assertTrue(repository.awaitInitialized())
        }
    }
}
