@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Usb
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.di.AppContainer.biliCookieRepo
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.core.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.effects.AudioReactive
import moe.ouom.neriplayer.core.player.engine.ReactiveRenderersFactory
import moe.ouom.neriplayer.core.player.engine.datasource.ConditionalHttpDataSourceFactory
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.lyrics.FloatingLyricsOverlayManager
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.core.player.lyrics.clearExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.lyrics.syncExternalBluetoothLyrics
import moe.ouom.neriplayer.core.player.lyrics.syncFloatingTranslatedLyrics
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.audio.isBluetoothOutputType
import moe.ouom.neriplayer.core.player.audio.isHeadsetLikeOutput
import moe.ouom.neriplayer.core.player.audio.isUsbOutputType
import moe.ouom.neriplayer.core.player.audio.isWiredOutputType
import moe.ouom.neriplayer.core.player.audio.requiresDisconnectConfirmation
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDebugLogger
import moe.ouom.neriplayer.core.player.debug.playWhenReadyChangeReasonName
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.offload.requiresPcmAudioProcessing
import moe.ouom.neriplayer.core.player.policy.usb.evaluateUsbExclusiveKeepAliveProgress
import moe.ouom.neriplayer.core.player.policy.pending.shouldAcceptPlayerCallback
import moe.ouom.neriplayer.core.player.policy.pending.shouldExposePlayerCallbackState
import moe.ouom.neriplayer.core.player.policy.usb.shouldSkipUsbExclusiveRouteRebuildForManualPlayback
import moe.ouom.neriplayer.core.player.policy.usb.isTransientUsbExclusiveOpenGate
import moe.ouom.neriplayer.core.player.policy.usb.shouldApplyActiveUsbBufferResize
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.core.player.policy.command.shouldClearResumePlaybackRequestOnPlayWhenReadyPause
import moe.ouom.neriplayer.core.player.playback.advanceAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.playback.clearAudioRouteMuteSuppression
import moe.ouom.neriplayer.core.player.playback.pauseForAudioRouteLoss
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.core.player.playback.restorePlaybackAfterTransientAudioRouteLoss
import moe.ouom.neriplayer.core.player.playback.startProgressUpdates
import moe.ouom.neriplayer.core.player.playback.suppressPlaybackForAudioRouteLoss
import moe.ouom.neriplayer.core.player.playback.PlaybackStatsTracker
import moe.ouom.neriplayer.core.player.prefetch.prefetchNextGenericTrackUrl
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.applyRestoredStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.restoreState
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeSeekRefreshPolicy
import moe.ouom.neriplayer.core.player.url.offlineCacheKeyFromUrl
import moe.ouom.neriplayer.core.player.url.shouldAttemptUrlRefresh
import moe.ouom.neriplayer.core.player.url.youtubePlaybackRecoveryStrategyForError
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveWakeLock
import moe.ouom.neriplayer.core.player.watchdog.cancelPlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.clearActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.trySwitchToNextPlaybackCandidateForRecovery
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.toUsbExclusivePreferences
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File

