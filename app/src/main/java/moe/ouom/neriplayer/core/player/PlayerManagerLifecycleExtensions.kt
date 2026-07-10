@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player

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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import moe.ouom.neriplayer.core.player.policy.shouldAcceptPlayerCallback
import moe.ouom.neriplayer.core.player.policy.shouldExposePlayerCallbackState
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.core.player.policy.shouldClearResumePlaybackRequestOnPlayWhenReadyPause
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.matchesUsbExclusiveDeviceKey
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.toUsbExclusivePreferences
import moe.ouom.neriplayer.util.NPLogger
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
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
        _playbackSoundState.value = playbackEffectsController.attachPlayer(player)
        applyPlaybackSoundConfig(playbackSoundConfig, persist = false)
        applyAudioFocusPolicy()
        applyUsbExclusivePlaybackPolicy()
        _playWhenReadyFlow.value = player.playWhenReady
        _playerPlaybackStateFlow.value = player.playbackState

        val audioOffload = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            )
            .build()

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(audioOffload)
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                NPLogger.e("NERI-Player", "onPlayerError: ${error.errorCodeName}", error)

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

                val currentUrl = _currentMediaUrl.value
                val isOfflineCache = currentUrl?.startsWith("http://offline.cache/") == true

                val cause = error.cause
                if (shouldAttemptUrlRefresh(error, _currentSongFlow.value, isOfflineCache)) {
                    val shouldBypassRefreshCooldown = pendingSeekPositionOrNull() != null &&
                        YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(
                            _currentSongFlow.value,
                            currentUrl
                        )
                    val resumePositionMs = pendingSeekPositionOrNull()
                        ?: player.currentPosition.coerceAtLeast(0L)
                    val resumePlaybackAfterRefresh = player.playWhenReady || player.isPlaying
                    refreshCurrentSongUrl(
                        resumePositionMs = resumePositionMs,
                        allowFallback = false,
                        reason = "playback_error_${error.errorCodeName}",
                        bypassCooldown = shouldBypassRefreshCooldown,
                        fallbackSeekPositionMs = resumePositionMs,
                        resumePlaybackAfterRefresh = resumePlaybackAfterRefresh
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
                if (state == Player.STATE_READY) {
                    if (shouldAcceptPlayerCallback(
                            playbackRequestToken,
                            loadedMediaRequestToken,
                            isPendingMediaLoadActive()
                        )
                    ) {
                        maybeBackfillCurrentSongDurationFromPlayer()
                    }
                }
                if (state == Player.STATE_ENDED) {
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
                syncPlaybackStatsPlayingState(
                    playing = isPlaying,
                    reason = "exo_is_playing_changed"
                )
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
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
                    StartupAudioFocusController.release("allow_mixed_playback_enabled")
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
    if (currentDevice == null || !isHeadsetLikeOutput(currentDevice.type)) {
        NPLogger.d("NERI-PlayerManager", "handleAudioBecomingNoisy(): ignored because output is not headset-like")
        return false
    }
    if (requiresDisconnectConfirmation(currentDevice.type)) {
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
    if (!changed) return

    UsbExclusiveAudioPathTracker.updateRequested(enabled)
    if (enabled) {
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        usbExclusiveRecoveryAttempts = 0
        pendingUsbExclusivePreferenceReconfigure = false
        applyAudioFocusPolicyOnMainThread()
        val shouldReconfigureNow = !isPlaybackActiveForUsbExclusiveSwitch()
        applyUsbExclusivePlaybackPolicy(
            reconfigureAudioSink = shouldReconfigureNow,
            reconfigureReason = "usb_exclusive_enabled"
        )
        if (!shouldReconfigureNow) {
            deferUsbExclusiveReconfigurationUntilPlaybackStops("usb_exclusive_enabled")
        }
    } else {
        releaseUsbExclusivePlaybackRoute(
            reason = "usb_exclusive_disabled",
            reconfigureAudioSink = true
        )
        applyAudioFocusPolicyOnMainThread()
        applyUsbExclusivePlaybackPolicy(reconfigureAudioSink = false)
    }
    schedulePlaybackSoundConfigApply(
        previousConfig = playbackSoundConfig,
        newConfig = playbackSoundConfig
    )
}

private fun PlayerManager.handleUsbExclusivePreferencesChanged(
    preferences: UsbExclusivePreferences
) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainScope.launch { handleUsbExclusivePreferencesChanged(preferences) }
        return
    }
    val changed = usbExclusivePreferences != preferences
    usbExclusivePreferences = preferences
    if (!changed || !usbExclusivePlaybackEnabled) return

    if (isPlaybackActiveForUsbExclusiveSwitch()) {
        pendingUsbExclusivePreferenceReconfigure = true
        NPLogger.i(
            "NERI-UsbExclusive",
            "USB preferences saved; defer native reconfiguration until playback stops"
        )
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
    if (isPlaybackActiveForUsbExclusiveSwitch() && reason.isUsbExclusiveActivationReason()) {
        deferUsbExclusiveReconfigurationUntilPlaybackStops(reason)
        return
    }
    cancelUsbExclusiveRecovery("manual_retry:$reason")
    usbExclusiveRecoveryAttempts = 0
    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    NPLogger.d("NERI-UsbExclusive", "retryUsbExclusivePlayback(): reason=$reason")
    applyUsbExclusivePlaybackPolicy(
        reconfigureAudioSink = true,
        reconfigureReason = reason
    )
}

internal fun PlayerManager.scheduleUsbExclusiveTransportRecovery(reason: String) {
    if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return
    if (usbExclusiveRecoveryAttempts >= USB_EXCLUSIVE_RECOVERY_MAX_ATTEMPTS) {
        NPLogger.w(
            "NERI-UsbExclusive",
            "scheduleUsbExclusiveTransportRecovery(): max attempts reached, reason=$reason"
        )
        return
    }
    usbExclusiveRecoveryAttempts += 1
    val attempt = usbExclusiveRecoveryAttempts
    val delayMs = USB_EXCLUSIVE_RECOVERY_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(3))
    usbExclusiveRecoveryJob?.cancel()
    usbExclusiveRecoveryJob = mainScope.launch {
        delay(delayMs)
        if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return@launch
        if (player.currentMediaItem == null) return@launch
        val shouldRecover = player.playWhenReady || _isPlayingFlow.value || resumePlaybackRequested
        if (!shouldRecover) return@launch
        NPLogger.i(
            "NERI-UsbExclusive",
            "Recovering native USB path: reason=$reason attempt=$attempt delayMs=$delayMs"
        )
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        applyAudioFocusPolicyOnMainThread()
        applyUsbExclusivePlaybackPolicy(
            reconfigureAudioSink = true,
            reconfigureReason = reason,
            allowReconfigureWhilePlaying = true
        )
    }
}

internal fun PlayerManager.markUsbExclusiveNativePathActive(reason: String) {
    if (usbExclusiveRecoveryAttempts > 0) {
        NPLogger.i("NERI-UsbExclusive", "native USB path recovered: reason=$reason")
    }
    usbExclusiveRecoveryAttempts = 0
    cancelUsbExclusiveRecovery("native_active:$reason")
    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
}

internal fun PlayerManager.releaseUsbExclusivePlaybackRoute(
    reason: String,
    reconfigureAudioSink: Boolean
) {
    cancelUsbExclusiveRecovery("release:$reason")
    usbAudioSinkReconfigureJob?.cancel()
    usbAudioSinkReconfigureJob = null
    usbExclusiveRecoveryAttempts = 0
    pendingUsbExclusivePreferenceReconfigure = false
    UsbExclusiveAudioPathTracker.forceSystemFallback(reason)
    UsbExclusiveAudioPathTracker.updateConfigured(
        usingNative = false,
        fallbackReason = reason,
        inputFormat = "none"
    )
    UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
    UsbExclusiveSessionController.stopGeneratedTone()
    UsbExclusiveSessionController.stopPlayerPcmSession(reason)
    StartupAudioFocusController.forceRelease(reason)
    if (!isPlayerInitialized()) return
    mainScope.launch {
        if (!isPlayerInitialized()) return@launch
        runCatching {
            player.setPreferredAudioDevice(null)
        }.onFailure { error ->
            NPLogger.w("NERI-UsbExclusive", "release route failed to clear preferred device", error)
        }
        applyAudioFocusPolicyOnMainThread()
        if (reconfigureAudioSink) {
            scheduleUsbAudioSinkReconfiguration(
                reason = "release:$reason",
                allowWhilePlaybackActive = true
            )
        }
    }
}

internal fun PlayerManager.recoverUsbExclusivePlaybackOnForeground(reason: String) {
    if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return
    usbExclusiveForegroundRecoveryJob?.cancel()
    usbExclusiveForegroundRecoveryJob = mainScope.launch {
        if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return@launch
        applyAudioFocusPolicyOnMainThread()
        applyUsbExclusivePlaybackPolicy(reconfigureAudioSink = false)
        UsbExclusiveSessionController.refresh(application)
        val pathState = UsbExclusiveAudioPathTracker.state.value
        val nativeState = UsbExclusiveSessionController.state.value
        val playbackActive = isPlaybackActiveForUsbExclusiveSwitch()
        val needsRecovery = playbackActive && (
            pathState.effectivePath != UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB ||
                !nativeState.streaming ||
                pathState.fallbackReason.isRecoverableUsbExclusiveFallback()
            )
        if (needsRecovery) {
            recoverUsbExclusiveAudioSinkNow("foreground_recovery:$reason")
            return@launch
        }
        if (!playbackActive || !nativeState.streaming) return@launch
        val completedFramesBefore = nativeState.completedAudioFrames
        delay(USB_EXCLUSIVE_FOREGROUND_STALL_CHECK_MS)
        if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) return@launch
        if (!isPlaybackActiveForUsbExclusiveSwitch()) return@launch
        UsbExclusiveSessionController.refresh(application)
        val refreshedNativeState = UsbExclusiveSessionController.state.value
        val refreshedPathState = UsbExclusiveAudioPathTracker.state.value
        val stalled = refreshedNativeState.streaming &&
            refreshedNativeState.completedAudioFrames <= completedFramesBefore &&
            (_isPlayingFlow.value || _playWhenReadyFlow.value || resumePlaybackRequested)
        if (stalled || refreshedPathState.fallbackReason.isRecoverableUsbExclusiveFallback()) {
            recoverUsbExclusiveAudioSinkNow("foreground_stalled:$reason")
        }
    }
}

