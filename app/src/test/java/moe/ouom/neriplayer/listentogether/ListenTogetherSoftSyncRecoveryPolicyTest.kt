package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.playback.ListenTogetherSoftSyncRecheckAction
import moe.ouom.neriplayer.listentogether.playback.resolveListenTogetherSoftSyncRecheckAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTogetherSoftSyncRecoveryPolicyTest {

    @Test
    fun `soft sync resets as soon as positive drift enters the dead zone`() {
        assertEquals(
            ListenTogetherSoftSyncRecheckAction.RESET_RATE,
            action(signedDriftMs = 599L)
        )
    }

    @Test
    fun `soft sync resets as soon as negative drift enters the dead zone`() {
        assertEquals(
            ListenTogetherSoftSyncRecheckAction.RESET_RATE,
            action(signedDriftMs = -599L, currentRate = 0.95f)
        )
    }

    @Test
    fun `soft sync keeps correcting exactly at the configured dead zone boundary`() {
        assertEquals(
            ListenTogetherSoftSyncRecheckAction.KEEP_RATE,
            action(signedDriftMs = 600L)
        )
        assertEquals(
            ListenTogetherSoftSyncRecheckAction.KEEP_RATE,
            action(signedDriftMs = -600L, currentRate = 0.95f)
        )
    }

    @Test
    fun `soft sync escalates to a hard room-state correction at force threshold`() {
        assertEquals(
            ListenTogetherSoftSyncRecheckAction.FORCE_POSITION_SYNC,
            action(signedDriftMs = 2_500L)
        )
        assertEquals(
            ListenTogetherSoftSyncRecheckAction.FORCE_POSITION_SYNC,
            action(signedDriftMs = -2_500L, currentRate = 0.95f)
        )
    }

    @Test
    fun `soft sync never survives a role state track or connection transition`() {
        listOf(
            action(isController = true),
            action(desiredPlaying = false),
            action(localPlaying = false),
            action(currentTrackMatchesRoom = false),
            action(sessionConnected = false)
        ).forEach { action ->
            assertEquals(ListenTogetherSoftSyncRecheckAction.RESET_RATE, action)
        }
    }

    @Test
    fun `normal playback rate has no recheck work`() {
        assertEquals(
            ListenTogetherSoftSyncRecheckAction.NONE,
            action(currentRate = 1f)
        )
    }

    private fun action(
        currentRate: Float = 1.05f,
        sessionConnected: Boolean = true,
        isController: Boolean = false,
        desiredPlaying: Boolean = true,
        localPlaying: Boolean = true,
        currentTrackMatchesRoom: Boolean = true,
        signedDriftMs: Long = 1_000L
    ) = resolveListenTogetherSoftSyncRecheckAction(
        currentRate = currentRate,
        sessionConnected = sessionConnected,
        isController = isController,
        desiredPlaying = desiredPlaying,
        localPlaying = localPlaying,
        currentTrackMatchesRoom = currentTrackMatchesRoom,
        signedDriftMs = signedDriftMs,
        softSyncMinDriftMs = 600L,
        forcePositionSyncDriftMs = 2_500L
    )
}