internal fun PlayerManager.initializeImpl(
    app: Application,
    maxCacheSize: Long = 1024L * 1024 * 1024,
    startupPlaybackPreferences: PlaybackPreferenceSnapshot? = null,
    restoredStateSnapshot: RestoredPlayerStateSnapshot? = null
) {
    synchronized(initializationLock) {
        if (initialized) {
            NPLogger.d("NERI-PlayerManager", "initialize(): ignored because already initialized")
            return
        }
        if (initializationInProgress) {
            NPLogger.d("NERI-PlayerManager", "initialize(): ignored because initialization is already running")
            return
        }
        initializationInProgress = true
    }
    try {
        runCatching {
            NPLogger.d(
                "NERI-PlayerManager",
                "initialize(): maxCacheSize=$maxCacheSize, app=${app.packageName}, stack=[${debugStackHint()}]"
            )
            application = app
            FloatingLyricsOverlayManager.initialize(app)
            currentCacheSize = maxCacheSize

            ioScope = newIoScope()
            mainScope = newMainScope()

        stateFile = File(app.filesDir, "last_playlist.json")
        playbackStateFile = File(app.filesDir, "last_playback_state.json")
        lastPersistedPlaylistReference = null
        lastPersistedPlaybackState = null
        lastStatePersistAtMs = 0L
        playbackStatsTracker = PlaybackStatsTracker()
        playbackStatsPersistJob = null
        val initialPlaybackPreferences =
            startupPlaybackPreferences ?: readPlaybackPreferenceSnapshotSync(app)
        preferredQuality = initialPlaybackPreferences.audioQuality
        youtubePreferredQuality = initialPlaybackPreferences.youtubeAudioQuality
        biliPreferredQuality = initialPlaybackPreferences.biliAudioQuality
        mobileDataFollowDefaultAudioQuality =
            initialPlaybackPreferences.mobileDataFollowDefaultAudioQuality
        mobileDataNeteaseAudioQuality =
            initialPlaybackPreferences.mobileDataNeteaseAudioQuality
        mobileDataYouTubeAudioQuality =
            initialPlaybackPreferences.mobileDataYouTubeAudioQuality
        mobileDataBiliAudioQuality =
            initialPlaybackPreferences.mobileDataBiliAudioQuality
        keepLastPlaybackProgressEnabled =
            initialPlaybackPreferences.keepLastPlaybackProgress
        keepPlaybackModeStateEnabled =
            initialPlaybackPreferences.keepPlaybackModeState
        neteaseAutoSourceSwitchEnabled =
            initialPlaybackPreferences.neteaseAutoSourceSwitch
        playbackFadeInEnabled = initialPlaybackPreferences.playbackFadeIn
        playbackCrossfadeNextEnabled =
            initialPlaybackPreferences.playbackCrossfadeNext
        playbackFadeInDurationMs =
            initialPlaybackPreferences.playbackFadeInDurationMs
        playbackFadeOutDurationMs =
            initialPlaybackPreferences.playbackFadeOutDurationMs
        playbackCrossfadeInDurationMs =
            initialPlaybackPreferences.playbackCrossfadeInDurationMs
        playbackCrossfadeOutDurationMs =
            initialPlaybackPreferences.playbackCrossfadeOutDurationMs
        stopOnBluetoothDisconnectEnabled =
            initialPlaybackPreferences.stopOnBluetoothDisconnect
        usbExclusivePlaybackEnabled =
            initialPlaybackPreferences.usbExclusivePlayback
        usbExclusivePreferences = initialPlaybackPreferences.toUsbExclusivePreferences()
        UsbExclusiveAudioPathTracker.updateRequested(usbExclusivePlaybackEnabled)
        allowMixedPlaybackEnabled =
            initialPlaybackPreferences.allowMixedPlayback
        cloudMusicLyricDefaultOffsetMs =
            initialPlaybackPreferences.cloudMusicLyricDefaultOffsetMs
        qqMusicLyricDefaultOffsetMs =
            initialPlaybackPreferences.qqMusicLyricDefaultOffsetMs
        externalBluetoothLyricsEnabled = false
        lyriconEnabled = initialPlaybackPreferences.lyriconEnabled
        LyriconManager.setEnabled(lyriconEnabled)
        if (lyriconEnabled && !LyriconManager.isInitialized()) {
            LyriconManager.initialize(app)
        }
        playbackSoundConfig = initialPlaybackPreferences.toPlaybackSoundConfig()
        NPLogger.d(
            "NERI-PlayerManager",
            "initialize(): prefs quality=$preferredQuality, youtubeQuality=$youtubePreferredQuality, biliQuality=$biliPreferredQuality, mobileDataFollowDefault=$mobileDataFollowDefaultAudioQuality, mobileDataQuality=$mobileDataNeteaseAudioQuality/$mobileDataYouTubeAudioQuality/$mobileDataBiliAudioQuality, keepProgress=$keepLastPlaybackProgressEnabled, keepMode=$keepPlaybackModeStateEnabled, neteaseAutoSourceSwitch=$neteaseAutoSourceSwitchEnabled, fadeIn=$playbackFadeInEnabled/${playbackFadeInDurationMs}ms, crossfade=$playbackCrossfadeNextEnabled/${playbackCrossfadeInDurationMs}ms, stopOnBluetoothDisconnect=$stopOnBluetoothDisconnectEnabled, usbExclusivePlayback=$usbExclusivePlaybackEnabled, allowMixedPlayback=$allowMixedPlaybackEnabled"
        )
        val okHttpClient = AppContainer.sharedOkHttpClient
        val upstreamFactory: HttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
        val conditionalFactory = ConditionalHttpDataSourceFactory(
            upstreamFactory,
            biliCookieRepo,
            AppContainer.youtubeAuthRepo,
            trafficStatsRepository = AppContainer.trafficStatsRepo
        )
        conditionalHttpFactory = conditionalFactory

        val finalDataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (maxCacheSize > 0) {
            val cacheDir = File(app.cacheDir, "media_cache")
            val dbProvider = StandaloneDatabaseProvider(app)

            cache = SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(maxCacheSize),
                dbProvider
            )

            val cacheDsFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(conditionalFactory)
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                .setEventListener(object : CacheDataSource.EventListener {
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                        AppContainer.trafficStatsRepo.recordCacheHitBytes(cachedBytesRead)
                    }

                    override fun onCacheIgnored(reason: Int) = Unit
                })

            androidx.media3.datasource.DefaultDataSource.Factory(app, cacheDsFactory)
        } else {
            NPLogger.d("NERI-Player", "Cache disabled by user setting (size=0).")
            androidx.media3.datasource.DefaultDataSource.Factory(app, conditionalFactory)
        }

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            finalDataSourceFactory,
            extractorsFactory
        )

        val renderersFactory = ReactiveRenderersFactory(app)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        player = ExoPlayer.Builder(app, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(buildAudioLoadControl())
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NONE)
            }
        _playbackSoundState.value = playbackEffectsController.attachPlayer(player)
        applyPlaybackSoundConfig(playbackSoundConfig, persist = false)
        applyAudioFocusPolicy()
        applyUsbExclusivePlaybackPolicy()
        _playWhenReadyFlow.value = player.playWhenReady
        _playerPlaybackStateFlow.value = player.playbackState

        AudioReactive.onEnabledChanged = { enabled ->
            mainScope.launch {
                updateAudioOffloadPreferences("audio_reactive_$enabled")
            }
        }
        updateAudioOffloadPreferences("player_initialize")
        player.addAudioOffloadListener(object : ExoPlayer.AudioOffloadListener {
            override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
                NPLogger.i(
                    "NERI-PlayerManager",
                    "audio offload playback changed: active=$isOffloadedPlayback"
                )
            }

            override fun onSleepingForOffloadChanged(isSleepingForOffload: Boolean) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "audio offload scheduling sleep changed: sleeping=$isSleepingForOffload"
                )
            }
        })

        player.repeatMode = Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                NPLogger.e("NERI-Player", "onPlayerError: ${error.errorCodeName}", error)
                cancelPlaybackStartupWatchdog(reason = "player_error")

                if (!shouldAcceptPlayerCallback(
                        playbackRequestToken,
                        loadedMediaRequestToken,
                        isPendingMediaLoadActive()
                    )
                ) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Ignoring stale player error during pending media load: requestToken=$playbackRequestToken, loadedToken=$loadedMediaRequestToken, error=${error.errorCodeName}"
                    )
                    return
                }

                val currentSong = _currentSongFlow.value
                val currentUrl = _currentMediaUrl.value
                val isOfflineCache = currentUrl?.startsWith("http://offline.cache/") == true

                val cause = error.cause
                val shouldResumeAfterRecovery = resumePlaybackRequested || player.playWhenReady || player.isPlaying
                if (
                    shouldResumeAfterRecovery &&
                    trySwitchToNextPlaybackCandidateForRecovery(reason = "player_error_${error.errorCodeName}")
                ) {
                    return
                }

                if (shouldAttemptUrlRefresh(error, currentSong, isOfflineCache)) {
                    val youtubeRecoveryStrategy = youtubePlaybackRecoveryStrategyForError(
                        error = error,
                        song = currentSong,
                        isOfflineCache = isOfflineCache
                    )
                    val cacheKeyToInvalidateBeforeResolve = if (youtubeRecoveryStrategy != null) {
                        offlineCacheKeyFromUrl(currentUrl)
                    } else {
                        null
                    }
                    val shouldBypassRefreshCooldown = (
                        pendingSeekPositionOrNull() != null &&
                            YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(
                                currentSong,
                                currentUrl
                            )
                        ) || cacheKeyToInvalidateBeforeResolve != null
                    val resumePositionMs = pendingSeekPositionOrNull()
                        ?: player.currentPosition.coerceAtLeast(0L)
                    val resumePlaybackAfterRefresh = player.playWhenReady || player.isPlaying
                    refreshCurrentSongUrl(
                        resumePositionMs = resumePositionMs,
                        allowFallback = false,
                        reason = "playback_error_${error.errorCodeName}",
                        bypassCooldown = shouldBypassRefreshCooldown,
                        fallbackSeekPositionMs = resumePositionMs,
                        resumePlaybackAfterRefresh = resumePlaybackAfterRefresh,
                        resumedPlaybackCommandSource = activePlaybackCommandSource,
                        youtubeRecoveryStrategy = youtubeRecoveryStrategy,
                        cacheKeyToInvalidateBeforeResolve = cacheKeyToInvalidateBeforeResolve
                    )
                    return
                }

                consecutivePlayFailures++

                val msg = when {
                    isOfflineCache -> {
                        NPLogger.w(
                            "NERI-Player",
                            "Offline cached playback failed, pausing current song and waiting for recovery."
                        )
                        getLocalizedString(
                            R.string.player_playback_failed_with_code,
                            error.errorCodeName
                        )
                    }
                    cause?.message?.contains("no protocol: null", ignoreCase = true) == true ->
                        getLocalizedString(R.string.player_playback_invalid_url)
                    error.errorCode ==
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        getLocalizedString(R.string.player_playback_network_error)
                    else ->
                        getLocalizedString(
                            R.string.player_playback_failed_with_code,
                            error.errorCodeName
                        )
                }

                postPlayerEvent(PlayerEvent.ShowError(msg))

                if (!resumePlaybackRequested) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "ignore playback failure auto-advance because playback is no longer requested"
                    )
                    return
                }

                if (consecutivePlayFailures >= MAX_CONSECUTIVE_FAILURES) {
                    stopPlaybackPreservingQueue(clearMediaUrl = true)
                    return
                }

                if (isOfflineCache) {
                    pause()
                } else {
                    mainScope.launch {
                        advanceAfterPlaybackFailure(
                            source = "playback_error_${error.errorCodeName}"
                        )
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!shouldExposePlayerCallbackState(
                        playbackRequestToken,
                        loadedMediaRequestToken,
                        isPendingMediaLoadActive()
                    )
                ) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Ignoring stale playback state during pending media load: requestToken=$playbackRequestToken, loadedToken=$loadedMediaRequestToken, state=${playbackStateName(state)}"
                    )
                    return
                }
                _playerPlaybackStateFlow.value = state
                if (state == Player.STATE_BUFFERING && player.playWhenReady) {
                    schedulePlaybackStartupWatchdog(reason = "state_buffering")
                }
                if (state == Player.STATE_READY) {
                    val accepted = shouldAcceptPlayerCallback(
                        playbackRequestToken,
                        loadedMediaRequestToken,
                        isPendingMediaLoadActive()
                    )
                    if (accepted) {
                        maybeBackfillCurrentSongDurationFromPlayer()
                        prefetchNextGenericTrackUrl()
                    }
                    if (player.playWhenReady || player.isPlaying) {
                        startProgressUpdates()
                        schedulePlaybackStartupWatchdog(reason = "state_ready")
                    }
                }
                if (state == Player.STATE_ENDED) {
                    cancelPlaybackStartupWatchdog(reason = "state_ended")
                    if (shouldAcceptPlayerCallback(
                            playbackRequestToken,
                            loadedMediaRequestToken,
                            isPendingMediaLoadActive()
                        )
                    ) {
                        handleTrackEndedIfNeeded(source = "playback_state_changed")
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!shouldAcceptPlayerCallback(
                        playbackRequestToken,
                        loadedMediaRequestToken,
                        isPendingMediaLoadActive()
                    )
                ) {
                    return
                }
                _isPlayingFlow.value = isPlaying
                LyriconManager.setPlaybackState(isPlaying)
                if (!isPlaying) {
                    syncPlaybackStatsPlayingState(
                        playing = false,
                        reason = "exo_is_playing_changed"
                    )
                }
                if (isPlaying) {
                    startProgressUpdates()
                    schedulePlaybackStartupWatchdog(reason = "is_playing_true")
                } else if (!player.playWhenReady) {
                    stopProgressUpdates()
                }
                val positionMs = resolveDisplayedPlaybackPosition(player.currentPosition)
                val shouldResumePlayback = shouldResumePlaybackSnapshot()
                scheduleStatePersist(
                    positionMs = positionMs,
                    shouldResumePlayback = shouldResumePlayback
                )
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!shouldExposePlayerCallbackState(
                        playbackRequestToken,
                        loadedMediaRequestToken,
                        isPendingMediaLoadActive()
                    )
                ) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Ignoring stale playWhenReady during pending media load: requestToken=$playbackRequestToken, loadedToken=$loadedMediaRequestToken, playWhenReady=$playWhenReady, reason=${playWhenReadyChangeReasonName(reason)}"
                    )
                    return
                }
                _playWhenReadyFlow.value = playWhenReady
                if (playWhenReady) {
                    startProgressUpdates()
                    schedulePlaybackStartupWatchdog(reason = "play_when_ready_true")
                } else if (!player.isPlaying) {
                    cancelPlaybackStartupWatchdog(reason = "play_when_ready_false")
                    stopProgressUpdates()
                }
                if (!playWhenReady) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "playWhenReady=false, reason=${playWhenReadyChangeReasonName(reason)}, state=${playbackStateName(player.playbackState)}, mediaId=${player.currentMediaItem?.mediaId}, stack=[${debugStackHint()}]"
                    )
                    if (
                        shouldClearResumePlaybackRequestOnPlayWhenReadyPause(
                            playWhenReady = playWhenReady,
                            playWhenReadyChangeReason = reason,
                            pendingPauseJobActive = pendingPauseJob?.isActive == true,
                            playJobActive = playJob?.isActive == true
                        )
                    ) {
                        updateResumePlaybackRequested(false)
                    }
                }
                if (
                    !playWhenReady &&
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                    player.playbackState == Player.STATE_ENDED &&
                    shouldAcceptPlayerCallback(
                        playbackRequestToken,
                        loadedMediaRequestToken,
                        isPendingMediaLoadActive()
                    )
                ) {
                    handleTrackEndedIfNeeded(source = "play_when_ready_end_of_item")
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                maybeBackfillCurrentSongDurationFromPlayer()
                if (player.playWhenReady || player.isPlaying) {
                    startProgressUpdates()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _playbackPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                maybeBackfillCurrentSongDurationFromPlayer()
                if (player.playWhenReady || player.isPlaying) {
                    startProgressUpdates()
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffleModeFlow.value = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                syncExoRepeatMode()
                _repeatModeFlow.value = repeatModeSetting
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                _playbackSoundState.value =
                    playbackEffectsController.onAudioSessionIdChanged(audioSessionId)
            }
        })

        player.playWhenReady = false

        ioScope.launch {
            settingsRepo.audioQualityFlow.collect { q ->
                val previousQuality = preferredQuality
                preferredQuality = q
                if (previousQuality != q) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.NETEASE,
                        reason = "netease_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.youtubeAudioQualityFlow.collect { q ->
                val previousQuality = youtubePreferredQuality
                youtubePreferredQuality = q
                if (previousQuality != q) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.YOUTUBE_MUSIC,
                        reason = "youtube_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.biliAudioQualityFlow.collect { q ->
                val previousQuality = biliPreferredQuality
                biliPreferredQuality = q
                if (previousQuality != q) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.BILIBILI,
                        reason = "bili_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.mobileDataFollowDefaultAudioQualityFlow.collect { enabled ->
                val previousValue = mobileDataFollowDefaultAudioQuality
                mobileDataFollowDefaultAudioQuality = enabled
                if (previousValue != enabled) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.NETEASE,
                        reason = "mobile_data_follow_default_quality_changed"
                    )
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.YOUTUBE_MUSIC,
                        reason = "mobile_data_follow_default_quality_changed"
                    )
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.BILIBILI,
                        reason = "mobile_data_follow_default_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.mobileDataNeteaseAudioQualityFlow.collect { q ->
                val previousQuality = mobileDataNeteaseAudioQuality
                mobileDataNeteaseAudioQuality = q
                if (previousQuality != q) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.NETEASE,
                        reason = "mobile_data_netease_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.mobileDataYouTubeAudioQualityFlow.collect { q ->
                val previousQuality = mobileDataYouTubeAudioQuality
                mobileDataYouTubeAudioQuality = q
                if (previousQuality != q) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.YOUTUBE_MUSIC,
                        reason = "mobile_data_youtube_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.mobileDataBiliAudioQualityFlow.collect { q ->
                val previousQuality = mobileDataBiliAudioQuality
                mobileDataBiliAudioQuality = q
                if (previousQuality != q) {
                    scheduleQualityRefresh(
                        source = PlaybackAudioSource.BILIBILI,
                        reason = "mobile_data_bili_quality_changed"
                    )
                }
            }
        }
        ioScope.launch {
            settingsRepo.lyriconEnabledFlow.collect { enabled ->
                lyriconEnabled = enabled
                LyriconManager.setEnabled(enabled)
                if (enabled) {
                    if (!LyriconManager.isInitialized()) {
                        LyriconManager.initialize(application)
                    }
                    syncLyriconSong(_currentSongFlow.value)
                    LyriconManager.setPlaybackState(_isPlayingFlow.value)
                    if (_isPlayingFlow.value) {
                        LyriconManager.setPosition(_playbackPositionMs.value)
                    }
                } else {
                    lyriconUpdateJob?.cancel()
                    lyriconUpdateJob = null
                }
            }
        }
        ioScope.launch {
            settingsRepo.statusBarLyricsEnabledFlow.collect { enabled ->
                statusBarLyricsEnable = enabled
                syncExternalBluetoothLyrics(_currentSongFlow.value)
            }
        }
        ioScope.launch {
            settingsRepo.externalBluetoothLyricsEnabledFlow.collect { enabled ->
                externalBluetoothLyricsEnabled = enabled
                syncExternalBluetoothLyrics(_currentSongFlow.value)
            }
        }
        ioScope.launch {
            settingsRepo.floatingLyricsPreferencesFlow.collect { preferences ->
                val normalized = preferences.normalized()
                val floatingLyricsEnabledChanged = floatingLyricsEnabled != normalized.enabled
                val showTranslationChanged = floatingLyricsShowTranslation != normalized.showTranslation
                floatingLyricsEnabled = normalized.enabled
                floatingLyricsShowTranslation = normalized.showTranslation
                FloatingLyricsOverlayManager.updatePreferences(normalized)
                when {
                    floatingLyricsEnabledChanged -> syncExternalBluetoothLyrics(_currentSongFlow.value)
                    showTranslationChanged -> syncFloatingTranslatedLyrics(_currentSongFlow.value)
                }
            }
        }
        mainScope.launch {
            combine(
                externalBluetoothLyricLineFlow,
                floatingTranslatedLyricLineFlow,
                currentSongFlow
            ) { lyricLine, translatedLine, currentSong ->
                Triple(lyricLine, translatedLine, currentSong)
            }.collect { (lyricLine, translatedLine, currentSong) ->
                FloatingLyricsOverlayManager.updateContent(
                    line = lyricLine.takeIf { currentSong != null },
                    translation = translatedLine.takeIf { currentSong != null }
                )
            }
        }
        ioScope.launch {
            settingsRepo.cloudMusicLyricDefaultOffsetMsFlow.collect { offsetMs ->
                cloudMusicLyricDefaultOffsetMs = offsetMs
                updateExternalBluetoothLyricLine(_playbackPositionMs.value)
            }
        }
        ioScope.launch {
            settingsRepo.qqMusicLyricDefaultOffsetMsFlow.collect { offsetMs ->
                qqMusicLyricDefaultOffsetMs = offsetMs
                updateExternalBluetoothLyricLine(_playbackPositionMs.value)
            }
        }
        ioScope.launch {
            settingsRepo.playbackFadeInFlow.collect { enabled ->
                playbackFadeInEnabled = enabled
            }
        }
        ioScope.launch {
            settingsRepo.playbackCrossfadeNextFlow.collect { enabled ->
                playbackCrossfadeNextEnabled = enabled
            }
        }
        ioScope.launch {
            settingsRepo.playbackFadeInDurationMsFlow.collect { duration ->
                playbackFadeInDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackFadeOutDurationMsFlow.collect { duration ->
                playbackFadeOutDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackCrossfadeInDurationMsFlow.collect { duration ->
                playbackCrossfadeInDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackCrossfadeOutDurationMsFlow.collect { duration ->
                playbackCrossfadeOutDurationMs = duration.coerceAtLeast(0L)
            }
        }
        ioScope.launch {
            settingsRepo.playbackSpeedFlow.collect { speed ->
                applyPlaybackSoundConfigIfChanged(playbackSoundConfig.copy(speed = speed))
            }
        }
        ioScope.launch {
            settingsRepo.playbackPitchFlow.collect { pitch ->
                applyPlaybackSoundConfigIfChanged(playbackSoundConfig.copy(pitch = pitch))
            }
        }
        ioScope.launch {
            settingsRepo.playbackLoudnessGainMbFlow.collect { levelMb ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(loudnessGainMb = levelMb)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackVolumeBalanceFlow.collect { balance ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(volumeBalance = balance)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackEqualizerEnabledFlow.collect { enabled ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(equalizerEnabled = enabled)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackEqualizerPresetFlow.collect { presetId ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(presetId = presetId)
                )
            }
        }
        ioScope.launch {
            settingsRepo.playbackEqualizerCustomBandLevelsFlow.collect { levels ->
                applyPlaybackSoundConfigIfChanged(
                    playbackSoundConfig.copy(customBandLevelsMb = levels)
                )
            }
        }
        ioScope.launch {
            settingsRepo.keepLastPlaybackProgressFlow.collect { enabled ->
                val changed = keepLastPlaybackProgressEnabled != enabled
                keepLastPlaybackProgressEnabled = enabled
                if (changed && initialized && currentPlaylist.isNotEmpty()) {
                    persistState()
                }
            }
        }
        ioScope.launch {
            settingsRepo.keepPlaybackModeStateFlow.collect { enabled ->
                val changed = keepPlaybackModeStateEnabled != enabled
                keepPlaybackModeStateEnabled = enabled
                if (changed && initialized && currentPlaylist.isNotEmpty()) {
                    persistState()
                }
            }
        }
        ioScope.launch {
            settingsRepo.neteaseAutoSourceSwitchFlow.collect { enabled ->
                neteaseAutoSourceSwitchEnabled = enabled
            }
        }
        ioScope.launch {
            settingsRepo.stopOnBluetoothDisconnectFlow.collect { enabled ->
                stopOnBluetoothDisconnectEnabled = enabled
            }
        }
        ioScope.launch {
            settingsRepo.usbExclusivePlaybackFlow.collect { enabled ->
                mainScope.launch {
                    handleUsbExclusivePlaybackSettingChanged(enabled)
                }
            }
        }
        ioScope.launch {
            settingsRepo.usbExclusivePreferencesFlow.collect { preferences ->
                mainScope.launch {
                    handleUsbExclusivePreferencesChanged(preferences)
                }
            }
        }
        ioScope.launch {
            settingsRepo.allowMixedPlaybackFlow.collect { enabled ->
                allowMixedPlaybackEnabled = enabled
                if (enabled) {
                    clearUsbExclusiveInterruptedPlaybackIntent("allow_mixed_playback_enabled")
                    StartupAudioFocusController.release("allow_mixed_playback_enabled")
                    UsbExclusiveSystemSoundGuard.forceRelease(
                        application,
                        "allow_mixed_playback_enabled"
                    )
                } else if (isUsbExclusiveNativePlaybackStable()) {
                    UsbExclusiveSystemSoundGuard.activate(
                        application,
                        "allow_mixed_playback_disabled"
                    )
                }
                applyAudioFocusPolicy()
            }
        }

        ioScope.launch {
            localRepo.playlists.collect { repoLists ->
                _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(repoLists)
            }
        }

        setupAudioDeviceCallback()
        if (restoredStateSnapshot != null) {
            applyRestoredStateSnapshot(restoredStateSnapshot)
        } else {
            restoreState()
        }

        sleepTimerManager = createSleepTimerManager()

        initialized = true
        NPLogger.d(
            "NERI-PlayerManager",
            "initialize(): success, cacheSize=$maxCacheSize, restoredQueueSize=${currentPlaylist.size}, currentIndex=$currentIndex, currentDevice=${_currentAudioDevice.value?.type}:${_currentAudioDevice.value?.name}"
        )
    }.onFailure { e ->
        NPLogger.e(
            "NERI-PlayerManager",
            "initialize(): failed, cacheSize=$maxCacheSize, currentPlaylistSize=${currentPlaylist.size}, currentIndex=$currentIndex",
            e
        )
        NPLogger.w(
            "NERI-PlayerManager",
            "initialize(): rollback begin, playerInitialized=${isPlayerInitialized()}, cacheInitialized=${isCacheInitialized()}, conditionalFactoryPresent=${conditionalHttpFactory != null}"
        )
        runCatching {
            val audioManager: AudioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
            audioDeviceCallback = null
            NPLogger.d("NERI-PlayerManager", "initialize(): rollback unregistered audio device callback")
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "initialize(): rollback unregister audio callback failed: ${it.message}")
        }
        runCatching {
            conditionalHttpFactory?.close()
            NPLogger.d("NERI-PlayerManager", "initialize(): rollback closed conditional http factory")
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "initialize(): rollback close conditional factory failed: ${it.message}")
        }
        conditionalHttpFactory = null
        runCatching {
            if (isPlayerInitialized()) {
                player.release()
                NPLogger.d("NERI-PlayerManager", "initialize(): rollback released player")
            }
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "initialize(): rollback release player failed: ${it.message}")
        }
        runCatching {
            _playbackSoundState.value = playbackEffectsController.release()
            NPLogger.d("NERI-PlayerManager", "initialize(): rollback released playback effects")
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "initialize(): rollback release effects failed: ${it.message}")
        }
        runCatching {
            if (isCacheInitialized()) {
                cache.release()
                NPLogger.d("NERI-PlayerManager", "initialize(): rollback released cache")
            }
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "initialize(): rollback release cache failed: ${it.message}")
        }
        runCatching {
            mainScope.cancel()
            NPLogger.d("NERI-PlayerManager", "initialize(): rollback cancelled mainScope")
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "initialize(): rollback cancel mainScope failed: ${it.message}")
        }
        runCatching {
            ioScope.cancel()
            NPLogger.d("NERI-PlayerManager", "initialize(): rollback cancelled ioScope")
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "initialize(): rollback cancel ioScope failed: ${it.message}")
        }
        runCatching {
            LyriconManager.release()
            NPLogger.d("NERI-PlayerManager", "initialize(): rollback released lyricon")
        }.onFailure {
            NPLogger.w("NERI-PlayerManager", "initialize(): rollback release lyricon failed: ${it.message}")
        }
        initialized = false
        }
    } finally {
        synchronized(initializationLock) {
            initializationInProgress = false
        }
    }
}

