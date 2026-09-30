package moe.ouom.neriplayer.data.ltw.session.liveness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.ltw.testing.*
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherSoftSyncRateRecheckOwnerTest {
    @Test
    fun `small drift keeps one recheck loop and converged drift resets rate`() = runTest {
        val f = Fixture(this)
        f.owner.reconcile(); f.owner.reconcile()
        advanceTimeBy(500); runCurrent()
        assertEquals(1, f.player.calls.count { it.startsWith("rate:") })
        assertTrue(f.player.syncPlaybackRate > 1f)
        f.player.playbackPositionFlow.value = 1_600L
        advanceTimeBy(500); runCurrent()
        assertEquals(1f, f.player.syncPlaybackRate)
        f.owner.stop()
    }

    @Test
    fun `large drift requests position synchronization then stops rechecking`() = runTest {
        val f = Fixture(this)
        f.room = testRoom(playing = true, position = 5_000L)
        f.owner.reconcile()
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf(5_000L), f.applied)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, f.applied.size)
        f.owner.stop()
    }

    @Test
    fun `missing room disconnected session and mismatched track reset rate`() = runTest {
        val f = Fixture(this)
        for (room in listOf(null, testRoom(emptyList(), playing = true), testRoom(listOf(testTrack("2")), playing = true), testRoom(playing = false))) {
            f.room = room
            f.player.syncPlaybackRate = 1.02f
            f.owner.reconcile()
            advanceTimeBy(500); runCurrent()
            assertEquals(1f, f.player.syncPlaybackRate)
        }
        f.room = testRoom(playing = true, position = 1_600L)
        f.session = f.session.copy(connectionState = ListenTogetherConnectionState.DISCONNECTED)
        f.player.syncPlaybackRate = 1.02f
        f.owner.reconcile(); advanceTimeBy(500); runCurrent()
        assertEquals(1f, f.player.syncPlaybackRate)
        f.owner.stop()
    }

    @Test
    fun `normal rate cancels a pending loop and rate normalization during wait stops without mutation`() = runTest {
        val f = Fixture(this)
        f.owner.reconcile()
        f.player.syncPlaybackRate = 1f
        advanceTimeBy(500); runCurrent()
        assertTrue(f.player.calls.isEmpty())
        f.player.syncPlaybackRate = 1.02f
        f.owner.reconcile()
        f.player.syncPlaybackRate = 1f
        f.owner.reconcile()
        advanceTimeBy(500); runCurrent()
        assertTrue(f.player.calls.isEmpty())
        f.owner.stop()
    }

    private class Fixture(scope: TestScope) {
        val player = FakeListenTogetherPlaybackHost().apply {
            currentSongFlow.value = testSong()
            isPlayingFlow.value = true
            syncPlaybackRate = 1.02f
        }
        var room: ListenTogetherRoomState? = testRoom(playing = true, position = 1_600L)
        var session = ListenTogetherSessionState(connectionState = ListenTogetherConnectionState.CONNECTED, role = "listener")
        val applied = mutableListOf<Long>()
        val owner = ListenTogetherSoftSyncRateRecheckOwner(scope, player, TestSongMapper, ListenTogetherSoftSyncRecheckConfig(500L, 600L, 1_500L, 2_500L), { session }, { room }, { it.role == "controller" }, { 0L }, { _, _, position -> applied += position })
    }
}
