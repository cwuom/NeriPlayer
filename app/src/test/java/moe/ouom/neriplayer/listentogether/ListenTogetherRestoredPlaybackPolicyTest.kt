package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.playback.ListenTogetherRestoredPlaybackAction
import moe.ouom.neriplayer.listentogether.playback.resolveListenTogetherRestoredPlaybackAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTogetherRestoredPlaybackPolicyTest {

    @Test
    fun `active listener waits for authoritative room state instead of restoring local play`() {
        assertEquals(
            ListenTogetherRestoredPlaybackAction.WAIT_FOR_AUTHORITATIVE_ROOM_STATE,
            resolveListenTogetherRestoredPlaybackAction(
                restoredPlaybackRequested = true,
                listenTogetherSessionActive = true,
                currentUserIsController = false
            )
        )
    }

    @Test
    fun `controller keeps local restored playback authority`() {
        assertEquals(
            ListenTogetherRestoredPlaybackAction.RESUME_LOCAL_PLAYBACK,
            resolveListenTogetherRestoredPlaybackAction(
                restoredPlaybackRequested = true,
                listenTogetherSessionActive = true,
                currentUserIsController = true
            )
        )
    }

    @Test
    fun `ordinary playback keeps existing restore behavior`() {
        assertEquals(
            ListenTogetherRestoredPlaybackAction.RESUME_LOCAL_PLAYBACK,
            resolveListenTogetherRestoredPlaybackAction(
                restoredPlaybackRequested = true,
                listenTogetherSessionActive = false,
                currentUserIsController = false
            )
        )
    }

    @Test
    fun `no restored playback request does not start playback`() {
        assertEquals(
            ListenTogetherRestoredPlaybackAction.SKIP,
            resolveListenTogetherRestoredPlaybackAction(
                restoredPlaybackRequested = false,
                listenTogetherSessionActive = true,
                currentUserIsController = false
            )
        )
    }
}