internal suspend fun PlayerManager.clearCacheImpl(
    clearAudio: Boolean = true,
    clearImage: Boolean = true
): Pair<Boolean, String> {
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        var apiRemovedCount = 0
        var physicalDeletedCount = 0
        var totalSpaceFreed = 0L

        try {
            if (clearAudio) {
                if (isCacheInitialized()) {
                    val keysSnapshot = HashSet(cache.keys)
                    keysSnapshot.forEach { key ->
                        try {
                            val resource = cache.getCachedSpans(key)
                            resource.forEach { totalSpaceFreed += it.length }
                            cache.removeResource(key)
                            apiRemovedCount++
                        } catch (_: Exception) {
                        }
                    }
                }

                val cacheDir = File(application.cacheDir, "media_cache")
                if (cacheDir.exists() && cacheDir.isDirectory) {
                    val files = cacheDir.listFiles() ?: emptyArray()
                    files.forEach { file ->
                        if (file.isFile && file.name.endsWith(".exo") && file.delete()) {
                            physicalDeletedCount++
                        }
                    }
                }
            }

            if (clearImage) {
                val imageCacheDir = File(application.cacheDir, "image_cache")
                if (imageCacheDir.exists() && imageCacheDir.isDirectory) {
                    val deleted = imageCacheDir.deleteRecursively()
                    if (deleted) {
                        imageCacheDir.mkdirs()
                    }
                }
            }

            NPLogger.d(
                "NERI-Player",
                "Cache Clear: API removed $apiRemovedCount keys, Physically deleted $physicalDeletedCount .exo files."
            )

            val msg = if (physicalDeletedCount > 0 || apiRemovedCount > 0 || clearImage) {
                getLocalizedString(R.string.cache_clear_complete)
            } else {
                getLocalizedString(R.string.settings_cache_empty)
            }
            Pair(true, msg)
        } catch (e: Exception) {
            NPLogger.e("NERI-Player", "Clear cache failed", e)
            Pair(
                false,
                getLocalizedString(
                    R.string.toast_cache_clear_error,
                    e.message ?: "Unknown"
                )
            )
        }
    }
}

internal fun PlayerManager.ensureInitializedImpl() {
    if (initialized || !isApplicationInitialized() || initializationInProgress) return
    NPLogger.d("NERI-PlayerManager", "ensureInitialized(): lazy initialize with existing application")
    initialize(application)
}

internal fun PlayerManager.updateAudioOffloadPreferences(reason: String) {
    if (!isPlayerInitialized()) return
    val requiresPcmProcessing = requiresPcmAudioProcessing(
        usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled,
        playbackSpeed = playbackSoundConfig.speed,
        playbackPitch = playbackSoundConfig.pitch,
        equalizerEnabled = playbackSoundConfig.equalizerEnabled,
        loudnessGainMb = playbackSoundConfig.loudnessGainMb,
        volumeBalance = playbackSoundConfig.volumeBalance,
        audioReactiveActive = AudioReactive.enabled,
        listenTogetherPlaybackRate = listenTogetherSyncPlaybackRate,
    )
    if (lastRequiresPcmAudioProcessing == requiresPcmProcessing) return
    lastRequiresPcmAudioProcessing = requiresPcmProcessing

    val offloadMode = if (requiresPcmProcessing) {
        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
    } else {
        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
    }
    val audioOffload = TrackSelectionParameters.AudioOffloadPreferences.Builder()
        .setAudioOffloadMode(offloadMode)
        .build()
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setAudioOffloadPreferences(audioOffload)
        .build()
    NPLogger.i(
        "NERI-PlayerManager",
        "audio offload preference updated: enabled=${!requiresPcmProcessing} reason=$reason"
    )
}

private fun PlayerManager.setupAudioDeviceCallback() {
    val audioManager: AudioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    _currentAudioDevice.value = getCurrentAudioDevice(audioManager)
    NPLogger.d(
        "NERI-PlayerManager",
        "setupAudioDeviceCallback(): initialDevice=${_currentAudioDevice.value?.type}:${_currentAudioDevice.value?.name}"
    )
    UsbExclusiveDebugLogger.logSnapshot(
        context = application,
        audioManager = audioManager,
        reason = "setup_initial",
        enabled = usbExclusivePlaybackEnabled
    )
    val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            NPLogger.d(
                "NERI-PlayerManager",
                "audioDevicesAdded(): count=${addedDevices?.size ?: 0}, devices=${addedDevices?.joinToString { "${it.type}:${it.productName}" }}"
            )
            UsbExclusiveDebugLogger.logAudioDeviceCallback(
                reason = "audioDevicesAdded",
                devices = addedDevices
            )
            handleDeviceChange(
                audioManager = audioManager,
                usbTopologyChanged = addedDevices?.any {
                    it.isSink && isUsbOutputType(it.type)
                } == true
            )
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            NPLogger.d(
                "NERI-PlayerManager",
                "audioDevicesRemoved(): count=${removedDevices?.size ?: 0}, devices=${removedDevices?.joinToString { "${it.type}:${it.productName}" }}"
            )
            UsbExclusiveDebugLogger.logAudioDeviceCallback(
                reason = "audioDevicesRemoved",
                devices = removedDevices
            )
            handleDeviceChange(
                audioManager = audioManager,
                usbTopologyChanged = removedDevices?.any {
                    it.isSink && isUsbOutputType(it.type)
                } == true
            )
        }
    }
    audioDeviceCallback = deviceCallback
    audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    NPLogger.d("NERI-PlayerManager", "setupAudioDeviceCallback(): callback registered")
}

internal fun PlayerManager.handleAudioBecomingNoisyImpl(): Boolean {
    ensureInitialized()
    if (!initialized) {
        NPLogger.d("NERI-PlayerManager", "handleAudioBecomingNoisy(): ignored because manager is not initialized")
        return false
    }
    if (!_isPlayingFlow.value) {
        NPLogger.d("NERI-PlayerManager", "handleAudioBecomingNoisy(): ignored because playback is already paused")
        return false
    }
    val currentDevice = _currentAudioDevice.value
    NPLogger.d(
        "NERI-PlayerManager",
        "handleAudioBecomingNoisy(): currentDevice=${currentDevice?.type}:${currentDevice?.name}, isPlaying=${_isPlayingFlow.value}"
    )
    if (usbExclusivePlaybackEnabled && currentDevice != null && isUsbOutputType(currentDevice.type)) {
        NPLogger.d("NERI-PlayerManager", "handleAudioBecomingNoisy(): ignored for USB exclusive route")
        return false
    }
    if (currentDevice != null && requiresDisconnectConfirmation(currentDevice.type)) {
        if (!shouldPauseForBluetoothDisconnect(currentDevice, null)) {
            NPLogger.d("NERI-PlayerManager", "handleAudioBecomingNoisy(): bluetooth confirmation rejected")
            return false
        }
        NPLogger.d(
            "NERI-PlayerManager",
            "handleAudioBecomingNoisy(): schedule delayed pause for device=${currentDevice.type}:${currentDevice.name}"
        )
        schedulePauseForBluetoothDisconnect(
            previousDevice = currentDevice,
            reason = "becoming_noisy"
        )
        return true
    }
    NPLogger.d("NERI-PlayerManager", "Audio becoming noisy, hard-pausing playback immediately.")
    suppressPlaybackForAudioRouteLoss(reason = "becoming_noisy_immediate")
    pauseForAudioRouteLoss(reason = "becoming_noisy_immediate")
    return true
}

private fun PlayerManager.handleDeviceChange(
    audioManager: AudioManager,
    usbTopologyChanged: Boolean
) {
    val previousDevice = _currentAudioDevice.value
    val newDevice = getCurrentAudioDevice(audioManager)
    _currentAudioDevice.value = newDevice
    val usbRouteChanged = previousDevice?.type != newDevice.type ||
        previousDevice?.name != newDevice.name
    val nativeOpenGate = UsbExclusiveSessionController.playerPcmOpenGateReason()
    if (
        usbExclusivePlaybackEnabled &&
        nativeOpenGate?.contains("usb_device_detached", ignoreCase = true) == true
    ) {
        UsbExclusiveAudioPathTracker.forceSystemFallback("usb_device_detached")
        NPLogger.d(
            "NERI-UsbExclusive",
            "ignore Android route callback after physical USB detach: gate=$nativeOpenGate"
        )
        return
    }
    val nativeState = UsbExclusiveSessionController.state.value
    if (
        usbExclusivePlaybackEnabled &&
        (
            nativeState.transitioning ||
                (nativeState.opened && nativeState.source == "player_pcm")
            )
    ) {
        NPLogger.d(
            "NERI-UsbExclusive",
            "ignore Android route churn while native USB owns the device: " +
                "previous=${previousDevice?.type}:${previousDevice?.name} " +
                "next=${newDevice.type}:${newDevice.name} topology=$usbTopologyChanged"
        )
        return
    }
    if (shouldTreatAsUsbExclusiveRouteJitter(previousDevice, newDevice)) {
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
        UsbExclusiveSessionController.deferPlayerPcmOpen(
            reason = "route_jitter",
            delayMs = USB_EXCLUSIVE_ROUTE_JITTER_REOPEN_COOLDOWN_MS
        )
        restorePlaybackAfterTransientAudioRouteLoss(reason = "usb_exclusive_route_jitter")
        return
    }
    if (usbRouteChanged || usbTopologyChanged) {
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    }
    applyUsbExclusivePlaybackPolicy(
        reconfigureAudioSink = usbExclusivePlaybackEnabled &&
            (usbRouteChanged || usbTopologyChanged)
    )
    UsbExclusiveDebugLogger.logSnapshot(
        context = application,
        audioManager = audioManager,
        reason = "device_change",
        enabled = usbExclusivePlaybackEnabled
    )
    NPLogger.d(
        "NERI-PlayerManager",
        "handleDeviceChange(): ${previousDevice?.type}:${previousDevice?.name} -> ${newDevice.type}:${newDevice.name}, isPlaying=${_isPlayingFlow.value}"
    )
    if (shouldPauseForBluetoothDisconnect(previousDevice, newDevice)) {
        schedulePauseForBluetoothDisconnect(
            previousDevice = previousDevice,
            reason = "device_changed_to_${newDevice.type}"
        )
    } else if (shouldPauseForImmediateOutputDisconnect(previousDevice, newDevice)) {
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
        NPLogger.d(
            "NERI-PlayerManager",
            "Detected immediate output disconnect (${previousDevice?.type} -> ${newDevice.type}), pausing playback."
        )
        suppressPlaybackForAudioRouteLoss(reason = "immediate_output_disconnect")
        pauseForAudioRouteLoss(reason = "immediate_output_disconnect")
    } else if (newDevice.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
        restorePlaybackAfterTransientAudioRouteLoss(reason = "device_changed_to_${newDevice.type}")
    }
}

private fun PlayerManager.handleUsbExclusivePlaybackSettingChanged(enabled: Boolean) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainScope.launch { handleUsbExclusivePlaybackSettingChanged(enabled) }
        return
    }
    val changed = usbExclusivePlaybackEnabled != enabled
    usbExclusivePlaybackEnabled = enabled
    NPLogger.d(
        "NERI-UsbExclusive",
        "settingsChanged(): enabled=$enabled, changed=$changed"
    )
    if (!changed) {
        val previousFallbackReason = UsbExclusiveAudioPathTracker.forcedSystemFallbackReason()
        UsbExclusiveAudioPathTracker.updateRequested(enabled)
        if (enabled && previousFallbackReason == "usb_exclusive_disabled") {
            UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
            applyAudioFocusPolicyOnMainThread()
            applyUsbExclusivePlaybackPolicy(reconfigureAudioSink = false)
        }
        return
    }

    val routeGeneration = usbExclusiveRouteGeneration + 1L
    usbExclusiveRouteGeneration = routeGeneration
    UsbExclusiveAudioPathTracker.updateRequested(enabled)
    usbExclusiveToggleTransitionJob?.cancel()
    usbExclusiveToggleTransitionJob = null
    val hasMediaToReconfigure = isPlayerInitialized() && player.currentMediaItem != null
    usbExclusiveToggleTransitionActive = hasMediaToReconfigure
    usbExclusiveToggleTransitionReason = if (hasMediaToReconfigure) {
        if (enabled) "usb_exclusive_enabled" else "usb_exclusive_disabled"
    } else {
        ""
    }
    markUsbExclusivePlaybackPreparing(hasMediaToReconfigure, "settings_changed:$enabled")
    val mediaItemCountBeforeToggle = if (hasMediaToReconfigure) player.mediaItemCount else 0
    val mediaItemIndexBeforeToggle = if (mediaItemCountBeforeToggle > 0) {
        player.currentMediaItemIndex.coerceIn(0, mediaItemCountBeforeToggle - 1)
    } else {
        null
    }
    val positionBeforeToggleMs = if (hasMediaToReconfigure) {
        player.currentPosition.coerceAtLeast(0L)
    } else {
        null
    }
    if (hasMediaToReconfigure) {
        usbExclusiveToggleTransitionJob = mainScope.launch {
            delay(8_000L)
            if (usbExclusiveToggleTransitionActive) {
                NPLogger.w(
                    "NERI-UsbExclusive",
                    "forcing USB toggle transition unlock after timeout: reason=$usbExclusiveToggleTransitionReason"
                )
                usbExclusiveToggleTransitionActive = false
                usbExclusiveToggleTransitionReason = ""
                markUsbExclusivePlaybackPreparing(false, "usb_toggle_timeout")
            }
        }
    }
    if (enabled) {
        if (hasMediaToReconfigure) {
            pauseImpl(
                forcePersist = false,
                commandSource = PlaybackCommandSource.LOCAL,
                allowFadeOut = false,
                preserveMutedVolume = false,
                debugReason = "usb_toggle_enable_prepare"
            )
        }
        cancelUsbExclusiveSystemAudioRelease("usb_exclusive_enabled")
        activateUsbExclusivePlaybackRoute("usb_exclusive_enabled")
    } else {
        if (hasMediaToReconfigure) {
            pauseImpl(
                forcePersist = false,
                commandSource = PlaybackCommandSource.LOCAL,
                allowFadeOut = false,
                preserveMutedVolume = false,
                debugReason = "usb_toggle_disable_prepare"
            )
        }
        releaseUsbExclusivePlaybackRoute(
            reason = "usb_exclusive_disabled",
            reconfigureAudioSink = true,
            restoreAudioFocus = false,
            routeGeneration = routeGeneration,
            playbackWasActiveBeforeRelease = false,
            releaseMediaItemIndex = mediaItemIndexBeforeToggle,
            releasePositionMs = positionBeforeToggleMs
        )
        applyUsbExclusivePlaybackPolicy(reconfigureAudioSink = false)
    }
    schedulePlaybackSoundConfigApply(
        previousConfig = playbackSoundConfig,
        newConfig = playbackSoundConfig
    )
    if (!hasMediaToReconfigure) {
        usbExclusiveToggleTransitionActive = false
        usbExclusiveToggleTransitionReason = ""
        markUsbExclusivePlaybackPreparing(false, "usb_toggle_no_media")
    }
}

