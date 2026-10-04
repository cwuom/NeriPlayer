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

import moe.ouom.neriplayer.data.sync.mapping.toSongItem
import moe.ouom.neriplayer.core.player.policy.service.TaskRemovedPlaybackCallbacks
import moe.ouom.neriplayer.core.player.policy.service.executeTaskRemovedPlaybackAction
import moe.ouom.neriplayer.core.player.policy.service.resolveTaskRemovedPlaybackAction
import moe.ouom.neriplayer.core.player.policy.service.resolveTaskRemovedTransportActive
import moe.ouom.neriplayer.core.player.service.artwork.AndroidPlaybackArtworkClock
import moe.ouom.neriplayer.core.player.service.artwork.AndroidPlaybackCoverSources
import moe.ouom.neriplayer.core.player.service.artwork.CoilPlaybackArtworkBitmapLoader
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkChange
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkOwner
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackCoverSourceResolver
import moe.ouom.neriplayer.core.player.runtime.service.PlaybackServiceIdleShutdownCoordinator
import moe.ouom.neriplayer.core.player.policy.service.shouldKeepPlaybackServiceSticky
import moe.ouom.neriplayer.core.player.policy.service.shouldPreservePlayerRuntimeOnForegroundPromotionFailure
import moe.ouom.neriplayer.core.player.policy.service.shouldSchedulePlaybackServiceIdleShutdown
import moe.ouom.neriplayer.core.player.policy.service.shouldUseStickyStartModeWhilePlayerRuntimeInitializes
import moe.ouom.neriplayer.core.player.service.lifecycle.suspendPlaybackForServiceRestart
import moe.ouom.neriplayer.core.player.service.notification.FlymeStatusBarLyricSupport
import moe.ouom.neriplayer.core.player.service.notification.isFloatingLyricsEffectivelyEnabled
import moe.ouom.neriplayer.core.player.service.notification.resolveStatusBarLyricNotificationState
import moe.ouom.neriplayer.core.player.service.notification.statusBarLyricNotificationStateFlow
import moe.ouom.neriplayer.core.player.service.presentation.AndroidPlaybackServicePresentationPort
import moe.ouom.neriplayer.core.player.service.presentation.AndroidPlaybackServicePresentationSource
import moe.ouom.neriplayer.core.player.service.presentation.PlaybackServicePresentationOwner
import moe.ouom.neriplayer.core.player.service.presentation.resolveListenTogetherMediaSessionPosition
import moe.ouom.neriplayer.core.player.service.usb.AndroidUsbExclusiveKeepAlivePort
import moe.ouom.neriplayer.core.player.service.usb.AndroidUsbExclusiveVolumeRoutingPort
import moe.ouom.neriplayer.core.player.service.usb.UsbExclusiveKeepAliveServiceHost
import moe.ouom.neriplayer.core.player.service.usb.UsbExclusiveMediaSessionVolumeRouter
import moe.ouom.neriplayer.core.player.service.usb.UsbExclusiveServiceKeepAliveOwner
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.PlayerManager.externalBluetoothLyricLineFlow
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.lifecycle.scheduleUsbExclusivePlaybackResumeAfterDeviceAttach
import moe.ouom.neriplayer.core.player.lifecycle.stopPlaybackAfterUsbExclusiveNativeFailure
import moe.ouom.neriplayer.core.player.persistence.persistStateNow
import moe.ouom.neriplayer.core.player.persistence.preloadRestoredStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.suppressPlaybackForAudioRouteLoss
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.path.sameUsbExclusiveAudioPathConfiguration
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemVolumeBridge
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.playback.DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES
import moe.ouom.neriplayer.data.settings.playback.PlaybackServiceIdleShutdownPreference
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshot
import moe.ouom.neriplayer.core.player.ltw.toSongItem
import moe.ouom.neriplayer.data.ltw.playback.currentTrack
import moe.ouom.neriplayer.core.player.presentation.widget.playbackWidgetProgressRefreshBucket
import moe.ouom.neriplayer.core.player.service.car.CAR_ACTION_CYCLE_REPEAT
import moe.ouom.neriplayer.core.player.service.car.CAR_ACTION_TOGGLE_SHUFFLE
import moe.ouom.neriplayer.core.player.service.car.CarMediaSessionCallback
import moe.ouom.neriplayer.core.player.service.car.CarMediaSessionControlPort
import moe.ouom.neriplayer.core.player.service.car.CarPlaybackBindingOwner
import moe.ouom.neriplayer.core.player.service.car.androidCarMediaLibrary
import moe.ouom.neriplayer.core.player.service.car.carMediaSessionQueue
import moe.ouom.neriplayer.core.player.service.car.carQueueIndex
import moe.ouom.neriplayer.core.player.service.car.clearCarQueueIdentityCache
import moe.ouom.neriplayer.core.player.service.car.library.CarPlaybackSelection
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import kotlin.time.Duration.Companion.milliseconds

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
            delay(SERVICE_FLOW_COLLECTOR_RESTART_DELAY_MS.milliseconds)
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

