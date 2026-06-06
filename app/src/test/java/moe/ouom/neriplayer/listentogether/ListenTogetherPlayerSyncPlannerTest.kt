package moe.ouom.neriplayer.listentogether

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTogetherPlayerSyncPlannerTest {

    @Test
    fun `same track heartbeat does not force playlist reload`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = false,
                targetIndexChanged = false,
                desiredPlaying = true,
                localPlaying = true,
                localPlaybackAlreadyStarting = false,
                awaitingAuthoritativeStream = false,
                expectedPositionMs = 1_600L,
                localPositionMs = 1_000L,
                ignoreUnexpectedZeroPositionRollback = false,
                causeType = null,
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertFalse(plan.shouldReloadPlaylist)
        assertFalse(plan.shouldSeek)
        assertFalse(plan.shouldIssuePlay)
    }

    @Test
    fun `heartbeat drift above threshold still triggers seek without reload`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = false,
                targetIndexChanged = false,
                desiredPlaying = true,
                localPlaying = true,
                localPlaybackAlreadyStarting = false,
                awaitingAuthoritativeStream = false,
                expectedPositionMs = 6_200L,
                localPositionMs = 0L,
                ignoreUnexpectedZeroPositionRollback = false,
                causeType = "HEARTBEAT",
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertFalse(plan.shouldReloadPlaylist)
        assertTrue(plan.shouldSeek)
        assertFalse(plan.shouldIssuePlay)
    }

    @Test
    fun `paused track switch only forces pause after remote reload`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = true,
                targetIndexChanged = false,
                desiredPlaying = false,
                localPlaying = true,
                localPlaybackAlreadyStarting = false,
                awaitingAuthoritativeStream = false,
                expectedPositionMs = 0L,
                localPositionMs = 0L,
                ignoreUnexpectedZeroPositionRollback = false,
                causeType = "SET_TRACK",
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertTrue(plan.shouldReloadPlaylist)
        assertTrue(plan.shouldForcePauseAfterRemoteLoad)
        assertFalse(plan.shouldSeek)
        assertFalse(plan.shouldIssuePlay)
    }

    @Test
    fun `passive zero rollback keeps current progress`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = false,
                targetIndexChanged = false,
                desiredPlaying = true,
                localPlaying = true,
                localPlaybackAlreadyStarting = false,
                awaitingAuthoritativeStream = false,
                expectedPositionMs = 0L,
                localPositionMs = 9_200L,
                ignoreUnexpectedZeroPositionRollback = true,
                causeType = "HEARTBEAT",
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertEquals(9_200L, plan.effectiveExpectedPositionMs)
        assertFalse(plan.shouldSeek)
    }

    @Test
    fun `playlist reload waiting for link does not issue play again`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = true,
                targetIndexChanged = false,
                desiredPlaying = true,
                localPlaying = false,
                localPlaybackAlreadyStarting = false,
                awaitingAuthoritativeStream = true,
                expectedPositionMs = 0L,
                localPositionMs = 0L,
                ignoreUnexpectedZeroPositionRollback = false,
                causeType = "LINK_READY",
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertTrue(plan.shouldReloadPlaylist)
        assertFalse(plan.shouldIssuePlay)
    }
}