private fun PlayerManager.stopInactivePlayerBeforeUsbExclusiveRelease(reason: String) {
    if (!isPlayerInitialized()) return
    val mediaItemCount = player.mediaItemCount
    if (mediaItemCount <= 0 || player.currentMediaItem == null) return
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    NPLogger.i(
        "NERI-UsbExclusive",
        "stop inactive player before USB release: reason=$reason positionMs=$positionMs"
    )
    runCatching {
        player.playWhenReady = false
        player.stop()
    }.onFailure { error ->
        NPLogger.w(
            "NERI-UsbExclusive",
            "stop inactive player before USB release failed: reason=$reason",
            error
        )
    }
    _isPlayingFlow.value = false
    _playWhenReadyFlow.value = false
    _playbackPositionMs.value = positionMs
    stopProgressUpdates()
    scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = false)
}

private fun PlayerManager.activateUsbExclusivePlaybackRoute(
    reason: String,
    waitForSystemRelease: Boolean = true
) {
    if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return
    val releaseJob = usbExclusiveSystemAudioReleaseJob
    if (waitForSystemRelease && releaseJob?.isActive == true) {
        cancelUsbExclusiveSystemAudioRelease(reason)
    }
    UsbExclusiveSessionController.clearRecoverablePlayerPcmOpenBlock(reason)
    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    usbExclusiveRecoveryAttempts = 0
    pendingUsbExclusivePreferenceReconfigure = false
    applyAudioFocusPolicyOnMainThread()
    applyUsbExclusivePlaybackPolicy(
        reconfigureAudioSink = true,
        reconfigureReason = reason,
        allowReconfigureWhilePlaying = true
    )
}

private fun PlayerManager.handleUsbExclusivePreferencesChanged(
    preferences: UsbExclusivePreferences
) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainScope.launch { handleUsbExclusivePreferencesChanged(preferences) }
        return
    }
    val previousPreferences = usbExclusivePreferences
    val changed = usbExclusivePreferences != preferences
    usbExclusivePreferences = preferences
    if (!changed || !usbExclusivePlaybackEnabled) return

    if (isPlaybackActiveForUsbExclusiveSwitch()) {
        applyActiveUsbExclusiveBuffer("preferences_changed")
        val routeReconfigurationRequired =
            previousPreferences.requiresUsbExclusiveRouteReconfiguration(preferences)
        pendingUsbExclusivePreferenceReconfigure = routeReconfigurationRequired
        NPLogger.i(
            "NERI-UsbExclusive",
            "USB preferences saved; deferRoute=$routeReconfigurationRequired"
        )
        if (routeReconfigurationRequired) {
            deferUsbExclusiveReconfigurationUntilPlaybackStops("usb_output_preferences_changed")
        }
        return
    }

    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    retryUsbExclusivePlayback("usb_output_preferences_changed")
}

internal fun PlayerManager.retryUsbExclusivePlayback(reason: String) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainScope.launch { retryUsbExclusivePlayback(reason) }
        return
    }
    if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return
    if (reason.isUsbExclusiveActivationReason()) {
        UsbExclusiveSessionController.clearRecoverablePlayerPcmOpenBlock("retry:$reason")
    }
    if (
        isPlaybackActiveForUsbExclusiveSwitch() &&
        reason.isUsbExclusiveActivationReason() &&
        !reason.isUserDrivenUsbExclusiveActivation()
    ) {
        deferUsbExclusiveReconfigurationUntilPlaybackStops(reason)
        return
    }
    cancelUsbExclusiveRecovery("manual_retry:$reason")
    usbExclusiveRecoveryAttempts = 0
    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    NPLogger.d("NERI-UsbExclusive", "retryUsbExclusivePlayback(): reason=$reason")
    applyUsbExclusivePlaybackPolicy(
        reconfigureAudioSink = true,
        reconfigureReason = reason,
        allowReconfigureWhilePlaying = reason.isUserDrivenUsbExclusiveActivation()
    )
}

internal fun PlayerManager.scheduleUsbExclusiveTransportRecovery(reason: String) {
    if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return
    pendingUsbExclusivePreferenceReconfigure = false
    usbExclusiveRecoveryAttempts = 0
    usbExclusiveRecoveryJob?.cancel()
    usbExclusiveRecoveryJob = null
    if (
        reason.isRecoverableUsbExclusiveFallback() &&
        recoverUsbExclusivePlaybackIfUnhealthy(
            reason = "transport_failure:$reason",
            forceRecovery = true
        )
    ) {
        return
    }
    if (isPlaybackActiveForUsbExclusiveSwitch()) {
        NPLogger.w(
            "NERI-UsbExclusive",
            "stop active playback after native USB failure because automatic recovery is not available: " +
                "reason=$reason path=${UsbExclusiveAudioPathTracker.state.value.effectivePath} " +
                "fallback=${UsbExclusiveAudioPathTracker.state.value.fallbackReason} " +
                "native=${UsbExclusiveSessionController.state.value.source}/" +
                UsbExclusiveSessionController.state.value.streaming
        )
        stopPlaybackAfterUsbExclusiveNativeFailure(reason)
        return
    }
    NPLogger.w(
        "NERI-UsbExclusive",
        "skip automatic native USB recovery while playback is idle; manual play will rebuild route: " +
            "reason=$reason"
    )
}

internal fun PlayerManager.markUsbExclusiveNativePathActive(reason: String) {
    if (usbExclusiveRecoveryAttempts > 0) {
        NPLogger.i("NERI-UsbExclusive", "native USB path recovered: reason=$reason")
    }
    usbExclusiveRecoveryAttempts = 0
    cancelUsbExclusiveRecovery("native_active:$reason")
    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    if (!allowMixedPlaybackEnabled) {
        UsbExclusiveSystemSoundGuard.activate(application, "native_active:$reason")
    }
}

internal fun PlayerManager.tryRecoverUsbExclusivePlaybackAfterNativeTransferFailure(
    reason: String,
    runtimeReport: String
): Boolean {
    if (
        usbExclusivePlaybackEnabled &&
        !allowMixedPlaybackEnabled &&
        (
            reason.isUsbExclusiveFirstCompletionTimeout() ||
                runtimeReport.isUsbExclusiveFirstCompletionTimeout()
            )
    ) {
        if (usbExclusiveRecoveryAttempts >= USB_EXCLUSIVE_FIRST_COMPLETION_RECOVERY_MAX_ATTEMPTS) {
            NPLogger.w(
                "NERI-UsbExclusive",
                "first completion timeout recovery limit reached: reason=$reason " +
                    "runtime=$runtimeReport"
            )
            return false
        }
        usbExclusiveRecoveryAttempts += 1
        val scheduledRecovery = recoverUsbExclusivePlaybackIfUnhealthy(
            reason = "first_completion_timeout_recovery:$reason",
            forceRecovery = true
        )
        if (!scheduledRecovery) {
            UsbExclusiveSessionController.requireFreshPlayerPcmOpen(
                reason = "first_completion_timeout_recovery"
            )
            scheduleUsbAudioSinkReconfiguration(
                reason = "usb_exclusive_first_completion_timeout_recovery",
                allowWhilePlaybackActive = true,
                bypassCooldown = true
            )
        }
        NPLogger.w(
            "NERI-UsbExclusive",
            "recover native USB playback after first completion timeout: " +
                "attempt=$usbExclusiveRecoveryAttempts reason=$reason runtime=$runtimeReport"
        )
        return true
    }
    if (
        usbExclusivePlaybackEnabled &&
        !allowMixedPlaybackEnabled &&
        (
            reason.isRecoverableUsbExclusiveNativeTransferFailure() ||
                runtimeReport.isRecoverableUsbExclusiveNativeTransferFailure()
            )
    ) {
        NPLogger.w(
            "NERI-UsbExclusive",
            "skip immediate native USB recovery after transfer failure: reason=$reason " +
                "runtime=$runtimeReport"
        )
    }
    return false
}

internal fun PlayerManager.releaseUsbExclusivePlaybackRoute(
    reason: String,
    reconfigureAudioSink: Boolean,
    restoreAudioFocus: Boolean = true,
    routeGeneration: Long = usbExclusiveRouteGeneration,
    playbackWasActiveBeforeRelease: Boolean = shouldKeepPlaybackActiveForUsbRouteSwitch(),
    releaseMediaItemIndex: Int? = null,
    releasePositionMs: Long? = null
) {
    val disablingUsbExclusive = reason == "usb_exclusive_disabled"
    val interruptedIntent = usbExclusiveInterruptedPlaybackIntent.takeIf { disablingUsbExclusive }
    val playbackShouldContinue = !disablingUsbExclusive &&
        (playbackWasActiveBeforeRelease || interruptedIntent != null)
    val effectiveReleaseMediaItemIndex = releaseMediaItemIndex ?: interruptedIntent?.mediaItemIndex
    val effectiveReleasePositionMs = releasePositionMs ?: interruptedIntent?.positionMs
    cancelUsbExclusiveRecovery("release:$reason")
    usbExclusiveSystemAudioResumeJob?.cancel()
    usbExclusiveSystemAudioResumeJob = null
    usbExclusiveSystemAudioWatchdogJob?.cancel()
    usbExclusiveSystemAudioWatchdogJob = null
    usbAudioSinkReconfigureJob?.cancel()
    usbAudioSinkReconfigureJob = null
    if (disablingUsbExclusive) {
        usbExclusiveSystemAudioReleaseJob?.cancel()
        usbExclusiveSystemAudioReleaseJob = null
        usbExclusiveSystemAudioReleaseInProgress = true
        cancelPendingPauseRequest(resetVolumeToFull = true)
        cancelVolumeFade(resetToFull = true)
        clearAudioRouteMuteSuppression(reason = "usb_exclusive_release_start:$reason")
        updateResumePlaybackRequested(false)
        clearUsbExclusiveInterruptedPlaybackIntent("usb_exclusive_disabled")
    }
    UsbExclusiveSessionController.deferPlayerPcmOpen(
        reason = reason,
        delayMs = USB_EXCLUSIVE_RELEASE_REOPEN_COOLDOWN_MS
    )
    usbExclusiveRecoveryAttempts = 0
    pendingUsbExclusivePreferenceReconfigure = false
    if (disablingUsbExclusive) {
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    } else {
        UsbExclusiveAudioPathTracker.forceSystemFallback(reason)
    }
    UsbExclusiveAudioPathTracker.updateConfigured(
        usingNative = false,
        fallbackReason = reason.takeUnless { disablingUsbExclusive },
        inputFormat = "none"
    )
    UsbExclusiveAudioPathTracker.updatePlaying(
        playing = playbackShouldContinue,
        usingNative = false
    )
    UsbExclusiveSessionController.stopGeneratedTone()
    if (disablingUsbExclusive) {
        UsbExclusiveSessionController.forceStopAllSessions(reason)
    } else {
        UsbExclusiveSessionController.stopPlayerPcmSession(reason)
    }
    UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(application, reason)
    StartupAudioFocusController.forceRelease(reason)
    if (!isPlayerInitialized()) {
        if (disablingUsbExclusive) {
            usbExclusiveSystemAudioReleaseInProgress = false
        }
        return
    }
    lateinit var releaseJob: kotlinx.coroutines.Job
    releaseJob = mainScope.launch {
        if (!isPlayerInitialized()) return@launch
        if (
            usbExclusiveRouteGeneration != routeGeneration ||
            (disablingUsbExclusive && usbExclusivePlaybackEnabled)
        ) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "skip stale USB route release: reason=$reason generation=$routeGeneration current=$usbExclusiveRouteGeneration"
            )
            return@launch
        }
        try {
            runCatching {
                player.setPreferredAudioDevice(null)
            }.onFailure { error ->
                NPLogger.w("NERI-UsbExclusive", "release route failed to clear preferred device", error)
            }
            cancelVolumeFade(resetToFull = true)
            clearAudioRouteMuteSuppression(reason = "usb_exclusive_release:$reason")
            if (restoreAudioFocus) {
                applyAudioFocusPolicyOnMainThread()
            }
            if (disablingUsbExclusive && reconfigureAudioSink) {
                val closeWaitStartedAtMs = SystemClock.elapsedRealtime()
                while (
                    UsbExclusiveSessionController.nativeCloseInFlightCount() > 0 &&
                    SystemClock.elapsedRealtime() - closeWaitStartedAtMs <
                    USB_EXCLUSIVE_NATIVE_CLOSE_WAIT_TIMEOUT_MS
                ) {
                    delay(USB_EXCLUSIVE_NATIVE_CLOSE_WAIT_POLL_MS)
                }
                val closeInFlight = UsbExclusiveSessionController.nativeCloseInFlightCount()
                if (closeInFlight > 0) {
                    NPLogger.w(
                        "NERI-UsbExclusive",
                        "native close timed out; force system audio reset: " +
                            "reason=$reason closeInFlight=$closeInFlight"
                    )
                    usbExclusiveSystemAudioReleaseInProgress = false
                    forceSystemAudioResetAfterUsbExclusiveRelease(
                        reason = "native_close_timeout_idle:$reason",
                        routeGeneration = routeGeneration,
                        resumePlayback = false,
                        releaseMediaItemIndex = effectiveReleaseMediaItemIndex,
                        releasePositionMs = effectiveReleasePositionMs,
                        allowWatchdog = false
                    )
                    return@launch
                }
                delay(USB_EXCLUSIVE_SYSTEM_AUDIO_RELEASE_DELAY_MS)
                if (
                    !isPlayerInitialized() ||
                    usbExclusivePlaybackEnabled ||
                    usbExclusiveRouteGeneration != routeGeneration
                ) {
                    NPLogger.d(
                        "NERI-UsbExclusive",
                        "skip stale system audio reset after USB release: reason=$reason generation=$routeGeneration current=$usbExclusiveRouteGeneration enabled=$usbExclusivePlaybackEnabled"
                    )
                    return@launch
                }
                usbExclusiveSystemAudioReleaseInProgress = false
                NPLogger.i(
                    "NERI-UsbExclusive",
                    "USB exclusive released; rebuild system audio without auto resume: reason=$reason"
                )
                forceSystemAudioResetAfterUsbExclusiveRelease(
                    reason = "idle:$reason",
                    routeGeneration = routeGeneration,
                    resumePlayback = false,
                    releaseMediaItemIndex = effectiveReleaseMediaItemIndex,
                    releasePositionMs = effectiveReleasePositionMs,
                    allowWatchdog = false
                )
                return@launch
            }
            restorePlaybackAfterTransientAudioRouteLoss(reason = "usb_exclusive_release:$reason")
            if (reconfigureAudioSink) {
                scheduleUsbAudioSinkReconfiguration(
                    reason = "release:$reason",
                    allowWhilePlaybackActive = true,
                    bypassCooldown = true
                )
            }
        } finally {
            if (usbExclusiveSystemAudioReleaseJob === releaseJob) {
                usbExclusiveSystemAudioReleaseJob = null
            }
            if (disablingUsbExclusive) {
                usbExclusiveSystemAudioReleaseInProgress = false
            }
        }
    }
    if (disablingUsbExclusive) {
        usbExclusiveSystemAudioReleaseJob = releaseJob
    }
}

