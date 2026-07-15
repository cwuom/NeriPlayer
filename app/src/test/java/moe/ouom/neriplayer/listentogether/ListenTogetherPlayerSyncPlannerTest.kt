package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.playback.shouldReloadListenTogetherAuthoritativeStream
import moe.ouom.neriplayer.listentogether.playback.shouldWaitForListenTogetherAuthoritativeStreamPlayback
import moe.ouom.neriplayer.listentogether.playback.sync.ListenTogetherPlayerSyncContext
import moe.ouom.neriplayer.listentogether.playback.sync.resolveListenTogetherPlayerSyncPlan
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
        assertTrue(plan.shouldIssuePause)
    }

    @Test
    fun `paused room still cancels pending local playback start`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = false,
                targetIndexChanged = false,
                desiredPlaying = false,
                localPlaying = false,
                localPlaybackAlreadyStarting = true,
                awaitingAuthoritativeStream = false,
                expectedPositionMs = 0L,
                localPositionMs = 0L,
                ignoreUnexpectedZeroPositionRollback = false,
                causeType = "JOIN_AUTO_PAUSE",
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertTrue(plan.shouldIssuePause)
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
    fun `passive update during track switch grace does not hard seek`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = false,
                targetIndexChanged = false,
                desiredPlaying = true,
                localPlaying = true,
                localPlaybackAlreadyStarting = false,
                awaitingAuthoritativeStream = false,
                expectedPositionMs = 10_000L,
                localPositionMs = 0L,
                ignoreUnexpectedZeroPositionRollback = false,
                trackSwitchGracePeriodActive = true,
                causeType = "HEARTBEAT",
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertFalse(plan.shouldSeek)
        assertEquals(0L, plan.effectiveExpectedPositionMs)
    }

    @Test
    fun `active track switch still seeks during grace`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = true,
                targetIndexChanged = false,
                desiredPlaying = true,
                localPlaying = true,
                localPlaybackAlreadyStarting = false,
                awaitingAuthoritativeStream = false,
                expectedPositionMs = 1_200L,
                localPositionMs = 0L,
                ignoreUnexpectedZeroPositionRollback = false,
                trackSwitchGracePeriodActive = true,
                causeType = "SET_TRACK",
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertTrue(plan.shouldReloadPlaylist)
        assertTrue(plan.shouldSeek)
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

    @Test
    fun `link ready reload resumes playback after stream arrives`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = true,
                targetIndexChanged = false,
                desiredPlaying = true,
                localPlaying = false,
                localPlaybackAlreadyStarting = false,
                awaitingAuthoritativeStream = false,
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
        assertTrue(plan.shouldIssuePlay)
    }

    @Test
    fun `playing room reissues play when local start is stuck`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = false,
                targetIndexChanged = false,
                desiredPlaying = true,
                localPlaying = false,
                localPlaybackAlreadyStarting = true,
                awaitingAuthoritativeStream = false,
                expectedPositionMs = 6_000L,
                localPositionMs = 6_000L,
                ignoreUnexpectedZeroPositionRollback = false,
                causeType = "WATCHDOG",
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertTrue(plan.shouldIssuePlay)
    }

    @Test
    fun `playing room still waits for controller stream before reissuing play`() {
        val plan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = false,
                targetIndexChanged = false,
                desiredPlaying = true,
                localPlaying = false,
                localPlaybackAlreadyStarting = true,
                awaitingAuthoritativeStream = true,
                expectedPositionMs = 6_000L,
                localPositionMs = 6_000L,
                ignoreUnexpectedZeroPositionRollback = false,
                causeType = "WATCHDOG",
                trackSwitchForceSyncMs = 500L,
                heartbeatDriftForceSyncMs = 5_000L,
                playingDriftForceSyncMs = 2_500L,
                pausedDriftForceSyncMs = 800L
            )
        )

        assertFalse(plan.shouldIssuePlay)
    }

    @Test
    fun `authoritative stream reload follows actual resolved stream instead of track field`() {
        assertTrue(
            shouldReloadListenTogetherAuthoritativeStream(
                remoteStreamUrl = "https://upos-sz-mirrorcos.bilivideo.com/audio.m4s?deadline=200",
                localResolvedStreamUrl = "https://upos-sz-mirrorcos.bilivideo.com/audio.m4s?deadline=100"
            )
        )
    }

    @Test
    fun `authoritative stream reload is needed before listener receives a direct stream`() {
        assertTrue(
            shouldReloadListenTogetherAuthoritativeStream(
                remoteStreamUrl = "https://upos-sz-mirrorcos.bilivideo.com/audio.m4s?deadline=200",
                localResolvedStreamUrl = null
            )
        )
    }

    @Test
    fun `authoritative stream reload is skipped when resolved stream already matches`() {
        val url = "https://upos-sz-mirrorcos.bilivideo.com/audio.m4s?deadline=200"

        assertFalse(
            shouldReloadListenTogetherAuthoritativeStream(
                remoteStreamUrl = url,
                localResolvedStreamUrl = url
            )
        )
    }

    @Test
    fun `authoritative stream wait is cleared after listener has resolved media url`() {
        assertFalse(
            shouldWaitForListenTogetherAuthoritativeStreamPlayback(
                playerWaitingForAuthoritativeStream = true,
                localTrackMatchesTarget = true,
                localTrackStreamUrl = null,
                localResolvedStreamUrl = "https://upos-sz-mirrorcos.bilivideo.com/audio.m4s?deadline=100"
            )
        )
    }

    @Test
    fun `authoritative stream wait is cleared after listener has track stream url`() {
        assertFalse(
            shouldWaitForListenTogetherAuthoritativeStreamPlayback(
                playerWaitingForAuthoritativeStream = true,
                localTrackMatchesTarget = true,
                localTrackStreamUrl = "https://upos-sz-mirrorcos.bilivideo.com/audio.m4s?deadline=100",
                localResolvedStreamUrl = null
            )
        )
    }

    @Test
    fun `authoritative stream wait stays active before listener has any direct url`() {
        assertTrue(
            shouldWaitForListenTogetherAuthoritativeStreamPlayback(
                playerWaitingForAuthoritativeStream = true,
                localTrackMatchesTarget = true,
                localTrackStreamUrl = null,
                localResolvedStreamUrl = null
            )
        )
    }

    @Test
    fun `authoritative stream wait stays active when local url belongs to another track`() {
        assertTrue(
            shouldWaitForListenTogetherAuthoritativeStreamPlayback(
                playerWaitingForAuthoritativeStream = true,
                localTrackMatchesTarget = false,
                localTrackStreamUrl = null,
                localResolvedStreamUrl = "https://upos-sz-mirrorcos.bilivideo.com/audio.m4s?deadline=100"
            )
        )
    }
}