private fun PlayerManager.recoverUsbExclusiveAudioSinkNow(reason: String) {
    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    applyUsbExclusivePlaybackPolicy(
        reconfigureAudioSink = true,
        reconfigureReason = reason,
        allowReconfigureWhilePlaying = true
    )
}

private fun PlayerManager.cancelUsbExclusiveRecovery(reason: String) {
    usbExclusiveRecoveryJob?.cancel()
    usbExclusiveRecoveryJob = null
    usbExclusiveForegroundRecoveryJob?.cancel()
    usbExclusiveForegroundRecoveryJob = null
    NPLogger.d("NERI-UsbExclusive", "cancelUsbExclusiveRecovery(): reason=$reason")
}

private const val USB_EXCLUSIVE_RECOVERY_BASE_DELAY_MS = 600L
private const val USB_EXCLUSIVE_RECOVERY_MAX_ATTEMPTS = 5
private const val USB_EXCLUSIVE_RECONFIGURE_DEBOUNCE_MS = 120L
private const val USB_EXCLUSIVE_RECONFIGURE_COOLDOWN_MS = 2_500L
private const val USB_EXCLUSIVE_SAFE_SWITCH_POLL_MS = 800L
private const val USB_EXCLUSIVE_FOREGROUND_STALL_CHECK_MS = 360L

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
    val audioManager: AudioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (usbExclusivePlaybackEnabled) {
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        UsbExclusiveDiagnostics.ensureUsbPermissionIfNeeded(
            context = application,
            reason = "apply_policy"
        )
    } else {
        UsbExclusiveSessionController.stopPlayerPcmSession("apply_policy_disabled")
    }
    val preferredDevice = if (usbExclusivePlaybackEnabled) {
        findPreferredUsbOutputDevice(audioManager, usbExclusivePreferences.selectedDeviceKey)
    } else {
        null
    }
    UsbExclusiveDebugLogger.logSnapshot(
        context = application,
        audioManager = audioManager,
        reason = "apply_policy_before_set",
        enabled = usbExclusivePlaybackEnabled,
        preferredDevice = preferredDevice
    )
    mainScope.launch {
        if (!isPlayerInitialized()) return@launch
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
    allowWhilePlaybackActive: Boolean = false
) {
    mainScope.launch {
        usbAudioSinkReconfigureJob?.cancel()
        usbAudioSinkReconfigureJob = mainScope.launch reconfigure@{
            val usbActivationReason = usbExclusivePlaybackEnabled &&
                reason.isUsbExclusiveActivationReason()
            if (
                usbActivationReason &&
                !allowWhilePlaybackActive &&
                isPlaybackActiveForUsbExclusiveSwitch()
            ) {
                pendingUsbExclusivePreferenceReconfigure = true
                NPLogger.i(
                    "NERI-UsbExclusive",
                    "defer USB reconfiguration while playback is active: reason=$reason"
                )
                return@reconfigure
            }
            val now = SystemClock.elapsedRealtime()
            val elapsedMs = now - lastUsbExclusiveAudioSinkReconfigureAtMs
            val cooldownMs = if (usbExclusivePlaybackEnabled && reason.isUsbExclusiveReason()) {
                USB_EXCLUSIVE_RECONFIGURE_COOLDOWN_MS
            } else {
                USB_EXCLUSIVE_RECONFIGURE_DEBOUNCE_MS
            }
            delay((cooldownMs - elapsedMs).coerceAtLeast(USB_EXCLUSIVE_RECONFIGURE_DEBOUNCE_MS))
            if (!isPlayerInitialized() || player.currentMediaItem == null) return@reconfigure
            if (
                usbActivationReason &&
                !allowWhilePlaybackActive &&
                isPlaybackActiveForUsbExclusiveSwitch()
            ) {
                pendingUsbExclusivePreferenceReconfigure = true
                return@reconfigure
            }
            val mediaItemCount = player.mediaItemCount
            if (mediaItemCount <= 0) return@reconfigure
            val mediaItemIndex = player.currentMediaItemIndex.coerceIn(0, mediaItemCount - 1)
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val resumePlayback = player.playWhenReady
            NPLogger.d(
                "NERI-UsbExclusive",
                "reconfigureAudioSink(): reason=$reason index=$mediaItemIndex positionMs=$positionMs playing=$resumePlayback"
            )
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.seekTo(mediaItemIndex, positionMs)
                player.prepare()
                player.playWhenReady = resumePlayback
            }.onSuccess {
                lastUsbExclusiveAudioSinkReconfigureAtMs = SystemClock.elapsedRealtime()
                pendingUsbExclusivePreferenceReconfigure = false
            }.onFailure { error ->
                runCatching { player.playWhenReady = resumePlayback }
                NPLogger.e("NERI-UsbExclusive", "reconfigureAudioSink() failed: reason=$reason", error)
            }
        }
    }
}