private fun PlayerManager.forceSystemAudioResetAfterUsbExclusiveRelease(
    reason: String,
    routeGeneration: Long,
    resumePlayback: Boolean,
    releaseMediaItemIndex: Int? = null,
    releasePositionMs: Long? = null,
    allowWatchdog: Boolean = true
) {
    if (!isPlayerInitialized()) return
    if (usbExclusivePlaybackEnabled || usbExclusiveRouteGeneration != routeGeneration) {
        NPLogger.d(
            "NERI-UsbExclusive",
            "skip system audio reset for stale USB release: reason=$reason generation=$routeGeneration current=$usbExclusiveRouteGeneration enabled=$usbExclusivePlaybackEnabled"
        )
        return
    }
    val mediaItemCount = player.mediaItemCount
    if (mediaItemCount <= 0 || player.currentMediaItem == null) {
        if (resumePlayback) {
            resumeInterruptedUsbExclusivePlaybackIfNeeded("system_audio_reset_no_media:$reason")
        }
        return
    }
    val mediaItemIndex = (releaseMediaItemIndex ?: player.currentMediaItemIndex)
        .coerceIn(0, mediaItemCount - 1)
    val positionMs = (releasePositionMs ?: player.currentPosition).coerceAtLeast(0L)
    usbExclusiveSystemAudioResumeJob?.cancel()
    usbExclusiveSystemAudioResumeJob = null
    usbExclusiveSystemAudioWatchdogJob?.cancel()
    usbExclusiveSystemAudioWatchdogJob = null
    cancelPendingPauseRequest(resetVolumeToFull = true)
    cancelVolumeFade(resetToFull = true)
    clearAudioRouteMuteSuppression(reason = "usb_system_audio_reset:$reason")
    playbackRequestToken += 1
    playJob?.cancel()
    playJob = null
    NPLogger.i(
        "NERI-UsbExclusive",
        "force system audio reset after USB release: reason=$reason index=$mediaItemIndex positionMs=$positionMs resume=$resumePlayback"
    )
    runCatching {
        player.setPreferredAudioDevice(null)
        player.volume = 1f
        updateResumePlaybackRequested(resumePlayback)
        applyAudioFocusPolicyOnMainThread()
        player.playWhenReady = false
        player.stop()
        player.seekTo(mediaItemIndex, positionMs)
        player.prepare()
        player.playWhenReady = resumePlayback
        if (resumePlayback) {
            _playWhenReadyFlow.value = true
            scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = true)
            player.play()
        } else {
            player.playWhenReady = false
            _isPlayingFlow.value = false
            _playWhenReadyFlow.value = false
            _playbackPositionMs.value = positionMs
            stopProgressUpdates()
            scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = false)
        }
        lastUsbExclusiveAudioSinkReconfigureAtMs = SystemClock.elapsedRealtime()
    }.onSuccess {
        pendingUsbExclusivePreferenceReconfigure = false
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        applyAudioFocusPolicyOnMainThread()
        if (resumePlayback) {
            clearUsbExclusiveInterruptedPlaybackIntent("system_audio_reset:$reason")
        }
        usbExclusiveToggleTransitionActive = false
        usbExclusiveToggleTransitionReason = ""
        markUsbExclusivePlaybackPreparing(false, "usb_system_audio_reset:$reason")
        if (resumePlayback && allowWatchdog) {
            scheduleUsbExclusiveSystemAudioFallbackWatchdog(
                reason = reason,
                routeGeneration = routeGeneration,
                mediaItemIndex = mediaItemIndex,
                positionMs = positionMs
            )
        }
    }.onFailure { error ->
        runCatching { player.playWhenReady = resumePlayback }
        usbExclusiveToggleTransitionActive = false
        usbExclusiveToggleTransitionReason = ""
        markUsbExclusivePlaybackPreparing(false, "usb_system_audio_reset_failed:$reason")
        NPLogger.e(
            "NERI-UsbExclusive",
            "force system audio reset after USB release failed: reason=$reason",
            error
        )
    }
}

private fun PlayerManager.scheduleUsbExclusiveSystemAudioFallbackWatchdog(
    reason: String,
    routeGeneration: Long,
    mediaItemIndex: Int,
    positionMs: Long
) {
    lateinit var watchdogJob: kotlinx.coroutines.Job
    watchdogJob = mainScope.launch {
        delay(USB_EXCLUSIVE_SYSTEM_AUDIO_RESUME_WATCHDOG_MS)
        if (usbExclusiveSystemAudioWatchdogJob !== watchdogJob) return@launch
        usbExclusiveSystemAudioWatchdogJob = null
        if (!isPlayerInitialized()) return@launch
        if (usbExclusivePlaybackEnabled || usbExclusiveRouteGeneration != routeGeneration) return@launch
        if (!player.playWhenReady || player.currentMediaItem == null) return@launch
        val currentIndex = player.currentMediaItemIndex
        val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
        val stalledState = player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_BUFFERING ||
            player.playbackState == Player.STATE_READY
        val stalled = currentIndex == mediaItemIndex &&
            !player.isPlaying &&
            stalledState &&
            currentPositionMs <= positionMs + USB_EXCLUSIVE_SYSTEM_AUDIO_STALL_TOLERANCE_MS
        if (!stalled) return@launch
        NPLogger.w(
            "NERI-UsbExclusive",
            "system audio fallback stalled after USB release; retry reset: reason=$reason " +
                "index=$currentIndex positionMs=$currentPositionMs state=${playbackStateName(player.playbackState)}"
        )
        forceSystemAudioResetAfterUsbExclusiveRelease(
            reason = "system_audio_watchdog:$reason",
            routeGeneration = routeGeneration,
            resumePlayback = true,
            releaseMediaItemIndex = currentIndex,
            releasePositionMs = currentPositionMs,
            allowWatchdog = false
        )
    }
    usbExclusiveSystemAudioWatchdogJob = watchdogJob
}

internal fun PlayerManager.stopPlaybackAfterUsbExclusiveNativeFailure(reason: String) {
    if (isTransientUsbExclusiveOpenGate(reason)) {
        NPLogger.i(
            "NERI-UsbExclusive",
            "keep playback request pending while native USB open gate is active: reason=$reason"
        )
        return
    }
    if (reason.isNativeTransitionInFlightGate()) {
        NPLogger.i(
            "NERI-UsbExclusive",
            "ignore native failure stop while USB transition is in flight: reason=$reason"
        )
        return
    }
    markUsbExclusivePlaybackPreparing(false, "native_failure:$reason")
    cancelUsbExclusiveRecovery("native_failure:$reason")
    usbAudioSinkReconfigureJob?.cancel()
    usbAudioSinkReconfigureJob = null
    usbExclusiveSystemAudioResumeJob?.cancel()
    usbExclusiveSystemAudioResumeJob = null
    usbExclusiveSystemAudioWatchdogJob?.cancel()
    usbExclusiveSystemAudioWatchdogJob = null
    usbExclusiveToggleTransitionJob?.cancel()
    usbExclusiveToggleTransitionJob = null
    usbExclusiveOpenGatePlaybackJob?.cancel()
    usbExclusiveOpenGatePlaybackJob = null
    usbExclusiveToggleTransitionActive = false
    usbExclusiveToggleTransitionReason = ""
    StartupAudioFocusController.forceRelease("native_failure:$reason")
    if (!isPlayerInitialized()) return
    runPlayerActionOnMainThread {
        if (!isPlayerInitialized() || !usbExclusivePlaybackEnabled) return@runPlayerActionOnMainThread
        val mediaItemCount = player.mediaItemCount
        val mediaItemIndex = player.currentMediaItemIndex
            .coerceIn(0, (mediaItemCount - 1).coerceAtLeast(0))
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val keepPlaybackIntent = shouldKeepPlaybackIntentAfterUsbNativeFailure()
        if (keepPlaybackIntent) {
            rememberUsbExclusiveInterruptedPlaybackIntent(
                reason = reason,
                mediaItemIndex = mediaItemIndex,
                positionMs = positionMs
            )
        } else {
            clearUsbExclusiveInterruptedPlaybackIntent("native_failure_idle:$reason")
            updateResumePlaybackRequested(false)
        }
        NPLogger.w(
            "NERI-UsbExclusive",
            "stop playback after native USB failure: reason=$reason index=$mediaItemIndex " +
                "positionMs=$positionMs keepIntent=$keepPlaybackIntent"
        )
        cancelPendingPauseRequest(resetVolumeToFull = true)
        cancelVolumeFade(resetToFull = true)
        clearAudioRouteMuteSuppression(reason = "usb_native_failure:$reason")
        playbackRequestToken += 1
        playJob?.cancel()
        playJob = null
        pendingMediaLoadActive = false
        pendingUsbExclusivePreferenceReconfigure = false
        UsbExclusiveSessionController.forceStopAllSessions("native_failure:$reason")
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(application, "native_failure:$reason")
        UsbExclusiveAudioPathTracker.forceSystemFallback(reason)
        runCatching {
            player.playWhenReady = false
            player.pause()
            player.stop()
        }.onFailure { error ->
            NPLogger.w(
                "NERI-UsbExclusive",
                "failed to stop player after native USB failure: reason=$reason",
                error
            )
        }
        _isPlayingFlow.value = false
        _playWhenReadyFlow.value = false
        _playbackPositionMs.value = positionMs
        stopProgressUpdates()
        scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = keepPlaybackIntent)
        postPlayerEvent(
            PlayerEvent.ShowError(
                getLocalizedString(R.string.settings_usb_exclusive_issue_transport)
            )
        )
    }
}

internal fun PlayerManager.prepareUsbExclusiveRouteForManualPlayback(reason: String): Boolean {
    if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return true
    val mediaItemCount = player.mediaItemCount
    if (mediaItemCount <= 0 || player.currentMediaItem == null) return true
    val diagnostics = UsbExclusiveDiagnostics.snapshot(application)
    if (
        shouldSkipUsbExclusiveRouteRebuildForManualPlayback(
            usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled,
            allowMixedPlaybackEnabled = allowMixedPlaybackEnabled,
            hasUsbAudioOutput = diagnostics.hasUsbAudioOutput,
            hasUsbHostAudioDevice = diagnostics.hasUsbHostAudioDevice
        )
    ) {
        NPLogger.i(
            "NERI-UsbExclusive",
            "block manual playback because no USB audio route is available: " +
                "reason=$reason hasUsbOutput=${diagnostics.hasUsbAudioOutput} " +
                "hasUsbHostAudioDevice=${diagnostics.hasUsbHostAudioDevice}"
        )
        markUsbExclusivePlaybackPreparing(false, "manual_play_no_usb:$reason")
        rememberUsbExclusiveInterruptedPlaybackIntent(
            reason = "manual_play_no_usb:$reason",
            mediaItemIndex = player.currentMediaItemIndex.coerceIn(0, mediaItemCount - 1),
            positionMs = player.currentPosition.coerceAtLeast(0L)
        )
        usbExclusiveOpenGatePlaybackJob?.cancel()
        usbExclusiveOpenGatePlaybackJob = null
        usbExclusiveToggleTransitionActive = false
        usbExclusiveToggleTransitionReason = ""
        StartupAudioFocusController.forceRelease("manual_play_no_usb:$reason")
        player.playWhenReady = false
        player.pause()
        _isPlayingFlow.value = false
        _playWhenReadyFlow.value = false
        scheduleStatePersist(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            shouldResumePlayback = true
        )
        postPlayerEvent(
            PlayerEvent.ShowError(
                getLocalizedString(R.string.settings_usb_exclusive_issue_device)
            )
        )
        return false
    }

    val pathState = UsbExclusiveAudioPathTracker.state.value
    val nativeState = UsbExclusiveSessionController.state.value
    val recoverableFallback = pathState.fallbackReason.isRecoverableUsbExclusiveFallback()
    val nativeSessionReusable =
        pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB &&
            pathState.fallbackReason == null &&
            nativeState.source == "player_pcm" &&
            nativeState.opened &&
            !nativeState.transitioning &&
            nativeState.lastError.isNullOrBlank()
    val needsRouteRebuild = !nativeSessionReusable
    if (isPlaybackActiveForUsbExclusiveSwitch() && !recoverableFallback && !needsRouteRebuild) {
        return true
    }
    if (!needsRouteRebuild) return true

    cancelUsbExclusiveRecovery("manual_play:$reason")
    usbAudioSinkReconfigureJob?.cancel()
    usbAudioSinkReconfigureJob = null
    usbExclusiveRouteGeneration += 1L
    usbExclusiveRecoveryAttempts = 0
    pendingUsbExclusivePreferenceReconfigure = false
    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    UsbExclusiveSessionController.clearRecoverablePlayerPcmOpenBlock("manual_play:$reason")
    val openGateReason = UsbExclusiveSessionController.playerPcmOpenGateReason()
    if (openGateReason != null) {
        NPLogger.i(
            "NERI-UsbExclusive",
            "wait for native USB route before manual playback: reason=$reason gate=$openGateReason"
        )
        scheduleUsbExclusivePlaybackAfterOpenGate(reason, openGateReason)
        return false
    }
    applyAudioFocusPolicyOnMainThread()
    applyUsbExclusivePlaybackPolicy(reconfigureAudioSink = false)

    val mediaItemIndex = player.currentMediaItemIndex.coerceIn(0, mediaItemCount - 1)
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    NPLogger.i(
        "NERI-UsbExclusive",
        "prepare native USB route for manual playback: reason=$reason index=$mediaItemIndex positionMs=$positionMs"
    )
    return runCatching {
        player.playWhenReady = false
        player.stop()
        player.seekTo(mediaItemIndex, positionMs)
        player.prepare()
        lastUsbExclusiveAudioSinkReconfigureAtMs = SystemClock.elapsedRealtime()
        true
    }.onFailure { error ->
        UsbExclusiveAudioPathTracker.forceSystemFallback("manual_play_reconfigure_failed")
        NPLogger.e(
            "NERI-UsbExclusive",
            "prepare native USB route for manual playback failed: reason=$reason",
            error
        )
    }.getOrDefault(false)
}

