@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.core.player.service

import moe.ouom.neriplayer.core.player.service.artwork.coverReferenceFileName
import moe.ouom.neriplayer.core.player.service.artwork.isArtworkReadyForSource
import moe.ouom.neriplayer.core.player.service.artwork.isLocalCoverReference
import moe.ouom.neriplayer.core.player.service.artwork.resolveMetadataCoverSource
import moe.ouom.neriplayer.core.player.service.artwork.resolveMetadataCoverSourceWithRecovery
import moe.ouom.neriplayer.core.player.service.artwork.resolveRemoteMetadataArtworkUri
import moe.ouom.neriplayer.core.player.service.artwork.shouldAcceptArtworkLoadCallback
import moe.ouom.neriplayer.core.player.service.artwork.shouldAllowServiceRemoteCoverFallback
import moe.ouom.neriplayer.core.player.service.artwork.shouldCommitCoverSourceRecovery
import moe.ouom.neriplayer.core.player.service.artwork.shouldDeferArtworkRetryToCoverResolver
import moe.ouom.neriplayer.core.player.service.artwork.shouldRequestArtworkLoad
import moe.ouom.neriplayer.core.player.service.presentation.mediaSessionPlaybackActions
import moe.ouom.neriplayer.core.player.service.presentation.resolveListenTogetherMediaSessionPosition
import moe.ouom.neriplayer.core.player.service.usb.shouldReassertUsbExclusiveForegroundService
import moe.ouom.neriplayer.core.player.service.usb.usbExclusiveKeepAliveIntervalMs
import android.media.AudioManager
import android.media.session.PlaybackState
import androidx.lifecycle.Lifecycle
import moe.ouom.neriplayer.core.player.audio.focus.shouldPauseUsbExclusiveForFocusChange
import moe.ouom.neriplayer.core.player.audio.focus.shouldSuppressUsbExclusiveForFocusChange
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlayerServicePolicyTest {

    @Test
    fun `cover reference file name decodes SAF document ids safely`() {
        assertEquals(
            "netease - LIKPIA - 夏·烟火-cb470461.jpg",
            coverReferenceFileName(
                "content://com.android.externalstorage.documents/" +
                    "document/primary%3ACovers%2Fnetease%20-%20LIKPIA%20-%20" +
                    "%E5%A4%8F%C2%B7%E7%83%9F%E7%81%AB-cb470461.jpg"
            )
        )
        assertEquals("cover.jpg", coverReferenceFileName("/tmp/Covers/cover.jpg"))
        assertNull(coverReferenceFileName("content://covers/%2E%2E"))
        assertNull(coverReferenceFileName(null))
    }

    @Test
    fun `local cover reference classifier excludes network urls`() {
        assertTrue(isLocalCoverReference("content://covers/cover.jpg"))
        assertTrue(isLocalCoverReference("file:///tmp/cover.jpg"))
        assertTrue(isLocalCoverReference("/tmp/cover.jpg"))
        assertFalse(isLocalCoverReference("https://example.com/cover.jpg"))
        assertFalse(isLocalCoverReference(null))
    }

    @Test
    fun `cover recovery commits only for the expected song and source`() {
        assertTrue(
            shouldCommitCoverSourceRecovery(
                currentSongKey = "song-1",
                expectedSongKey = "song-1",
                currentCoverSource = "content://old/cover.jpg",
                expectedCoverSource = "content://old/cover.jpg"
            )
        )
        assertFalse(
            shouldCommitCoverSourceRecovery(
                currentSongKey = "song-2",
                expectedSongKey = "song-1",
                currentCoverSource = "content://old/cover.jpg",
                expectedCoverSource = "content://old/cover.jpg"
            )
        )
        assertFalse(
            shouldCommitCoverSourceRecovery(
                currentSongKey = "song-1",
                expectedSongKey = "song-1",
                currentCoverSource = "content://new/cover.jpg",
                expectedCoverSource = "content://old/cover.jpg"
            )
        )
    }

    @Test
    fun `retained artwork never suppresses a new source request`() {
        assertTrue(
            isArtworkReadyForSource(
                artworkPresent = true,
                artworkOwnerSongKey = "song-1",
                currentSongKey = "song-1",
                artworkSource = "content://covers/one.jpg",
                requestedSource = "content://covers/one.jpg"
            )
        )
        assertFalse(
            isArtworkReadyForSource(
                artworkPresent = true,
                artworkOwnerSongKey = "song-1",
                currentSongKey = "song-2",
                artworkSource = "content://covers/one.jpg",
                requestedSource = "content://covers/two.jpg"
            )
        )
        assertFalse(
            isArtworkReadyForSource(
                artworkPresent = true,
                artworkOwnerSongKey = "song-1",
                currentSongKey = "song-1",
                artworkSource = "content://covers/one.jpg",
                requestedSource = "content://covers/two.jpg"
            )
        )
    }

    @Test
    fun `artwork callbacks require the active request generation`() {
        assertTrue(
            shouldAcceptArtworkLoadCallback(
                requestGeneration = 4L,
                currentGeneration = 4L,
                inFlightGeneration = 4L,
                requestSource = "content://covers/song.jpg",
                inFlightSource = "content://covers/song.jpg",
                requestSongKey = "song",
                inFlightSongKey = "song"
            )
        )
        assertFalse(
            shouldAcceptArtworkLoadCallback(
                requestGeneration = 3L,
                currentGeneration = 4L,
                inFlightGeneration = 4L,
                requestSource = "content://covers/song.jpg",
                inFlightSource = "content://covers/song.jpg",
                requestSongKey = "song",
                inFlightSongKey = "song"
            )
        )
        assertFalse(
            shouldAcceptArtworkLoadCallback(
                requestGeneration = 4L,
                currentGeneration = 4L,
                inFlightGeneration = 4L,
                requestSource = "content://covers/song.jpg",
                inFlightSource = "content://covers/song.jpg",
                requestSongKey = "song-a",
                inFlightSongKey = "song-b"
            )
        )
    }

    @Test
    fun `local songs do not use remote cover fallback without an explicit custom cover`() {
        assertFalse(
            shouldAllowServiceRemoteCoverFallback(
                isLocalSong = true,
                hasExplicitCustomCover = false
            )
        )
        assertTrue(
            shouldAllowServiceRemoteCoverFallback(
                isLocalSong = true,
                hasExplicitCustomCover = true
            )
        )
        assertTrue(
            shouldAllowServiceRemoteCoverFallback(
                isLocalSong = false,
                hasExplicitCustomCover = false
            )
        )
    }

    @Test
    fun `cover recovery wins over a stale immediate source for the same song`() {
        assertEquals(
            "content://new-tree/Covers/song.jpg",
            resolveMetadataCoverSourceWithRecovery(
                songKey = "song",
                immediateCoverSource = "content://old-tree/Covers/song.jpg",
                retainedSongKey = "song",
                retainedCoverSource = "content://old-tree/Covers/song.jpg",
                recoverySongKey = "song",
                recoveryCoverSource = "content://new-tree/Covers/song.jpg",
                recoveryImmediateCoverSource = "content://old-tree/Covers/song.jpg"
            )
        )
        assertEquals(
            "content://newer-tree/Covers/song.jpg",
            resolveMetadataCoverSourceWithRecovery(
                songKey = "song",
                immediateCoverSource = "content://newer-tree/Covers/song.jpg",
                retainedSongKey = "song",
                retainedCoverSource = "content://old-tree/Covers/song.jpg",
                recoverySongKey = "song",
                recoveryCoverSource = "content://new-tree/Covers/song.jpg",
                recoveryImmediateCoverSource = "content://old-tree/Covers/song.jpg"
            )
        )
    }

    @Test
    fun `media session stop is downgraded to pause-only`() {
        assertFalse(
            shouldStopServiceForExternalPauseCommand(
                source = MEDIA_SESSION_STOP_SOURCE,
                stopServiceRequested = true,
            )
        )
    }

    @Test
    fun `explicit stop intent still tears down service`() {
        assertTrue(
            shouldStopServiceForExternalPauseCommand(
                source = "intent_stop",
                stopServiceRequested = true,
            )
        )
    }

    @Test
    fun `media session actions no longer advertise stop`() {
        val actions = mediaSessionPlaybackActions()

        assertEquals(0L, actions and PlaybackState.ACTION_STOP)
        assertTrue(actions and PlaybackState.ACTION_PLAY != 0L)
        assertTrue(actions and PlaybackState.ACTION_PAUSE != 0L)
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue(actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue(actions and PlaybackState.ACTION_SEEK_TO != 0L)
    }

    @Test
    fun `media session fallback wraps a single repeat room position`() {
        val roomState = ListenTogetherRoomState(
            roomId = "room-1",
            version = 1L,
            track = ListenTogetherTrack(
                stableKey = "netease:1",
                channelId = "netease",
                audioId = "1",
                name = "track",
                artist = "artist",
                durationMs = 60_000L
            ),
            playback = ListenTogetherPlaybackState(
                state = "playing",
                basePositionMs = 58_000L,
                baseTimestampMs = 1_000L,
                repeatMode = 1
            )
        )

        assertEquals(
            3_000L,
            resolveListenTogetherMediaSessionPosition(roomState, nowMs = 6_000L)
        )
    }

    @Test
    fun `usb exclusive audio focus changes never mute the independent DAC path`() {
        assertFalse(shouldSuppressUsbExclusiveForFocusChange(AudioManager.AUDIOFOCUS_GAIN))
        assertFalse(shouldSuppressUsbExclusiveForFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertFalse(shouldSuppressUsbExclusiveForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertFalse(
            shouldSuppressUsbExclusiveForFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            )
        )
    }

    @Test
    fun `audio focus loss does not pause the independent DAC path`() {
        assertFalse(shouldPauseUsbExclusiveForFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertFalse(
            shouldPauseUsbExclusiveForFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            )
        )
        assertFalse(
            shouldPauseUsbExclusiveForFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            )
        )
        assertFalse(shouldPauseUsbExclusiveForFocusChange(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `android o and above always use foreground service start`() {
        assertTrue(
            shouldUseForegroundServiceStart(
                sdkInt = 26,
                forceForeground = false,
                shouldRunPlaybackServiceInForeground = false,
                callerHasResumedUi = false
            )
        )
    }

    @Test
    fun `pre o can use background start when foreground is unnecessary`() {
        assertFalse(
            shouldUseForegroundServiceStart(
                sdkInt = 25,
                forceForeground = false,
                shouldRunPlaybackServiceInForeground = false,
                callerHasResumedUi = false
            )
        )
    }

    @Test
    fun `forced listen together sync uses foreground service when caller is not resumed`() {
        assertTrue(
            shouldUseForegroundServiceStart(
                sdkInt = 25,
                forceForeground = true,
                shouldRunPlaybackServiceInForeground = false,
                callerHasResumedUi = false
            )
        )
    }

    @Test
    fun `resumed activity caller avoids foreground service timer`() {
        assertFalse(
            shouldUseForegroundServiceStart(
                sdkInt = 36,
                forceForeground = true,
                shouldRunPlaybackServiceInForeground = true,
                callerHasResumedUi = true
            )
        )
    }

    @Test
    fun `widget action only starts in foreground when the playback service is unavailable`() {
        assertFalse(
            shouldStartPlaybackWidgetActionInForeground(
                serviceInstanceActive = true,
                serviceForegroundActive = true,
            ),
        )
        assertTrue(
            shouldStartPlaybackWidgetActionInForeground(
                serviceInstanceActive = false,
                serviceForegroundActive = false,
            ),
        )
        assertTrue(
            shouldStartPlaybackWidgetActionInForeground(
                serviceInstanceActive = true,
                serviceForegroundActive = false,
            ),
        )
    }

    @Test
    fun `widget accepts a state independent play pause command`() {
        assertTrue(isSupportedPlaybackWidgetAction(AudioPlayerService.ACTION_TOGGLE_PLAY_PAUSE))
        assertTrue(isSupportedPlaybackWidgetAction(AudioPlayerService.ACTION_RESTORE_VOLUME))
        assertFalse(isSupportedPlaybackWidgetAction("moe.ouom.neriplayer.action.UNKNOWN"))
    }

    @Test
    fun `USB exclusive background keepalive runs before the vendor freeze window`() {
        assertEquals(1_000L, usbExclusiveKeepAliveIntervalMs(appInForeground = false))
        assertEquals(5_000L, usbExclusiveKeepAliveIntervalMs(appInForeground = true))
    }

    @Test
    fun `only active background foreground service is reasserted`() {
        assertTrue(
            shouldReassertUsbExclusiveForegroundService(
                appInForeground = false,
                foregroundStarted = true,
                usbExclusivePlaybackActive = true
            )
        )
        assertFalse(
            shouldReassertUsbExclusiveForegroundService(
                appInForeground = true,
                foregroundStarted = true,
                usbExclusivePlaybackActive = true
            )
        )
        assertFalse(
            shouldReassertUsbExclusiveForegroundService(
                appInForeground = false,
                foregroundStarted = false,
                usbExclusivePlaybackActive = true
            )
        )
        assertFalse(
            shouldReassertUsbExclusiveForegroundService(
                appInForeground = false,
                foregroundStarted = true,
                usbExclusivePlaybackActive = false
            )
        )
    }

    @Test
    fun `only resumed activity can use direct playback service start`() {
        assertTrue(
            canUseDirectPlaybackServiceStart(
                isFinishing = false,
                isDestroyed = false,
                lifecycleState = Lifecycle.State.RESUMED,
                hasWindowFocus = true
            )
        )
        assertFalse(
            canUseDirectPlaybackServiceStart(
                isFinishing = false,
                isDestroyed = false,
                lifecycleState = Lifecycle.State.STARTED,
                hasWindowFocus = true
            )
        )
        assertFalse(
            canUseDirectPlaybackServiceStart(
                isFinishing = false,
                isDestroyed = false,
                lifecycleState = Lifecycle.State.RESUMED,
                hasWindowFocus = false
            )
        )
    }

    @Test
    fun `service start not allowed failure is downgraded`() {
        assertTrue(
            isServiceStartNotAllowedFailure(
                IllegalStateException("Not allowed to start service Intent { act=test }")
            )
        )
        assertFalse(
            isServiceStartNotAllowedFailure(IllegalStateException("different failure"))
        )
    }

    @Test
    fun `recent explicit sync start suppresses redundant app bootstrap start`() {
        assertTrue(
            shouldSkipRedundantSyncServiceStart(
                source = "app_bootstrap",
                lastSuccessfulSource = "external_audio_import",
                lastSuccessfulStartElapsedRealtime = 10_000L,
                nowElapsedRealtime = 10_800L
            )
        )
    }

    @Test
    fun `stale sync start does not suppress app bootstrap`() {
        assertFalse(
            shouldSkipRedundantSyncServiceStart(
                source = "app_bootstrap",
                lastSuccessfulSource = "external_audio_import",
                lastSuccessfulStartElapsedRealtime = 10_000L,
                nowElapsedRealtime = 12_000L
            )
        )
    }

    @Test
    fun `non bootstrap sources are never deduped by bootstrap policy`() {
        assertFalse(
            shouldSkipRedundantSyncServiceStart(
                source = "play_songs_and_open_now_playing",
                lastSuccessfulSource = "external_audio_import",
                lastSuccessfulStartElapsedRealtime = 10_000L,
                nowElapsedRealtime = 10_200L
            )
        )
    }

    @Test
    fun `local playback sync start is skipped only when service is already ready`() {
        assertTrue(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = "local_playback_command_play_playlist",
                serviceReady = true,
                hasItems = true
            )
        )
        assertFalse(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = "local_playback_command_play_playlist",
                serviceReady = false,
                hasItems = true
            )
        )
        assertFalse(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = "local_playback_command_play_playlist",
                serviceReady = true,
                hasItems = false
            )
        )
        assertFalse(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = "app_bootstrap",
                serviceReady = true,
                hasItems = true
            )
        )
    }

    @Test
    fun `local playback sync is kept alive during usb exclusive playback`() {
        assertFalse(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = "local_playback_command_play_playlist",
                serviceReady = true,
                hasItems = true,
                usbExclusivePlaybackActive = true
            )
        )
    }

    @Test
    fun `opening now playing from local playback is treated as local sync start only for local songs`() {
        assertTrue(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE,
                serviceReady = true,
                hasItems = true,
                hasLocalCurrentSong = true
            )
        )
        assertFalse(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE,
                serviceReady = true,
                hasItems = true,
                hasLocalCurrentSong = false
            )
        )
    }

    @Test
    fun `local playback action sync is skipped only after service is already tracking a song`() {
        assertTrue(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = "local_playback_command_play_playlist",
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = true
            )
        )
        assertFalse(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = "local_playback_command_play_playlist",
                foregroundStarted = false,
                hasItems = true,
                hasCurrentSong = true
            )
        )
        assertFalse(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = "local_playback_command_play_playlist",
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = false
            )
        )
        assertFalse(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = "app_bootstrap",
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `full local playback sync is not skipped during usb exclusive playback`() {
        assertFalse(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = "local_playback_command_play_playlist",
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = true,
                usbExclusivePlaybackActive = true
            )
        )
    }

    @Test
    fun `opening now playing from local playback skips full sync only when current song is local`() {
        assertTrue(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE,
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = true,
                hasLocalCurrentSong = true
            )
        )
        assertFalse(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE,
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = true,
                hasLocalCurrentSong = false
            )
        )
    }

    @Test
    fun `metadata cover source keeps deferred result for the same song`() {
        assertEquals(
            "content://covers/local.jpg",
            resolveMetadataCoverSource(
                songKey = "song-1",
                immediateCoverSource = null,
                retainedSongKey = "song-1",
                retainedCoverSource = "content://covers/local.jpg"
            )
        )
    }

    @Test
    fun `metadata cover source does not leak deferred result to another song`() {
        assertEquals(
            null,
            resolveMetadataCoverSource(
                songKey = "song-2",
                immediateCoverSource = null,
                retainedSongKey = "song-1",
                retainedCoverSource = "content://covers/local.jpg"
            )
        )
    }

    @Test
    fun `artwork load is retried after cooldown for the same cover`() {
        assertTrue(
            shouldRequestArtworkLoad(
                coverSource = "https://example.com/cover.jpg",
                artworkReady = false,
                inFlightCoverSource = null,
                lastFailedCoverSource = "https://example.com/cover.jpg",
                lastFailureAtElapsedRealtime = 1_000L,
                nowElapsedRealtime = 4_500L
            )
        )
    }

    @Test
    fun `artwork load is not retried while retry cooldown is still active`() {
        assertFalse(
            shouldRequestArtworkLoad(
                coverSource = "https://example.com/cover.jpg",
                artworkReady = false,
                inFlightCoverSource = null,
                lastFailedCoverSource = "https://example.com/cover.jpg",
                lastFailureAtElapsedRealtime = 1_000L,
                nowElapsedRealtime = 2_500L
            )
        )
    }

    @Test
    fun `artwork failure cooldown is isolated per song`() {
        assertTrue(
            shouldRequestArtworkLoad(
                coverSource = "https://example.com/shared.jpg",
                artworkReady = false,
                inFlightCoverSource = null,
                lastFailedCoverSource = "https://example.com/shared.jpg",
                lastFailureAtElapsedRealtime = 1_000L,
                nowElapsedRealtime = 2_000L,
                currentSongKey = "song-2",
                lastFailedSongKey = "song-1",
            )
        )
        assertFalse(
            shouldRequestArtworkLoad(
                coverSource = "https://example.com/shared.jpg",
                artworkReady = false,
                inFlightCoverSource = "https://example.com/shared.jpg",
                lastFailedCoverSource = null,
                lastFailureAtElapsedRealtime = -1L,
                nowElapsedRealtime = 2_000L,
                currentSongKey = "song-2",
                inFlightSongKey = "song-2",
            )
        )
    }

    @Test
    fun `local artwork retry waits for the SAF cover resolver`() {
        assertTrue(
            shouldDeferArtworkRetryToCoverResolver(
                isLocalCover = true,
                coverResolutionRequested = true
            )
        )
        assertFalse(
            shouldDeferArtworkRetryToCoverResolver(
                isLocalCover = true,
                coverResolutionRequested = false
            )
        )
        assertFalse(
            shouldDeferArtworkRetryToCoverResolver(
                isLocalCover = false,
                coverResolutionRequested = true
            )
        )
    }

    @Test
    fun `only remote cover uri is exposed to media metadata uri fields`() {
        assertEquals(
            "https://example.com/cover.jpg",
            resolveRemoteMetadataArtworkUri("https://example.com/cover.jpg")
        )
        assertEquals(
            null,
            resolveRemoteMetadataArtworkUri("content://covers/local.jpg")
        )
    }
}
