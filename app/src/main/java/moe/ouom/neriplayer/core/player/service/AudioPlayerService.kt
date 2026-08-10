package moe.ouom.neriplayer.core.player.service

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.player.service/AudioPlayerService
 * Updated: 2026/3/23
 */

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.activity.shouldProcessUsbDeviceAttachedAction
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.PlayerManager.externalBluetoothLyricLineFlow
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.lifecycle.recoverUsbExclusivePlaybackIfUnhealthy
import moe.ouom.neriplayer.core.player.lifecycle.scheduleUsbExclusivePlaybackResumeAfterDeviceAttach
import moe.ouom.neriplayer.core.player.lifecycle.stopPlaybackAfterUsbExclusiveNativeFailure
import moe.ouom.neriplayer.core.player.metadata.resolveExternalBluetoothMetadataText
import moe.ouom.neriplayer.core.player.metadata.shouldUseExternalBluetoothLyrics
import moe.ouom.neriplayer.core.player.persistence.preloadRestoredStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.policy.usb.shouldRunUsbExclusiveBackgroundAudioAnchor
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveKeepAliveProgress
import moe.ouom.neriplayer.core.player.policy.usb.evaluateUsbExclusiveKeepAliveProgress
import moe.ouom.neriplayer.core.player.timer.SleepTimerMode
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveWakeLock
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveBackgroundAudioAnchor
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemVolumeBridge
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics
import moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES
import moe.ouom.neriplayer.data.settings.PlaybackServiceIdleShutdownPreference
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.traffic.isOfflineModeNow
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.playback.expectedPositionMs
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.util.media.IsLandHelp
import moe.ouom.neriplayer.util.media.buildRemoteSongShareUrl
import moe.ouom.neriplayer.util.media.isShareablePublicHttpUrl
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.widget.playbackWidgetPresentationChanged
import moe.ouom.neriplayer.widget.playbackWidgetProgressRefreshBucket
import moe.ouom.neriplayer.widget.PlaybackWidgetState
import moe.ouom.neriplayer.widget.PlaybackWidgetUpdater
import moe.ouom.neriplayer.widget.buildPlaybackWidgetState
import moe.ouom.neriplayer.widget.shouldPartiallyUpdatePlaybackWidgetProgress

private suspend inline fun <T> kotlinx.coroutines.flow.Flow<T>.collectSafely(
    source: String,
    crossinline action: suspend (T) -> Unit
) {
    while (true) {
        try {
            collect { value ->
                try {
                    action(value)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NPLogger.e("NERI-APS", "$source collect handler failed", e)
                }
            }
            return
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            NPLogger.e("NERI-APS", "$source collect failed; restarting", e)
            delay(SERVICE_FLOW_COLLECTOR_RESTART_DELAY_MS)
        }
    }
}

private data class UsbExclusiveNativeServiceSignal(
    val opened: Boolean,
    val streaming: Boolean,
    val paused: Boolean,
    val transitioning: Boolean,
    val source: String,
    val handle: Long,
    val lastError: String?
)

private data class PlaybackNotificationSnapshot(
    val songKey: String?,
    val title: String,
    val text: String,
    val isTransportActive: Boolean,
    val isPlaybackControlPlaying: Boolean,
    val isFavorite: Boolean,
    val requiresInteractiveFavoriteConfirmation: Boolean,
    val largeIconReady: Boolean,
    val coverSource: String?,
    val statusBarLyricState: StatusBarLyricNotificationState,
    val floatingLyricsEnabled: Boolean,
)

private data class PlaybackMetadataSnapshot(
    val songKey: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val displayTitle: String,
    val displaySubtitle: String,
    val displayDescription: String?,
    val durationMs: Long,
    val coverSource: String?,
    val largeIconReady: Boolean,
)

private data class PendingStartCommand(
    val intent: Intent?,
    val flags: Int,
    val startId: Int,
)

internal const val MEDIA_SESSION_STOP_SOURCE = "media_session_stop"
internal const val PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE = "play_songs_and_open_now_playing"
private const val PLAYBACK_STATE_PROGRESS_BUCKET_MS = 2_000L
private const val MEDIA_ARTWORK_SIZE_PX = 512
private const val NOTIFICATION_ARTWORK_SIZE_PX = 256
private const val MEDIA_ARTWORK_MAX_RETRY_ATTEMPTS = 2
private const val MEDIA_ARTWORK_RETRY_COOLDOWN_MS = 3_000L
private const val SERVICE_FLOW_COLLECTOR_RESTART_DELAY_MS = 1_000L
private const val USB_EXCLUSIVE_FOREGROUND_KEEPALIVE_INTERVAL_MS = 5_000L
private const val USB_EXCLUSIVE_BACKGROUND_KEEPALIVE_INTERVAL_MS = 1_000L
private const val USB_EXCLUSIVE_KEEPALIVE_STALL_WARN_MS = 25_000L
private const val USB_EXCLUSIVE_KEEPALIVE_STALL_RECOVERY_TICKS = 1
private const val USB_EXCLUSIVE_KEEPALIVE_LOG_INTERVAL_TICKS = 3L

internal fun isLocalPlaybackCommandSyncSource(
    source: String,
    hasLocalCurrentSong: Boolean = false
): Boolean {
    return source.startsWith("local_playback_command_") ||
        (hasLocalCurrentSong && source == PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE)
}

internal fun shouldStopServiceForExternalPauseCommand(
    source: String,
    stopServiceRequested: Boolean,
): Boolean {
    // 系统外部控制面板的 stop 经常只是"结束本次会话", 不能把当前队列一并释放掉
    return stopServiceRequested && source != MEDIA_SESSION_STOP_SOURCE
}

internal fun shouldStopPlaybackOnTaskRemoved(
    hasPlaybackSurfaceContent: Boolean,
    transportActive: Boolean,
): Boolean {
    return hasPlaybackSurfaceContent && transportActive
}

internal fun mediaSessionPlaybackActions(): Long {
    return PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_SEEK_TO
}

internal fun shouldUseForegroundServiceStart(
    sdkInt: Int,
    forceForeground: Boolean,
    shouldRunPlaybackServiceInForeground: Boolean,
    callerHasResumedUi: Boolean
): Boolean {
    if (callerHasResumedUi) {
        return false
    }
    return sdkInt >= Build.VERSION_CODES.O ||
        forceForeground ||
        shouldRunPlaybackServiceInForeground
}

internal fun shouldStartPlaybackWidgetActionInForeground(
    serviceInstanceActive: Boolean,
    serviceForegroundActive: Boolean,
): Boolean {
    return !serviceInstanceActive || !serviceForegroundActive
}

internal fun isSupportedPlaybackWidgetAction(action: String): Boolean {
    return when (action) {
        AudioPlayerService.ACTION_PLAY,
        AudioPlayerService.ACTION_PAUSE,
        AudioPlayerService.ACTION_TOGGLE_PLAY_PAUSE,
        AudioPlayerService.ACTION_NEXT,
        AudioPlayerService.ACTION_PREV,
        AudioPlayerService.ACTION_TOGGLE_FAV,
        AudioPlayerService.ACTION_TOGGLE_FLOATING_LYRICS -> true
        else -> false
    }
}

internal fun usbExclusiveKeepAliveIntervalMs(appInForeground: Boolean): Long {
    return if (appInForeground) {
        USB_EXCLUSIVE_FOREGROUND_KEEPALIVE_INTERVAL_MS
    } else {
        USB_EXCLUSIVE_BACKGROUND_KEEPALIVE_INTERVAL_MS
    }
}

internal fun shouldReassertUsbExclusiveForegroundService(
    appInForeground: Boolean,
    foregroundStarted: Boolean,
    usbExclusivePlaybackActive: Boolean
): Boolean {
    return !appInForeground && foregroundStarted && usbExclusivePlaybackActive
}

private fun Intent.usbDeviceExtra(): UsbDevice? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}

internal fun canUseDirectPlaybackServiceStart(
    isFinishing: Boolean,
    isDestroyed: Boolean,
    lifecycleState: Lifecycle.State?,
    hasWindowFocus: Boolean
): Boolean {
    if (isFinishing || isDestroyed) {
        return false
    }
    return lifecycleState?.isAtLeast(Lifecycle.State.RESUMED) == true && hasWindowFocus
}

internal fun isServiceStartNotAllowedFailure(error: Throwable): Boolean {
    if (error !is IllegalStateException) {
        return false
    }
    val simpleName = error::class.java.simpleName
    if (
        simpleName == "BackgroundServiceStartNotAllowedException" ||
        simpleName == "ForegroundServiceStartNotAllowedException"
    ) {
        return true
    }
    return error.message?.contains("Not allowed to start service") == true
}

internal fun shouldSkipRedundantSyncServiceStart(
    source: String,
    lastSuccessfulSource: String?,
    lastSuccessfulStartElapsedRealtime: Long,
    nowElapsedRealtime: Long,
    dedupeWindowMs: Long = 1500L
): Boolean {
    if (source != "app_bootstrap") {
        return false
    }
    if (lastSuccessfulStartElapsedRealtime <= 0L) {
        return false
    }
    if (lastSuccessfulSource == null) {
        return false
    }
    val elapsed = nowElapsedRealtime - lastSuccessfulStartElapsedRealtime
    return elapsed in 0L..dedupeWindowMs
}

internal fun shouldSkipLocalPlaybackSyncServiceStart(
    source: String,
    serviceReady: Boolean,
    hasItems: Boolean,
    hasLocalCurrentSong: Boolean = false,
    usbExclusivePlaybackActive: Boolean = false
): Boolean {
    if (!isLocalPlaybackCommandSyncSource(source, hasLocalCurrentSong)) {
        return false
    }
    if (usbExclusivePlaybackActive) {
        return false
    }
    return serviceReady && hasItems
}

internal fun shouldSkipFullSyncForLocalPlaybackAction(
    source: String,
    foregroundStarted: Boolean,
    hasItems: Boolean,
    hasCurrentSong: Boolean,
    hasLocalCurrentSong: Boolean = false,
    usbExclusivePlaybackActive: Boolean = false
): Boolean {
    if (!isLocalPlaybackCommandSyncSource(source, hasLocalCurrentSong)) {
        return false
    }
    if (usbExclusivePlaybackActive) {
        return false
    }
    return foregroundStarted && hasItems && hasCurrentSong
}

internal fun resolveMetadataCoverSource(
    songKey: String?,
    immediateCoverSource: String?,
    retainedSongKey: String?,
    retainedCoverSource: String?
): String? {
    immediateCoverSource?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return retainedCoverSource?.takeIf {
        retainedSongKey == songKey && it.isNotBlank()
    }
}

internal fun shouldRequestArtworkLoad(
    coverSource: String?,
    artworkReady: Boolean,
    inFlightCoverSource: String?,
    lastFailedCoverSource: String?,
    lastFailureAtElapsedRealtime: Long,
    nowElapsedRealtime: Long,
    retryCooldownMs: Long = MEDIA_ARTWORK_RETRY_COOLDOWN_MS
): Boolean {
    val normalizedSource = coverSource?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if (artworkReady) {
        return false
    }
    if (inFlightCoverSource == normalizedSource) {
        return false
    }
    if (lastFailedCoverSource != normalizedSource || lastFailureAtElapsedRealtime <= 0L) {
        return true
    }
    val elapsed = nowElapsedRealtime - lastFailureAtElapsedRealtime
    return elapsed !in 0L..<retryCooldownMs
}

internal fun resolveRemoteMetadataArtworkUri(coverSource: String?): String? {
    val normalizedSource = coverSource?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return normalizedSource.takeIf {
        it.startsWith("http://", ignoreCase = true) ||
            it.startsWith("https://", ignoreCase = true)
    }
}

@SuppressLint("ObsoleteSdkInt")
private fun Context.findActivityReadyForDirectServiceStart(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            val isDestroyed: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 &&
                current.isDestroyed
            val lifecycleState = (current as? LifecycleOwner)?.lifecycle?.currentState
            return current.takeIf {
                canUseDirectPlaybackServiceStart(
                    isFinishing = it.isFinishing,
                    isDestroyed = isDestroyed,
                    lifecycleState = lifecycleState,
                    hasWindowFocus = it.hasWindowFocus()
                )
            }
        }
        current = current.baseContext
    }
    return null
}