private fun PlayerManager.scheduleUsbExclusivePlaybackAfterOpenGate(
    reason: String,
    initialGateReason: String
) {
    usbExclusiveOpenGatePlaybackJob?.cancel()
    usbExclusiveOpenGatePlaybackJob = mainScope.launch {
        val startedAtMs = SystemClock.elapsedRealtime()
        var gateReason: String? = initialGateReason
        while (
            usbExclusivePlaybackEnabled &&
            resumePlaybackRequested &&
            gateReason != null &&
            SystemClock.elapsedRealtime() - startedAtMs < USB_EXCLUSIVE_OPEN_GATE_WAIT_TIMEOUT_MS
        ) {
            delay(USB_EXCLUSIVE_OPEN_GATE_WAIT_POLL_MS)
            UsbExclusiveSessionController.clearRecoverablePlayerPcmOpenBlock(
                "manual_play_wait:$reason"
            )
            gateReason = UsbExclusiveSessionController.playerPcmOpenGateReason()
        }
        if (!usbExclusivePlaybackEnabled || !resumePlaybackRequested) {
            markUsbExclusivePlaybackPreparing(false, "manual_play_cancelled:$reason")
            if (!usbExclusivePlaybackEnabled) {
                resumeInterruptedUsbExclusivePlaybackIfNeeded("open_gate_cancelled:$reason")
            }
            return@launch
        }
        if (gateReason != null) {
            markUsbExclusivePlaybackPreparing(false, "manual_play_gate_timeout:$gateReason")
            stopPlaybackAfterUsbExclusiveNativeFailure(gateReason)
            return@launch
        }
        if (!prepareUsbExclusiveRouteForManualPlayback("open_gate_retry:$reason")) {
            return@launch
        }
        applyAudioFocusPolicyOnMainThread()
        player.playWhenReady = true
        player.play()
        NPLogger.i(
            "NERI-UsbExclusive",
            "resumed pending playback after native gate: reason=$reason"
        )
    }
}

internal fun PlayerManager.updateUsbExclusiveForegroundState(
    foreground: Boolean,
    reason: String
) {
    if (usbExclusiveAppInForeground == foreground) return
    usbExclusiveAppInForeground = foreground
    if (!foreground) {
        usbExclusiveForegroundRecoveryJob?.cancel()
        usbExclusiveForegroundRecoveryJob = null
        scheduleUsbExclusiveBackgroundAudit(reason)
    } else {
        usbExclusiveBackgroundAuditJob?.cancel()
        usbExclusiveBackgroundAuditJob = null
    }
    if (!usbExclusivePlaybackEnabled) return
    applyActiveUsbExclusiveBuffer(reason)
}

private fun PlayerManager.scheduleUsbExclusiveBackgroundAudit(reason: String) {
    usbExclusiveBackgroundAuditJob?.cancel()
    if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return
    val routeGeneration = usbExclusiveRouteGeneration
    val checkpointsMs = listOf(1_000L, 5_000L, 15_000L)
    usbExclusiveBackgroundAuditJob = mainScope.launch {
        var elapsedMs = 0L
        var lastAuditHandle = 0L
        var lastAuditCompletedFrames = -1L
        var lastAuditSignalBytes = -1L
        var lastAuditZeroFillBytes = -1L
        var lastAuditOutputPeak = Float.NaN
        var auditStallTicks = 0
        for (checkpointMs in checkpointsMs) {
            delay((checkpointMs - elapsedMs).coerceAtLeast(0L))
            elapsedMs = checkpointMs
            if (usbExclusiveBackgroundAuditJob == null) return@launch
            if (usbExclusiveAppInForeground || !usbExclusivePlaybackEnabled || !isPlayerInitialized()) {
                return@launch
            }
            if (routeGeneration != usbExclusiveRouteGeneration) return@launch
            UsbExclusiveSessionController.refresh(application)
            val nativeState = UsbExclusiveSessionController.state.value
            val pathState = UsbExclusiveAudioPathTracker.state.value
            val playerPosition = runCatching { player.currentPosition }.getOrDefault(-1L)
            val playerState = runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)
            NPLogger.i(
                "NERI-UsbExclusive",
                "background USB audit: reason=$reason elapsedMs=$checkpointMs " +
                    "serviceInstance=${AudioPlayerService.isInstanceActiveForDiagnostics()} " +
                    "serviceForeground=${AudioPlayerService.isForegroundActiveForDiagnostics()} " +
                    "wakeLock=${UsbExclusiveWakeLock.isHeld()} path=${pathState.effectivePath} " +
                    "sinkPlaying=${pathState.sinkPlaying} nativeStreaming=${nativeState.streaming} " +
                    "completedFrames=${nativeState.completedAudioFrames} " +
                    "queuedFrames=${nativeState.queuedAudioFrames} " +
                    "pcm=${nativeState.pcmLevelBytes}/${nativeState.pcmCapacityBytes} " +
                    "pcmFree=${nativeState.pcmFreeBytes} " +
                    "backpressureEvents=${nativeState.pcmBackpressureEvents} " +
                    "backpressureCurrentMs=${nativeState.pcmBackpressureCurrentMs} " +
                    "signalFrames=${nativeState.playerSignalFrames} " +
                    "silentFrames=${nativeState.playerSilentFrames} " +
                    "zeroFillBytes=${nativeState.playerZeroFillBytes} " +
                    "outputPeak=${nativeState.outputPeak} " +
                    "lastOutputPeak=${nativeState.lastOutputPeak} " +
                    "playerState=${playbackStateName(playerState)} " +
                    "playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} " +
                    "positionMs=$playerPosition runtime=${nativeState.runtimeReport}"
            )
            val shouldCheckFakeProgress =
                isTransportActiveWithoutInitialization() &&
                    pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB &&
                    pathState.sinkPlaying &&
                    nativeState.source == "player_pcm" &&
                    nativeState.streaming &&
                    !nativeState.transitioning
            if (shouldCheckFakeProgress) {
                val decision = evaluateUsbExclusiveKeepAliveProgress(
                    previousHandle = lastAuditHandle,
                    currentHandle = nativeState.handle,
                    previousCompletedFrames = lastAuditCompletedFrames,
                    currentCompletedFrames = nativeState.completedAudioFrames,
                    previousSignalBytes = lastAuditSignalBytes,
                    currentSignalBytes = nativeState.playerSignalBytes,
                    previousZeroFillBytes = lastAuditZeroFillBytes,
                    currentZeroFillBytes = nativeState.playerZeroFillBytes,
                    previousOutputPeak = lastAuditOutputPeak,
                    currentOutputPeak = nativeState.lastOutputPeak,
                    previousStallTicks = auditStallTicks,
                    recoveryTicks = 1
                )
                auditStallTicks = decision.stallTicks
                lastAuditHandle = nativeState.handle
                lastAuditCompletedFrames = nativeState.completedAudioFrames
                lastAuditSignalBytes = nativeState.playerSignalBytes
                lastAuditZeroFillBytes = nativeState.playerZeroFillBytes
                lastAuditOutputPeak = nativeState.lastOutputPeak
                if (decision.shouldRecover) {
                    NPLogger.w(
                        "NERI-UsbExclusive",
                        "background USB audit detected fake native progress: " +
                            "reason=$reason elapsedMs=$checkpointMs progress=${decision.progress} " +
                            "completedFrames=${nativeState.completedAudioFrames} " +
                            "signalBytes=${nativeState.playerSignalBytes} " +
                            "zeroFillBytes=${nativeState.playerZeroFillBytes} " +
                            "lastOutputPeak=${nativeState.lastOutputPeak}"
                    )
                    recoverUsbExclusivePlaybackIfUnhealthy(
                        reason = "background_audit_fake_progress:$reason:$checkpointMs",
                        forceRecovery = true
                    )
                    return@launch
                }
            } else {
                auditStallTicks = 0
                lastAuditHandle = nativeState.handle
                lastAuditCompletedFrames = nativeState.completedAudioFrames
                lastAuditSignalBytes = nativeState.playerSignalBytes
                lastAuditZeroFillBytes = nativeState.playerZeroFillBytes
                lastAuditOutputPeak = nativeState.lastOutputPeak
            }
            recoverUsbExclusivePlaybackIfUnhealthy(reason = "background_audit:$reason:$checkpointMs")
        }
        if (usbExclusiveBackgroundAuditJob === coroutineContext[kotlinx.coroutines.Job]) {
            usbExclusiveBackgroundAuditJob = null
        }
    }
}

internal fun PlayerManager.recoverUsbExclusivePlaybackIfUnhealthy(
    reason: String,
    forceRecovery: Boolean = false
): Boolean {
    if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled || !isPlayerInitialized()) {
        return false
    }
    if (!forceRecovery && !isPlaybackActiveForUsbExclusiveSwitch()) return false
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainScope.launch { recoverUsbExclusivePlaybackIfUnhealthy(reason, forceRecovery) }
        return true
    }
    val nativeState = UsbExclusiveSessionController.state.value
    if (nativeState.transitioning || nativeState.source == "tone") return false
    val openGateReason = UsbExclusiveSessionController.playerPcmOpenGateReason()
    if (openGateReason != null) {
        NPLogger.i(
            "NERI-UsbExclusive",
            "defer USB recovery while native open gate is active: reason=$reason gate=$openGateReason"
        )
        return false
    }
    val pathState = UsbExclusiveAudioPathTracker.state.value
    val recoverableFallback = pathState.fallbackReason.isRecoverableUsbExclusiveFallback()
    val nativeStreaming = pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB &&
        nativeState.source == "player_pcm" &&
        nativeState.streaming
    val intentionalSystemFallback = pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM &&
        pathState.fallbackReason != null &&
        !recoverableFallback
    val staleSystemPath = pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM &&
        pathState.fallbackReason == null
    val nativePathStopped = pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB &&
        !nativeStreaming
    val needsRecovery = forceRecovery || recoverableFallback || staleSystemPath || nativePathStopped
    if (intentionalSystemFallback && !forceRecovery) return false
    if (!needsRecovery) return false

    usbExclusiveRouteGeneration += 1L
    pendingUsbExclusivePreferenceReconfigure = false
    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    UsbExclusiveSessionController.clearRecoverablePlayerPcmOpenBlock("usb_recovery:$reason")
    UsbExclusiveSessionController.requireFreshPlayerPcmOpen("usb_recovery:$reason")
    markUsbExclusivePlaybackPreparing(true, "usb_recovery:$reason")
    applyAudioFocusPolicyOnMainThread()
    scheduleUsbAudioSinkReconfiguration(
        reason = "usb_recovery:$reason",
        allowWhilePlaybackActive = true,
        bypassCooldown = true
    )
    NPLogger.w(
        "NERI-UsbExclusive",
        "recover USB exclusive playback by rebuilding native route: reason=$reason " +
            "force=$forceRecovery attempt=$usbExclusiveRecoveryAttempts " +
            "path=${pathState.effectivePath} fallback=${pathState.fallbackReason} " +
            "native=${nativeState.source}/${nativeState.streaming} " +
            "completedFrames=${nativeState.completedAudioFrames} runtime=${nativeState.runtimeReport}"
    )
    return true
}

private fun PlayerManager.applyActiveUsbExclusiveBuffer(reason: String) {
    val targetBufferMs = usbExclusivePreferences.bufferDurationMs(
        appInForeground = usbExclusiveAppInForeground
    )
    val nativeState = UsbExclusiveSessionController.state.value
    if (!shouldApplyActiveUsbBufferResize(
            streaming = nativeState.streaming,
            currentBufferMs = nativeState.bufferDurationMs,
            targetBufferMs = targetBufferMs
        )
    ) {
        NPLogger.d(
            "NERI-UsbExclusive",
            "defer active USB buffer shrink: reason=$reason current=${nativeState.bufferDurationMs} target=$targetBufferMs"
        )
        return
    }
    val applied = UsbExclusiveSessionController.configureActivePlayerBufferDuration(
        durationMs = targetBufferMs,
        appInForeground = usbExclusiveAppInForeground
    )
    if (applied) {
        NPLogger.d(
            "NERI-UsbExclusive",
            "updated active USB buffer: reason=$reason foreground=$usbExclusiveAppInForeground bufferMs=$targetBufferMs"
        )
    }
}

private fun UsbExclusivePreferences.requiresUsbExclusiveRouteReconfiguration(
    next: UsbExclusivePreferences
): Boolean {
    return selectedDeviceKey != next.selectedDeviceKey ||
        sampleRateMode != next.sampleRateMode ||
        bitDepthMode != next.bitDepthMode ||
        unsupportedFormatPolicy != next.unsupportedFormatPolicy ||
        sampleRateCompatibilityEnabled != next.sampleRateCompatibilityEnabled ||
        bitDepthCompatibilityEnabled != next.bitDepthCompatibilityEnabled ||
        channelCompatibilityEnabled != next.channelCompatibilityEnabled
}

internal fun PlayerManager.recoverUsbExclusivePlaybackOnForeground(reason: String) {
    if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return
    usbExclusiveForegroundRecoveryJob?.cancel()
    usbExclusiveForegroundRecoveryJob = mainScope.launch {
        if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return@launch
        if (isUsbExclusiveNativePlaybackStable()) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "skip foreground USB recovery because native playback is already stable: reason=$reason"
            )
            return@launch
        }
        applyAudioFocusPolicyOnMainThread()
        applyUsbExclusivePlaybackPolicy(reconfigureAudioSink = false)
        UsbExclusiveSessionController.refresh(application)
        val nativeState = UsbExclusiveSessionController.state.value
        if (nativeState.transitioning || UsbExclusiveSessionController.playerPcmOpenGateReason() != null) {
            NPLogger.i(
                "NERI-UsbExclusive",
                "skip foreground USB recovery while native transition is active: reason=$reason " +
                    "runtime=${nativeState.runtimeReport}"
            )
            return@launch
        }
        if (recoverUsbExclusivePlaybackIfUnhealthy(reason = "foreground_recovery:$reason")) {
            return@launch
        }
        val playbackActive = isPlaybackActiveForUsbExclusiveSwitch()
        if (!playbackActive || !nativeState.streaming) return@launch
        val completedFramesBefore = nativeState.completedAudioFrames
        delay(USB_EXCLUSIVE_FOREGROUND_STALL_CHECK_MS)
        if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return@launch
        if (!isPlaybackActiveForUsbExclusiveSwitch()) return@launch
        UsbExclusiveSessionController.refresh(application)
        val refreshedNativeState = UsbExclusiveSessionController.state.value
        val stalled = refreshedNativeState.streaming &&
            refreshedNativeState.completedAudioFrames <= completedFramesBefore &&
            shouldKeepPlaybackActiveForUsbRouteSwitch()
        if (stalled) {
            NPLogger.w(
                "NERI-UsbExclusive",
                "foreground USB stream looks stalled; rebuild native route: " +
                    "reason=$reason completedBefore=$completedFramesBefore " +
                    "completedAfter=${refreshedNativeState.completedAudioFrames}"
            )
            recoverUsbExclusivePlaybackIfUnhealthy(
                reason = "foreground_stalled:$reason",
                forceRecovery = true
            )
            return@launch
        }
        usbExclusiveToggleTransitionActive = false
        usbExclusiveToggleTransitionReason = ""
        markUsbExclusivePlaybackPreparing(false, "usb_foreground_stable")
    }
}

private fun PlayerManager.cancelUsbExclusiveRecovery(reason: String) {
    usbExclusiveRecoveryJob?.cancel()
    usbExclusiveRecoveryJob = null
    usbExclusiveForegroundRecoveryJob?.cancel()
    usbExclusiveForegroundRecoveryJob = null
    usbExclusiveBackgroundAuditJob?.cancel()
    usbExclusiveBackgroundAuditJob = null
    NPLogger.d("NERI-UsbExclusive", "cancelUsbExclusiveRecovery(): reason=$reason")
}

