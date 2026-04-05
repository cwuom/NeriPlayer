package moe.ouom.neriplayer.core.player

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
 * File: moe.ouom.neriplayer.core.player/AudioPlayerService
 * Updated: 2026/3/23
 */

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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.session.MediaButtonReceiver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import androidx.core.graphics.createBitmap
import java.io.File
import android.content.pm.ServiceInfo
import androidx.core.net.toUri

private data class PlaybackNotificationSnapshot(
    val title: String,
    val text: String,
    val isTransportActive: Boolean,
    val isFavorite: Boolean,
    val requiresInteractiveFavoriteConfirmation: Boolean,
    val largeIconReady: Boolean,
    val coverSource: String?,
)

internal const val MEDIA_SESSION_STOP_SOURCE = "media_session_stop"

internal fun shouldStopServiceForExternalPauseCommand(
    source: String,
    stopServiceRequested: Boolean,
): Boolean {
    // 系统外部控制面板的 stop 经常只是“结束本次会话”，不能把当前队列一并释放掉
    return stopServiceRequested && source != MEDIA_SESSION_STOP_SOURCE
}

internal fun mediaSessionPlaybackActions(): Long {
    return PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_PLAY_PAUSE or
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
        PlaybackStateCompat.ACTION_SEEK_TO
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

private fun Context.findActivityReadyForDirectServiceStart(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            val isDestroyed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 &&
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
        const val ACTION_STOP = "moe.ouom.neriplayer.action.STOP"
        const val ACTION_NEXT = "moe.ouom.neriplayer.action.NEXT"
        const val ACTION_PREV = "moe.ouom.neriplayer.action.PREV"
        const val ACTION_SYNC = "moe.ouom.neriplayer.action.SYNC"
        const val ACTION_TOGGLE_FAV = "moe.ouom.neriplayer.action.TOGGLE_FAVORITE"
        const val EXTRA_START_SOURCE = "audio_service_start_source"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "neriplayer_playback_channel"
        private const val SYNC_START_DEDUPE_WINDOW_MS = 1500L
        @Volatile
        private var lastSuccessfulSyncStartElapsedRealtime: Long = 0L
        @Volatile
        private var lastSuccessfulSyncStartSource: String? = null

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
    }

    private lateinit var becomingNoisyReceiver: BroadcastReceiver

    private lateinit var mediaSession: MediaSessionCompat

    private var currentCoverSource: String? = null
    private var currentLargeIcon: Bitmap? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaSessionPlaybackStateThrottler = MediaSessionPlaybackStateThrottler()
    private var allowServiceRestart = true
    private var hasReceivedStartCommand = false
    private var isForegroundStarted = false
    private var lastNotificationSnapshot: PlaybackNotificationSnapshot? = null

    private fun shouldKeepServiceSticky(): Boolean {
        return PlayerManager.hasItems() && PlayerManager.shouldRunPlaybackServiceInForeground()
    }

    private fun buildStateSummary(): String {
        return "hasItems=${PlayerManager.hasItems()} currentSong=${PlayerManager.currentSongFlow.value != null} " +
            "isPlaying=${PlayerManager.isPlayingFlow.value} transportActive=${PlayerManager.isTransportActive()} " +
            "foreground=$isForegroundStarted allowRestart=$allowServiceRestart"
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { PlayerManager.play(); updateAll() }
        override fun onPause() { handleExternalPauseCommand("media_session_pause") }
        override fun onSkipToNext() { PlayerManager.next(); updateAll() }
        override fun onSkipToPrevious() { PlayerManager.previous(); updateAll() }
        override fun onStop() {
            handleExternalPauseCommand(MEDIA_SESSION_STOP_SOURCE, stopService = true)
        }
        override fun onSeekTo(pos: Long) {
            PlayerManager.seekTo(pos)
            updatePlaybackState(force = true)
            updateNotification()
        }
        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_TOGGLE_FAV) {
                if (canToggleFavoriteFromExternalSurface(PlayerManager.currentSongFlow.value)) {
                    PlayerManager.toggleCurrentFavorite()
                }
                updateAll()
            }
        }
    }

    private fun handleExternalPauseCommand(source: String, stopService: Boolean = false) {
        NPLogger.d("NERI-APS", "Received external pause command: source=$source")
        if (PlayerManager.shouldIgnoreExternalPauseCommand()) {
            NPLogger.w(
                "NERI-APS",
                "Ignored stale external pause during auto track transition: source=$source"
            )
            updatePlaybackState(force = true)
            updateNotification()
            return
        }
        PlayerManager.pause()
        updateAll()
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

    override fun onCreate() {
        super.onCreate()
        NPLogger.d("NERI-APS", "onCreate begin ${buildStateSummary()}")
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NeriPlayer Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        mediaSession = MediaSessionCompat(this, "NeriPlayerSession").apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }
        startForegroundImmediately(buildBootstrapNotification(), "service_create")

        // 服务必须尽快进入前台，不能在这里阻塞前台通知启动
        PlayerManager.initialize(application as Application)

        serviceScope.launch {
            PlayerManager.currentSongFlow.collect {
                if (it == null && !PlayerManager.hasItems()) {
                    if (!hasReceivedStartCommand) {
                        return@collect
                    }
                    NPLogger.w("NERI-APS", "currentSongFlow requested self-stop because playlist is empty")
                    stopForegroundIfStarted("playlist_became_empty")
                    stopSelf()
                    return@collect
                }
                updateMetadata()
                updatePlaybackState(force = true)
                updateNotification()
            }
        }

        serviceScope.launch {
            PlayerManager.isPlayingFlow.collect {
                updatePlaybackState()
                updateNotification()
            }
        }
        serviceScope.launch {
            PlayerManager.playWhenReadyFlow.collect {
                updatePlaybackState()
                updateNotification()
            }
        }
        serviceScope.launch {
            PlayerManager.playerPlaybackStateFlow.collect {
                updatePlaybackState()
                updateNotification()
            }
        }
        serviceScope.launch {
            PlayerManager.playbackPositionFlow.collect {
                updatePlaybackState()
            }
        }
        serviceScope.launch {
            PlayerManager.playbackSoundStateFlow.collect {
                updatePlaybackState()
            }
        }

        serviceScope.launch {
            PlayerManager.sleepTimerManager.timerState.collect {
                updateNotification()
            }
        }

        becomingNoisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    if (PlayerManager.handleAudioBecomingNoisy()) {
                        NPLogger.d("NERI-APS", "Handled audio becoming noisy according to playback policy.")
                        updatePlaybackState(force = true)
                        updateNotification()
                    }
                }
            }
        }
        val noisyIntentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val startSource = intent?.getStringExtra(EXTRA_START_SOURCE) ?: "unspecified"
        NPLogger.d(
            "NERI-APS",
            "onStartCommand action=$action source=$startSource flags=$flags startId=$startId ${buildStateSummary()}"
        )
        allowServiceRestart = true
        hasReceivedStartCommand = true

        if (!isForegroundStarted && action != ACTION_STOP) {
            startForegroundImmediately(buildBootstrapNotification(), "on_start_command:$action:$startSource")
        }
        if (action == null && !PlayerManager.hasItems()) {
            allowServiceRestart = false
            NPLogger.w("NERI-APS", "Stopping service because null action arrived without playlist")
            stopForegroundIfStarted("null_action_without_items")
            stopSelf()
            return START_NOT_STICKY
        }

        if (action != ACTION_STOP && action != null) {
            ensureForegroundStarted()
        }

        // 处理媒体按钮
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (action) {
            ACTION_PLAY -> {
                @Suppress("DEPRECATION")
                val songList = intent.getParcelableArrayListExtra<SongItem>("playlist")
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
                if (!PlayerManager.hasItems()) {
                    allowServiceRestart = false
                    NPLogger.w("NERI-APS", "Ignoring ACTION_SYNC because playlist is empty, source=$startSource")
                    stopForegroundIfStarted("sync_without_items")
                    stopSelf()
                    return START_NOT_STICKY
                }
                NPLogger.d("NERI-APS", "Handling ACTION_SYNC source=$startSource ${buildStateSummary()}")
                updateAll()
            }

            ACTION_TOGGLE_FAV -> {
                if (canToggleFavoriteFromExternalSurface(PlayerManager.currentSongFlow.value)) {
                    PlayerManager.toggleCurrentFavorite()
                }
                updateNotification()
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
        } else {
            allowServiceRestart = false
            NPLogger.w("NERI-APS", "Stopping service because playlist is empty after action handling")
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
        return startMode
    }

    private fun buildNotification(): Notification {
        val isTransportActive = PlayerManager.isTransportActive()
        val song = PlayerManager.currentSongFlow.value

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
        val favoriteActionIntent = if (requiresInteractiveFavoriteConfirmation(song)) {
            contentIntent
        } else {
            toggleFavIntent
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_neriplayer_round)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 3)
            )

        val isFav = isFavoriteSong(song)

        val favIcon = IconCompat.createWithResource(
            this,
            if (isFav) R.drawable.ic_baseline_favorite_24 else R.drawable.ic_outline_favorite_24
        )
        val favAction = NotificationCompat.Action.Builder(
            favIcon,
            if (isFav) getString(R.string.favorite_remove) else getString(R.string.favorite_add),
            favoriteActionIntent
        ).build()

        builder.addAction(R.drawable.round_skip_previous_24, getString(R.string.player_previous), prevIntent)
        builder.addAction(
            if (isTransportActive) R.drawable.round_pause_24 else R.drawable.round_play_arrow_24,
            if (isTransportActive) getString(R.string.player_pause) else getString(R.string.player_play),
            if (isTransportActive) pauseIntent else playIntent
        )
        builder.addAction(favAction)
        builder.addAction(R.drawable.round_skip_next_24, getString(R.string.player_next), nextIntent)

        builder.setContentTitle(song?.displayName() ?: "NeriPlayer")

        val timerState = PlayerManager.sleepTimerManager.timerState.value
        val contentText = if (timerState.isActive) {
            val timerInfo = when (timerState.mode) {
                SleepTimerMode.COUNTDOWN -> {
                    val remaining = PlayerManager.sleepTimerManager.formatRemainingTime()
                    getString(R.string.notification_timer_remaining, remaining)
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

        currentLargeIcon?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun buildBootstrapNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_neriplayer_round)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.player_notification_preparing))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun isFavoriteSong(song: SongItem?): Boolean {
        if (song == null) return false
        val favorites = PlayerManager.playlistsFlow.value
            .firstOrNull { FavoritesPlaylist.isSystemPlaylist(it, this) }
            ?: return false
        return favorites.songs.any { it.sameIdentityAs(song) }
    }

    private fun requiresInteractiveFavoriteConfirmation(song: SongItem?): Boolean {
        if (song == null) return false
        return !isFavoriteSong(song) && LocalSongSupport.isLocalSong(song, this)
    }

    private fun canToggleFavoriteFromExternalSurface(song: SongItem?): Boolean {
        return !requiresInteractiveFavoriteConfirmation(song)
    }

    private fun updateAll() {
        updateMetadata()
        updatePlaybackState(force = true)
        updateNotification()
    }

    /** 鏋勫缓鎸囧悜鏈?Service 鐨?PendingIntent */
    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioPlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        if (!isForegroundStarted) {
            return
        }
        val snapshot = buildNotificationSnapshot()
        if (snapshot == lastNotificationSnapshot) {
            return
        }
        lastNotificationSnapshot = snapshot
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotificationSnapshot(): PlaybackNotificationSnapshot {
        val song = PlayerManager.currentSongFlow.value
        val timerState = PlayerManager.sleepTimerManager.timerState.value
        val text = if (timerState.isActive) {
            val timerInfo = when (timerState.mode) {
                SleepTimerMode.COUNTDOWN -> {
                    val remaining = PlayerManager.sleepTimerManager.formatRemainingTime()
                    getString(R.string.notification_timer_remaining, remaining)
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
        return PlaybackNotificationSnapshot(
            title = song?.displayName() ?: "NeriPlayer",
            text = text,
            isTransportActive = PlayerManager.isTransportActive(),
            isFavorite = isFavoriteSong(song),
            requiresInteractiveFavoriteConfirmation = requiresInteractiveFavoriteConfirmation(song),
            largeIconReady = currentLargeIcon != null,
            coverSource = currentCoverSource,
        )
    }

    private fun updateMetadata() {
        val song = PlayerManager.currentSongFlow.value
        val duration = song?.durationMs ?: 0L
        val coverSource = song.effectiveCoverSource()

        if (coverSource != currentCoverSource) {
            currentCoverSource = coverSource
            currentLargeIcon = null
            requestLargeIconAsync(coverSource)
        }

        val displayTitle = song?.displayName() ?: "NeriPlayer"
        val displayArtist = song?.displayArtist().orEmpty()

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displayArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentLargeIcon)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentLargeIcon)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, currentLargeIcon)

        // Do not set local URIs to METADATA_KEY_ALBUM_ART_URI, as it may prompt the System UI
        // to attempt loading them directly (which can fail due to permission issues) and
        // override the bitmap we already provided via METADATA_KEY_ALBUM_ART

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun updatePlaybackState(force: Boolean = false) {
        val isTransportActive = PlayerManager.isTransportActive()
        val isBuffering = PlayerManager.isTransportBuffering()
        val pos = PlayerManager.playbackPositionFlow.value

        val song = PlayerManager.currentSongFlow.value
        val isFav = isFavoriteSong(song)

        val favIconRes = if (isFav) R.drawable.ic_baseline_favorite_24
        else R.drawable.ic_outline_favorite_24
        val favText = if (isFav) getString(R.string.favorite_remove) else getString(R.string.favorite_add)

        val favCustom = PlaybackStateCompat.CustomAction.Builder(
            ACTION_TOGGLE_FAV, favText, favIconRes
        ).build()

        val actions = mediaSessionPlaybackActions()

        val playbackState = when {
            isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            isTransportActive -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val playbackSpeed = if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
            PlayerManager.playbackSoundStateFlow.value.speed
        } else {
            0.0f
        }
        val controlFingerprint = when {
            !canToggleFavoriteFromExternalSurface(song) -> 0
            isFav -> 2
            else -> 1
        }
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

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                playbackState,
                pos,
                playbackSpeed
            )

        if (canToggleFavoriteFromExternalSurface(song)) {
            stateBuilder.addCustomAction(favCustom)
        }

        mediaSession.setPlaybackState(stateBuilder.build())
        mediaSessionPlaybackStateThrottler.recordDispatch(
            playbackState = playbackState,
            positionMs = pos,
            speed = playbackSpeed,
            controlFingerprint = controlFingerprint,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
    }

    private fun requestLargeIconAsync(url: String?) {
        if (url.isNullOrBlank()) {
            currentLargeIcon = null
            updateNotification()
            return
        }
        val appCtx = applicationContext
        serviceScope.launch(Dispatchers.IO) {
            try {
                val loader = coil.Coil.imageLoader(appCtx)
                val request = ImageRequest.Builder(appCtx)
                    .data(url)
                    .allowHardware(false)
                    .size(256)
                    .build()
                val result = loader.execute(request)
                val drawable = result.drawable ?: return@launch
                val bmp = drawable.toBitmap()
                withContext(Dispatchers.Main) {
                    if (url == currentCoverSource) {
                        currentLargeIcon = bmp
                        updateMetadata()
                        updateNotification()
                    }
                }
            } catch (e: Exception) {
                NPLogger.d("NERI-APS", "Cover load failed: ${e.message}")
            }
        }
    }

    private fun SongItem?.effectiveCoverSource(): String? {
        val song = this ?: return null
        return song.customCoverUrl?.takeIf { it.isNotBlank() }
            ?: song.coverUrl?.takeIf { it.isNotBlank() }
    }

    private fun normalizeArtworkUri(source: String?): String? {
        val raw = source?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching { raw.toUri() }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        return when {
            scheme.isNullOrBlank() -> filePathToUriString(raw)
            scheme == "file" -> parsed?.path?.let(::filePathToUriString) ?: raw
            else -> raw
        }
    }

    private fun filePathToUriString(path: String): String {
        val file = File(path)
        return Uri.fromFile(file).toString()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        NPLogger.w(
            "NERI-APS",
            "onTaskRemoved hasItems=${PlayerManager.hasItems()} isPlaying=${PlayerManager.isPlayingFlow.value}"
        )
        // 从最近任务移除时不再直接停播，只禁止这次会话后续自动恢复
        if (PlayerManager.hasItems()) {
            PlayerManager.suppressFutureAutoResumeForCurrentSession(forcePersist = true)
            updateNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NPLogger.w(
            "NERI-APS",
            "onDestroy ${buildStateSummary()}"
        )
        unregisterReceiver(becomingNoisyReceiver)
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        if (!allowServiceRestart || !PlayerManager.hasItems()) {
            PlayerManager.release()
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        NPLogger.w(
            "NERI-APS",
            "onTrimMemory level=$level ${buildStateSummary()}"
        )
    }

    override fun onLowMemory() {
        super.onLowMemory()
        NPLogger.w(
            "NERI-APS",
            "onLowMemory ${buildStateSummary()}"
        )
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

    private fun startForegroundImmediately(notification: Notification, reason: String): Boolean {
        return try {
            NPLogger.d("NERI-APS", "startForegroundImmediately reason=$reason ${buildStateSummary()}")
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
            NPLogger.d("NERI-APS", "startForegroundImmediately success reason=$reason")
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
    }

    private fun NotificationPaddedIcon(
        @DrawableRes resId: Int,
        boxDp: Int = 24,
        glyphDp: Int = 18
    ): IconCompat {
        val d = AppCompatResources.getDrawable(this, resId)!!.mutate()
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