@Suppress("unused")
class AudioPlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "moe.ouom.neriplayer.action.PLAY"
        const val ACTION_PAUSE = "moe.ouom.neriplayer.action.PAUSE"
        const val ACTION_TOGGLE_PLAY_PAUSE =
            "moe.ouom.neriplayer.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_STOP = "moe.ouom.neriplayer.action.STOP"
        const val ACTION_NEXT = "moe.ouom.neriplayer.action.NEXT"
        const val ACTION_PREV = "moe.ouom.neriplayer.action.PREV"
        const val ACTION_SYNC = "moe.ouom.neriplayer.action.SYNC"
        const val ACTION_TOGGLE_FAV = "moe.ouom.neriplayer.action.TOGGLE_FAVORITE"
        const val ACTION_TOGGLE_FLOATING_LYRICS =
            "moe.ouom.neriplayer.action.TOGGLE_FLOATING_LYRICS"
        private const val LEGACY_ACTION_HIDE_FLOATING_LYRICS =
            "moe.ouom.neriplayer.action.HIDE_FLOATING_LYRICS"
        const val EXTRA_START_SOURCE = "audio_service_start_source"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "neriplayer_playback_channel"
        private const val SYNC_START_DEDUPE_WINDOW_MS = 1500L
        @Volatile
        private var lastSuccessfulSyncStartElapsedRealtime: Long = 0L
        @Volatile
        private var lastSuccessfulSyncStartSource: String? = null
        @Volatile
        private var isServiceInstanceActive: Boolean = false
        @Volatile
        private var isServiceForegroundActive: Boolean = false
        @Volatile
        private var activeServiceInstance: AudioPlayerService? = null

        fun isReadyForPassiveLocalPlaybackSync(): Boolean {
            return isServiceInstanceActive && isServiceForegroundActive
        }

        fun isInstanceActiveForDiagnostics(): Boolean = isServiceInstanceActive

        fun isForegroundActiveForDiagnostics(): Boolean = isServiceForegroundActive

        internal fun refreshPlaybackWidgetsFromActiveService(reason: String): Boolean {
            val service = activeServiceInstance ?: return false
            NPLogger.d("NERI-APS", "Refreshing playback widgets from active service: reason=$reason")
            service.updatePlaybackWidget(force = true)
            return true
        }

        internal fun refreshPlaybackWidgetAfterSeekFromActiveService(reason: String): Boolean {
            val service = activeServiceInstance ?: return false
            NPLogger.d(
                "NERI-APS",
                "Refreshing playback widget after seek from active service: reason=$reason"
            )
            service.updatePlaybackWidget(force = true)
            return true
        }

        internal fun reassertForegroundForActiveUsbExclusivePlayback(reason: String) {
            activeServiceInstance?.requestUsbExclusiveBackgroundForegroundReassert(reason)
        }

        internal fun updateUsbExclusiveBackgroundAudioAnchor(reason: String) {
            activeServiceInstance?.updateUsbExclusiveBackgroundAudioAnchor(reason)
        }

        fun createSyncIntent(context: Context, source: String): Intent {
            return Intent(context, AudioPlayerService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_START_SOURCE, source)
            }
        }

        fun startSyncService(
            context: Context,
            source: String,
            forceForeground: Boolean = false
        ): Boolean {
            if (SafeModeManager.shouldEnterSafeMode(context)) {
                NPLogger.w("NERI-APS", "Skip sync service start while safe mode is active: source=$source")
                return false
            }
            val nowElapsedRealtime = SystemClock.elapsedRealtime()
            if (
                shouldSkipRedundantSyncServiceStart(
                    source = source,
                    lastSuccessfulSource = lastSuccessfulSyncStartSource,
                    lastSuccessfulStartElapsedRealtime = lastSuccessfulSyncStartElapsedRealtime,
                    nowElapsedRealtime = nowElapsedRealtime,
                    dedupeWindowMs = SYNC_START_DEDUPE_WINDOW_MS
                )
            ) {
                NPLogger.d(
                    "NERI-APS",
                    "Skip redundant sync start: source=$source lastSource=$lastSuccessfulSyncStartSource"
                )
                return true
            }
            val intent = createSyncIntent(context, source)
            val callerHasResumedUi = context.findActivityReadyForDirectServiceStart() != null
            val shouldStartInForeground = shouldUseForegroundServiceStart(
                sdkInt = Build.VERSION.SDK_INT,
                forceForeground = forceForeground,
                shouldRunPlaybackServiceInForeground = PlayerManager.shouldRunPlaybackServiceInForeground(),
                callerHasResumedUi = callerHasResumedUi
            )
            return try {
                if (shouldStartInForeground) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
                lastSuccessfulSyncStartElapsedRealtime = nowElapsedRealtime
                lastSuccessfulSyncStartSource = source
                true
            } catch (error: IllegalStateException) {
                if (!isServiceStartNotAllowedFailure(error)) {
                    throw error
                }
                NPLogger.w(
                    "NERI-APS",
                    "Deferred audio service start: source=$source foreground=$shouldStartInForeground resumedUi=$callerHasResumedUi",
                    error
                )
                false
            }
        }

        internal fun dispatchPlaybackWidgetAction(
            context: Context,
            action: String,
        ): Boolean {
            if (!isSupportedPlaybackWidgetAction(action)) {
                NPLogger.w("NERI-APS", "Ignoring unsupported playback widget action: $action")
                return false
            }
            if (SafeModeManager.shouldEnterSafeMode(context)) {
                NPLogger.w("NERI-APS", "Skipping playback widget action in safe mode: $action")
                return false
            }
            val intent = Intent(context, AudioPlayerService::class.java).apply {
                this.action = action
                putExtra(EXTRA_START_SOURCE, "app_widget")
            }
            val startInForeground = shouldStartPlaybackWidgetActionInForeground(
                serviceInstanceActive = isServiceInstanceActive,
                serviceForegroundActive = isServiceForegroundActive,
            )
            return try {
                if (startInForeground) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (error: IllegalStateException) {
                if (!isServiceStartNotAllowedFailure(error)) {
                    throw error
                }
                NPLogger.w(
                    "NERI-APS",
                    "Playback widget action could not start service: action=$action foreground=$startInForeground",
                    error,
                )
                false
            }
        }
    }

    private lateinit var becomingNoisyReceiver: BroadcastReceiver

    private val mediaSessionAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private lateinit var mediaSession: MediaSession
    private var mediaSessionUsesUsbExclusiveVolumeProvider = false
    private var usbExclusiveVolumeProvider: UsbExclusiveLockScreenVolumeProvider? = null

    private var currentCoverSongKey: String? = null
    private var currentCoverSource: String? = null
    private var currentMediaArtwork: Bitmap? = null
    private var currentNotificationLargeIcon: Bitmap? = null
    private var artworkLoadInFlightSource: String? = null
    private var artworkLoadJob: Job? = null
    private var lastArtworkLoadFailedSource: String? = null
    private var lastArtworkLoadFailedAtElapsedRealtime: Long = -1L
    private var artworkRetryJob: Job? = null
    private var artworkRetryAttemptCount: Int = 0
    private var coverResolutionInFlightSongKey: String? = null
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, throwable ->
            NPLogger.e("NERI-AudioService", "Uncaught coroutine exception in serviceScope", throwable)
        }
    )
    private val mediaSessionPlaybackStateThrottler = MediaSessionPlaybackStateThrottler()
    private var allowServiceRestart = true
    private var hasReceivedStartCommand = false
    private var isForegroundStarted = false
    private var lastNotificationSnapshot: PlaybackNotificationSnapshot? = null
    private var lastMetadataSnapshot: PlaybackMetadataSnapshot? = null
    private var lastPlaybackWidgetState: PlaybackWidgetState? = null
    private var statusBarLyricState = resolveStatusBarLyricNotificationState(
        enabled = false,
        line = null,
    )
    private var floatingLyricsEnabledForNotification = false
    private var usbDeviceAttachHandlingEnabled = true
    private var usbExclusiveKeepAliveJob: Job? = null
    private var usbExclusiveKeepAliveTick: Long = 0L
    private var lastUsbExclusiveKeepAliveAtMs: Long = 0L
    private var lastUsbExclusiveNativeHandle: Long = 0L
    private var lastUsbExclusiveCompletedFrames: Long = -1L
    private var lastUsbExclusiveSignalBytes: Long = -1L
    private var lastUsbExclusiveZeroFillBytes: Long = -1L
    private var lastUsbExclusiveOutputPeak: Float = Float.NaN
    private var usbExclusiveKeepAliveStallTicks: Int = 0
    private var playerInitializationJob: Job? = null
    private var playerRuntimeReady = false
    private val pendingStartCommands = ArrayDeque<PendingStartCommand>()
    private val pendingPlayerActions = ArrayDeque<() -> Unit>()
    private var latestStartId = 0
    private var keepPlayerRuntimeAfterServiceStop = false
    private var favoriteSongKeys: Set<String> = emptySet()
    private val idleShutdownCoordinator = PlaybackServiceIdleShutdownCoordinator(
        scope = serviceScope,
        delayMs = PlaybackServiceIdleShutdownPreference.delayMs(
            DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES
        ),
        isEligible = ::isEligibleForIdleShutdown,
        currentStartId = { latestStartId },
        onShutdown = ::stopIdlePlaybackService,
    )

    private fun shouldKeepServiceSticky(): Boolean {
        if (!playerRuntimeReady) return false
        val playbackSurfaceAvailable = hasPlaybackSurfaceContent()
        if (!playbackSurfaceAvailable) return false
        return shouldKeepPlaybackServiceSticky(
            playerRuntimeReady = true,
            hasPlaybackSurfaceContent = true,
            hasResumableQueue = PlayerManager.hasItems(),
            foregroundPlaybackRequired = PlayerManager.shouldRunPlaybackServiceInForeground(),
            listenTogetherSessionActive = isListenTogetherSessionActive(),
        )
    }

    private fun buildStateSummary(): String {
        return "hasItems=${PlayerManager.hasItems()} currentSong=${PlayerManager.currentSongFlow.value != null} " +
            "isPlaying=${PlayerManager.isPlayingFlow.value} " +
            "transportActive=${PlayerManager.isTransportActiveWithoutInitialization()} " +
            "listenTogetherActive=${isListenTogetherSessionActive()} foreground=$isForegroundStarted " +
            "runtimeReady=$playerRuntimeReady allowRestart=$allowServiceRestart"
    }

    private fun drainPendingStartCommands() {
        if (pendingStartCommands.isEmpty()) return
        val commands = pendingStartCommands.toList()
        pendingStartCommands.clear()
        commands.forEach { command ->
            onStartCommand(command.intent, command.flags, command.startId)
        }
    }

    private fun drainPendingPlayerActions() {
        if (pendingPlayerActions.isEmpty()) return
        val actions = pendingPlayerActions.toList()
        pendingPlayerActions.clear()
        actions.forEach { it() }
    }

    private fun runWhenPlayerRuntimeReady(source: String, action: () -> Unit) {
        if (playerRuntimeReady) {
            action()
            return
        }
        pendingPlayerActions.addLast(action)
        NPLogger.d("NERI-APS", "Deferring player action until runtime is ready: source=$source")
    }

    private fun refreshFavoriteSongKeys(): Boolean {
        val previousFavoriteSongKeys = favoriteSongKeys
        val updatedFavoriteSongKeys = if (PlayerManager.localPlaylistsReady) {
            PlayerManager.playlistsFlow.value
                .firstOrNull { FavoritesPlaylist.isSystemPlaylist(it, this) }
                ?.songs
                ?.mapTo(mutableSetOf()) { it.stableKey() }
                .orEmpty()
        } else {
            emptySet()
        }
        favoriteSongKeys = updatedFavoriteSongKeys
        return hasCurrentSongFavoriteStateChanged(
            currentSongKey = playbackSurfaceSong()?.stableKey(),
            previousFavoriteSongKeys = previousFavoriteSongKeys,
            updatedFavoriteSongKeys = updatedFavoriteSongKeys,
        )
    }

    private fun refreshIdleShutdown(reason: String) {
        NPLogger.d("NERI-APS", "Refresh idle shutdown: reason=$reason")
        idleShutdownCoordinator.refresh()
    }

    private fun stopIdlePlaybackService(scheduledStartId: Int) {
        NPLogger.i("NERI-APS", "Stopping idle playback service startId=$scheduledStartId")
        PlayerManager.scheduleStatePersist(
            positionMs = PlayerManager.playbackPositionFlow.value,
            shouldResumePlayback = false,
            debounceMs = 0L,
        )
        flushPlaybackStatsSafely("service_idle_shutdown", "idle shutdown")
        allowServiceRestart = false
        keepPlayerRuntimeAfterServiceStop = true
        stopForegroundIfStarted("idle_timeout")
        if (scheduledStartId > 0) {
            stopSelfResult(scheduledStartId)
        } else {
            stopSelf()
        }
    }

    private fun isEligibleForIdleShutdown(): Boolean {
        val playerInitialized = PlayerManager.initialized
        val nativeState = UsbExclusiveSessionController.state.value
        val usbSessionActiveOrTransitioning = nativeState.opened ||
            nativeState.streaming ||
            nativeState.transitioning ||
            UsbExclusiveSessionController.nativeCloseInFlightCount() > 0
        return shouldSchedulePlaybackServiceIdleShutdown(
            playerInitialized = playerInitialized,
            hasPlaybackSurfaceContent = hasPlaybackSurfaceContent(),
            transportActive = playerInitialized &&
                PlayerManager.isTransportActiveWithoutInitialization(),
            transportBuffering = playerInitialized && PlayerManager.isTransportBuffering(),
            listenTogetherSessionActive = isListenTogetherSessionActive(),
            usbSessionActiveOrTransitioning = usbSessionActiveOrTransitioning,
            sleepTimerActive = playerInitialized &&
                PlayerManager.sleepTimerManager.timerState.value.isActive,
        )
    }

    private fun isUsbExclusivePlaybackActiveForServiceKeepAlive(): Boolean {
        return PlayerManager.isUsbExclusivePlaybackActiveForForegroundService()
    }

    private fun updateUsbExclusiveBackgroundAudioAnchor(reason: String) {
        val shouldRun = shouldRunUsbExclusiveBackgroundAudioAnchor(
            appInForeground = PlayerManager.usbExclusiveAppInForeground,
            serviceForeground = isForegroundStarted,
            usbExclusivePlaybackActive = isUsbExclusivePlaybackActiveForServiceKeepAlive()
        )
        if (shouldRun) {
            UsbExclusiveBackgroundAudioAnchor.start(this, reason)
        } else {
            UsbExclusiveBackgroundAudioAnchor.stop(reason)
        }
    }

    private fun ensureUsbExclusiveKeepAliveLoop() {
        if (usbExclusiveKeepAliveJob?.isActive == true) return
        usbExclusiveKeepAliveJob = serviceScope.launch {
            NPLogger.i("NERI-APS", "USB exclusive keepalive started")
            while (true) {
                delay(usbExclusiveKeepAliveIntervalMs(PlayerManager.usbExclusiveAppInForeground))
                if (!isUsbExclusivePlaybackActiveForServiceKeepAlive()) {
                    NPLogger.i("NERI-APS", "USB exclusive keepalive stopped because playback is inactive")
                    usbExclusiveKeepAliveTick = 0L
                    lastUsbExclusiveKeepAliveAtMs = 0L
                    lastUsbExclusiveNativeHandle = 0L
                    lastUsbExclusiveCompletedFrames = -1L
                    usbExclusiveKeepAliveStallTicks = 0
                    usbExclusiveKeepAliveJob = null
                    return@launch
                }
                runUsbExclusiveKeepAliveTick()
            }
        }
    }

    private fun runUsbExclusiveKeepAliveTick() {
        val nowMs = SystemClock.elapsedRealtime()
        val gapMs = if (lastUsbExclusiveKeepAliveAtMs > 0L) {
            nowMs - lastUsbExclusiveKeepAliveAtMs
        } else {
            0L
        }
        usbExclusiveKeepAliveTick += 1L
        lastUsbExclusiveKeepAliveAtMs = nowMs

        val foregroundReasserted = if (
            shouldReassertUsbExclusiveForegroundService(
                appInForeground = PlayerManager.usbExclusiveAppInForeground,
                foregroundStarted = isForegroundStarted,
                usbExclusivePlaybackActive = isUsbExclusivePlaybackActiveForServiceKeepAlive()
            )
        ) {
            reassertForegroundForUsbExclusiveBackground("usb_keepalive")
        } else {
            false
        }
        if (!ensureForegroundStarted()) {
            handleForegroundPromotionFailure("usb_keepalive")
            return
        }
        updateUsbExclusiveBackgroundAudioAnchor("usb_keepalive")

        UsbExclusiveSessionController.refresh(this)
        UsbExclusiveSessionController.maintainWakeLock(this, "service_keepalive")
        updatePlaybackState(force = true)
        updateNotification()

        val nativeState = UsbExclusiveSessionController.state.value
        val pathState = UsbExclusiveAudioPathTracker.state.value
        val levelLine = "pcm=${nativeState.pcmLevelBytes}/${nativeState.pcmCapacityBytes} " +
            "free=${nativeState.pcmFreeBytes} backpressureCurrentMs=${nativeState.pcmBackpressureCurrentMs}"
        val signalLine = "signalFrames=${nativeState.playerSignalFrames} " +
            "silentFrames=${nativeState.playerSilentFrames} " +
            "zeroFillBytes=${nativeState.playerZeroFillBytes} " +
            "peak=${nativeState.lastOutputPeak} " +
            "channelPeaks=${nativeState.lastChannel0OutputPeak}/" +
            nativeState.lastChannel1OutputPeak
        val message = "USB exclusive keepalive tick=$usbExclusiveKeepAliveTick gapMs=$gapMs " +
            "path=${pathState.effectivePath} native=${nativeState.source}/${nativeState.streaming} " +
            "foregroundReasserted=$foregroundReasserted wakeLock=${UsbExclusiveWakeLock.isHeld()} " +
            "audioAnchor=${UsbExclusiveBackgroundAudioAnchor.diagnosticSummary()} " +
            "completedFrames=${nativeState.completedAudioFrames} " +
            "$levelLine $signalLine"
        if (gapMs > USB_EXCLUSIVE_KEEPALIVE_STALL_WARN_MS) {
            NPLogger.w("NERI-APS", "$message possible_background_freeze=true")
        } else if (usbExclusiveKeepAliveTick % USB_EXCLUSIVE_KEEPALIVE_LOG_INTERVAL_TICKS == 0L) {
            NPLogger.i("NERI-APS", message)
        }
        recoverUsbExclusivePlaybackIfKeepAliveStalled(
            nativeHandle = nativeState.handle,
            completedFrames = nativeState.completedAudioFrames,
            diagnosticMessage = message
        )
    }

    private fun recoverUsbExclusivePlaybackIfKeepAliveStalled(
        nativeHandle: Long,
        completedFrames: Long,
        diagnosticMessage: String
    ) {
        val pathState = UsbExclusiveAudioPathTracker.state.value
        val nativeState = UsbExclusiveSessionController.state.value
        val metrics = nativeState.runtimeReport.usbRuntimeMetrics()
        val nativePlaybackExpected = PlayerManager.usbExclusivePlaybackEnabled &&
            PlayerManager.isTransportActiveWithoutInitialization() &&
            pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB &&
            pathState.sinkPlaying &&
            nativeState.source == "player_pcm"
        val transportStoppedUnexpectedly = nativePlaybackExpected &&
            nativeState.opened &&
            !nativeState.streaming &&
            !nativeState.paused &&
            !nativeState.transitioning &&
            metrics.transportFailed == true
        if (transportStoppedUnexpectedly) {
            usbExclusiveKeepAliveStallTicks = 0
            NPLogger.w(
                "NERI-APS",
                "USB exclusive keepalive found stopped failed transport; scheduling recovery. " +
                    diagnosticMessage
            )
            PlayerManager.recoverUsbExclusivePlaybackIfUnhealthy(
                reason = "service_keepalive_transport_stopped",
                forceRecovery = true
            )
            return
        }
        val shouldCheckStall = nativePlaybackExpected && nativeState.streaming
        if (!shouldCheckStall) {
            lastUsbExclusiveNativeHandle = nativeHandle
            lastUsbExclusiveCompletedFrames = completedFrames
            lastUsbExclusiveSignalBytes = nativeState.playerSignalBytes
            lastUsbExclusiveZeroFillBytes = nativeState.playerZeroFillBytes
            lastUsbExclusiveOutputPeak = nativeState.lastOutputPeak
            usbExclusiveKeepAliveStallTicks = 0
            return
        }
        val decision = evaluateUsbExclusiveKeepAliveProgress(
            previousHandle = lastUsbExclusiveNativeHandle,
            currentHandle = nativeHandle,
            previousCompletedFrames = lastUsbExclusiveCompletedFrames,
            currentCompletedFrames = completedFrames,
            previousSignalBytes = lastUsbExclusiveSignalBytes,
            currentSignalBytes = nativeState.playerSignalBytes,
            previousZeroFillBytes = lastUsbExclusiveZeroFillBytes,
            currentZeroFillBytes = nativeState.playerZeroFillBytes,
            previousOutputPeak = lastUsbExclusiveOutputPeak,
            currentOutputPeak = nativeState.lastOutputPeak,
            outputSampleRate = metrics.sampleRate ?: 0,
            outputFrameBytes = metrics.outputFrameBytes ?: 0,
            currentPcmLevelBytes = metrics.pcmLevelBytes ?: -1L,
            previousStallTicks = usbExclusiveKeepAliveStallTicks,
            recoveryTicks = USB_EXCLUSIVE_KEEPALIVE_STALL_RECOVERY_TICKS
        )
        if (decision.progress == UsbExclusiveKeepAliveProgress.COUNTER_RESET) {
            NPLogger.i(
                "NERI-APS",
                "USB exclusive keepalive reset frame baseline after native counter reset: " +
                    "handle=$nativeHandle previous=$lastUsbExclusiveCompletedFrames current=$completedFrames"
            )
        }
        lastUsbExclusiveNativeHandle = nativeHandle
        lastUsbExclusiveCompletedFrames = completedFrames
        lastUsbExclusiveSignalBytes = nativeState.playerSignalBytes
        lastUsbExclusiveZeroFillBytes = nativeState.playerZeroFillBytes
        lastUsbExclusiveOutputPeak = nativeState.lastOutputPeak
        usbExclusiveKeepAliveStallTicks = decision.stallTicks
        if (!decision.shouldRecover) return
        usbExclusiveKeepAliveStallTicks = 0
        NPLogger.w(
            "NERI-APS",
            "USB exclusive keepalive detected stalled native frames; scheduling recovery. $diagnosticMessage"
        )
        PlayerManager.recoverUsbExclusivePlaybackIfUnhealthy(
            reason = "service_keepalive_stalled",
            forceRecovery = true
        )
    }

    private fun updateUsbExclusiveServiceKeepAlive(reason: String) {
        if (isUsbExclusivePlaybackActiveForServiceKeepAlive()) {
            updateUsbExclusiveBackgroundAudioAnchor(reason)
            ensureUsbExclusiveKeepAliveLoop()
            return
        }
        UsbExclusiveBackgroundAudioAnchor.stop("inactive:$reason")
        usbExclusiveKeepAliveJob?.cancel()
        usbExclusiveKeepAliveJob = null
        usbExclusiveKeepAliveTick = 0L
        lastUsbExclusiveKeepAliveAtMs = 0L
        lastUsbExclusiveNativeHandle = 0L
        lastUsbExclusiveCompletedFrames = -1L
        usbExclusiveKeepAliveStallTicks = 0
        NPLogger.d("NERI-APS", "USB exclusive keepalive idle reason=$reason")
    }

    private fun requestUsbExclusiveBackgroundForegroundReassert(reason: String) {
        serviceScope.launch {
            val usbPlaybackActive = isUsbExclusivePlaybackActiveForServiceKeepAlive()
            if (PlayerManager.usbExclusiveAppInForeground || !usbPlaybackActive) {
                return@launch
            }
            val foregroundReady = if (isForegroundStarted) {
                reassertForegroundForUsbExclusiveBackground("usb_background_transition:$reason")
            } else {
                ensureForegroundStarted()
            }
            if (!foregroundReady) {
                NPLogger.w(
                    "NERI-APS",
                    "USB exclusive background foreground reassert failed: reason=$reason"
                )
                return@launch
            }
            updateUsbExclusiveBackgroundAudioAnchor("usb_background_transition:$reason")
            usbExclusiveKeepAliveJob?.cancel()
            usbExclusiveKeepAliveJob = null
            lastUsbExclusiveKeepAliveAtMs = 0L
            ensureUsbExclusiveKeepAliveLoop()
            runUsbExclusiveKeepAliveTick()
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            runWhenPlayerRuntimeReady("media_session_play") {
                keepPlayerRuntimeAfterServiceStop = false
                PlayerManager.play()
                updateAll()
                refreshIdleShutdown("media_session_play")
            }
        }
        override fun onPause() {
            runWhenPlayerRuntimeReady("media_session_pause") {
                handleExternalPauseCommand("media_session_pause")
            }
        }
        override fun onSkipToNext() {
            runWhenPlayerRuntimeReady("media_session_next") {
                PlayerManager.next()
                updateAll()
            }
        }
        override fun onSkipToPrevious() {
            runWhenPlayerRuntimeReady("media_session_previous") {
                PlayerManager.previous()
                updateAll()
            }
        }
        override fun onStop() {
            runWhenPlayerRuntimeReady("media_session_stop") {
                handleExternalPauseCommand(MEDIA_SESSION_STOP_SOURCE, stopService = true)
            }
        }
        override fun onSeekTo(pos: Long) {
            runWhenPlayerRuntimeReady("media_session_seek") {
                PlayerManager.seekTo(pos)
                updatePlaybackState(force = true)
                updateNotification()
                updatePlaybackWidget(force = true)
            }
        }
        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACTION_TOGGLE_FAV -> {
                    runWhenPlayerRuntimeReady("media_session_favorite") {
                        if (canToggleFavoriteFromExternalSurface(PlayerManager.currentSongFlow.value)) {
                            PlayerManager.toggleCurrentFavorite()
                        }
                        updateAll()
                    }
                }
                ACTION_TOGGLE_FLOATING_LYRICS -> {
                    runWhenPlayerRuntimeReady("media_session_toggle_floating_lyrics") {
                        applyFloatingLyricsExternalAction(legacyHideAction = false)
                        updateAll()
                    }
                }
                LEGACY_ACTION_HIDE_FLOATING_LYRICS -> {
                    runWhenPlayerRuntimeReady("media_session_legacy_hide_floating_lyrics") {
                        applyFloatingLyricsExternalAction(legacyHideAction = true)
                        updateAll()
                    }
                }
            }
        }
    }

    private fun dispatchMediaButtonIntent(intent: Intent?) {
        val mediaButtonIntent = intent ?: return
        if (mediaButtonIntent.action != Intent.ACTION_MEDIA_BUTTON) return
        val keyEvent = IntentCompat.getParcelableExtra(
            mediaButtonIntent,
            Intent.EXTRA_KEY_EVENT,
            KeyEvent::class.java
        ) ?: return
        mediaSession.controller.dispatchMediaButtonEvent(keyEvent)
    }

    private fun updateMediaSessionVolumeRouting(pathState: UsbExclusiveAudioPathState) {
        if (
            shouldUseUsbExclusiveRemoteVolumeRouting(
                effectivePath = pathState.effectivePath,
                bitPerfect = PlayerManager.usbExclusivePreferences.bitPerfect
            )
        ) {
            if (mediaSessionUsesUsbExclusiveVolumeProvider) return
            enableUsbExclusiveMediaSessionVolumeRouting()
        } else {
            disableUsbExclusiveMediaSessionVolumeRouting("path=${pathState.effectivePath}")
        }
    }

    private fun enableUsbExclusiveMediaSessionVolumeRouting() {
        val provider = createUsbExclusiveVolumeProvider()
        runCatching {
            mediaSession.setPlaybackToRemote(provider)
            usbExclusiveVolumeProvider = provider
            mediaSessionUsesUsbExclusiveVolumeProvider = true
            UsbExclusiveSystemVolumeBridge.updateSessionVolumeFraction(
                usbExclusiveVolumeFractionFromProviderIndex(
                    providerIndex = provider.currentVolume,
                    providerMaxIndex = provider.maxVolume
                )
            )
            NPLogger.i("NERI-APS", "USB exclusive MediaSession volume routing enabled")
        }.onFailure { error ->
            mediaSessionUsesUsbExclusiveVolumeProvider = false
            usbExclusiveVolumeProvider = null
            UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
            runCatching {
                mediaSession.setPlaybackToLocal(mediaSessionAudioAttributes)
            }
            NPLogger.w("NERI-APS", "USB exclusive MediaSession volume routing failed", error)
        }
    }

    private fun disableUsbExclusiveMediaSessionVolumeRouting(reason: String) {
        val wasRemote = mediaSessionUsesUsbExclusiveVolumeProvider
        mediaSessionUsesUsbExclusiveVolumeProvider = false
        usbExclusiveVolumeProvider = null
        if (wasRemote && this::mediaSession.isInitialized) {
            runCatching {
                mediaSession.setPlaybackToLocal(mediaSessionAudioAttributes)
            }.onFailure { error ->
                NPLogger.w(
                    "NERI-APS",
                    "USB exclusive MediaSession volume routing reset failed: reason=$reason",
                    error
                )
            }
        }
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        if (wasRemote) {
            NPLogger.i(
                "NERI-APS",
                "USB exclusive MediaSession volume routing disabled: reason=$reason"
            )
        }
    }

    private fun createUsbExclusiveVolumeProvider(): UsbExclusiveLockScreenVolumeProvider {
        val streamVolume = runCatching {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val minVolume = audioManager?.getStreamMinVolume(AudioManager.STREAM_MUSIC) ?: 0
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 100
            val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: maxVolume
            Triple(minVolume, maxVolume, currentVolume)
        }.getOrElse { error ->
            NPLogger.w("NERI-APS", "failed to read media volume for USB volume routing", error)
            Triple(0, 100, 100)
        }
        val providerMaxIndex = usbExclusiveVolumeProviderMaxIndex(
            minVolume = streamVolume.first,
            maxVolume = streamVolume.second
        )
        val providerCurrentIndex = usbExclusiveVolumeProviderCurrentIndex(
            currentVolume = streamVolume.third,
            minVolume = streamVolume.first,
            maxVolume = streamVolume.second
        )
        return UsbExclusiveLockScreenVolumeProvider(
            maxVolume = providerMaxIndex,
            initialVolume = providerCurrentIndex,
            onVolumeFractionChanged = UsbExclusiveSystemVolumeBridge::updateSessionVolumeFraction
        )
    }

    private fun handleExternalPauseCommand(source: String, stopService: Boolean = false) {
        NPLogger.d("NERI-APS", "Received external pause command: source=$source")
        if (PlayerManager.shouldIgnoreExternalPauseCommand(source)) {
            NPLogger.w(
                "NERI-APS",
                "Ignored guarded external pause command: source=$source"
            )
            updatePlaybackState(force = true)
            updateNotification()
            updatePlaybackWidget(force = true)
            return
        }
        PlayerManager.pause()
        updateAll()
        refreshIdleShutdown("external_pause:$source")
        val shouldStopService = shouldStopServiceForExternalPauseCommand(source, stopService)
        if (stopService && !shouldStopService) {
            NPLogger.w("NERI-APS", "Treating external stop as pause-only: source=$source")
        }
        if (shouldStopService) {
            allowServiceRestart = false
            stopForegroundIfStarted("external_pause_command:$source")
            stopSelf()
        }
    }

    private fun isFloatingLyricsCurrentlyEnabled(): Boolean {
        return isFloatingLyricsEffectivelyEnabled(
            enabled = floatingLyricsEnabledForNotification,
        )
    }

    private fun applyFloatingLyricsExternalAction(legacyHideAction: Boolean) {
        val targetEnabled = resolveFloatingLyricsExternalTargetEnabled(
            currentEnabled = isFloatingLyricsCurrentlyEnabled(),
            legacyHideAction = legacyHideAction,
        )
        serviceScope.launch {
            runCatching {
                AppContainer.settingsRepo.setFloatingLyricsEnabled(targetEnabled)
            }.onFailure { error ->
                NPLogger.e(
                    "NERI-APS",
                    "Failed to persist floating lyrics toggle from external surface",
                    error
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (SafeModeManager.shouldEnterSafeMode(this)) {
            isServiceInstanceActive = false
            allowServiceRestart = false
            NPLogger.w("NERI-APS", "onCreate ignored because safe mode is active")
            // 即使经 startForegroundService / START_STICKY 拉起,也必须先满足 FGS 5s 契约再退出
            startForegroundForSafeModeThenStop("safe_mode_create")
            return
        }
        isServiceInstanceActive = true
        activeServiceInstance = this
        NPLogger.d("NERI-APS", "onCreate begin ${buildStateSummary()}")
        ensurePlaybackNotificationChannel()

        mediaSession = MediaSession(this, "NeriPlayerSession").apply {
            setCallback(mediaSessionCallback)
            setPlaybackToLocal(mediaSessionAudioAttributes)
            isActive = true
        }
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        if (!startForegroundImmediately(buildBootstrapNotification(), "service_create")) {
            handleForegroundPromotionFailure("service_create")
            return
        }

        initializePlayerRuntime()
    }

    private fun initializePlayerRuntime() {
        if (PlayerManager.initialized) {
            finishPlayerRuntimeSetup()
            return
        }
        playerInitializationJob?.cancel()
        playerInitializationJob = serviceScope.launch {
            awaitConcurrentPlayerInitialization()
            if (PlayerManager.initialized) {
                finishPlayerRuntimeSetup()
                return@launch
            }
            val app = application as Application
            val playbackPreferences = withContext(Dispatchers.IO) {
                readPlaybackPreferenceSnapshot(app)
            }
            val restoredStateSnapshot = preloadRestoredStateSnapshot(
                app = app,
                keepLastPlaybackProgressEnabled = playbackPreferences.keepLastPlaybackProgress,
                keepPlaybackModeStateEnabled = playbackPreferences.keepPlaybackModeState,
            )
            PlayerManager.initializePreloaded(
                app = app,
                startupPlaybackPreferences = playbackPreferences,
                restoredStateSnapshot = restoredStateSnapshot,
            )
            awaitConcurrentPlayerInitialization()
            if (!PlayerManager.initialized) {
                NPLogger.e("NERI-APS", "Player runtime initialization failed")
                handleForegroundPromotionFailure("player_initialize")
                return@launch
            }
            finishPlayerRuntimeSetup()
        }
    }

    private suspend fun awaitConcurrentPlayerInitialization() {
        repeat(400) {
            if (!PlayerManager.initializationInProgress) return
            delay(25L)
        }
    }

    private fun finishPlayerRuntimeSetup() {
        if (playerRuntimeReady) return
        playerRuntimeReady = true
        refreshFavoriteSongKeys()

        serviceScope.launch {
            AppContainer.settingsRepo.playbackServiceIdleShutdownMinutesFlow
                .collectSafely("playbackServiceIdleShutdownMinutesFlow") { minutes ->
                    val delayMs = PlaybackServiceIdleShutdownPreference.delayMs(minutes)
                    NPLogger.i(
                        "NERI-APS",
                        "Playback service idle shutdown updated: minutes=$minutes delayMs=$delayMs"
                    )
                    idleShutdownCoordinator.updateDelayMs(delayMs)
                }
        }
        serviceScope.launch {
            AppContainer.settingsRepo.usbDeviceAttachHandlingEnabledFlow
                .collectSafely("usbDeviceAttachHandlingEnabledFlow") { enabled ->
                    usbDeviceAttachHandlingEnabled = enabled
                }
        }
        serviceScope.launch {
            PlayerManager.currentSongFlow.collectSafely("currentSongFlow") {
                if (it == null && !hasPlaybackSurfaceContent()) {
                    if (!hasReceivedStartCommand || pendingStartCommands.isNotEmpty()) {
                        return@collectSafely
                    }
                    NPLogger.w("NERI-APS", "currentSongFlow requested self-stop because playback surface is empty")
                    stopForegroundIfStarted("playlist_became_empty")
                    stopSelf()
                    return@collectSafely
                }
                updateMetadata()
                updatePlaybackState(force = true)
                updateNotification()
                updateUsbExclusiveServiceKeepAlive("current_song")
                refreshIdleShutdown("current_song")
            }
        }
        val listenTogetherSessionManager = AppContainer.listenTogetherSessionManager
        serviceScope.launch {
            listenTogetherSessionManager.sessionState.collectSafely("listenTogetherSessionState") {
                handleListenTogetherServiceStateChanged("session")
                refreshIdleShutdown("listen_together_session")
            }
        }
        serviceScope.launch {
            listenTogetherSessionManager.roomState.collectSafely("listenTogetherRoomState") {
                handleListenTogetherServiceStateChanged("room")
                refreshIdleShutdown("listen_together_room")
            }
        }
        serviceScope.launch {
            PlayerManager.playlistsFlow.collectSafely("playlistsFlow") {
                if (!refreshFavoriteSongKeys()) return@collectSafely
                updatePlaybackState(force = true)
                updateNotification()
            }
        }
        serviceScope.launch {
            PlayerManager.localPlaylistsReadyFlow.collectSafely("localPlaylistsReadyFlow") {
                refreshFavoriteSongKeys()
                updatePlaybackState(force = true)
                updateNotification()
            }
        }
        serviceScope.launch {
            GlobalDownloadManager.downloadPresenceVersion
                .collectSafely("downloadPresenceVersion") {
                    updateMetadata()
                    updateNotification()
                }
        }
        serviceScope.launch {
            PlayerManager.externalBluetoothLyricPayloadFlow.collectSafely(
                "externalBluetoothLyricPayloadFlow"
            ) {
                updateMetadata()
            }
        }
        serviceScope.launch {
            PlayerManager.currentAudioDeviceFlow.collectSafely("currentAudioDeviceFlow") {
                updateMetadata()
            }
        }

        serviceScope.launch {
            PlayerManager.isPlayingFlow.collectSafely("isPlayingFlow") {
                updatePlaybackState()
                updateNotification()
                updateUsbExclusiveServiceKeepAlive("is_playing")
                refreshIdleShutdown("is_playing")
            }
        }
        serviceScope.launch {
            PlayerManager.playbackControlPlayingFlow.collectSafely("playbackControlPlayingFlow") {
                updateNotification()
                updateUsbExclusiveServiceKeepAlive("playback_control")
            }
        }
        serviceScope.launch {
            PlayerManager.playWhenReadyFlow.collectSafely("playWhenReadyFlow") {
                updatePlaybackState()
                updateNotification()
                updateUsbExclusiveServiceKeepAlive("play_when_ready")
                refreshIdleShutdown("play_when_ready")
            }
        }
        serviceScope.launch {
            PlayerManager.playerPlaybackStateFlow.collectSafely("playerPlaybackStateFlow") {
                updatePlaybackState(force = true)
                updateNotification()
                updateUsbExclusiveServiceKeepAlive("player_state")
                refreshIdleShutdown("player_state")
            }
        }
        serviceScope.launch {
            UsbExclusiveSessionController.state
                .map { state ->
                    UsbExclusiveNativeServiceSignal(
                        opened = state.opened,
                        streaming = state.streaming,
                        paused = state.paused,
                        transitioning = state.transitioning,
                        source = state.source,
                        handle = state.handle,
                        lastError = state.lastError
                    )
                }
                .distinctUntilChanged()
                .collectSafely("usbExclusiveSessionState") {
                    updateUsbExclusiveServiceKeepAlive("usb_native_state")
                    refreshIdleShutdown("usb_native_state")
                }
        }
        serviceScope.launch {
            UsbExclusiveAudioPathTracker.state.collectSafely("usbExclusiveAudioPathState") { pathState ->
                updateMediaSessionVolumeRouting(pathState)
                updateUsbExclusiveServiceKeepAlive("usb_path_state")
                refreshIdleShutdown("usb_path_state")
            }
        }
        serviceScope.launch {
            PlayerManager.playbackPositionFlow
                .map { positionMs ->
                    positionMs.coerceAtLeast(0L) / PLAYBACK_STATE_PROGRESS_BUCKET_MS
                }
                .distinctUntilChanged()
                .collectSafely("playbackPositionFlow") {
                    updatePlaybackState()
                }
        }
        serviceScope.launch {
            PlayerManager.playbackPositionFlow
                .map(::playbackWidgetProgressRefreshBucket)
                .distinctUntilChanged()
                .collectSafely("playbackWidgetPositionFlow") {
                    updatePlaybackWidgetProgress()
                }
        }
        serviceScope.launch {
            PlayerManager.playbackSoundStateFlow.collectSafely("playbackSoundStateFlow") {
                updatePlaybackState()
            }
        }

        serviceScope.launch {
            PlayerManager.sleepTimerManager.timerState.collectSafely("sleepTimerState") {
                updateNotification()
                refreshIdleShutdown("sleep_timer")
            }
        }

        serviceScope.launch {
            statusBarLyricNotificationStateFlow(
                enabledFlow = AppContainer.settingsRepo.statusBarLyricsEnabledFlow,
                lineFlow = externalBluetoothLyricLineFlow,
            ).collectSafely("statusBarLyricNotificationStateFlow") { state ->
                if (statusBarLyricState != state) {
                    statusBarLyricState = state
                    updateNotification()
                }
            }
        }
        serviceScope.launch {
            AppContainer.settingsRepo.floatingLyricsPreferencesFlow
                .map { it.enabled }
                .distinctUntilChanged()
                .collectSafely("floatingLyricsEnabledFlow") { enabled ->
                    if (floatingLyricsEnabledForNotification != enabled) {
                        floatingLyricsEnabledForNotification = enabled
                        updatePlaybackState(force = true)
                        updateNotification()
                        updatePlaybackWidget(force = true)
                    }
                }
        }
        becomingNoisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                        if (PlayerManager.handleAudioBecomingNoisy()) {
                            NPLogger.d("NERI-APS", "Handled audio becoming noisy according to playback policy.")
                            updatePlaybackState(force = true)
                            updateNotification()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val detachedDevice = intent.usbDeviceExtra()
                        if (!UsbExclusiveSessionController.handleUsbDeviceDetached(detachedDevice)) {
                            return
                        }
                        NPLogger.w(
                            "NERI-APS",
                            "active USB audio device detached id=${detachedDevice?.deviceId} " +
                                "name=${detachedDevice?.deviceName}"
                        )
                        StartupAudioFocusController.forceRelease("usb_device_detached")
                        PlayerManager.stopPlaybackAfterUsbExclusiveNativeFailure(
                            "usb_device_detached"
                        )
                        UsbExclusiveSystemSoundGuard.forceRelease(
                            this@AudioPlayerService,
                            "usb_device_detached"
                        )
                        updatePlaybackState(force = true)
                        updateNotification()
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (
                            !shouldProcessUsbDeviceAttachedAction(
                                intent.action,
                                usbDeviceAttachHandlingEnabled
                            )
                        ) {
                            NPLogger.i("NERI-APS", "Ignored USB audio device attach by settings")
                            return
                        }
                        val attachedDevice = intent.usbDeviceExtra()
                        if (
                            !UsbExclusiveSessionController.handleUsbDeviceAttached(
                                this@AudioPlayerService,
                                attachedDevice
                            )
                        ) {
                            return
                        }
                        NPLogger.i(
                            "NERI-APS",
                            "USB audio device attached after active route detach " +
                                "id=${attachedDevice?.deviceId} name=${attachedDevice?.deviceName}"
                        )
                        PlayerManager.scheduleUsbExclusivePlaybackResumeAfterDeviceAttach(
                            "usb_device_attached"
                        )
                        updatePlaybackState(force = true)
                        updateNotification()
                    }
                }
            }
        }
        val noisyIntentFilter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                becomingNoisyReceiver,
                noisyIntentFilter,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(becomingNoisyReceiver, noisyIntentFilter)
        }

        updateMetadata()
        updatePlaybackState(force = true)
        updateNotification()
        updateUsbExclusiveServiceKeepAlive("service_create")
        refreshIdleShutdown("player_runtime_ready")
        drainPendingStartCommands()
        drainPendingPlayerActions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val startSource = intent?.getStringExtra(EXTRA_START_SOURCE) ?: "unspecified"
        NPLogger.d(
            "NERI-APS",
            "onStartCommand action=$action source=$startSource flags=$flags startId=$startId ${buildStateSummary()}"
        )
        if (SafeModeManager.shouldEnterSafeMode(this)) {
            // 安全模式下 onCreate 已早退且 mediaSession 未初始化;此处短路,先满足 FGS 契约再退出,
            // 避免走前台分支读取未初始化的 mediaSession 崩溃
            NPLogger.w(
                "NERI-APS",
                "onStartCommand short-circuited because safe mode is active source=$startSource"
            )
            isServiceInstanceActive = false
            allowServiceRestart = false
            startForegroundForSafeModeThenStop("safe_mode_start_command:$action:$startSource")
            return START_NOT_STICKY
        }
        allowServiceRestart = true
        keepPlayerRuntimeAfterServiceStop = false
        hasReceivedStartCommand = true
        latestStartId = startId

        if (!isForegroundStarted && action != ACTION_STOP) {
            if (!startForegroundImmediately(
                    buildBootstrapNotification(),
                    "on_start_command:$action:$startSource"
                )) {
                return handleForegroundPromotionFailure(
                    reason = "on_start_command:$action:$startSource",
                    startId = startId
                )
            }
        }
        if (!playerRuntimeReady) {
            pendingStartCommands.addLast(
                PendingStartCommand(
                    intent = intent?.let { Intent(it) },
                    flags = flags,
                    startId = startId,
                )
            )
            NPLogger.d(
                "NERI-APS",
                "Deferring start command until player runtime is ready: action=$action startId=$startId"
            )
            return if (
                shouldUseStickyStartModeWhilePlayerRuntimeInitializes(
                    hasExplicitAction = action != null
                )
            ) {
                START_STICKY
            } else {
                START_REDELIVER_INTENT
            }
        }
        if (action == null && !hasPlaybackSurfaceContent()) {
            allowServiceRestart = false
            NPLogger.w("NERI-APS", "Stopping service because null action arrived without playback content")
            stopForegroundIfStarted("null_action_without_items")
            stopSelf()
            return START_NOT_STICKY
        }

        if (action != ACTION_STOP && action != null) {
            if (!ensureForegroundStarted()) {
                return handleForegroundPromotionFailure(
                    reason = "ensure_foreground:$action:$startSource",
                    startId = startId
                )
            }
        }

        dispatchMediaButtonIntent(intent)

        when (action) {
            ACTION_PLAY -> {
                val songList = IntentCompat.getParcelableArrayListExtra(
                    intent,
                    "playlist",
                    SongItem::class.java
                )
                val startIndex = intent.getIntExtra("index", 0)
                if (!songList.isNullOrEmpty()) {
                    PlayerManager.playPlaylist(songList, startIndex)
                } else if (PlayerManager.hasItems()) {
                    PlayerManager.play()
                }
                updateAll()
            }
            ACTION_PAUSE -> {
                handleExternalPauseCommand("intent_pause")
            }
            ACTION_TOGGLE_PLAY_PAUSE -> {
                if (PlayerManager.hasItems()) {
                    PlayerManager.togglePlayPause()
                }
                updateAll()
                refreshIdleShutdown("widget_toggle_play_pause")
            }
            ACTION_NEXT -> {
                PlayerManager.next()
                updateAll()
            }
            ACTION_PREV -> {
                PlayerManager.previous()
                updateAll()
            }
            ACTION_STOP -> {
                handleExternalPauseCommand("intent_stop", stopService = true)
                return START_NOT_STICKY
            }

            ACTION_SYNC -> {
                if (!hasPlaybackSurfaceContent()) {
                    allowServiceRestart = false
                    NPLogger.w("NERI-APS", "Ignoring ACTION_SYNC because playback content is empty, source=$startSource")
                    stopForegroundIfStarted("sync_without_items")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (
                    shouldSkipFullSyncForLocalPlaybackAction(
                        source = startSource,
                        foregroundStarted = isForegroundStarted,
                        hasItems = PlayerManager.hasItems(),
                        hasCurrentSong = PlayerManager.currentSongFlow.value != null,
                        hasLocalCurrentSong = PlayerManager.currentSongFlow.value?.let {
                            LocalSongSupport.isLocalSong(it, this)
                        } == true,
                        usbExclusivePlaybackActive = PlayerManager
                            .isUsbExclusivePlaybackActiveForForegroundService()
                    )
                ) {
                    NPLogger.d(
                        "NERI-APS",
                        "Skipping full ACTION_SYNC because active service already tracks local playback, source=$startSource"
                    )
                } else {
                    NPLogger.d("NERI-APS", "Handling ACTION_SYNC source=$startSource ${buildStateSummary()}")
                    updateAll()
                }
            }

            ACTION_TOGGLE_FAV -> {
                if (canToggleFavoriteFromExternalSurface(PlayerManager.currentSongFlow.value)) {
                    PlayerManager.toggleCurrentFavorite()
                }
                updateNotification()
                updatePlaybackWidget(force = true)
            }

            ACTION_TOGGLE_FLOATING_LYRICS -> {
                applyFloatingLyricsExternalAction(legacyHideAction = false)
                updatePlaybackState(force = true)
                updateNotification(force = true)
                updatePlaybackWidget(force = true)
            }

            LEGACY_ACTION_HIDE_FLOATING_LYRICS -> {
                applyFloatingLyricsExternalAction(legacyHideAction = true)
                updatePlaybackState(force = true)
                updateNotification(force = true)
                updatePlaybackWidget(force = true)
            }
        }

        if (PlayerManager.hasItems()) {
            val foregroundReady = ensureForegroundStarted()
            if (!foregroundReady && action == null) {
                NPLogger.w(
                    "NERI-APS",
                    "Foreground start deferred after background restart; skip restoring playback."
                )
                allowServiceRestart = false
                stopSelf()
                return START_NOT_STICKY
            }
            if (action == null) {
                val restoredPlaybackPositionMs = PlayerManager.resumeRestoredPlaybackIfNeeded()
                if (restoredPlaybackPositionMs != null) {
                    NPLogger.w("NERI-APS", "Restored playback after process restart")
                    updateAll()
                }
            }
        } else if (hasPlaybackSurfaceContent()) {
            ensureForegroundStarted()
            updateAll()
        } else {
            allowServiceRestart = false
            NPLogger.w("NERI-APS", "Stopping service because playback content is empty after action handling")
            stopForegroundIfStarted("no_items_after_action")
            stopSelf()
            return START_NOT_STICKY
        }

        val startMode = if (allowServiceRestart && shouldKeepServiceSticky()) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
        NPLogger.d(
            "NERI-APS",
            "onStartCommand complete action=$action source=$startSource startMode=$startMode ${buildStateSummary()}"
        )
        updateUsbExclusiveServiceKeepAlive("on_start_command:$action:$startSource")
        refreshIdleShutdown("on_start_command:$action:$startSource")
        return startMode
    }

    private fun buildNotification(): Notification {
        val isPlaybackControlPlaying = PlayerManager.playbackControlPlayingFlow.value
        val song = playbackSurfaceSong()

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent  = servicePendingIntent(ACTION_PREV, 1)
        val playIntent  = servicePendingIntent(ACTION_PLAY, 2)
        val pauseIntent = servicePendingIntent(ACTION_PAUSE, 3)
        val nextIntent  = servicePendingIntent(ACTION_NEXT, 4)
        val toggleFavIntent = servicePendingIntent(ACTION_TOGGLE_FAV, 6)
        val toggleFloatingLyricsIntent = servicePendingIntent(ACTION_TOGGLE_FLOATING_LYRICS, 7)
        val favoriteActionIntent = if (requiresInteractiveFavoriteConfirmation(song)) {
            contentIntent
        } else {
            toggleFavIntent
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 3)
            )
            .applyForegroundServiceBehavior()

        val isFav = isFavoriteSong(song)

        val favAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (isFav) R.drawable.ic_baseline_favorite_24 else R.drawable.ic_outline_favorite_24
            ),
            if (isFav) getString(R.string.favorite_remove) else getString(R.string.favorite_add),
            favoriteActionIntent
        ).build()

        builder.addAction(
            mediaNotificationAction(
                iconRes = R.drawable.round_skip_previous_24,
                title = getString(R.string.player_previous),
                pendingIntent = prevIntent
            )
        )
        builder.addAction(
            mediaNotificationAction(
                iconRes = if (isPlaybackControlPlaying) {
                    R.drawable.round_pause_24
                } else {
                    R.drawable.round_play_arrow_24
                },
                title = if (isPlaybackControlPlaying) {
                    getString(R.string.player_pause)
                } else {
                    getString(R.string.player_play)
                },
                pendingIntent = if (isPlaybackControlPlaying) pauseIntent else playIntent
            )
        )
        builder.addAction(favAction)
        builder.addAction(
            mediaNotificationAction(
                iconRes = R.drawable.round_skip_next_24,
                title = getString(R.string.player_next),
                pendingIntent = nextIntent
            )
        )
        val floatingLyricsEnabled = isFloatingLyricsCurrentlyEnabled()
        builder.addAction(
            mediaNotificationAction(
                iconRes = if (floatingLyricsEnabled) {
                    R.drawable.ic_lyrics_off_24
                } else {
                    R.drawable.ic_lyrics_24
                },
                title = getString(
                    if (floatingLyricsEnabled) {
                        R.string.notification_hide_floating_lyrics
                    } else {
                        R.string.notification_show_floating_lyrics
                    }
                ),
                pendingIntent = toggleFloatingLyricsIntent
            )
        )

        builder.setContentTitle(song?.displayName() ?: "NeriPlayer")
        val currentStatusBarLyricState = statusBarLyricState
        currentStatusBarLyricState.line?.let(builder::setTicker)

        val timerState = PlayerManager.sleepTimerManager.timerState.value
        val contentText = if (timerState.isActive) {
            val timerInfo = when (timerState.mode) {
                SleepTimerMode.COUNTDOWN,
                SleepTimerMode.COUNTDOWN_FINISH_CURRENT -> {
                    val remaining = PlayerManager.sleepTimerManager.formatRemainingTimeForNotification()
                    val stringRes = if (timerState.mode == SleepTimerMode.COUNTDOWN_FINISH_CURRENT) {
                        R.string.notification_timer_finish_current_remaining
                    } else {
                        R.string.notification_timer_remaining
                    }
                    getString(stringRes, remaining)
                }
                SleepTimerMode.FINISH_CURRENT -> getString(R.string.notification_stop_after_current)
                SleepTimerMode.FINISH_PLAYLIST -> getString(R.string.notification_stop_after_playlist)
            }
            song?.displayArtist()
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it | $timerInfo" }
                ?: timerInfo
        } else {
            song?.displayArtist() ?: ""
        }
        builder.setContentText(contentText)

        currentNotificationLargeIcon?.let { builder.setLargeIcon(it) }

        return builder.build().apply {
            attachXiaomiMusicIslandShareExtras(song)

            if (currentStatusBarLyricState.hasTicker) {
                val FLAG_ALWAYS_SHOW_TICKER = 0x01000000
                val FLAG_ONLY_UPDATE_TICKER = 0x02000000
                // 魅族状态栏歌词依赖这两个私有通知标记
                flags = flags.or(FLAG_ALWAYS_SHOW_TICKER)
                flags = flags.or(FLAG_ONLY_UPDATE_TICKER)
                // ticker_icon: 状态栏歌词前的小图标
                extras.putInt("ticker_icon", R.drawable.ic_notification_small)
                // false 表示沿用缓存图标, 图标资源变化时才需要切换
                extras.putBoolean("ticker_icon_switch", false)
            }
        }

    }

    private fun Notification.attachXiaomiMusicIslandShareExtras(song: SongItem?) {
        if (song == null) return
        val shareUrl = buildRemoteSongShareUrl(song, PlayerManager.currentPlaylist)
            ?.takeIf(::isShareablePublicHttpUrl)
            ?: return

        runCatching {
            val icon = Bundle().apply {
                putParcelable(
                    "miui_media_album_icon",
                    Icon.createWithResource(
                        this@AudioPlayerService,
                        R.drawable.ic_notification_small,
                    ),
                )
            }
            val islandBundle = IsLandHelp.isLandMusicShare(
                addpic = icon,
                title = song.displayName(),
                content = song.displayArtist(),
                shareContent = shareUrl,
            )
            extras.putAll(islandBundle)
        }.onFailure { error ->
            NPLogger.w("NERI-APS", "Xiaomi music island share extras failed", error)
        }
    }

    private fun buildBootstrapNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.player_notification_preparing))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .applyForegroundServiceBehavior()
        // mediaSession 尚未初始化时降级为不带媒体样式的通知,避免读取 lateinit 崩溃
        if (this::mediaSession.isInitialized) {
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
            )
        }
        return builder.build()
    }

    private fun mediaNotificationAction(
        @DrawableRes iconRes: Int,
        title: CharSequence,
        pendingIntent: PendingIntent
    ): Notification.Action {
        return Notification.Action.Builder(
            Icon.createWithResource(this, iconRes),
            title,
            pendingIntent
        ).build()
    }

    private fun Notification.Builder.applyForegroundServiceBehavior(): Notification.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return this
    }

    /**
     * 极简前台通知:不依赖任何实例成员(尤其是尚未初始化的 mediaSession),
     * 仅用于安全模式早退时满足 FGS 5s 契约后立即撤下
     */
    private fun buildMinimalForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(getString(R.string.app_name))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 确保播放通知渠道存在,可在安全模式早退分支于任何成员初始化前安全调用 */
    private fun ensurePlaybackNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NeriPlayer Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun isFavoriteSong(song: SongItem?): Boolean {
        if (song == null) return false
        return song.stableKey() in favoriteSongKeys
    }

    private fun requiresInteractiveFavoriteConfirmation(song: SongItem?): Boolean {
        return shouldUseInteractiveFavoriteIntent(
            localPlaylistsReady = PlayerManager.localPlaylistsReady,
            hasCurrentSong = song != null,
            isFavorite = isFavoriteSong(song),
            isLocalSong = song?.let { LocalSongSupport.isLocalSong(it, this) } == true,
        )
    }

    private fun canToggleFavoriteFromExternalSurface(song: SongItem?): Boolean {
        return shouldAllowExternalFavoriteToggle(
            localPlaylistsReady = PlayerManager.localPlaylistsReady,
            hasCurrentSong = song != null,
            requiresInteractiveConfirmation = requiresInteractiveFavoriteConfirmation(song),
        )
    }

    private fun updateAll() {
        updateMetadata()
        updatePlaybackState(force = true)
        updateNotification()
        updatePlaybackWidget()
    }

    /** 构建指向本 Service 的 PendingIntent */
    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioPlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification(force: Boolean = false) {
        if (!isForegroundStarted) {
            return
        }
        val snapshot = buildNotificationSnapshot()
        if (!force && snapshot == lastNotificationSnapshot) {
            return
        }
        lastNotificationSnapshot = snapshot
        val nm: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
        updatePlaybackWidget()
    }

    private fun buildNotificationSnapshot(): PlaybackNotificationSnapshot {
        val song = playbackSurfaceSong()
        val timerState = PlayerManager.sleepTimerManager.timerState.value
        val text = if (timerState.isActive) {
            val timerInfo = when (timerState.mode) {
                SleepTimerMode.COUNTDOWN,
                SleepTimerMode.COUNTDOWN_FINISH_CURRENT -> {
                    val remaining = PlayerManager.sleepTimerManager.formatRemainingTimeForNotification()
                    val stringRes = if (timerState.mode == SleepTimerMode.COUNTDOWN_FINISH_CURRENT) {
                        R.string.notification_timer_finish_current_remaining
                    } else {
                        R.string.notification_timer_remaining
                    }
                    getString(stringRes, remaining)
                }
                SleepTimerMode.FINISH_CURRENT -> getString(R.string.notification_stop_after_current)
                SleepTimerMode.FINISH_PLAYLIST -> getString(R.string.notification_stop_after_playlist)
            }
            song?.displayArtist()
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it | $timerInfo" }
                ?: timerInfo
        } else {
            song?.displayArtist() ?: ""
        }
        val currentStatusBarLyricState = statusBarLyricState
        return PlaybackNotificationSnapshot(
            songKey = song?.stableKey(),
            title = song?.displayName() ?: "NeriPlayer",
            text = text,
            isTransportActive = PlayerManager.isTransportActive(),
            isPlaybackControlPlaying = PlayerManager.playbackControlPlayingFlow.value,
            isFavorite = isFavoriteSong(song),
            requiresInteractiveFavoriteConfirmation = requiresInteractiveFavoriteConfirmation(song),
            largeIconReady = currentNotificationLargeIcon != null,
            coverSource = currentCoverSource,
            statusBarLyricState = currentStatusBarLyricState,
            floatingLyricsEnabled = isFloatingLyricsCurrentlyEnabled(),
        )
    }

    private fun updatePlaybackWidget(force: Boolean = false) {
        if (!PlaybackWidgetUpdater.hasInstalledWidgets(this)) {
            return
        }
        val state = buildCurrentPlaybackWidgetState()
        if (!force && !playbackWidgetPresentationChanged(lastPlaybackWidgetState, state)) {
            return
        }
        lastPlaybackWidgetState = state
        PlaybackWidgetUpdater.updateFromPlaybackService(
            context = this,
            state = state,
            artwork = currentNotificationLargeIcon,
        )
    }

    private fun updatePlaybackWidgetProgress() {
        if (!PlaybackWidgetUpdater.hasInstalledWidgets(this)) {
            return
        }
        val state = buildCurrentPlaybackWidgetState()
        if (playbackWidgetPresentationChanged(lastPlaybackWidgetState, state)) {
            updatePlaybackWidget(force = true)
            return
        }
        if (!shouldPartiallyUpdatePlaybackWidgetProgress(lastPlaybackWidgetState, state)) {
            return
        }
        PlaybackWidgetUpdater.updatePlaybackProgressFromPlaybackService(
            context = this,
            state = state,
        )
    }

    private fun buildCurrentPlaybackWidgetState(): PlaybackWidgetState {
        val song = playbackSurfaceSong()
        val fallbackSongActive = PlayerManager.currentSongFlow.value == null && song != null
        val positionMs = if (fallbackSongActive) {
            listenTogetherExpectedPositionMs()
        } else {
            PlayerManager.playbackPositionFlow.value
        }
        val isBuffering = PlayerManager.isTransportBuffering()
        val isPlaying = PlayerManager.isTransportActive() ||
            (fallbackSongActive && isListenTogetherRemotePlaying())
        val status = when {
            isBuffering -> getString(R.string.widget_playback_buffering)
            isPlaying -> getString(R.string.widget_playback_playing)
            song != null -> getString(R.string.widget_playback_paused)
            else -> getString(R.string.widget_playback_ready)
        }
        return buildPlaybackWidgetState(
            title = song?.displayName() ?: getString(R.string.app_name),
            subtitle = song?.displayArtist()
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.widget_playback_idle_subtitle),
            status = status,
            positionMs = positionMs,
            durationMs = song?.durationMs ?: 0L,
            hasSong = song != null,
            isPlaying = isPlaying,
            isFavorite = isFavoriteSong(song),
            canToggleFavorite = canToggleFavoriteFromExternalSurface(song),
            isFloatingLyricsEnabled = isFloatingLyricsCurrentlyEnabled(),
            artworkReady = currentNotificationLargeIcon != null,
            contentId = song?.stableKey().orEmpty(),
            coverId = currentCoverSource.orEmpty(),
            artworkPending = song != null && (
                !currentCoverSource.isNullOrBlank() ||
                    coverResolutionInFlightSongKey == song.stableKey()
                ),
        )
    }

    private fun updateMetadata() {
        val song = playbackSurfaceSong()
        val songKey = song?.stableKey()
        val duration = song?.durationMs ?: 0L
        val immediateCoverSource = song.effectiveCoverSource()
        val coverSource = resolveMetadataCoverSource(
            songKey = songKey,
            immediateCoverSource = immediateCoverSource,
            retainedSongKey = currentCoverSongKey,
            retainedCoverSource = currentCoverSource,
        )

        if (songKey != currentCoverSongKey || coverSource != currentCoverSource) {
            val songChanged = songKey != currentCoverSongKey
            val sourceChanged = coverSource != currentCoverSource
            currentCoverSongKey = songKey
            currentCoverSource = coverSource
            if (songChanged || sourceChanged) {
                currentMediaArtwork = null
                currentNotificationLargeIcon = null
                artworkLoadInFlightSource = null
                artworkLoadJob?.cancel()
                artworkLoadJob = null
                artworkRetryJob?.cancel()
                artworkRetryJob = null
                artworkRetryAttemptCount = 0
            }
        }
        if (song == null) {
            coverResolutionInFlightSongKey = null
        } else {
            resolveCoverSourceAsyncIfNeeded(song, song.stableKey(), immediateCoverSource)
        }
        requestLargeIconIfNeeded(currentCoverSource)

        val normalTitle = song?.displayName() ?: "NeriPlayer"
        val normalArtist = song?.displayArtist().orEmpty()
        val lyricPayload = PlayerManager.externalBluetoothLyricPayloadFlow.value
        val useBluetoothLyrics = shouldUseExternalBluetoothLyrics(
            audioDeviceType = PlayerManager.currentAudioDeviceFlow.value?.type,
            payload = lyricPayload,
            forceSendLyrics = PlayerManager.dynamicIslandLyricsEnabled
        )
        val metadataText = resolveExternalBluetoothMetadataText(
            normalTitle = normalTitle,
            normalArtist = normalArtist,
            payload = lyricPayload,
            useBluetoothLyrics = useBluetoothLyrics
        )
        val snapshot = PlaybackMetadataSnapshot(
            songKey = songKey,
            title = metadataText.title,
            artist = metadataText.artist,
            album = metadataText.album,
            displayTitle = metadataText.displayTitle,
            displaySubtitle = metadataText.displaySubtitle,
            displayDescription = metadataText.displayDescription,
            durationMs = duration,
            coverSource = currentCoverSource,
            largeIconReady = currentMediaArtwork != null,
        )
        if (snapshot == lastMetadataSnapshot) {
            return
        }
        lastMetadataSnapshot = snapshot

        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, metadataText.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, metadataText.artist)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, metadataText.displayTitle)
            .putString(
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                metadataText.displaySubtitle
            )
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentMediaArtwork)

        metadataText.album?.let { album ->
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        }
        metadataText.displayDescription?.let { description ->
            metadataBuilder.putString(
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
                description
            )
        }

        resolveRemoteMetadataArtworkUri(currentCoverSource)?.let { artworkUri ->
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artworkUri)
        }

        // Do not set local URIs to METADATA_KEY_ALBUM_ART_URI, as it may prompt the System UI
        // to attempt loading them directly (which can fail due to permission issues) and
        // override the bitmap we already provided via METADATA_KEY_ALBUM_ART

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun resolveCoverSourceAsyncIfNeeded(
        song: SongItem,
        songKey: String,
        immediateCoverSource: String?
    ) {
        if (!immediateCoverSource.isNullOrBlank()) {
            return
        }
        if (coverResolutionInFlightSongKey == songKey) {
            return
        }
        if (currentCoverSongKey == songKey && !currentCoverSource.isNullOrBlank()) {
            return
        }

        coverResolutionInFlightSongKey = songKey
        val appCtx = applicationContext
        serviceScope.launch(Dispatchers.IO) {
            val resolvedCoverSource = runCatching {
                song.displayCoverUrl(appCtx)?.takeIf { it.isNotBlank() }
            }.getOrElse {
                NPLogger.d("NERI-APS", "Deferred cover resolve failed: ${it.message}")
                null
            }
            withContext(Dispatchers.Main) {
                if (coverResolutionInFlightSongKey == songKey) {
                    coverResolutionInFlightSongKey = null
                }
                val currentSurfaceSong = playbackSurfaceSong()
                if (currentSurfaceSong?.stableKey() != songKey || resolvedCoverSource.isNullOrBlank()) {
                    if (
                        currentSurfaceSong?.stableKey() == songKey &&
                        resolvedCoverSource.isNullOrBlank()
                    ) {
                        updatePlaybackWidget(force = true)
                    }
                    return@withContext
                }
                if (currentCoverSongKey == songKey && currentCoverSource == resolvedCoverSource) {
                    requestLargeIconIfNeeded(resolvedCoverSource)
                    return@withContext
                }
                currentCoverSongKey = songKey
                currentCoverSource = resolvedCoverSource
                currentMediaArtwork = null
                currentNotificationLargeIcon = null
                lastMetadataSnapshot = null
                updateMetadata()
                updateNotification()
            }
        }
    }

    private fun updatePlaybackState(force: Boolean = false) {
        val isTransportActive = PlayerManager.isTransportActive()
        val isBuffering = PlayerManager.isTransportBuffering()
        val fallbackSongActive = PlayerManager.currentSongFlow.value == null && playbackSurfaceSong() != null
        val pos = if (fallbackSongActive) {
            listenTogetherExpectedPositionMs()
        } else {
            PlayerManager.playbackPositionFlow.value
        }

        val song = playbackSurfaceSong()
        val isFav = isFavoriteSong(song)

        val favIconRes = if (isFav) R.drawable.ic_baseline_favorite_24
        else R.drawable.ic_outline_favorite_24
        val favText = if (isFav) getString(R.string.favorite_remove) else getString(R.string.favorite_add)

        val favCustom = PlaybackState.CustomAction.Builder(
            ACTION_TOGGLE_FAV, favText, favIconRes
        ).build()
        val floatingLyricsEnabled = isFloatingLyricsCurrentlyEnabled()
        val floatingLyricsCustom = PlaybackState.CustomAction.Builder(
            ACTION_TOGGLE_FLOATING_LYRICS,
            getString(
                if (floatingLyricsEnabled) {
                    R.string.notification_hide_floating_lyrics
                } else {
                    R.string.notification_show_floating_lyrics
                }
            ),
            if (floatingLyricsEnabled) R.drawable.ic_lyrics_off_24 else R.drawable.ic_lyrics_24
        ).build()

        val actions = mediaSessionPlaybackActions()

        val playbackState = when {
            isBuffering -> PlaybackState.STATE_BUFFERING
            isTransportActive -> PlaybackState.STATE_PLAYING
            fallbackSongActive && isListenTogetherRemotePlaying() -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        val playbackSpeed = if (playbackState == PlaybackState.STATE_PLAYING) {
            PlayerManager.playbackSoundStateFlow.value.speed
        } else {
            0.0f
        }
        val favoriteControlFingerprint = when {
            !canToggleFavoriteFromExternalSurface(song) -> 0
            isFav -> 2
            else -> 1
        }
        val controlFingerprint = buildMediaSessionControlFingerprint(
            favoriteControlFingerprint = favoriteControlFingerprint,
            floatingLyricsEnabled = floatingLyricsEnabled,
        )
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()

        if (!mediaSessionPlaybackStateThrottler.shouldDispatch(
                playbackState = playbackState,
                positionMs = pos,
                speed = playbackSpeed,
                controlFingerprint = controlFingerprint,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                force = force,
            )
        ) {
            return
        }

        val stateBuilder = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                playbackState,
                pos,
                playbackSpeed
            )

        if (canToggleFavoriteFromExternalSurface(song)) {
            stateBuilder.addCustomAction(favCustom)
        }
        stateBuilder.addCustomAction(floatingLyricsCustom)

        mediaSession.setPlaybackState(stateBuilder.build())
        mediaSessionPlaybackStateThrottler.recordDispatch(
            playbackState = playbackState,
            positionMs = pos,
            speed = playbackSpeed,
            controlFingerprint = controlFingerprint,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
    }

    private fun requestLargeIconIfNeeded(url: String?) {
        if (
            !shouldRequestArtworkLoad(
                coverSource = url,
                artworkReady = currentMediaArtwork != null,
                inFlightCoverSource = artworkLoadInFlightSource,
                lastFailedCoverSource = lastArtworkLoadFailedSource,
                lastFailureAtElapsedRealtime = lastArtworkLoadFailedAtElapsedRealtime,
                nowElapsedRealtime = SystemClock.elapsedRealtime(),
            )
        ) {
            return
        }
        requestLargeIconAsync(url ?: return)
    }

    private fun requestLargeIconAsync(url: String) {
        val appCtx = applicationContext
        artworkLoadInFlightSource = url
        artworkLoadJob?.cancel()
        artworkLoadJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val loader = coil.Coil.imageLoader(appCtx)
                val request = offlineCachedImageRequest(
                    context = appCtx,
                    data = url,
                    sizePx = MEDIA_ARTWORK_SIZE_PX,
                    allowHardware = false,
                    offlineMode = appCtx.isOfflineModeNow()
                )
                val result = loader.execute(request)
                val drawable = result.drawable ?: run {
                    withContext(Dispatchers.Main) {
                        markArtworkLoadFailed(url, "drawable was null")
                    }
                    return@launch
                }
                val bmp = drawable.toBitmap()
                val notificationBmp = bmp.scaledToMaxDimension(NOTIFICATION_ARTWORK_SIZE_PX)
                withContext(Dispatchers.Main) {
                    if (artworkLoadInFlightSource == url) {
                        artworkLoadInFlightSource = null
                    }
                    if (artworkLoadJob === coroutineContext[Job]) {
                        artworkLoadJob = null
                    }
                    if (url == currentCoverSource) {
                        lastArtworkLoadFailedSource = null
                        lastArtworkLoadFailedAtElapsedRealtime = -1L
                        artworkRetryJob?.cancel()
                        artworkRetryJob = null
                        artworkRetryAttemptCount = 0
                        currentMediaArtwork = bmp
                        currentNotificationLargeIcon = notificationBmp
                        updateMetadata()
                        updateNotification()
                    }
                }

                NPLogger.d(
                    "NERI-APS",
                    "cover bitmap=${bmp.width}x${bmp.height}, bytes=${bmp.byteCount / 1024 / 1024}MB"
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (artworkLoadJob === coroutineContext[Job]) {
                        artworkLoadJob = null
                    }
                    markArtworkLoadFailed(url, e.message)
                }
            }
        }
    }

    private fun markArtworkLoadFailed(url: String, reason: String?) {
        if (artworkLoadInFlightSource == url) {
            artworkLoadInFlightSource = null
        }
        if (url == currentCoverSource) {
            lastArtworkLoadFailedSource = url
            lastArtworkLoadFailedAtElapsedRealtime = SystemClock.elapsedRealtime()
            scheduleArtworkRetry(url)
        }
        NPLogger.d("NERI-APS", "Cover load failed: ${reason ?: "unknown"}")
    }

    private fun scheduleArtworkRetry(url: String) {
        if (artworkRetryAttemptCount >= MEDIA_ARTWORK_MAX_RETRY_ATTEMPTS) {
            return
        }
        artworkRetryAttemptCount += 1
        artworkRetryJob?.cancel()
        artworkRetryJob = serviceScope.launch {
            delay(MEDIA_ARTWORK_RETRY_COOLDOWN_MS)
            if (url != currentCoverSource || currentMediaArtwork != null) {
                return@launch
            }
            requestLargeIconIfNeeded(url)
        }
    }

    private fun Bitmap.scaledToMaxDimension(maxDimensionPx: Int): Bitmap {
        val longestSide = maxOf(width, height)
        if (longestSide <= maxDimensionPx) {
            return this
        }

        val scale = maxDimensionPx.toFloat() / longestSide
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return scale(scaledWidth, scaledHeight, true)
    }

    private fun SongItem?.effectiveCoverSource(): String? {
        val song = this ?: return null
        return song.displayCoverUrl(this@AudioPlayerService)?.takeIf { it.isNotBlank() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val hasPlaybackSurfaceContent = hasPlaybackSurfaceContent()
        val transportActive = runCatching {
            PlayerManager.isTransportActiveWithoutInitialization()
        }.getOrDefault(false)
        NPLogger.w(
            "NERI-APS",
            "onTaskRemoved hasSurface=$hasPlaybackSurfaceContent " +
                "transportActive=$transportActive hasItems=${PlayerManager.hasItems()} " +
                "isPlaying=${PlayerManager.isPlayingFlow.value}"
        )
        if (
            shouldStopPlaybackOnTaskRemoved(
                hasPlaybackSurfaceContent = hasPlaybackSurfaceContent,
                transportActive = transportActive,
            )
        ) {
            allowServiceRestart = false
            flushPlaybackStatsSafely("task_removed", "task removed")
            runCatching {
                PlayerManager.stopPlaybackImmediately("task_removed")
            }.onFailure {
                NPLogger.w("NERI-APS", "playback stop failed during task removed", it)
            }
            runCatching {
                PlayerManager.scheduleStatePersist(
                    positionMs = PlayerManager.playbackPositionFlow.value,
                    shouldResumePlayback = false,
                    debounceMs = 0L
                )
            }.onFailure {
                NPLogger.w("NERI-APS", "state persist failed during task removed", it)
            }
            stopForegroundIfStarted("task_removed")
            stopSelf()
            return
        }
        if (PlayerManager.hasItems()) {
            runCatching {
                PlayerManager.scheduleStatePersist(
                    positionMs = PlayerManager.playbackPositionFlow.value,
                    shouldResumePlayback = false,
                    debounceMs = 0L
                )
            }.onFailure {
                NPLogger.w("NERI-APS", "state persist failed during inactive task removed", it)
            }
            runCatching { updateNotification() }
                .onFailure {
                    NPLogger.w(
                        "NERI-APS",
                        "notification update failed during inactive task removed",
                        it
                    )
                }
        }
    }

    private fun flushPlaybackStatsSafely(reason: String, context: String) {
        runCatching { PlayerManager.flushPlaybackStatsAsync(reason) }
            .onFailure { NPLogger.w("NERI-APS", "playback stats flush failed during $context", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NPLogger.w(
            "NERI-APS",
            "onDestroy ${buildStateSummary()}"
        )
        val preservePlaybackForRestart = allowServiceRestart && shouldKeepServiceSticky()
        try {
            isServiceForegroundActive = false
            isServiceInstanceActive = false
            idleShutdownCoordinator.cancel()
            playerInitializationJob?.cancel()
            playerInitializationJob = null
            pendingStartCommands.clear()
            pendingPlayerActions.clear()
            flushPlaybackStatsSafely("service_destroy", "destroy")
            if (this::becomingNoisyReceiver.isInitialized) {
                runCatching { unregisterReceiver(becomingNoisyReceiver) }
                    .onFailure { NPLogger.w("NERI-APS", "unregisterReceiver failed during destroy", it) }
            }
            usbExclusiveKeepAliveJob?.cancel()
            usbExclusiveKeepAliveJob = null
            UsbExclusiveBackgroundAudioAnchor.stop("service_destroy")
            artworkLoadJob?.cancel()
            artworkLoadJob = null
            serviceScope.cancel()
            disableUsbExclusiveMediaSessionVolumeRouting("service_destroy")
            if (this::mediaSession.isInitialized) {
                runCatching {
                    mediaSession.isActive = false
                    mediaSession.release()
                }.onFailure { NPLogger.w("NERI-APS", "media session release failed", it) }
            }
            if (keepPlayerRuntimeAfterServiceStop) {
                NPLogger.i("NERI-APS", "Keeping paused player runtime after idle service shutdown")
            } else if (preservePlaybackForRestart) {
                runCatching {
                    PlayerManager.suspendPlaybackForServiceRestart("service_destroy")
                }.onFailure { error ->
                    NPLogger.w(
                        "NERI-APS",
                        "player suspend failed during restartable destroy",
                        error
                    )
                }
            } else {
                runCatching { PlayerManager.release() }
                    .onFailure { NPLogger.w("NERI-APS", "player release failed during destroy", it) }
            }
        } finally {
            clearActiveServiceReference()
            playerRuntimeReady = false
            favoriteSongKeys = emptySet()
            currentMediaArtwork = null
            currentNotificationLargeIcon = null
            shutdownUsbRuntime("service_destroy")
            super.onDestroy()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        NPLogger.w(
            "NERI-APS",
            "onTrimMemory level=$level ${buildStateSummary()}"
        )
        if (level >= TRIM_MEMORY_UI_HIDDEN && PlayerManager.hasItems()) {
            flushPlaybackStatsSafely("service_trim_memory_$level", "trim memory")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        NPLogger.w(
            "NERI-APS",
            "onLowMemory ${buildStateSummary()}"
        )
        if (PlayerManager.hasItems()) {
            flushPlaybackStatsSafely("service_low_memory", "low memory")
        }
    }


    private fun ensureForegroundStarted(): Boolean {
        if (isForegroundStarted) {
            updateNotification()
            return true
        }
        val notification = buildNotification()
        NPLogger.d("NERI-APS", "ensureForegroundStarted requested ${buildStateSummary()}")
        return startForegroundImmediately(notification, "ensure_foreground")
    }

    private fun reassertForegroundForUsbExclusiveBackground(reason: String): Boolean {
        return startForegroundImmediately(
            notification = buildNotification(),
            reason = reason,
            verbose = false
        )
    }

    private fun clearActiveServiceReference() {
        if (activeServiceInstance === this) {
            activeServiceInstance = null
        }
    }

    private fun handleForegroundPromotionFailure(
        reason: String,
        startId: Int? = null
    ): Int {
        NPLogger.e("NERI-APS", "foreground promotion failed reason=$reason")
        allowServiceRestart = false
        isServiceForegroundActive = false
        isServiceInstanceActive = false
        clearActiveServiceReference()
        // 前台提升失败仅代表服务无法保持前台, 不代表播放运行时必须销毁
        // 若仍在播放或用户仍有播放诉求, 保留运行时以保住当前播放
        val preservePlayerRuntime = shouldPreservePlayerRuntimeOnForegroundPromotionFailure(
            enginePlaying = runCatching { PlayerManager.isPlayingFlow.value }.getOrDefault(false),
            playbackControlPlaying = runCatching { PlayerManager.playbackControlPlayingFlow.value }
                .getOrDefault(false),
        )
        if (preservePlayerRuntime) {
            // 让随后的 onDestroy 走"保留运行时"分支, 避免销毁正在播放的会话
            keepPlayerRuntimeAfterServiceStop = true
            NPLogger.w(
                "NERI-APS",
                "foreground promotion failed but playback is active; preserving player runtime reason=$reason"
            )
        }
        releaseServiceResourcesAfterForegroundFailure(reason, preservePlayerRuntime)
        shutdownUsbRuntime("foreground_promotion_failed:$reason")
        if (startId != null) {
            stopSelfResult(startId)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun releaseServiceResourcesAfterForegroundFailure(
        reason: String,
        preservePlayerRuntime: Boolean
    ) {
        usbExclusiveKeepAliveJob?.cancel()
        usbExclusiveKeepAliveJob = null
        serviceScope.coroutineContext.cancelChildren()
        disableUsbExclusiveMediaSessionVolumeRouting("foreground_promotion_failed:$reason")
        if (this::mediaSession.isInitialized) {
            runCatching {
                mediaSession.isActive = false
                mediaSession.release()
            }.onFailure { error ->
                NPLogger.w("NERI-APS", "media session release failed after FGS failure reason=$reason", error)
            }
        }
        if (preservePlayerRuntime) {
            // 保住当前播放: 不销毁 PlayerManager 运行时, 仅放弃前台化并停止服务
            NPLogger.i(
                "NERI-APS",
                "skipping player release after FGS failure to keep active playback alive reason=$reason"
            )
            return
        }
        runCatching { PlayerManager.release() }
            .onFailure { error ->
                NPLogger.w("NERI-APS", "player release failed after FGS failure reason=$reason", error)
            }
    }

    private fun shutdownUsbRuntime(reason: String) {
        UsbExclusiveSessionController.forceStopAllSessions(reason)
        UsbExclusiveSystemSoundGuard.forceRelease(this, reason)
        StartupAudioFocusController.forceRelease(reason)
    }

    private fun startForegroundImmediately(
        notification: Notification,
        reason: String,
        verbose: Boolean = true
    ): Boolean {
        return try {
            if (verbose) {
                NPLogger.d("NERI-APS", "startForegroundImmediately reason=$reason ${buildStateSummary()}")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
            isServiceForegroundActive = true
            if (verbose) {
                NPLogger.d("NERI-APS", "startForegroundImmediately success reason=$reason")
            }
            true
        } catch (e: SecurityException) {
            NPLogger.e("NERI-APS", "Failed to start foreground service, reason=$reason", e)
            false
        } catch (e: RuntimeException) {
            if (isForegroundStartNotAllowed(e)) {
                NPLogger.w("NERI-APS", "startForeground not allowed right now, reason=$reason: ${e.message}")
                false
            } else {
                throw e
            }
        }
    }

    private fun isForegroundStartNotAllowed(error: RuntimeException): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    }

    private fun stopForegroundIfStarted(reason: String) {
        if (!isForegroundStarted) {
            return
        }
        NPLogger.w("NERI-APS", "stopForegroundIfStarted reason=$reason ${buildStateSummary()}")
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
        isServiceForegroundActive = false
    }

    /**
     * 安全模式早退路径:无论是否经 startForegroundService 拉起,都先用不依赖任何成员的极简通知
     * 满足 Android 12+ 的 FGS 5s 契约,随后立即撤下前台并 stopSelf,
     * 避免 ForegroundServiceDidNotStartInTime / 读取未初始化 mediaSession 崩溃
     */
    private fun startForegroundForSafeModeThenStop(reason: String) {
        ensurePlaybackNotificationChannel()
        if (startForegroundImmediately(buildMinimalForegroundNotification(), reason)) {
            stopForegroundIfStarted(reason)
        }
        isServiceForegroundActive = false
        stopSelf()
    }

    private fun handleListenTogetherServiceStateChanged(reason: String) {
        if (!hasPlaybackSurfaceContent()) {
            stopSelfIfPlaybackSurfaceEmpty("listen_together_$reason")
            return
        }
        ensureForegroundStarted()
        updateAll()
    }

    private fun stopSelfIfPlaybackSurfaceEmpty(reason: String) {
        if (
            !hasReceivedStartCommand ||
            pendingStartCommands.isNotEmpty() ||
            hasPlaybackSurfaceContent()
        ) {
            return
        }
        allowServiceRestart = false
        NPLogger.w("NERI-APS", "Stopping service because playback surface is empty: reason=$reason")
        stopForegroundIfStarted(reason)
        stopSelf()
    }

    private fun playbackSurfaceSong(): SongItem? {
        return PlayerManager.currentSongFlow.value ?: listenTogetherRoomSong()
    }

    private fun listenTogetherRoomSong(): SongItem? {
        val room = AppContainer.listenTogetherSessionManager.roomState.value ?: return null
        val track = room.track ?: room.queue.getOrNull(room.currentIndex)
        return track?.toSongItem()
    }

    private fun hasPlaybackSurfaceContent(): Boolean {
        return PlayerManager.hasItems() || isListenTogetherSessionActive() || listenTogetherRoomSong() != null
    }

    private fun isListenTogetherSessionActive(): Boolean {
        return !AppContainer.listenTogetherSessionManager.sessionState.value.roomId.isNullOrBlank()
    }

    private fun isListenTogetherRemotePlaying(): Boolean {
        return AppContainer.listenTogetherSessionManager.roomState.value?.playback?.state == "playing"
    }

    private fun listenTogetherExpectedPositionMs(): Long {
        return AppContainer.listenTogetherSessionManager.roomState.value
            ?.let(::resolveListenTogetherMediaSessionPosition)
            ?: 0L
    }

    private fun NotificationPaddedIcon(
        @DrawableRes resId: Int,
        boxDp: Int = 24,
        glyphDp: Int = 18
    ): IconCompat {
        val d = (AppCompatResources.getDrawable(this, resId) ?: return IconCompat.createWithResource(this, resId)).mutate()
        DrawableCompat.setTintList(d, null)

        fun dp2px(dp: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()

        val boxPx = dp2px(boxDp)
        val glyphPx = dp2px(glyphDp)
        val left = (boxPx - glyphPx) / 2
        val top  = (boxPx - glyphPx) / 2

        val bmp = createBitmap(boxPx, boxPx)
        val canvas = Canvas(bmp)
        d.setBounds(left, top, left + glyphPx, top + glyphPx)
        d.draw(canvas)

        return IconCompat.createWithBitmap(bmp)
    }
}

internal fun resolveListenTogetherMediaSessionPosition(
    roomState: ListenTogetherRoomState,
    nowMs: Long = System.currentTimeMillis()
): Long {
    val activeTrack = roomState.track ?: roomState.queue.getOrNull(roomState.currentIndex)
    return roomState.playback.expectedPositionMs(
        nowMs = nowMs,
        durationMs = activeTrack?.durationMs ?: 0L
    )
}