private fun PlayerManager.cancelUsbExclusiveSystemAudioRelease(reason: String) {
    val releaseJob = usbExclusiveSystemAudioReleaseJob
    usbExclusiveSystemAudioResumeJob?.cancel()
    usbExclusiveSystemAudioResumeJob = null
    usbExclusiveSystemAudioWatchdogJob?.cancel()
    usbExclusiveSystemAudioWatchdogJob = null
    if (releaseJob == null) {
        usbExclusiveSystemAudioReleaseInProgress = false
        return
    }
    if (releaseJob.isActive) {
        NPLogger.i(
            "NERI-UsbExclusive",
            "cancel stale Android audio release before USB activation: reason=$reason"
        )
        releaseJob.cancel()
    }
    usbExclusiveSystemAudioReleaseJob = null
    usbExclusiveSystemAudioReleaseInProgress = false
}

private fun PlayerManager.shouldKeepPlaybackActiveForUsbRouteSwitch(): Boolean {
    if (!isPlayerInitialized()) return false
    if (resumePlaybackRequested) return true
    if (usbExclusiveInterruptedPlaybackIntent != null) return true
    if (playJob?.isActive == true) return true
    return if (Looper.myLooper() == Looper.getMainLooper()) {
        player.isPlaying || player.playWhenReady
    } else {
        _isPlayingFlow.value || _playWhenReadyFlow.value
    }
}

internal fun PlayerManager.resumeInterruptedUsbExclusivePlaybackIfNeeded(reason: String): Boolean {
    val intent = usbExclusiveInterruptedPlaybackIntent ?: return false
    if (usbExclusivePlaybackEnabled || currentPlaylist.isEmpty()) return false
    if (intent.mediaItemIndex !in currentPlaylist.indices) {
        clearUsbExclusiveInterruptedPlaybackIntent("invalid_index:$reason")
        return false
    }
    clearUsbExclusiveInterruptedPlaybackIntent("resume:$reason")
    NPLogger.i(
        "NERI-UsbExclusive",
        "resume playback interrupted by USB exclusive failure: reason=$reason " +
            "index=${intent.mediaItemIndex} positionMs=${intent.positionMs} " +
            "failure=${intent.reason}"
    )
    updateResumePlaybackRequested(true)
    playAtIndex(
        intent.mediaItemIndex,
        resumePositionMs = intent.positionMs.coerceAtLeast(0L),
        forceStartupProtectionFade = intent.positionMs > 0L
    )
    return true
}

private fun PlayerManager.shouldKeepPlaybackIntentAfterUsbNativeFailure(): Boolean {
    if (!isPlayerInitialized()) return false
    if (currentPlaylist.isEmpty()) return false
    return resumePlaybackRequested ||
        playJob?.isActive == true ||
        player.playWhenReady ||
        player.isPlaying ||
        _playWhenReadyFlow.value ||
        _isPlayingFlow.value
}

private fun PlayerManager.rememberUsbExclusiveInterruptedPlaybackIntent(
    reason: String,
    mediaItemIndex: Int,
    positionMs: Long
) {
    val safeIndex = mediaItemIndex.takeIf { it in currentPlaylist.indices } ?: return
    val intent = PlayerManager.UsbExclusiveInterruptedPlaybackIntent(
        mediaItemIndex = safeIndex,
        positionMs = positionMs.coerceAtLeast(0L),
        requestToken = playbackRequestToken,
        reason = reason
    )
    usbExclusiveInterruptedPlaybackIntent = intent
    updateResumePlaybackRequested(true)
    NPLogger.i(
        "NERI-UsbExclusive",
        "remember playback intent after USB exclusive interruption: reason=$reason " +
            "index=${intent.mediaItemIndex} positionMs=${intent.positionMs} " +
            "token=${intent.requestToken}"
    )
}

internal fun PlayerManager.clearUsbExclusiveInterruptedPlaybackIntent(reason: String) {
    val intent = usbExclusiveInterruptedPlaybackIntent ?: return
    usbExclusiveInterruptedPlaybackIntent = null
    NPLogger.d(
        "NERI-UsbExclusive",
        "clear interrupted USB playback intent: reason=$reason " +
            "index=${intent.mediaItemIndex} positionMs=${intent.positionMs} " +
            "failure=${intent.reason}"
    )
}

private const val USB_EXCLUSIVE_RECONFIGURE_DEBOUNCE_MS = 120L
private const val USB_EXCLUSIVE_RECONFIGURE_COOLDOWN_MS = 2_500L
private const val USB_EXCLUSIVE_OPEN_GATE_RETRY_DELAY_MS = 3_800L
private const val USB_EXCLUSIVE_OPEN_GATE_WAIT_TIMEOUT_MS = 8_000L
private const val USB_EXCLUSIVE_OPEN_GATE_WAIT_POLL_MS = 100L
private const val USB_EXCLUSIVE_SAFE_SWITCH_POLL_MS = 800L
private const val USB_EXCLUSIVE_FOREGROUND_STALL_CHECK_MS = 360L
private const val USB_EXCLUSIVE_ROUTE_JITTER_REOPEN_COOLDOWN_MS = 4_000L
private const val USB_EXCLUSIVE_RELEASE_REOPEN_COOLDOWN_MS = 3_500L
private const val USB_EXCLUSIVE_SYSTEM_AUDIO_RELEASE_DELAY_MS = 650L
private const val USB_EXCLUSIVE_NATIVE_CLOSE_WAIT_TIMEOUT_MS = 4_000L
private const val USB_EXCLUSIVE_NATIVE_CLOSE_WAIT_POLL_MS = 50L
private const val USB_EXCLUSIVE_SYSTEM_AUDIO_RESUME_WATCHDOG_MS = 1_600L
private const val USB_EXCLUSIVE_SYSTEM_AUDIO_STALL_TOLERANCE_MS = 50L
private const val USB_EXCLUSIVE_FIRST_COMPLETION_RECOVERY_MAX_ATTEMPTS = 1

private fun PlayerManager.shouldPauseForBluetoothDisconnect(
    previousDevice: AudioDevice?,
    newDevice: AudioDevice?
): Boolean {
    if (!stopOnBluetoothDisconnectEnabled) return false
    if (!_isPlayingFlow.value) return false
    if (previousDevice == null || !requiresDisconnectConfirmation(previousDevice.type)) return false
    return newDevice == null || newDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
}

private fun PlayerManager.schedulePauseForBluetoothDisconnect(
    previousDevice: AudioDevice?,
    reason: String
) {
    if (previousDevice == null || !requiresDisconnectConfirmation(previousDevice.type)) return
    bluetoothDisconnectPauseJob?.cancel()
    suppressPlaybackForAudioRouteLoss(reason = "bluetooth_disconnect_pending:$reason")
    NPLogger.d(
        "NERI-PlayerManager",
        "schedulePauseForBluetoothDisconnect(): device=${previousDevice.type}:${previousDevice.name}, reason=$reason, delayMs=$BLUETOOTH_DISCONNECT_CONFIRM_DELAY_MS"
    )
    bluetoothDisconnectPauseJob = mainScope.launch {
        delay(BLUETOOTH_DISCONNECT_CONFIRM_DELAY_MS)
        if (!stopOnBluetoothDisconnectEnabled || !_isPlayingFlow.value) {
            NPLogger.d(
                "NERI-PlayerManager",
                "schedulePauseForBluetoothDisconnect(): canceled after delay, enabled=$stopOnBluetoothDisconnectEnabled, isPlaying=${_isPlayingFlow.value}, reason=$reason"
            )
            restorePlaybackAfterTransientAudioRouteLoss(reason = "bluetooth_disconnect_canceled:$reason")
            bluetoothDisconnectPauseJob = null
            return@launch
        }

        val audioManager: AudioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val confirmedDevice = getCurrentAudioDevice(audioManager)
        _currentAudioDevice.value = confirmedDevice
        if (confirmedDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            NPLogger.d(
                "NERI-PlayerManager",
                "Confirmed bluetooth disconnect ($reason), pausing playback."
            )
            pauseForAudioRouteLoss(reason = "bluetooth_disconnect_confirmed:$reason")
        } else {
            NPLogger.d(
                "NERI-PlayerManager",
                "Ignored transient bluetooth route change ($reason): ${confirmedDevice.type}"
            )
            restorePlaybackAfterTransientAudioRouteLoss(
                reason = "bluetooth_disconnect_transient:${confirmedDevice.type}"
            )
        }
        bluetoothDisconnectPauseJob = null
    }
}

private fun PlayerManager.getCurrentAudioDevice(audioManager: AudioManager): AudioDevice {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val usbDevice = devices.firstOrNull { isUsbOutputType(it.type) }
    if (usbExclusivePlaybackEnabled && usbDevice != null) {
        return toUsbAudioDevice(usbDevice)
    }
    val bluetoothDevice = devices.firstOrNull { isBluetoothOutputType(it.type) }
    if (bluetoothDevice != null) {
        return try {
            AudioDevice(
                name = bluetoothDevice.productName.toString()
                    .ifBlank { getLocalizedString(R.string.device_bluetooth_headset) },
                type = bluetoothDevice.type,
                icon = Icons.Default.BluetoothAudio
            )
        } catch (_: SecurityException) {
            AudioDevice(
                getLocalizedString(R.string.device_bluetooth_headset),
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                Icons.Default.BluetoothAudio
            )
        }
    }
    val wiredHeadset = devices.firstOrNull { isWiredOutputType(it.type) }
    if (wiredHeadset != null) {
        if (isUsbOutputType(wiredHeadset.type)) {
            return toUsbAudioDevice(wiredHeadset)
        }
        return AudioDevice(
            getLocalizedString(R.string.device_wired_headset),
            wiredHeadset.type,
            Icons.Default.Headset
        )
    }
    return AudioDevice(
        getLocalizedString(R.string.device_speaker),
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        Icons.Default.SpeakerGroup
    )
}

private fun PlayerManager.applyUsbExclusivePlaybackPolicy(
    reconfigureAudioSink: Boolean = false,
    reconfigureReason: String = "usb_policy_changed",
    allowReconfigureWhilePlaying: Boolean = false
) {
    if (!isPlayerInitialized()) return
    updateAudioOffloadPreferences("usb_exclusive_policy")
    val audioManager: AudioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (usbExclusivePlaybackEnabled) {
        if (reconfigureAudioSink) {
            usbExclusiveRouteGeneration += 1L
            UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        }
        UsbExclusiveDiagnostics.ensureUsbPermissionIfNeeded(
            context = application,
            reason = "apply_policy"
        )
    } else {
        UsbExclusiveSessionController.stopPlayerPcmSession("apply_policy_disabled")
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(application, "apply_policy_disabled")
    }
    val preferredDevice: AudioDeviceInfo? = null
    val policyGeneration = usbExclusiveRouteGeneration
    UsbExclusiveDebugLogger.logSnapshot(
        context = application,
        audioManager = audioManager,
        reason = "apply_policy_before_set",
        enabled = usbExclusivePlaybackEnabled,
        preferredDevice = preferredDevice
    )
    mainScope.launch {
        if (!isPlayerInitialized()) return@launch
        if (usbExclusiveRouteGeneration != policyGeneration) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "skip stale USB route policy: generation=$policyGeneration current=$usbExclusiveRouteGeneration"
            )
            return@launch
        }
        runCatching {
            player.setPreferredAudioDevice(preferredDevice)
        }.onSuccess {
            NPLogger.d(
                "NERI-PlayerManager",
                "applyUsbExclusivePlaybackPolicy(): enabled=$usbExclusivePlaybackEnabled, target=${preferredDevice.describeForLog()}"
            )
            UsbExclusiveDebugLogger.logSnapshot(
                context = application,
                audioManager = audioManager,
                reason = "apply_policy_after_set",
                enabled = usbExclusivePlaybackEnabled,
                preferredDevice = preferredDevice
            )
        }.onFailure { error ->
            NPLogger.w(
                "NERI-UsbExclusive",
                "applyUsbExclusivePlaybackPolicy(): setPreferredAudioDevice failed, enabled=$usbExclusivePlaybackEnabled, target=${preferredDevice.describeForLog()}",
                error
            )
        }
        if (reconfigureAudioSink) {
            scheduleUsbAudioSinkReconfiguration(
                reason = reconfigureReason,
                allowWhilePlaybackActive = allowReconfigureWhilePlaying
            )
        }
    }
}

