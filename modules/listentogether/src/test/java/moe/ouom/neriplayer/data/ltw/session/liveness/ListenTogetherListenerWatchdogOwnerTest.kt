package moe.ouom.neriplayer.data.ltw.session.liveness

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import moe.ouom.neriplayer.data.ltw.testing.FakeListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.ltw.testing.TestSongMapper
import moe.ouom.neriplayer.data.ltw.testing.testRoom
import moe.ouom.neriplayer.data.ltw.testing.testSong
import moe.ouom.neriplayer.data.ltw.testing.testTrack
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherListenerWatchdogOwnerTest {
    @Test
    fun `watchdog repairs silent listener at most once per cooldown`() = runTest {
        val port = FakePort()
        val owner = ListenTogetherListenerWatchdogOwner(this, FakeListenTogetherPlaybackHost(), TestSongMapper, port, 600L) { testScheduler.currentTime + 1_000L }
        owner.start()
        owner.start()
        advanceTimeBy(8_000L)
        runCurrent()
        assertEquals(1, port.refreshes)
        assertEquals(1, port.memberRetries)

        port.lastMessageAtElapsedMs = 9_000L
        advanceTimeBy(8_000L)
        runCurrent()
        assertEquals(1, port.refreshes)

        port.pendingRepairVersion = 4L
        advanceTimeBy(24_000L)
        runCurrent()
        assertEquals(2, port.refreshes)
        owner.stop()
    }

    @Test
    fun `watchdog ignores disconnected and controller sessions`() = runTest {
        val port = FakePort()
        val owner = ListenTogetherListenerWatchdogOwner(this, FakeListenTogetherPlaybackHost(), TestSongMapper, port, 600L) { testScheduler.currentTime + 1_000L }
        port.sessionState = port.sessionState.copy(connectionState = ListenTogetherConnectionState.DISCONNECTED)
        owner.start()
        advanceTimeBy(8_000L)
        runCurrent()
        port.sessionState = port.sessionState.copy(connectionState = ListenTogetherConnectionState.CONNECTED)
        port.controller = true
        advanceTimeBy(8_000L)
        runCurrent()
        assertEquals(0, port.refreshes)
        assertEquals(0, port.memberRetries)
        owner.stop()
    }

    @Test
    fun `watchdog ignores blank room and does not refresh without base url`() = runTest {
        val port = FakePort()
        val owner = ListenTogetherListenerWatchdogOwner(this, FakeListenTogetherPlaybackHost(), TestSongMapper, port, 600L) { testScheduler.currentTime + 1_000L }
        port.sessionState = port.sessionState.copy(roomId = "")
        owner.start()
        advanceTimeBy(8_000L)
        runCurrent()
        assertEquals(0, port.memberRetries)

        port.sessionState = port.sessionState.copy(roomId = "room", baseUrl = null)
        advanceTimeBy(8_000L)
        runCurrent()
        assertEquals(1, port.memberRetries)
        assertEquals(0, port.refreshes)
        owner.stop()
    }

    @Test
    fun `inactive room still checks controller link and reports repair failure`() = runTest {
        val port = FakePort()
        val owner = ListenTogetherListenerWatchdogOwner(this, FakeListenTogetherPlaybackHost(), TestSongMapper, port, 600L) { testScheduler.currentTime + 1_000L }
        port.room = ListenTogetherRoomState(
            roomId = "room", version = 1L, roomStatus = ListenTogetherRoomStatuses.CONTROLLER_OFFLINE
        )
        port.failRefresh = true
        owner.start()
        advanceTimeBy(8_000L)
        runCurrent()
        assertEquals(listOf("listener_watchdog"), port.linkCauses)
        assertTrue(port.appliedCauses.isEmpty())
        assertEquals(listOf("listener_watchdog"), port.failureReasons)
        owner.stop()
    }

    @Test
    fun `in sync listener is left alone while drift or a version gap triggers a repair`() = runTest {
        val port = FakePort()
        val player = FakeListenTogetherPlaybackHost().apply {
            currentSongFlow.value = testSong()
            isPlayingFlow.value = true
            playbackPositionFlow.value = 10_200L
        }
        port.room = testRoom(playing = true, position = 10_000L)
        val owner = ListenTogetherListenerWatchdogOwner(this, player, TestSongMapper, port, 600L) { testScheduler.currentTime + 1_000L }
        owner.start()
        advanceTimeBy(8_000L); runCurrent()
        assertTrue(port.appliedCauses.isEmpty())

        player.playbackPositionFlow.value = 10_600L
        advanceTimeBy(8_000L); runCurrent()
        assertEquals(listOf("WATCHDOG"), port.appliedCauses)

        player.playbackPositionFlow.value = 10_000L
        port.pendingRepairVersion = 6L
        advanceTimeBy(8_000L); runCurrent()
        assertEquals(listOf("WATCHDOG", "WATCHDOG"), port.appliedCauses)

        port.pendingRepairVersion = -1L
        port.room = testRoom(playing = false, position = 10_000L)
        advanceTimeBy(8_000L); runCurrent()
        assertEquals(3, port.appliedCauses.size)
        player.isPlayingFlow.value = false
        advanceTimeBy(8_000L); runCurrent()
        assertEquals(3, port.appliedCauses.size)

        port.room = testRoom(listOf(testTrack("2")), position = 10_000L)
        advanceTimeBy(8_000L); runCurrent()
        port.room = testRoom(emptyList(), position = 10_000L)
        advanceTimeBy(8_000L); runCurrent()
        assertEquals(5, port.appliedCauses.size)
        owner.stop()
    }

    @Test
    fun `stalled listener is repaired even at the room position`() = runTest {
        val port = FakePort()
        val player = FakeListenTogetherPlaybackHost().apply {
            currentSongFlow.value = testSong()
            playWhenReadyFlow.value = true
            playerPlaybackStateFlow.value = Player.STATE_BUFFERING
            playbackPositionFlow.value = 10_000L
        }
        port.room = testRoom(playing = true, position = 10_000L)
        val owner = ListenTogetherListenerWatchdogOwner(this, player, TestSongMapper, port, 600L) { testScheduler.currentTime + 1_000L }
        owner.start()
        advanceTimeBy(16_000L); runCurrent()
        assertEquals(listOf("WATCHDOG", "WATCHDOG_STALL"), port.appliedCauses)
        assertTrue(port.linkCauses.contains("WATCHDOG_STALL"))
        owner.stop()
    }

    private class FakePort : ListenTogetherListenerWatchdogPort {
        var sessionState = ListenTogetherSessionState(
            baseUrl = "https://example.test", roomId = "room",
            connectionState = ListenTogetherConnectionState.CONNECTED
        )
        var room: ListenTogetherRoomState? = null
        var controller = false
        var pendingRepairVersion = -1L
        var lastMessageAtElapsedMs = 0L
        var refreshes = 0
        var memberRetries = 0
        var failRefresh = false
        val linkCauses = mutableListOf<String>()
        val appliedCauses = mutableListOf<String>()
        val failureReasons = mutableListOf<String>()

        override fun snapshot() = ListenTogetherListenerWatchdogSnapshot(
            sessionState, room, controller, pendingRepairVersion, lastMessageAtElapsedMs, 0L
        )
        override fun isControllerNow(): Boolean = controller
        override fun retryPendingMemberRequest(room: ListenTogetherRoomState?) {
            memberRetries++
        }
        override fun applyRoomStateToPlayer(room: ListenTogetherRoomState, cause: String, expectedPositionMs: Long) {
            appliedCauses += cause
        }
        override fun requestControllerLink(room: ListenTogetherRoomState, cause: String, force: Boolean) {
            linkCauses += cause
        }
        override suspend fun refreshRoomState(baseUrl: String, roomId: String) {
            refreshes++
            if (failRefresh) error("refresh unavailable")
        }
        override fun onRefreshFailure(error: Throwable, reason: String) {
            failureReasons += reason
        }
    }
}
