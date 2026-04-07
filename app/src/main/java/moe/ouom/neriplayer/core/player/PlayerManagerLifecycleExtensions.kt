@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player

import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.media3.common.AudioAttributes
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
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.di.AppContainer.biliCookieRepo
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.core.player.audio.isBluetoothOutputType
import moe.ouom.neriplayer.core.player.audio.isHeadsetLikeOutput
import moe.ouom.neriplayer.core.player.audio.isWiredOutputType
import moe.ouom.neriplayer.core.player.audio.requiresDisconnectConfirmation
import moe.ouom.neriplayer.core.player.debug.playWhenReadyChangeReasonName
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.util.NPLogger
import java.io.File

internal fun PlayerManager.initializeImpl(
    app: Application,
    maxCacheSize: Long = 1024L * 1024 * 1024
) {
    if (initialized) {
        NPLogger.d("NERI-PlayerManager", "initialize(): ignored because already initialized")
        return
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "initialize(): maxCacheSize=$maxCacheSize, app=${app.packageName}, stack=[${debugStackHint()}]"
    )
    application = app
    currentCacheSize = maxCacheSize

    ioScope = newIoScope()
    mainScope = newMainScope()

    runCatching {
        stateFile = File(app.filesDir, "last_playlist.json")
        playbackStateFile = File(app.filesDir, "last_playback_state.json")
        lastPersistedPlaylistReference = null
        lastPersistedPlaybackState = null
        lastStatePersistAtMs = 0L
        val initialPlaybackPreferences = readPlaybackPreferenceSnapshotSync(app)
        preferredQuality = initialPlaybackPreferences.audioQuality
        youtubePreferredQuality = initialPlaybackPreferences.youtubeAudioQuality
        biliPreferredQuality = initialPlaybackPreferences.biliAudioQuality
        keepLastPlaybackProgressEnabled =
            initialPlaybackPreferences.keepLastPlaybackProgress
        keepPlaybackModeStateEnabled =
            initialPlaybackPreferences.keepPlaybackModeState
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
        allowMixedPlaybackEnabled =
            initialPlaybackPreferences.allowMixedPlayback
        playbackSoundConfig = initialPlaybackPreferences.toPlaybackSoundConfig()
        NPLogger.d(
            "NERI-PlayerManager",
            "initialize(): prefs quality=$preferredQuality, youtubeQuality=$youtubePreferredQuality, biliQuality=$biliPreferredQuality, keepProgress=$keepLastPlaybackProgressEnabled, keepMode=$keepPlaybackModeStateEnabled, fadeIn=$playbackFadeInEnabled/${playbackFadeInDurationMs}ms, crossfade=$playbackCrossfadeNextEnabled/${playbackCrossfadeInDurationMs}ms, stopOnBluetoothDisconnect=$stopOnBluetoothDisconnectEnabled, allowMixedPlayback=$allowMixedPlaybackEnabled"
        )
        val okHttpClient = AppContainer.sharedOkHttpClient
        val upstreamFactory: HttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
        val conditionalFactory = ConditionalHttpDataSourceFactory(
            upstreamFactory,
            biliCookieRepo,
            AppContainer.youtubeAuthRepo
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
                    mainScope.launch { handleTrackEndedIfNeeded(source = "playback_error") }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                _playerPlaybackStateFlow.value = state
                if (state == Player.STATE_READY) {
                    maybeBackfillCurrentSongDurationFromPlayer()
                }
                if (state == Player.STATE_ENDED) {
                    handleTrackEndedIfNeeded(source = "playback_state_changed")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlayingFlow.value = isPlaying
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                val positionMs = player.currentPosition.coerceAtLeast(0L)
                val shouldResumePlayback = shouldResumePlaybackSnapshot()
                scheduleStatePersist(
                    positionMs = positionMs,
                    shouldResumePlayback = shouldResumePlayback
                )
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                _playWhenReadyFlow.value = playWhenReady
                if (!playWhenReady) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "playWhenReady=false, reason=${playWhenReadyChangeReasonName(reason)}, state=${playbackStateName(player.playbackState)}, mediaId=${player.currentMediaItem?.mediaId}, stack=[${debugStackHint()}]"
                    )
                }
                if (
                    !playWhenReady &&
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                    player.playbackState == Player.STATE_ENDED
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
            settingsRepo.stopOnBluetoothDisconnectFlow.collect { enabled ->
                stopOnBluetoothDisconnectEnabled = enabled
            }
        }
        ioScope.launch {
            settingsRepo.allowMixedPlaybackFlow.collect { enabled ->
                allowMixedPlaybackEnabled = enabled
                applyAudioFocusPolicy()
            }
        }

        ioScope.launch {
            localRepo.playlists.collect { repoLists ->
                _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(repoLists)
            }
        }

        setupAudioDeviceCallback()
        restoreState()

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
        initialized = false
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
    if (!initialized && isApplicationInitialized()) {
        NPLogger.d("NERI-PlayerManager", "ensureInitialized(): lazy initialize with existing application")
        initialize(application)
    }
}

private fun PlayerManager.setupAudioDeviceCallback() {
    val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    _currentAudioDevice.value = getCurrentAudioDevice(audioManager)
    NPLogger.d(
        "NERI-PlayerManager",
        "setupAudioDeviceCallback(): initialDevice=${_currentAudioDevice.value?.type}:${_currentAudioDevice.value?.name}"
    )
    val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            NPLogger.d(
                "NERI-PlayerManager",
                "audioDevicesAdded(): count=${addedDevices?.size ?: 0}, devices=${addedDevices?.joinToString { "${it.type}:${it.productName}" }}"
            )
            handleDeviceChange(audioManager)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            NPLogger.d(
                "NERI-PlayerManager",
                "audioDevicesRemoved(): count=${removedDevices?.size ?: 0}, devices=${removedDevices?.joinToString { "${it.type}:${it.productName}" }}"
            )
            handleDeviceChange(audioManager)
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
    NPLogger.d("NERI-PlayerManager", "Audio becoming noisy, pausing playback immediately.")
    pause()
    return true
}

private fun PlayerManager.handleDeviceChange(audioManager: AudioManager) {
    val previousDevice = _currentAudioDevice.value
    val newDevice = getCurrentAudioDevice(audioManager)
    _currentAudioDevice.value = newDevice
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
        pause()
    } else if (newDevice.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
    }
}

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
            bluetoothDisconnectPauseJob = null
            return@launch
        }

        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val confirmedDevice = getCurrentAudioDevice(audioManager)
        _currentAudioDevice.value = confirmedDevice
        if (confirmedDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            NPLogger.d(
                "NERI-PlayerManager",
                "Confirmed bluetooth disconnect ($reason), pausing playback."
            )
            pause()
        } else {
            NPLogger.d(
                "NERI-PlayerManager",
                "Ignored transient bluetooth route change ($reason): ${confirmedDevice.type}"
            )
        }
        bluetoothDisconnectPauseJob = null
    }
}

private fun PlayerManager.getCurrentAudioDevice(audioManager: AudioManager): AudioDevice {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
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

    try {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
    } catch (e: Exception) {
        NPLogger.w("NERI-PlayerManager", "release(): unregisterAudioDeviceCallback failed", e)
    }
    audioDeviceCallback = null

    stopProgressUpdates()
    cancelVolumeFade(resetToFull = true)
    cancelPendingPauseRequest(resetVolumeToFull = true)
    bluetoothDisconnectPauseJob?.cancel()
    bluetoothDisconnectPauseJob = null
    playbackSoundPersistJob?.cancel()
    playbackSoundPersistJob = null
    playJob?.cancel()
    playJob = null

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
    _currentSongFlow.value = null
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
