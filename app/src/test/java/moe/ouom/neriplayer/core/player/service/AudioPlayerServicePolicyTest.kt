@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.core.player.service

import android.media.AudioManager
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.session.PlaybackState
import androidx.lifecycle.Lifecycle
import moe.ouom.neriplayer.activity.shouldProcessUsbDeviceAttachedAction
import moe.ouom.neriplayer.activity.usbDeviceAttachAliasComponentState
import moe.ouom.neriplayer.core.player.audio.focus.shouldPauseUsbExclusiveForFocusChange
import moe.ouom.neriplayer.core.player.audio.focus.shouldSuppressUsbExclusiveForFocusChange
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherPlaybackState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import kotlinx.coroutines.test.runTest
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
    fun `active transport is persisted before service stops when task is removed`() {
        val action = resolveTaskRemovedPlaybackAction(
            hasPlaybackSurfaceContent = true,
            playerTransportActive = true,
            listenTogetherRemotePlaying = false,
            hasItems = true,
        )

        assertTrue(action.stopPlaybackImmediately)
        assertTrue(action.persistPlaybackState)
        assertTrue(action.stopServiceAfterPersist)
        assertFalse(action.updateNotificationAfterPersist)
    }

    @Test
    fun `listen together transport is persisted before service stops when task is removed`() {
        val action = resolveTaskRemovedPlaybackAction(
            hasPlaybackSurfaceContent = true,
            playerTransportActive = false,
            listenTogetherRemotePlaying = true,
            hasItems = false,
        )

        assertTrue(action.stopPlaybackImmediately)
        assertTrue(action.persistPlaybackState)
        assertTrue(action.stopServiceAfterPersist)
        assertFalse(action.updateNotificationAfterPersist)
    }

    @Test
    fun `paused queue is persisted but service is retained when task is removed`() {
        val action = resolveTaskRemovedPlaybackAction(
            hasPlaybackSurfaceContent = true,
            playerTransportActive = false,
            listenTogetherRemotePlaying = false,
            hasItems = true,
        )

        assertFalse(action.stopPlaybackImmediately)
        assertTrue(action.persistPlaybackState)
        assertFalse(action.stopServiceAfterPersist)
        assertTrue(action.updateNotificationAfterPersist)
    }

    @Test
    fun `empty playback surface is ignored for task removal`() {
        val action = resolveTaskRemovedPlaybackAction(
            hasPlaybackSurfaceContent = false,
            playerTransportActive = true,
            listenTogetherRemotePlaying = true,
            hasItems = false,
        )

        assertFalse(action.stopPlaybackImmediately)
        assertFalse(action.persistPlaybackState)
        assertFalse(action.stopServiceAfterPersist)
        assertFalse(action.updateNotificationAfterPersist)
    }

    @Test
    fun `active task removal waits for persistence before stopping service`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = true,
                listenTogetherRemotePlaying = false,
                hasItems = true,
            ),
            callbacks = taskRemovedCallbacks(calls)
        )

        assertEquals(
            listOf(
                "stop_playback",
                "persist:task_removed",
                "stop_foreground:task_removed",
                "stop_self",
            ),
            calls
        )
    }

    @Test
    fun `task removal still persists and stops service after stop playback failure`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = true,
                listenTogetherRemotePlaying = false,
                hasItems = true,
            ),
            callbacks = taskRemovedCallbacks(
                calls = calls,
                stopPlaybackImmediately = {
                    calls += "stop_playback"
                    error("stop failed")
                }
            )
        )

        assertEquals(
            listOf(
                "stop_playback",
                "stop_error:IllegalStateException",
                "persist:task_removed",
                "stop_foreground:task_removed",
                "stop_self",
            ),
            calls
        )
    }

    @Test
    fun `inactive task removal persists before notification and keeps service`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = false,
                listenTogetherRemotePlaying = false,
                hasItems = true,
            ),
            callbacks = taskRemovedCallbacks(calls)
        )

        assertEquals(
            listOf(
                "persist:inactive_task_removed",
                "update_notification",
            ),
            calls
        )
    }

    @Test
    fun `empty task removal performs no playback side effects`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = false,
                playerTransportActive = true,
                listenTogetherRemotePlaying = true,
                hasItems = false,
            ),
            callbacks = taskRemovedCallbacks(calls)
        )

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `task removal stop predicate requires content and active transport`() {
        assertTrue(
            shouldStopPlaybackOnTaskRemoved(
                hasPlaybackSurfaceContent = true,
                transportActive = true,
            )
        )
        assertFalse(
            shouldStopPlaybackOnTaskRemoved(
                hasPlaybackSurfaceContent = true,
                transportActive = false,
            )
        )
        assertFalse(
            shouldStopPlaybackOnTaskRemoved(
                hasPlaybackSurfaceContent = false,
                transportActive = true,
            )
        )
    }

    @Test
    fun `remote listen together playback counts as active task removal transport`() {
        assertTrue(
            resolveTaskRemovedTransportActive(
                playerTransportActive = false,
                listenTogetherRemotePlaying = true,
            )
        )
        assertTrue(
            resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = false,
                listenTogetherRemotePlaying = true,
                hasItems = false,
            ).stopPlaybackImmediately
        )
    }

    @Test
    fun `active task removal keeps service when persistence fails`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = true,
                listenTogetherRemotePlaying = false,
                hasItems = true,
            ),
            callbacks = taskRemovedCallbacks(
                calls = calls,
                persistPlaybackState = { reason ->
                    calls += "persist:$reason"
                    false
                }
            )
        )

        assertEquals(
            listOf(
                "stop_playback",
                "persist:task_removed",
                "update_notification",
            ),
            calls
        )
    }

    private fun taskRemovedCallbacks(
        calls: MutableList<String>,
        stopPlaybackImmediately: () -> Unit = {
            calls += "stop_playback"
        },
        persistPlaybackState: suspend (String) -> Boolean = { reason ->
            calls += "persist:$reason"
            true
        },
    ): TaskRemovedPlaybackCallbacks {
        return TaskRemovedPlaybackCallbacks(
            stopPlaybackImmediately = stopPlaybackImmediately,
            persistPlaybackState = persistPlaybackState,
            stopForegroundIfStarted = { reason ->
                calls += "stop_foreground:$reason"
            },
            stopSelf = {
                calls += "stop_self"
            },
            updateNotification = {
                calls += "update_notification"
            },
            onPlaybackStopFailure = { error ->
                calls += "stop_error:${error::class.simpleName}"
            },
            onNotificationUpdateFailure = { error ->
                calls += "notification_error:${error::class.simpleName}"
            },
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
    fun `USB attach action is ignored when handling setting is off`() {
        assertFalse(
            shouldProcessUsbDeviceAttachedAction(
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                handlingEnabled = false
            )
        )
    }

    @Test
    fun `non USB attach action is still processed when handling setting is off`() {
        assertTrue(
            shouldProcessUsbDeviceAttachedAction(
                AudioManager.ACTION_AUDIO_BECOMING_NOISY,
                handlingEnabled = false
            )
        )
    }

    @Test
    fun `USB attach alias follows handling setting`() {
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            usbDeviceAttachAliasComponentState(handlingEnabled = true)
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            usbDeviceAttachAliasComponentState(handlingEnabled = false)
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