private fun PlayerManager.deferUsbExclusiveReconfigurationUntilPlaybackStops(reason: String) {
    pendingUsbExclusivePreferenceReconfigure = true
    usbAudioSinkReconfigureJob?.cancel()
    usbAudioSinkReconfigureJob = mainScope.launch reconfigure@{
        NPLogger.i(
            "NERI-UsbExclusive",
            "defer native USB switch until playback stops: reason=$reason"
        )
        while (
            usbExclusivePlaybackEnabled &&
            isPlayerInitialized() &&
            player.currentMediaItem != null &&
            isPlaybackActiveForUsbExclusiveSwitch()
        ) {
            delay(USB_EXCLUSIVE_SAFE_SWITCH_POLL_MS)
        }
        if (!usbExclusivePlaybackEnabled || !isPlayerInitialized()) {
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

private fun String?.isRecoverableUsbExclusiveFallback(): Boolean {
    val reason = this ?: return false
    if (reason.startsWith("sample_rate_unsupported")) return false
    if (reason.startsWith("bit_depth_unsupported")) return false
    return true
}

private fun findPreferredUsbOutputDevice(
    audioManager: AudioManager,
    selectedDeviceKey: String
): AudioDeviceInfo? {
    val usbOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.isSink && isUsbOutputType(it.type) }
        .sortedBy(AudioDeviceInfo::getId)
    return usbOutputs
        .firstOrNull { it.matchesUsbExclusiveDeviceKey(selectedDeviceKey) }
        ?: usbOutputs.singleOrNull()
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
    if (!_isPlayingFlow.value) return false
    return newDevice == null || newDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
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
    updateResumePlaybackRequested(false)
    lastAutoTrackAdvanceAtMs = 0L
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
    flushPlaybackStatsBlocking("release", stopTracking = true)
    drainPlaybackStatsPersistJobBlocking("release")
    playbackSoundPersistJob?.cancel()
    playbackSoundPersistJob = null
    playJob?.cancel()
    playJob = null
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
    ioScope.cancel()

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

    initialized = false
    NPLogger.d("NERI-PlayerManager", "release(): completed")
}