private data class PendingStartCommand(
    val intent: Intent?,
    val flags: Int,
    val startId: Int,
)

internal const val MEDIA_SESSION_STOP_SOURCE = "media_session_stop"
internal const val PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE = "play_songs_and_open_now_playing"
private const val PLAYBACK_STATE_PROGRESS_BUCKET_MS = 2_000L
private const val SERVICE_FLOW_COLLECTOR_RESTART_DELAY_MS = 1_000L
private const val TASK_REMOVED_STATE_PERSIST_TIMEOUT_MS = 3_000L

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
        AudioPlayerService.ACTION_RESTORE_VOLUME,
        AudioPlayerService.ACTION_TOGGLE_PLAY_PAUSE,
        AudioPlayerService.ACTION_NEXT,
        AudioPlayerService.ACTION_PREV,
        AudioPlayerService.ACTION_TOGGLE_FAV,
        AudioPlayerService.ACTION_TOGGLE_FLOATING_LYRICS -> true
        else -> false
    }
}

private fun Intent.usbDeviceExtra(): UsbDevice? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}

fun canUseDirectPlaybackServiceStart(
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

fun shouldSkipLocalPlaybackSyncServiceStart(
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
        const val ACTION_RESTORE_VOLUME = "moe.ouom.neriplayer.action.RESTORE_VOLUME"
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
        internal const val ACTION_BIND_CAR = "moe.ouom.neriplayer.action.BIND_CAR"

        internal const val NOTIFICATION_ID = 1
        internal const val CHANNEL_ID = "neriplayer_playback_channel"
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

        fun refreshPlaybackWidgetsFromActiveService(reason: String): Boolean {
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
            if (PlayerDependencies.presentation.shouldEnterSafeMode(context)) {
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

        fun dispatchPlaybackWidgetAction(
            context: Context,
            action: String,
        ): Boolean {
            if (!isSupportedPlaybackWidgetAction(action)) {
                NPLogger.w("NERI-APS", "Ignoring unsupported playback widget action: $action")
                return false
            }
            if (PlayerDependencies.presentation.shouldEnterSafeMode(context)) {
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

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, throwable ->
            NPLogger.e("NERI-AudioService", "Uncaught coroutine exception in serviceScope", throwable)
        }
    )
    private inner class UsbKeepAliveHost : UsbExclusiveKeepAliveServiceHost {
        override fun foregroundStarted(): Boolean = isForegroundStarted
        override fun reassertForeground(reason: String): Boolean =
            reassertForegroundForUsbExclusiveBackground(reason)
        override fun ensureForeground(): Boolean = ensureForegroundStarted()
        override fun onForegroundFailure(reason: String) {
            handleForegroundPromotionFailure(reason)
        }
        override fun updatePlaybackPresentation() {
            updatePlaybackState(force = true)
            updateNotification()
        }
    }

    private val usbKeepAliveOwner = UsbExclusiveServiceKeepAliveOwner(
        scope = serviceScope,
        port = AndroidUsbExclusiveKeepAlivePort(this, UsbKeepAliveHost()),
    )
    private val usbVolumeRouterDelegate = lazy {
        UsbExclusiveMediaSessionVolumeRouter(AndroidUsbExclusiveVolumeRoutingPort(
            context = this,
            mediaSession = { presentationOwner.sessionOrNull() },
            audioAttributes = presentationOwner.audioAttributes(),
        ))
    }
    private val usbVolumeRouter: UsbExclusiveMediaSessionVolumeRouter get() = usbVolumeRouterDelegate.value
    private val artworkOwnerDelegate = lazy {
        PlaybackArtworkOwner(
            resolver = PlaybackCoverSourceResolver(AndroidPlaybackCoverSources(applicationContext)),
            loader = CoilPlaybackArtworkBitmapLoader(applicationContext),
            clock = AndroidPlaybackArtworkClock,
            scope = serviceScope,
            ioDispatcher = Dispatchers.IO,
            onChange = ::onArtworkChanged,
        )
    }
    private val artworkOwner: PlaybackArtworkOwner get() = artworkOwnerDelegate.value
    private val presentationPortDelegate = lazy { AndroidPlaybackServicePresentationPort(this) }
    private val presentationPort: AndroidPlaybackServicePresentationPort get() = presentationPortDelegate.value
    private val presentationOwnerDelegate = lazy {
        PlaybackServicePresentationOwner(
            source = AndroidPlaybackServicePresentationSource(this),
            port = presentationPort,
            artwork = artworkOwner,
            scope = serviceScope,
        )
    }
    private val presentationOwner: PlaybackServicePresentationOwner get() = presentationOwnerDelegate.value

    private fun onArtworkChanged(change: PlaybackArtworkChange) {
        if (change == PlaybackArtworkChange.RESOLUTION_FINISHED_EMPTY) {
            updatePlaybackWidget(force = true)
            return
        }
        refreshArtworkPresentation(change)
    }

    private fun refreshArtworkPresentation(change: PlaybackArtworkChange) {
        if (change == PlaybackArtworkChange.SOURCE_RESOLVED) presentationOwner.invalidateMetadataSnapshot()
        updateMetadata()
        updateNotification()
    }

    private var allowServiceRestart = true
    private var hasReceivedStartCommand = false
    private var isForegroundStarted = false
    private var statusBarLyricState = resolveStatusBarLyricNotificationState(
        enabled = false,
        line = null,
    )
    private var floatingLyricsEnabledForNotification = false
    private var usbDeviceAttachHandlingEnabled = true
    private var playerInitializationJob: Job? = null
    private var playerRuntimeReady = false
    private val carPlaybackBinding = CarPlaybackBindingOwner {
        presentationOwner.sessionOrNull()?.sessionToken
    }
    private val pendingStartCommands = ArrayDeque<PendingStartCommand>()
    private val pendingPlayerActions = ArrayDeque<() -> Unit>()
    private var latestStartId = 0
    private var keepPlayerRuntimeAfterServiceStop = false
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
        if (!playerRuntimeReady) initializePlayerRuntime()
        if (playerRuntimeReady) {
            action()
            return
        }
        pendingPlayerActions.addLast(action)
        NPLogger.d("NERI-APS", "Deferring player action until runtime is ready: source=$source")
    }

    private fun refreshFavoriteSongKeys(): Boolean {
        return presentationOwner.refreshFavoriteSongKeys()
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
        if (!hasReceivedStartCommand) return false
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

    private fun updateUsbExclusiveBackgroundAudioAnchor(reason: String) {
        usbKeepAliveOwner.updateAnchor(reason)
    }

    private fun updateUsbExclusiveServiceKeepAlive(reason: String) {
        if (!hasReceivedStartCommand) return
        usbKeepAliveOwner.update(reason)
    }

    private fun requestUsbExclusiveBackgroundForegroundReassert(reason: String) {
        if (!hasReceivedStartCommand) return
        usbKeepAliveOwner.requestBackgroundForegroundReassert(reason)
    }

    private val mediaSessionCallback = CarMediaSessionCallback(object : CarMediaSessionControlPort {
        override fun runWhenReady(source: String, action: () -> Unit) = runWhenPlayerRuntimeReady(source, action)
        override fun runWhenLibraryReady(source: String, action: () -> Unit) = runWhenPlayerRuntimeReady(source) {
            serviceScope.launch {
                val ready = withTimeoutOrNull(10_000L) { PlayerManager.localPlaylistsReadyFlow.first { it } }
                if (ready == true) action()
            }
        }
        override fun library() = androidCarMediaLibrary(this@AudioPlayerService)
        override fun resume() = resumeFromMediaSession()
        override fun playSelection(selection: CarPlaybackSelection) = playCarSelection(selection)
        override fun playQueueItem(id: Long) {
            val index = carQueueIndex(PlayerManager.currentQueueFlow.value, id) ?: return
            if (!promoteMediaSessionPlayback()) return
            PlayerManager.playFromQueue(index)
            updateAll()
        }
        override fun pause(source: String, stopService: Boolean) = handleExternalPauseCommand(source, stopService)
        override fun next() {
            if (!promoteMediaSessionPlayback()) return
            PlayerManager.next()
            updateAll()
        }
        override fun previous() {
            if (!promoteMediaSessionPlayback()) return
            PlayerManager.previous()
            updateAll()
        }
        override fun seek(positionMs: Long) {
            PlayerManager.seekTo(positionMs)
            updatePlaybackState(force = true)
            updateNotification()
            updatePlaybackWidget(force = true)
        }
        override fun customAction(action: String, extras: Bundle?) = handleMediaSessionCustomAction(action)
    })

    private fun resumeFromMediaSession() {
        if (!PlayerManager.hasItems() && !PlayerManager.audioRouteMuteSuppressedFlow.value) return
        if (!promoteMediaSessionPlayback()) return
        if (PlayerManager.audioRouteMuteSuppressedFlow.value) PlayerManager.restoreAudioRouteMute()
        else PlayerManager.play()
        updateAll()
        refreshIdleShutdown("media_session_play")
    }

    private fun playCarSelection(selection: CarPlaybackSelection) {
        val song = selection.songs.getOrNull(selection.startIndex) ?: return
        if (PlayerManager.shouldBlockLocalSongSwitch(song, PlaybackCommandSource.LOCAL)) return
        if (!promoteMediaSessionPlayback()) return
        val playlistId = selection.localPlaylistId
        if (playlistId == null) PlayerManager.playPlaylist(selection.songs, selection.startIndex)
        else PlayerManager.playLocalPlaylist(playlistId, selection.songs, selection.startIndex)
        updateAll()
        refreshIdleShutdown("car_media_selection")
    }

    private fun promoteMediaSessionPlayback(): Boolean {
        if (!isForegroundStarted) {
            if (!startSyncService(this, "car_media_play", forceForeground = true)) return false
            if (!ensureForegroundStarted()) {
                handleForegroundPromotionFailure("car_media_play")
                return false
            }
        }
        keepPlayerRuntimeAfterServiceStop = false
        return true
    }

    private fun handleMediaSessionCustomAction(action: String) {
        when (action) {
            ACTION_TOGGLE_FAV -> {
                if (canToggleFavoriteFromExternalSurface(PlayerManager.currentSongFlow.value)) {
                    PlayerManager.toggleCurrentFavorite()
                }
            }
            ACTION_TOGGLE_FLOATING_LYRICS -> applyFloatingLyricsExternalAction(legacyHideAction = false)
            LEGACY_ACTION_HIDE_FLOATING_LYRICS -> applyFloatingLyricsExternalAction(legacyHideAction = true)
            CAR_ACTION_TOGGLE_SHUFFLE -> PlayerManager.setShuffle(!PlayerManager.shuffleModeFlow.value)
            CAR_ACTION_CYCLE_REPEAT -> PlayerManager.cycleRepeatMode()
            else -> return
        }
        updateAll()
    }

    private fun updateCarQueue() {
        val queue = PlayerManager.currentQueueSnapshot()
        presentationOwner.sessionOrNull()?.setQueue(carMediaSessionQueue(this, queue.playlist, queue.currentIndex))
    }

    private fun dispatchMediaButtonIntent(intent: Intent?) {
        presentationOwner.dispatchMediaButtonIntent(intent)
    }

    private fun updateMediaSessionVolumeRouting(pathState: UsbExclusiveAudioPathState) {
        usbVolumeRouter.update(pathState.effectivePath, PlayerManager.usbExclusivePreferences.bitPerfect)
    }

    private fun disableUsbExclusiveMediaSessionVolumeRouting(reason: String) {
        if (usbVolumeRouterDelegate.isInitialized()) {
            usbVolumeRouter.disable(reason)
        } else {
            UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        }
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
        presentationOwner.applyFloatingLyricsExternalAction(
            currentEnabled = isFloatingLyricsCurrentlyEnabled(),
            legacyHideAction = legacyHideAction,
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (PlayerDependencies.presentation.shouldEnterSafeMode(this)) {
            isServiceInstanceActive = false
            allowServiceRestart = false
            NPLogger.w("NERI-APS", "onCreate ignored because safe mode is active")
            return
        }
        startPlaybackService()
    }

    private fun startPlaybackService() {
        isServiceInstanceActive = true
        activeServiceInstance = this
        NPLogger.d("NERI-APS", "onCreate begin ${buildStateSummary()}")
        ensurePlaybackNotificationChannel()

        presentationOwner.initializeSession(mediaSessionCallback)
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        initializePlayerRuntime()
    }

    private fun initializePlayerRuntime() {
        if (PlayerManager.initialized) {
            finishPlayerRuntimeSetup()
            return
        }
        if (playerInitializationJob?.isActive == true) return
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
            delay(25L.milliseconds)
        }
    }

    private fun finishPlayerRuntimeSetup() {
        if (playerRuntimeReady) return
        playerRuntimeReady = true
        carPlaybackBinding.markRuntimeReady(true)
        refreshFavoriteSongKeys()

        serviceScope.launch {
            PlayerDependencies.repositories.settingsRepo.playbackServiceIdleShutdownMinutesFlow
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
            PlayerDependencies.repositories.settingsRepo.usbDeviceAttachHandlingEnabledFlow
                .collectSafely("usbDeviceAttachHandlingEnabledFlow") { enabled ->
                    usbDeviceAttachHandlingEnabled = enabled
                }
        }
        serviceScope.launch {
            PlayerManager.currentQueueFlow.collectSafely("carQueueFlow") {
                updateCarQueue()
                updateMetadata()
                updatePlaybackState(force = true)
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
                updateCarQueue()
                updatePlaybackState(force = true)
                updateNotification()
                updateUsbExclusiveServiceKeepAlive("current_song")
                refreshIdleShutdown("current_song")
            }
        }
        val listenTogetherSessionManager = PlayerDependencies.listenTogether
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
            PlayerDependencies.downloads.downloadPresenceVersion
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
            PlayerManager.audioRouteMuteSuppressedFlow.collectSafely("audioRouteMuteSuppressedFlow") {
                updateNotification()
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
            UsbExclusiveAudioPathTracker.state
                .distinctUntilChanged(::sameUsbExclusiveAudioPathConfiguration)
                .collectSafely("usbExclusiveAudioPathState") { pathState ->
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
                enabledFlow = PlayerDependencies.repositories.settingsRepo.statusBarLyricsEnabledFlow,
                lineFlow = externalBluetoothLyricLineFlow,
                deviceSupported = FlymeStatusBarLyricSupport.isSupported,
            ).collectSafely("statusBarLyricNotificationStateFlow") { state ->
                if (statusBarLyricState != state) {
                    statusBarLyricState = state
                    updateNotification()
                }
            }
        }
        serviceScope.launch {
            PlayerDependencies.repositories.settingsRepo.floatingLyricsPreferencesFlow
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
                        if (PlayerManager.shouldMuteListenTogetherListenerForAudioRouteLoss()) {
                            PlayerManager.suppressPlaybackForAudioRouteLoss(
                                reason = "listen_together_usb_output_disconnect"
                            )
                        }
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
                            !PlayerDependencies.presentation.shouldProcessUsbAttachedAction(
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
        if (PlayerDependencies.presentation.shouldEnterSafeMode(this)) {
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
        keepPlayerRuntimeAfterServiceStop = false
        if (!playerRuntimeReady) initializePlayerRuntime()
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
            ACTION_RESTORE_VOLUME -> {
                PlayerManager.restoreAudioRouteMute()
                updateAll()
            }
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
                    if (PlayerManager.audioRouteMuteSuppressedFlow.value) {
                        PlayerManager.restoreAudioRouteMute()
                    } else {
                        PlayerManager.play()
                    }
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

    private fun buildNotification(): Notification = presentationOwner.buildNotification(
        lyricState = statusBarLyricState,
        floatingLyricsEnabled = isFloatingLyricsCurrentlyEnabled(),
    )

    private fun buildBootstrapNotification(): Notification = presentationOwner.buildBootstrapNotification()

    private fun buildMinimalForegroundNotification(): Notification =
        presentationPort.buildMinimalForegroundNotification()

    private fun ensurePlaybackNotificationChannel() {
        presentationPort.ensureNotificationChannel()
    }

    private fun canToggleFavoriteFromExternalSurface(song: SongItem?): Boolean =
        presentationOwner.canToggleFavorite(song)

    private fun updateAll() {
        updateMetadata()
        updatePlaybackState(force = true)
        updateNotification()
        updatePlaybackWidget()
    }

    private fun updateNotification(force: Boolean = false) {
        presentationOwner.updateNotification(
            force = force,
            foregroundStarted = isForegroundStarted,
            lyricState = statusBarLyricState,
            floatingLyricsEnabled = isFloatingLyricsCurrentlyEnabled(),
        )
    }

    private fun updatePlaybackWidget(force: Boolean = false) {
        presentationOwner.updateWidget(force, isFloatingLyricsCurrentlyEnabled())
    }

    private fun updatePlaybackWidgetProgress() {
        presentationOwner.updateWidgetProgress(isFloatingLyricsCurrentlyEnabled())
    }

    private fun updateMetadata() {
        presentationOwner.updateMetadata()
    }

    private fun updatePlaybackState(force: Boolean = false) {
        presentationOwner.updatePlaybackState(force, isFloatingLyricsCurrentlyEnabled())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val hasPlaybackSurfaceContent = hasPlaybackSurfaceContent()
        val hasItems = PlayerManager.hasItems()
        val playerTransportActive = runCatching {
            PlayerManager.isTransportActiveWithoutInitialization()
        }.getOrDefault(false)
        val listenTogetherRemotePlaying = isListenTogetherRemotePlaying()
        val transportActive = resolveTaskRemovedTransportActive(
            playerTransportActive = playerTransportActive,
            listenTogetherRemotePlaying = listenTogetherRemotePlaying,
        )
        val taskRemovedAction = resolveTaskRemovedPlaybackAction(
            hasPlaybackSurfaceContent = hasPlaybackSurfaceContent,
            playerTransportActive = playerTransportActive,
            listenTogetherRemotePlaying = listenTogetherRemotePlaying,
            hasItems = hasItems,
        )
        NPLogger.w(
            "NERI-APS",
            "onTaskRemoved hasSurface=$hasPlaybackSurfaceContent " +
                "transportActive=$transportActive playerTransport=$playerTransportActive " +
                "listenTogetherRemotePlaying=$listenTogetherRemotePlaying hasItems=$hasItems " +
                "isPlaying=${PlayerManager.isPlayingFlow.value}"
        )
        if (taskRemovedAction.stopPlaybackImmediately) {
            allowServiceRestart = false
            flushPlaybackStatsSafely("task_removed", "task removed")
            serviceScope.launch {
                executeTaskRemovedPlaybackAction(
                    action = taskRemovedAction,
                    callbacks = taskRemovedPlaybackCallbacks()
                )
            }
            return
        }
        if (taskRemovedAction.persistPlaybackState) {
            serviceScope.launch {
                executeTaskRemovedPlaybackAction(
                    action = taskRemovedAction,
                    callbacks = taskRemovedPlaybackCallbacks()
                )
            }
        }
    }

    private fun taskRemovedPlaybackCallbacks(): TaskRemovedPlaybackCallbacks {
        return TaskRemovedPlaybackCallbacks(
            stopPlaybackImmediately = {
                PlayerManager.stopPlaybackImmediately(
                    reason = "task_removed",
                    forcePersist = false
                )
            },
            persistPlaybackState = { reason ->
                persistTaskRemovedPlaybackState(reason)
            },
            stopForegroundIfStarted = { reason ->
                stopForegroundIfStarted(reason)
            },
            stopSelf = {
                stopSelf()
            },
            updateNotification = {
                updateNotification()
            },
            onPlaybackStopFailure = { error ->
                NPLogger.w("NERI-APS", "playback stop failed during task removed", error)
            },
            onNotificationUpdateFailure = { error ->
                NPLogger.w(
                    "NERI-APS",
                    "notification update failed during inactive task removed",
                    error
                )
            },
        )
    }

    private suspend fun persistTaskRemovedPlaybackState(reason: String): Boolean {
        return runCatching {
            withTimeout(TASK_REMOVED_STATE_PERSIST_TIMEOUT_MS.milliseconds) {
                PlayerManager.persistStateNow(
                    positionMs = PlayerManager.playbackPositionFlow.value,
                    shouldResumePlayback = false,
                    reason = reason
                )
            }
        }.onFailure { error ->
            NPLogger.w("NERI-APS", "state persist failed during $reason", error)
        }.getOrDefault(false)
    }

    private fun flushPlaybackStatsSafely(reason: String, context: String) {
        runCatching { PlayerManager.flushPlaybackStatsAsync(reason) }
            .onFailure { NPLogger.w("NERI-APS", "playback stats flush failed during $context", it) }
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (!presentationOwnerDelegate.isInitialized()) return null
        return carPlaybackBinding.bind(intent)?.also {
            if (!hasReceivedStartCommand) keepPlayerRuntimeAfterServiceStop = true
        }
    }

    override fun onUnbind(intent: Intent?): Boolean = carPlaybackBinding.unbind(intent)

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        carPlaybackBinding.rebind(intent)
    }

    override fun onDestroy() {
        NPLogger.w("NERI-APS", "onDestroy ${buildStateSummary()}")
        val preservePlaybackForRestart = shouldPreservePlaybackForRestart()
        try {
            releaseServiceOwnedResources()
            releasePlayerRuntimeAfterServiceStop(preservePlaybackForRestart)
        } finally {
            clearActiveServiceReference()
            playerRuntimeReady = false
            carPlaybackBinding.markRuntimeReady(false)
            if (presentationOwnerDelegate.isInitialized()) presentationOwner.resetFavoriteSongKeys()
            shutdownUsbRuntime("service_destroy")
            super.onDestroy()
        }
    }

    private fun shouldPreservePlaybackForRestart(): Boolean {
        if (!allowServiceRestart) return false
        return shouldKeepServiceSticky()
    }

    private fun releaseServiceOwnedResources() {
        isServiceForegroundActive = false
        isServiceInstanceActive = false
        idleShutdownCoordinator.cancel()
        cancelPlayerInitializationForDestroy()
        pendingStartCommands.clear()
        pendingPlayerActions.clear()
        flushPlaybackStatsSafely("service_destroy", "destroy")
        unregisterNoisyReceiverForDestroy()
        cancelUsbKeepAliveForDestroy()
        closeArtworkOwnerForDestroy()
        serviceScope.cancel()
        disableUsbExclusiveMediaSessionVolumeRouting("service_destroy")
        releaseMediaSessionForDestroy()
        clearCarQueueIdentityCache()
    }

    private fun cancelPlayerInitializationForDestroy() {
        playerInitializationJob?.cancel()
        playerInitializationJob = null
    }

    private fun cancelUsbKeepAliveForDestroy() {
        usbKeepAliveOwner.close()
    }

    private fun unregisterNoisyReceiverForDestroy() {
        if (!this::becomingNoisyReceiver.isInitialized) return
        unregisterInitializedNoisyReceiver()
    }

    private fun unregisterInitializedNoisyReceiver() {
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
            .onFailure { NPLogger.w("NERI-APS", "unregisterReceiver failed during destroy", it) }
    }

    private fun closeArtworkOwnerForDestroy() {
        if (artworkOwnerDelegate.isInitialized()) artworkOwner.close()
    }

    private fun releaseMediaSessionForDestroy() {
        if (presentationOwnerDelegate.isInitialized()) presentationOwner.releaseSessionForDestroy()
    }

    private fun releasePlayerRuntimeAfterServiceStop(preservePlaybackForRestart: Boolean) {
        if (keepPlayerRuntimeAfterServiceStop) {
            NPLogger.i("NERI-APS", "Keeping paused player runtime after idle service shutdown")
            return
        }
        releasePlayerRuntimeUnlessKept(preservePlaybackForRestart)
    }

    private fun releasePlayerRuntimeUnlessKept(preservePlaybackForRestart: Boolean) {
        if (preservePlaybackForRestart) {
            suspendPlayerRuntimeForRestart()
        } else {
            releasePlayerRuntimeForDestroy()
        }
    }

    private fun suspendPlayerRuntimeForRestart() {
        runCatching { PlayerManager.suspendPlaybackForServiceRestart("service_destroy") }
            .onFailure { NPLogger.w("NERI-APS", "player suspend failed during restartable destroy", it) }
    }

    private fun releasePlayerRuntimeForDestroy() {
        runCatching { PlayerManager.release() }
            .onFailure { NPLogger.w("NERI-APS", "player release failed during destroy", it) }
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
        hasReceivedStartCommand = false
        pendingStartCommands.clear()
        pendingPlayerActions.clear()
        mediaSessionCallback.cancelPendingPlaybackRequests()
        stopForegroundIfStarted("foreground_promotion_failed:$reason")
        isServiceForegroundActive = false
        isServiceInstanceActive = carPlaybackBinding.isBound
        if (!carPlaybackBinding.isBound) clearActiveServiceReference()
        // 前台提升失败仅代表服务无法保持前台, 不代表播放运行时必须销毁
        // 保留正在播放的运行时, 以及仅浏览绑定时已有的暂停队列
        val preservePlayerRuntime = shouldPreservePlayerRuntimeOnForegroundPromotionFailure(
            enginePlaying = runCatching { PlayerManager.isPlayingFlow.value }.getOrDefault(false),
            playbackControlPlaying = runCatching { PlayerManager.playbackControlPlayingFlow.value }
                .getOrDefault(false),
            keepPausedRuntime = keepPlayerRuntimeAfterServiceStop || carPlaybackBinding.isBound,
        )
        if (preservePlayerRuntime) {
            // 让随后的 onDestroy 保留已有运行时
            keepPlayerRuntimeAfterServiceStop = true
            NPLogger.w(
                "NERI-APS",
                "foreground promotion failed; preserving existing player runtime reason=$reason"
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
        cancelUsbKeepAliveAfterForegroundFailure()
        disableUsbExclusiveMediaSessionVolumeRouting("foreground_promotion_failed:$reason")
        // 仍有车机绑定时 stopSelf 不会销毁服务，保留会话和观察任务以便重试
        if (carPlaybackBinding.isBound) return
        serviceScope.coroutineContext.cancelChildren()
        releaseMediaSessionAfterForegroundFailure(reason)
        clearCarQueueIdentityCache()
        releasePlayerAfterForegroundFailure(reason, preservePlayerRuntime)
    }

    private fun cancelUsbKeepAliveAfterForegroundFailure() {
        usbKeepAliveOwner.cancelLoop()
    }

    private fun releaseMediaSessionAfterForegroundFailure(reason: String) {
        if (presentationOwnerDelegate.isInitialized()) {
            presentationOwner.releaseSessionAfterForegroundFailure(reason)
        }
    }

    private fun releasePlayerAfterForegroundFailure(reason: String, preservePlayerRuntime: Boolean) {
        if (preservePlayerRuntime) {
            // 前台失败后停止服务, 保留已有播放或暂停队列
            NPLogger.i(
                "NERI-APS",
                "skipping player release after FGS failure to keep existing runtime reason=$reason"
            )
            return
        }
        releasePlayerRuntimeAfterForegroundFailure(reason)
    }

    private fun releasePlayerRuntimeAfterForegroundFailure(reason: String) {
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
     * 安全模式早退时仅创建通知端口，先满足 FGS 5s 契约，再撤下前台
     * 避免初始化 MediaSession 和封面加载状态
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
        if (!hasReceivedStartCommand) {
            updateMetadata()
            updatePlaybackState(force = true)
            return
        }
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
        val room = PlayerDependencies.listenTogether.roomState.value ?: return null
        val track = room.currentTrack()
        return track?.toSongItem()
    }

    private fun hasPlaybackSurfaceContent(): Boolean {
        return PlayerManager.hasItems() || isListenTogetherSessionActive() || listenTogetherRoomSong() != null
    }

    private fun isListenTogetherSessionActive(): Boolean {
        return !PlayerDependencies.listenTogether.sessionState.value.roomId.isNullOrBlank()
    }

    private fun isListenTogetherRemotePlaying(): Boolean {
        return PlayerDependencies.listenTogether.roomState.value?.playback?.state == "playing"
    }

    private fun listenTogetherExpectedPositionMs(): Long {
        return PlayerDependencies.listenTogether.roomState.value
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
