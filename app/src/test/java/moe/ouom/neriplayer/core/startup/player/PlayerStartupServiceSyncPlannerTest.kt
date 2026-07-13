package moe.ouom.neriplayer.core.startup.player

import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommand
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartupServiceSyncPlannerTest {
    @Test
    fun `local playback command source is delayed and skipped when service is already ready`() {
        val plan = PlayerStartupServiceSyncPlanner.planServiceStart(
            source = "local_playback_command_play",
            forceForeground = false,
            serviceReady = true,
            hasItems = true,
            hasLocalCurrentSong = false,
            usbExclusivePlaybackActive = false
        )

        assertFalse(plan.shouldStartService)
        assertTrue(plan.isLocalPlaybackCommand)
    }

    @Test
    fun `usb exclusive playback keeps local command service start active`() {
        val plan = PlayerStartupServiceSyncPlanner.planServiceStart(
            source = "local_playback_command_next",
            forceForeground = false,
            serviceReady = true,
            hasItems = true,
            hasLocalCurrentSong = false,
            usbExclusivePlaybackActive = true
        )

        assertTrue(plan.shouldStartService)
        assertTrue(plan.isLocalPlaybackCommand)
    }

    @Test
    fun `normal source always starts service`() {
        val plan = PlayerStartupServiceSyncPlanner.planServiceStart(
            source = "search_result_play_next",
            forceForeground = false,
            serviceReady = true,
            hasItems = true,
            hasLocalCurrentSong = false,
            usbExclusivePlaybackActive = false
        )

        assertTrue(plan.shouldStartService)
        assertFalse(plan.isLocalPlaybackCommand)
    }

    @Test
    fun `local playback command creates service start`() {
        val start = PlayerStartupServiceSyncPlanner.planLocalPlaybackCommand(
            command = PlaybackCommand(
                type = "NEXT",
                source = PlaybackCommandSource.LOCAL
            ),
            hasItems = true,
            shouldRunServiceInForeground = true
        )

        assertEquals(
            PlayerStartupServiceStart(
                source = "local_playback_command_next",
                forceForeground = true
            ),
            start
        )
    }

    @Test
    fun `remote playback command does not sync service`() {
        val start = PlayerStartupServiceSyncPlanner.planLocalPlaybackCommand(
            command = PlaybackCommand(
                type = "NEXT",
                source = PlaybackCommandSource.REMOTE_SYNC
            ),
            hasItems = true,
            shouldRunServiceInForeground = true
        )

        assertNull(start)
    }

    @Test
    fun `unsupported local playback command does not sync service`() {
        val start = PlayerStartupServiceSyncPlanner.planLocalPlaybackCommand(
            command = PlaybackCommand(
                type = "SEEK",
                source = PlaybackCommandSource.LOCAL
            ),
            hasItems = true,
            shouldRunServiceInForeground = true
        )

        assertNull(start)
    }

    @Test
    fun `empty queue local playback command does not sync service`() {
        val start = PlayerStartupServiceSyncPlanner.planLocalPlaybackCommand(
            command = PlaybackCommand(
                type = "PLAY",
                source = PlaybackCommandSource.LOCAL
            ),
            hasItems = false,
            shouldRunServiceInForeground = true
        )

        assertNull(start)
    }
}