internal fun PlayerManager.scheduleUsbAudioSinkReconfiguration(
    reason: String,
    allowWhilePlaybackActive: Boolean = false,
    bypassCooldown: Boolean = false
) {
    mainScope.launch {
        val scheduledGeneration = usbExclusiveRouteGeneration
        usbAudioSinkReconfigureJob?.cancel()
        usbAudioSinkReconfigureJob = mainScope.launch reconfigure@{
            val usbActivationReason = usbExclusivePlaybackEnabled &&
                reason.isUsbExclusiveActivationReason()
            if (usbExclusiveRouteGeneration != scheduledGeneration) {
                NPLogger.d(
                    "NERI-UsbExclusive",
                    "skip stale USB reconfiguration before delay: reason=$reason generation=$scheduledGeneration current=$usbExclusiveRouteGeneration"
                )
                return@reconfigure
            }
            if (
                usbExclusivePlaybackEnabled &&
                !allowWhilePlaybackActive &&
                isPlaybackActiveForUsbExclusiveSwitch()
            ) {
                if (!usbExclusiveAppInForeground) {
                    pendingUsbExclusivePreferenceReconfigure = false
                    NPLogger.i(
                        "NERI-UsbExclusive",
                        "skip USB reconfiguration wait while app is backgrounded: reason=$reason"
                    )
                    return@reconfigure
                }
                pendingUsbExclusivePreferenceReconfigure = true
                NPLogger.i(
                    "NERI-UsbExclusive",
                    "defer USB reconfiguration while playback is active: reason=$reason allowWhilePlaybackActive=$allowWhilePlaybackActive activation=$usbActivationReason"
                )
                return@reconfigure
            }
            val now = SystemClock.elapsedRealtime()
            val elapsedMs = now - lastUsbExclusiveAudioSinkReconfigureAtMs
            val cooldownMs = if (bypassCooldown) {
                USB_EXCLUSIVE_RECONFIGURE_DEBOUNCE_MS
            } else if (reason.contains("open_gate_retry", ignoreCase = true)) {
                USB_EXCLUSIVE_OPEN_GATE_RETRY_DELAY_MS
            } else if (usbExclusivePlaybackEnabled && reason.isUsbExclusiveReason()) {
                USB_EXCLUSIVE_RECONFIGURE_COOLDOWN_MS
            } else {
                USB_EXCLUSIVE_RECONFIGURE_DEBOUNCE_MS
            }
            delay((cooldownMs - elapsedMs).coerceAtLeast(USB_EXCLUSIVE_RECONFIGURE_DEBOUNCE_MS))
            if (!isPlayerInitialized() || player.currentMediaItem == null) return@reconfigure
            if (usbExclusiveRouteGeneration != scheduledGeneration) {
                NPLogger.d(
                    "NERI-UsbExclusive",
                    "skip stale USB reconfiguration after delay: reason=$reason generation=$scheduledGeneration current=$usbExclusiveRouteGeneration"
                )
                return@reconfigure
            }
            if (
                usbExclusivePlaybackEnabled &&
                !allowWhilePlaybackActive &&
                isPlaybackActiveForUsbExclusiveSwitch()
            ) {
                if (!usbExclusiveAppInForeground) {
                    pendingUsbExclusivePreferenceReconfigure = false
                    NPLogger.i(
                        "NERI-UsbExclusive",
                        "skip delayed USB reconfiguration wait while app is backgrounded: reason=$reason"
                    )
                    return@reconfigure
                }
                pendingUsbExclusivePreferenceReconfigure = true
                return@reconfigure
            }
            val mediaItemCount = player.mediaItemCount
            if (mediaItemCount <= 0) return@reconfigure
            val mediaItemIndex = player.currentMediaItemIndex.coerceIn(0, mediaItemCount - 1)
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val resumePlayback = shouldKeepPlaybackActiveForUsbRouteSwitch()
            val shouldWaitForSystemAudioRelease =
                usbExclusivePlaybackEnabled && reason.isUsbExclusiveActivationReason()
            NPLogger.d(
                "NERI-UsbExclusive",
                "reconfigureAudioSink(): reason=$reason index=$mediaItemIndex positionMs=$positionMs playing=$resumePlayback"
            )
            val stopped = runCatching {
                restorePlaybackAfterTransientAudioRouteLoss(reason = "usb_reconfigure:$reason")
                player.playWhenReady = false
                player.stop()
            }.onFailure { error ->
                runCatching { player.playWhenReady = resumePlayback }
                NPLogger.e(
                    "NERI-UsbExclusive",
                    "reconfigureAudioSink() failed to stop current sink: reason=$reason",
                    error
                )
            }.isSuccess
            if (!stopped) return@reconfigure
            if (shouldWaitForSystemAudioRelease) {
                delay(USB_EXCLUSIVE_SYSTEM_AUDIO_RELEASE_DELAY_MS)
                if (!isPlayerInitialized() || player.currentMediaItem == null) return@reconfigure
                if (usbExclusiveRouteGeneration != scheduledGeneration) {
                    NPLogger.d(
                        "NERI-UsbExclusive",
                        "skip stale USB reconfiguration after system release delay: reason=$reason generation=$scheduledGeneration current=$usbExclusiveRouteGeneration"
                    )
                    return@reconfigure
                }
            }
            runCatching {
                player.seekTo(mediaItemIndex, positionMs)
                player.prepare()
                updateResumePlaybackRequested(resumePlayback)
                player.playWhenReady = resumePlayback
                if (resumePlayback) {
                    player.play()
                }
            }.onSuccess {
                lastUsbExclusiveAudioSinkReconfigureAtMs = SystemClock.elapsedRealtime()
                pendingUsbExclusivePreferenceReconfigure = false
                usbExclusiveToggleTransitionActive = false
                usbExclusiveToggleTransitionReason = ""
                markUsbExclusivePlaybackPreparing(false, "usb_reconfigure_success")
            }.onFailure { error ->
                runCatching { player.playWhenReady = resumePlayback }
                usbExclusiveToggleTransitionActive = false
                usbExclusiveToggleTransitionReason = ""
                markUsbExclusivePlaybackPreparing(false, "usb_reconfigure_failed")
                NPLogger.e("NERI-UsbExclusive", "reconfigureAudioSink() failed: reason=$reason", error)
            }
        }
    }
}

private fun PlayerManager.deferUsbExclusiveReconfigurationUntilPlaybackStops(reason: String) {
    pendingUsbExclusivePreferenceReconfigure = true
    usbAudioSinkReconfigureJob?.cancel()
    usbAudioSinkReconfigureJob = mainScope.launch reconfigure@{
        if (!usbExclusiveAppInForeground) {
            pendingUsbExclusivePreferenceReconfigure = false
            NPLogger.i(
                "NERI-UsbExclusive",
                "skip deferred native USB switch while app is backgrounded: reason=$reason"
            )
            return@reconfigure
        }
        NPLogger.i(
            "NERI-UsbExclusive",
            "defer native USB switch until playback stops: reason=$reason"
        )
        while (
            usbExclusivePlaybackEnabled &&
            usbExclusiveAppInForeground &&
            isPlayerInitialized() &&
            player.currentMediaItem != null &&
            isPlaybackActiveForUsbExclusiveSwitch()
        ) {
            delay(USB_EXCLUSIVE_SAFE_SWITCH_POLL_MS)
        }
        if (!usbExclusivePlaybackEnabled || !usbExclusiveAppInForeground || !isPlayerInitialized()) {
            pendingUsbExclusivePreferenceReconfigure = false
            return@reconfigure
        }
        if (player.currentMediaItem == null) {
            pendingUsbExclusivePreferenceReconfigure = false
            return@reconfigure
        }
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        pendingUsbExclusivePreferenceReconfigure = false
        scheduleUsbAudioSinkReconfiguration("deferred:$reason")
    }
}

private fun PlayerManager.isPlaybackActiveForUsbExclusiveSwitch(): Boolean {
    if (!initialized || _currentSongFlow.value == null) return false
    return isTransportActiveWithoutInitialization() ||
        _playerPlaybackStateFlow.value == Player.STATE_BUFFERING
}

private fun String.isUsbExclusiveReason(): Boolean {
    return contains("usb", ignoreCase = true) ||
        contains("native", ignoreCase = true)
}

private fun String.isUsbExclusiveActivationReason(): Boolean {
    if (!isUsbExclusiveReason()) return false
    if (contains("disabled", ignoreCase = true)) return false
    if (contains("fallback", ignoreCase = true)) return false
    if (contains("failed", ignoreCase = true)) return false
    return contains("enabled", ignoreCase = true) ||
        contains("preference", ignoreCase = true) ||
        contains("policy", ignoreCase = true) ||
        contains("foreground", ignoreCase = true) ||
        contains("permission", ignoreCase = true) ||
        contains("device", ignoreCase = true)
}

private fun String.isUserDrivenUsbExclusiveActivation(): Boolean {
    return contains("enabled", ignoreCase = true) ||
        contains("manual", ignoreCase = true) ||
        contains("playback_start", ignoreCase = true) ||
        contains("permission", ignoreCase = true) ||
        contains("preference", ignoreCase = true)
}

private fun String?.isRecoverableUsbExclusiveFallback(): Boolean {
    val reason = this ?: return false
    if (reason.isNativeTransitionInFlightGate()) return false
    if (reason.contains("permission", ignoreCase = true)) return false
    if (reason.contains("usb_device_detached", ignoreCase = true)) return false
    if (reason.startsWith("no_", ignoreCase = true)) return false
    if (reason.startsWith("No permitted", ignoreCase = true)) return false
    if (reason.startsWith("unsupported_input", ignoreCase = true)) return false
    if (reason.startsWith("channel_count_unsupported", ignoreCase = true)) return false
    if (reason.startsWith("sample_rate_unsupported")) return false
    if (reason.startsWith("bit_depth_unsupported")) return false
    if (reason.contains("requires_system", ignoreCase = true)) return false
    if (reason.contains("requires_system_audio", ignoreCase = true)) return false
    if (reason.contains("playback_parameters_require", ignoreCase = true)) return false
    if (reason.contains("skip_silence_requires", ignoreCase = true)) return false
    if (reason.contains("tunneling_requires", ignoreCase = true)) return false
    if (reason.contains("aux_effect_requires", ignoreCase = true)) return false
    if (reason.contains("equalizer_requires", ignoreCase = true)) return false
    if (reason.contains("loudness_requires", ignoreCase = true)) return false
    return true
}

private fun String.isNativeTransitionInFlightGate(): Boolean {
    return startsWith("native_transition_in_flight") ||
        startsWith("transition_in_flight")
}

private fun String.isRecoverableUsbExclusiveNativeTransferFailure(): Boolean {
    if (contains("LIBUSB_ERROR_NO_DEVICE", ignoreCase = true)) return false
    if (contains("permission", ignoreCase = true)) return false
    return isUsbExclusiveFirstCompletionTimeout() ||
        contains("native_transport_failed", ignoreCase = true) ||
        contains("transportFailed=true", ignoreCase = true) ||
        contains("LIBUSB_ERROR_IO", ignoreCase = true) ||
        contains("transfer_status=5", ignoreCase = true) ||
        contains("resubmit_failed", ignoreCase = true) ||
        contains("submiturb failed", ignoreCase = true)
}

private fun String.isUsbExclusiveFirstCompletionTimeout(): Boolean {
    return contains("event_loop_first_completion_timeout", ignoreCase = true)
}

private fun String.isLifecycleForegroundRecoveryReason(): Boolean {
    return startsWith("foreground_recovery:lifecycle", ignoreCase = true) ||
        startsWith("foreground_stalled:lifecycle", ignoreCase = true)
}

private fun PlayerManager.toUsbAudioDevice(device: AudioDeviceInfo): AudioDevice {
    return AudioDevice(
        name = device.productName.toString()
            .ifBlank { getLocalizedString(R.string.device_usb_audio) },
        type = device.type,
        icon = Icons.Default.Usb
    )
}

private fun AudioDeviceInfo?.describeForLog(): String {
    return this?.let { "${it.type}:${it.productName}" } ?: "none"
}

private fun PlayerManager.shouldPauseForImmediateOutputDisconnect(
    previousDevice: AudioDevice?,
    newDevice: AudioDevice?
): Boolean {
    if (previousDevice == null || !isWiredOutputType(previousDevice.type)) return false
    if (usbExclusivePlaybackEnabled && isUsbOutputType(previousDevice.type)) return false
    if (!_isPlayingFlow.value) return false
    return newDevice == null || newDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
}

private fun PlayerManager.shouldTreatAsUsbExclusiveRouteJitter(
    previousDevice: AudioDevice?,
    newDevice: AudioDevice?
): Boolean {
    if (!usbExclusivePlaybackEnabled) return false
    val previousUsb = previousDevice?.type?.let(::isUsbOutputType) == true
    val newUsb = newDevice?.type?.let(::isUsbOutputType) == true
    if (previousUsb && newUsb) {
        return previousDevice?.type != newDevice?.type || previousDevice?.name != newDevice?.name
    }
    return previousUsb != newUsb
}

internal fun PlayerManager.releaseImpl() {
    if (!initialized) {
        NPLogger.d("NERI-PlayerManager", "release(): ignored because manager is already released")
        return
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "release(): begin, currentSong=${_currentSongFlow.value?.name}, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, isPlaying=${_isPlayingFlow.value}, mediaUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
    try {
        updateResumePlaybackRequested(false)
        lastAutoTrackAdvanceAtMs = 0L
        cancelPlaybackStartupWatchdog(reason = "release")
        clearActivePlaybackCandidates()
        StartupAudioFocusController.release("player_release")

        try {
            val audioManager: AudioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        } catch (e: Exception) {
            NPLogger.w("NERI-PlayerManager", "release(): unregisterAudioDeviceCallback failed", e)
        }
        audioDeviceCallback = null

        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        clearAudioRouteMuteSuppression(reason = "release")
        cancelPendingPauseRequest(resetVolumeToFull = true)
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
        flushPlaybackStatsAsync("release", stopTracking = true)
        playbackSoundPersistJob?.cancel()
        playbackSoundPersistJob = null
        usbAudioSinkReconfigureJob?.cancel()
        usbAudioSinkReconfigureJob = null
        usbExclusiveSystemAudioReleaseJob?.cancel()
        usbExclusiveSystemAudioReleaseJob = null
        usbExclusiveSystemAudioResumeJob?.cancel()
        usbExclusiveSystemAudioResumeJob = null
        usbExclusiveSystemAudioWatchdogJob?.cancel()
        usbExclusiveSystemAudioWatchdogJob = null
        usbExclusiveToggleTransitionJob?.cancel()
        usbExclusiveToggleTransitionJob = null
        usbExclusiveSystemAudioReleaseInProgress = false
        usbExclusiveToggleTransitionActive = false
        usbExclusiveToggleTransitionReason = ""
        markUsbExclusivePlaybackPreparing(false, "player_release")
        usbExclusiveRecoveryJob?.cancel()
        usbExclusiveRecoveryJob = null
        usbExclusiveForegroundRecoveryJob?.cancel()
        usbExclusiveForegroundRecoveryJob = null
        usbExclusiveBackgroundAuditJob?.cancel()
        usbExclusiveBackgroundAuditJob = null
        UsbExclusiveSessionController.forceStopAllSessions("player_release")
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(application, "player_release")
        playJob?.cancel()
        playJob = null
        currentGenericUrlPrefetchJob?.cancel()
        currentGenericUrlPrefetchJob = null
        currentGenericUrlPrefetchKey = null
        genericUrlPrefetchCache.clear()
        lyriconUpdateJob?.cancel()
        lyriconUpdateJob = null
        externalBluetoothLyricsLoadJob?.cancel()
        externalBluetoothLyricsLoadJob = null
        externalBluetoothLyrics = emptyList()
        floatingTranslatedLyrics = emptyList()
        externalBluetoothLyricsSongKey = null
        externalBluetoothLyricsEnabled = false
        floatingLyricsEnabled = false
        floatingLyricsShowTranslation = true
        statusBarLyricsEnable = false
        clearExternalBluetoothLyricLine()
        FloatingLyricsOverlayManager.release()
        LyriconManager.release()

        if (isPlayerInitialized()) {
            runCatching { player.stop() }
            player.release()
        }
        _playbackSoundState.value = playbackEffectsController.release()
        _playWhenReadyFlow.value = false
        _playerPlaybackStateFlow.value = Player.STATE_IDLE
        if (isCacheInitialized()) {
            cache.release()
        }
        conditionalHttpFactory?.close()
        conditionalHttpFactory = null

        mainScope.cancel()
        val releaseIoScope = ioScope
        val pendingStatsJob = synchronized(playbackStatsPersistLock) {
            playbackStatsPersistJob
        }
        if (pendingStatsJob == null) {
            releaseIoScope.cancel()
        } else {
            releaseIoScope.launch {
                pendingStatsJob.join()
                releaseIoScope.cancel()
            }
        }

        _isPlayingFlow.value = false
        _currentMediaUrl.value = null
        _currentPlaybackAudioInfo.value = null
        currentMediaUrlResolvedAtMs = 0L
        setCurrentSongForPlayback(null)
        _currentQueueFlow.value = emptyList()
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L

        currentPlaylist = emptyList()
        currentIndex = -1
        shuffleBag.clear()
        shuffleHistory.clear()
        shuffleFuture.clear()
        consecutivePlayFailures = 0

        NPLogger.d("NERI-PlayerManager", "release(): completed")
    } finally {
        initialized = false
        AudioReactive.onEnabledChanged = null
        lastRequiresPcmAudioProcessing = null
        runCatching { conditionalHttpFactory?.close() }
            .onFailure { NPLogger.w("NERI-PlayerManager", "release(): final conditional factory close failed", it) }
        conditionalHttpFactory = null
        UsbExclusiveSessionController.forceStopAllSessions("player_release_finally")
        UsbExclusiveSystemSoundGuard.forceRelease(application, "player_release_finally")
        StartupAudioFocusController.forceRelease("player_release_finally")
    }
}
