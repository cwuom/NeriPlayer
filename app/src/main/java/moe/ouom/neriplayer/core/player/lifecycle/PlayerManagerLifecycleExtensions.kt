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
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache.CacheException
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.di.AppContainer.biliCookieRepo
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.currentPositionMsOr
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.audio.isBluetoothOutputType
import moe.ouom.neriplayer.core.player.audio.isHeadsetLikeOutput
import moe.ouom.neriplayer.core.player.audio.isUsbOutputType
import moe.ouom.neriplayer.core.player.audio.isWiredOutputType
import moe.ouom.neriplayer.core.player.audio.requiresDisconnectConfirmation
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDebugLogger
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.core.player.debug.playWhenReadyChangeReasonName
import moe.ouom.neriplayer.core.player.debug.playbackSuppressionReasonName
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.effects.AudioReactive
import moe.ouom.neriplayer.core.player.engine.PlaybackVolumeNormalizationState
import moe.ouom.neriplayer.core.player.engine.ReactiveRenderersFactory
import moe.ouom.neriplayer.core.player.engine.datasource.ConditionalHttpDataSourceFactory
import moe.ouom.neriplayer.core.player.lyrics.FloatingLyricsOverlayManager
import moe.ouom.neriplayer.core.player.lyrics.clearExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.lyrics.syncExternalBluetoothLyrics
import moe.ouom.neriplayer.core.player.lyrics.syncExternalTranslatedLyrics
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.applyRestoredStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.restoreState
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.AppPlaybackStatsWritePort
import moe.ouom.neriplayer.core.player.playback.PlaybackStatsOwner
import moe.ouom.neriplayer.core.player.playback.advanceAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.playback.clearAudioRouteMuteSuppression
import moe.ouom.neriplayer.core.player.playback.pauseForAudioRouteLoss
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.core.player.playback.playImpl
import moe.ouom.neriplayer.core.player.playback.restorePlaybackAfterTransientAudioRouteLoss
import moe.ouom.neriplayer.core.player.playback.startProgressUpdates
import moe.ouom.neriplayer.core.player.playback.suppressPlaybackForAudioRouteLoss
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.core.player.policy.audio.BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT
import moe.ouom.neriplayer.core.player.policy.audio.BLUETOOTH_DISCONNECT_CONFIRM_INITIAL_DELAY_MS
import moe.ouom.neriplayer.core.player.policy.audio.BLUETOOTH_DISCONNECT_CONFIRM_SAMPLE_INTERVAL_MS
import moe.ouom.neriplayer.core.player.policy.audio.shouldConfirmBluetoothDisconnect
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.command.shouldClearResumePlaybackRequestOnPlayWhenReadyPause
import moe.ouom.neriplayer.core.player.policy.command.shouldResumeSilentlyForListenTogetherNoisyPause
import moe.ouom.neriplayer.core.player.policy.offload.pcmAudioRequirements
import moe.ouom.neriplayer.core.player.policy.offload.shouldUpdateAudioOffloadForReactiveChange
import moe.ouom.neriplayer.core.player.policy.pending.shouldAcceptPlayerCallback
import moe.ouom.neriplayer.core.player.policy.pending.shouldExposePlayerCallbackState
import moe.ouom.neriplayer.core.player.policy.usb.isTransientUsbExclusiveOpenGate
import moe.ouom.neriplayer.core.player.policy.usb.shouldDeferUsbExclusiveNoisyRouteToNativePath
import moe.ouom.neriplayer.core.player.policy.usb.shouldDeferUsbExclusiveRecoveryForPendingReconfiguration
import moe.ouom.neriplayer.core.player.policy.usb.shouldSkipRedundantUsbExclusiveReconfiguration
import moe.ouom.neriplayer.core.player.policy.usb.shouldSkipUsbExclusiveRouteRebuildForManualPlayback
import moe.ouom.neriplayer.core.player.policy.usb.shouldStopUsbExclusivePlaybackForNoisyRoute
import moe.ouom.neriplayer.core.player.policy.wake.PlaybackTransitionWakeLock
import moe.ouom.neriplayer.core.player.prefetch.prefetchNextGenericTrackUrl
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeSeekRefreshPolicy
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.listentogether.playback.shouldMuteListenTogetherListenerForOutputDisconnect
import moe.ouom.neriplayer.core.player.url.currentPlaybackCacheKeyForRecovery
import moe.ouom.neriplayer.core.player.url.invalidateCachedResourceForPlaybackRecovery
import moe.ouom.neriplayer.core.player.url.shouldAdvanceAfterStuckTrackEnd
import moe.ouom.neriplayer.core.player.url.shouldAttemptUrlRefresh
import moe.ouom.neriplayer.core.player.url.shouldInvalidateCacheAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.url.shouldInvalidateCacheForPlaybackRecovery
import moe.ouom.neriplayer.core.player.url.shouldRecoverMissingLocalPlayback
import moe.ouom.neriplayer.core.player.url.shouldTreatPlaybackFailureAsTrackEnd
import moe.ouom.neriplayer.core.player.url.youtubePlaybackRecoveryStrategyForError
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.recovery.PlayerManagerUsbExclusiveLivenessPort
import moe.ouom.neriplayer.core.player.usb.recovery.PlayerManagerUsbInterruptedPlaybackPort
import moe.ouom.neriplayer.core.player.usb.recovery.UsbExclusiveLivenessOwner
import moe.ouom.neriplayer.core.player.usb.recovery.UsbInterruptedPlaybackOwner
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveErrorCode
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState
import moe.ouom.neriplayer.core.player.usb.transport.isRecoverableTransportFailure
import moe.ouom.neriplayer.core.player.usb.transport.usbExclusiveErrorCode
import moe.ouom.neriplayer.core.player.watchdog.cancelPlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.clearActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackRuntimeWatchdog
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackRuntimeWatchdog
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.trySwitchToNextPlaybackCandidateForRecovery
import moe.ouom.neriplayer.data.settings.LyricSourcePreferencePolicy
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.CacheSizePolicy
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.toUsbExclusivePreferences
import java.io.File

private const val MEDIA_CACHE_DIRECTORY_NAME = "media_cache"

private fun PlayerManager.logPlaybackStateTransition(event: String) {
    val playerSnapshot = if (isPlayerInitialized()) {
        val positionMs = player.currentPositionMsOr(-1L)
        val bufferedMs = runCatching { player.totalBufferedDuration }
            .getOrDefault(-1L)
        "state=${playbackStateName(player.playbackState)}, " +
            "isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}, " +
            "suppression=${playbackSuppressionReasonName(player.playbackSuppressionReason)}, " +
            "positionMs=$positionMs, bufferedMs=$bufferedMs"
    } else {
        "state=UNINITIALIZED"
    }
    NPLogger.d(
        "NERI-PlaybackState",
        "event=$event, requestToken=$playbackRequestToken, " +
            "loadedToken=$loadedMediaRequestToken, " +
            "resumeRequested=$resumePlaybackRequested, " +
            "pending=${isPendingMediaLoadActive()}, " +
            "routeMute=${audioRouteMuteSuppressedFlow.value}, $playerSnapshot"
    )
}

private fun Throwable.playbackDiagnosticSummary(): String {
    return message
        ?.replace(Regex("https?://\\S+"), "<redacted-url>")
        ?.replace('\n', ' ')
        ?.take(240)
        .orEmpty()
}

private fun Throwable.isSimpleCacheFolderLocked(): Boolean {
    return generateSequence(this) { it.cause }
        .any { cause ->
            cause.message?.contains(
                "Another SimpleCache instance uses the folder",
                ignoreCase = true
            ) == true
        }
}

private fun Throwable.hasCacheInitializationFailure(): Boolean {
    return generateSequence(this) { it.cause }
        .any { it is CacheException }
}

internal fun shouldRebuildMediaCacheAfterInitializationFailure(error: Throwable): Boolean {
    return !error.isSimpleCacheFolderLocked() && error.hasCacheInitializationFailure()
}

internal fun isMediaCacheDirectorySafeToOpen(cacheDir: File): Boolean {
    return !cacheDir.exists() ||
        (cacheDir.isDirectory && cacheDir.listFiles()?.isEmpty() == true)
}

private fun createVerifiedMediaCache(
    app: Application,
    maxCacheSize: Long,
    databaseProvider: StandaloneDatabaseProvider
): SimpleCache? {
    val cacheDir = File(app.cacheDir, MEDIA_CACHE_DIRECTORY_NAME)

    fun openCache(): SimpleCache {
        val cacheEvictor = if (
            maxCacheSize == CacheSizePolicy.UNLIMITED_CACHE_SIZE_BYTES
        ) {
            NoOpCacheEvictor()
        } else {
            LeastRecentlyUsedCacheEvictor(maxCacheSize)
        }
        val createdCache = SimpleCache(
            cacheDir,
            cacheEvictor,
            databaseProvider
        )
        return try {
            createdCache.checkInitialization()
            createdCache
        } catch (error: Exception) {
            runCatching { createdCache.release() }
            throw error
        }
    }

    val firstAttempt = runCatching { openCache() }
    firstAttempt.getOrNull()?.let { return it }

    val firstError = firstAttempt.exceptionOrNull() ?: return null
    if (
        firstError.isSimpleCacheFolderLocked() ||
            SimpleCache.isCacheFolderLocked(cacheDir)
    ) {
        NPLogger.w(
            "NERI-PlayerManager",
            "media cache is already locked; continue without cache for this process",
            firstError
        )
        return null
    }

    if (!shouldRebuildMediaCacheAfterInitializationFailure(firstError)) {
        NPLogger.w(
            "NERI-PlayerManager",
            "media cache initialization failed outside the cache store; playback will bypass cache",
            firstError
        )
        return null
    }

    NPLogger.w(
        "NERI-PlayerManager",
        "media cache initialization failed; rebuilding the cache directory",
        firstError
    )
    val removedBrokenCache = runCatching {
        SimpleCache.delete(cacheDir, databaseProvider)
        isMediaCacheDirectorySafeToOpen(cacheDir)
    }.onFailure { deleteError ->
        NPLogger.w(
            "NERI-PlayerManager",
            "failed to remove broken media cache; playback will bypass cache",
            deleteError
        )
    }.getOrDefault(false)
    if (!removedBrokenCache) {
        NPLogger.w(
            "NERI-PlayerManager",
            "broken media cache could not be removed completely; playback will bypass cache"
        )
        return null
    }

    return runCatching { openCache() }
        .onFailure { error ->
            NPLogger.w(
                "NERI-PlayerManager",
                "rebuilt media cache is unavailable; playback will bypass cache",
                error
            )
        }
        .getOrNull()
}

internal fun PlayerManager.releaseMediaCache() {
    val mediaCache = cache
    cache = null
    mediaCache?.release()
}

internal fun PlayerManager.initializeImpl(
    app: Application,
    maxCacheSize: Long = 1024L * 1024 * 1024,
    startupPlaybackPreferences: PlaybackPreferenceSnapshot? = null,
    restoredStateSnapshot: RestoredPlayerStateSnapshot? = null
) {
    if (!beginInitialization()) return
    val effectiveMaxCacheSize = CacheSizePolicy.normalizeCacheSizeBytes(maxCacheSize)
    try {
        try {
            prepareInitializationSession(app, effectiveMaxCacheSize)
            applyInitialPlaybackPreferences(app, startupPlaybackPreferences)
            initializePlaybackEngine(app, effectiveMaxCacheSize)
            observePlaybackSettings()
            completeInitialization(restoredStateSnapshot, effectiveMaxCacheSize)
        } catch (error: Throwable) {
            rollbackInitialization(error, effectiveMaxCacheSize)
        }
    } finally {
        finishInitializationAttempt()
    }
}

private fun PlayerManager.beginInitialization(): Boolean = synchronized(initializationLock) {
    val ignoreReason = initializationIgnoreReason(initialized, initializationInProgress)
    if (ignoreReason != null) {
        NPLogger.d("NERI-PlayerManager", ignoreReason)
        false
    } else {
        initializationInProgress = true
        true
    }
}

internal fun initializationIgnoreReason(initialized: Boolean, inProgress: Boolean): String? = when {
    initialized -> "initialize(): ignored because already initialized"
    inProgress -> "initialize(): ignored because initialization is already running"
    else -> null
}

private fun PlayerManager.prepareInitializationSession(app: Application, effectiveMaxCacheSize: Long) {
    NPLogger.d(
        "NERI-PlayerManager",
        "initialize(): maxCacheSize=$effectiveMaxCacheSize, app=${app.packageName}, stack=[${debugStackHint()}]"
    )
    application = app
    _localPlaylistsReadyFlow.value = false
    FloatingLyricsOverlayManager.initialize(app)
    currentCacheSize = effectiveMaxCacheSize

    statePersistenceCoordinator.reopen()
    urlRefreshController.cancelCurrent()
    ioScope = newIoScope()
    mainScope = newMainScope()

    stateFile = File(app.filesDir, "last_playlist.json")
    playbackStateFile = File(app.filesDir, "last_playback_state.json")
    statePersistenceWriter.invalidate()
    shuffleRestorePlaylistReference = null
    shuffleRestoreCurrentIndex = -1
    lastStatePersistAtMs = 0L
    lastLongFormPlaybackProgressPersistAtMs = 0L
    playbackStatsOwner = PlaybackStatsOwner(ioScope, AppPlaybackStatsWritePort)
    val appWasInForeground = usbExclusiveLivenessOwner.appInForeground
    usbExclusiveLivenessOwner.cancelJobs()
    usbExclusiveLivenessOwner = UsbExclusiveLivenessOwner(
        mainScope,
        PlayerManagerUsbExclusiveLivenessPort,
        initialForeground = appWasInForeground
    )
    val interruptedIntent = usbInterruptedPlaybackOwner.intent
    usbInterruptedPlaybackOwner.cancelReattach()
    usbInterruptedPlaybackOwner = UsbInterruptedPlaybackOwner(
        mainScope,
        PlayerManagerUsbInterruptedPlaybackPort,
        initialIntent = interruptedIntent
    )
}

private fun PlayerManager.applyInitialPlaybackPreferences(
    app: Application,
    startupPlaybackPreferences: PlaybackPreferenceSnapshot?
) {
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
    rememberLongFormPlaybackProgressEnabled =
        initialPlaybackPreferences.rememberLongFormPlaybackProgress
    keepPlaybackModeStateEnabled =
        initialPlaybackPreferences.keepPlaybackModeState
    neteaseAutoSourceSwitchEnabled =
        initialPlaybackPreferences.neteaseAutoSourceSwitch
    neteaseLocalSourceFallbackEnabled =
        initialPlaybackPreferences.neteaseLocalSourceFallback
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
    kugouLyricDefaultOffsetMs =
        initialPlaybackPreferences.kugouLyricDefaultOffsetMs
    lrclibLyricDefaultOffsetMs =
        initialPlaybackPreferences.lrclibLyricDefaultOffsetMs
    amllTtmlLyricDefaultOffsetMs =
        initialPlaybackPreferences.amllTtmlLyricDefaultOffsetMs
    externalBluetoothLyricsEnabled = false
    externalBluetoothTranslationEnabled = false
    dynamicIslandLyricsEnabled = false
    amllLyricsEnabled = initialPlaybackPreferences.amllLyricsEnabled
    preferWordTimedLyrics = initialPlaybackPreferences.preferWordTimedLyrics
    defaultLyricSource = LyricSourcePreferencePolicy.fromStorage(
        initialPlaybackPreferences.defaultLyricSource
    )
    lyriconEnabled = initialPlaybackPreferences.lyriconEnabled
    LyriconManager.setEnabled(lyriconEnabled)
    initializeLyriconIfEnabled(app)
    playbackSoundConfig = initialPlaybackPreferences.toPlaybackSoundConfig()
    playbackHighResolutionOutputEnabled =
        initialPlaybackPreferences.playbackHighResolutionOutputEnabled
    NPLogger.d(
        "NERI-PlayerManager",
        "initialize(): prefs quality=$preferredQuality, youtubeQuality=$youtubePreferredQuality, biliQuality=$biliPreferredQuality, mobileDataFollowDefault=$mobileDataFollowDefaultAudioQuality, mobileDataQuality=$mobileDataNeteaseAudioQuality/$mobileDataYouTubeAudioQuality/$mobileDataBiliAudioQuality, keepProgress=$keepLastPlaybackProgressEnabled, rememberLongFormProgress=$rememberLongFormPlaybackProgressEnabled, keepMode=$keepPlaybackModeStateEnabled, neteaseAutoSourceSwitch=$neteaseAutoSourceSwitchEnabled, neteaseLocalSourceFallback=$neteaseLocalSourceFallbackEnabled, fadeIn=$playbackFadeInEnabled/${playbackFadeInDurationMs}ms, crossfade=$playbackCrossfadeNextEnabled/${playbackCrossfadeInDurationMs}ms, highResolutionOutput=$playbackHighResolutionOutputEnabled, stopOnBluetoothDisconnect=$stopOnBluetoothDisconnectEnabled, usbExclusivePlayback=$usbExclusivePlaybackEnabled, allowMixedPlayback=$allowMixedPlaybackEnabled"
    )
}

private fun PlayerManager.initializeLyriconIfEnabled(app: Application) {
    if (lyriconEnabled) initializeLyriconWhenNeeded(app)
}

private fun initializeLyriconWhenNeeded(app: Application) {
    if (!LyriconManager.isInitialized()) LyriconManager.initialize(app)
}

private fun PlayerManager.initializePlaybackEngine(app: Application, effectiveMaxCacheSize: Long) {
    val okHttpClient = AppContainer.sharedOkHttpClient
    val upstreamFactory: HttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
    val conditionalFactory = ConditionalHttpDataSourceFactory(
        upstreamFactory,
        biliCookieRepo,
        AppContainer.youtubeAuthRepo,
        trafficStatsRepository = AppContainer.trafficStatsRepo
    )
    conditionalHttpFactory = conditionalFactory

    val finalDataSourceFactory = createPlaybackDataSourceFactory(
        app,
        effectiveMaxCacheSize,
        conditionalFactory
    )

    val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
    val mediaSourceFactory = DefaultMediaSourceFactory(
        finalDataSourceFactory,
        extractorsFactory
    )

    // USB 独占优先保留解码器的原生整数 PCM, 别在进入 native USB 前强行改成 float
    val enableFloatOutput = shouldEnableFloatPlaybackOutput(
        playbackHighResolutionOutputEnabled,
        usbExclusivePlaybackEnabled
    )
    val renderersFactory = ReactiveRenderersFactory(app)
        .setEnableAudioFloatOutput(enableFloatOutput)
        // 硬解初始化失败时允许 Media3 尝试低优先级的兼容解码器
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

    player = ExoPlayer.Builder(app, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(buildAudioLoadControl())
        .build()
    player.addAnalyticsListener(object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            NPLogger.i(
                "NERI-PlaybackDecoder",
                "audio input format: sampleMimeType=${format.sampleMimeType}, " +
                    "containerMimeType=${format.containerMimeType}, codecs=${format.codecs}, " +
                    "sampleRate=${format.sampleRate}, channels=${format.channelCount}, " +
                    "pcmEncoding=${format.pcmEncoding}"
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            NPLogger.i(
                "NERI-PlaybackDecoder",
                "audio decoder initialized: name=$decoderName, " +
                    "durationMs=$initializationDurationMs"
            )
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long
        ) {
            NPLogger.w(
                "NERI-PlaybackDecoder",
                "audio underrun: bufferSize=$bufferSize, bufferMs=$bufferSizeMs, " +
                    "elapsedSinceFeedMs=$elapsedSinceLastFeedMs"
            )
        }

        override fun onAudioSinkError(
            eventTime: AnalyticsListener.EventTime,
            audioSinkError: Exception
        ) {
            NPLogger.e(
                "NERI-PlaybackDecoder",
                "audio sink error: type=${audioSinkError::class.java.simpleName}, " +
                    "message=${audioSinkError.playbackDiagnosticSummary()}"
            )
        }

        override fun onAudioCodecError(
            eventTime: AnalyticsListener.EventTime,
            audioCodecError: Exception
        ) {
            NPLogger.e(
                "NERI-PlaybackDecoder",
                "audio codec error: type=${audioCodecError::class.java.simpleName}, " +
                    "message=${audioCodecError.playbackDiagnosticSummary()}"
            )
        }

        override fun onAudioDecoderReleased(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String
        ) {
            NPLogger.i(
                "NERI-PlaybackDecoder",
                "audio decoder released: name=$decoderName"
            )
        }
    })
    applyInitialPlaybackWakeMode()
    _playbackSoundState.value = playbackEffectsController.attachPlayer(player)
    applyPlaybackSoundConfig(playbackSoundConfig, persist = false)
    applyAudioFocusPolicy()
    applyUsbExclusivePlaybackPolicy()
    _playWhenReadyFlow.value = player.playWhenReady
    _playerPlaybackStateFlow.value = player.playbackState

    AudioReactive.onEnabledChanged = { enabled ->
        mainScope.launch {
            val playbackActive = isTransportActiveWithoutInitialization()
            if (
                shouldUpdateAudioOffloadForReactiveChange(
                    audioReactiveEnabled = enabled,
                    playbackActive = playbackActive
                )
            ) {
                updateAudioOffloadPreferences("audio_reactive_$enabled")
            } else {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "keep audio offload pipeline during active playback: " +
                        "audioReactive=$enabled"
                )
            }
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

            cancelPlaybackStartupWatchdog(reason = "player_error")
            resetPlaybackRuntimeWatchdog(reason = "player_error")

            if (shouldAdvanceAfterStuckTrackEnd(error, resumePlaybackRequested)) {
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Media3 reported a track that did not end; advance the queue without invalidating cache"
                )
                handleTrackEndedIfNeeded(source = "media3_stuck_playing_not_ending")
                return
            }
            if (shouldTreatPlaybackFailureAsTrackEnd(error)) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Ignore track-end timeout because playback is no longer requested"
                )
                return
            }

            val currentSong = _currentSongFlow.value
            val currentUrl = _currentMediaUrl.value
            val isOfflineCache = currentUrl?.startsWith("http://offline.cache/") == true
            val isLocalFileMissingRecovery = shouldRecoverMissingLocalPlayback(
                error = error,
                isLocalSong = currentSong?.let { song -> isLocalSong(song) } == true,
                currentUrl = currentUrl
            )
            if (isLocalFileMissingRecovery) {
                // 迁移完成后旧 file URI 可能在 Media3 打开前才失效，先丢弃桥接
                // 让下一次解析从当前 SAF 快照按文件名重绑定
                currentSong?.let(AudioDownloadManager::invalidateCompletedAudioReference)
            }
            val shouldInvalidateCache =
                shouldInvalidateCacheForPlaybackRecovery(error, isOfflineCache)

            val cause = error.cause
            val shouldResumeAfterRecovery = resumePlaybackRequested
            if (
                !isLocalFileMissingRecovery &&
                shouldResumeAfterRecovery &&
                trySwitchToNextPlaybackCandidateForRecovery(
                    reason = "player_error_${error.errorCodeName}",
                    invalidateCurrentCache = shouldInvalidateCache,
                    expectedRequestToken = playbackRequestToken
                )
            ) {
                return
            }

            if (shouldAttemptUrlRefresh(error, currentSong, isOfflineCache)) {
                val youtubeRecoveryStrategy = youtubePlaybackRecoveryStrategyForError(
                    error = error,
                    song = currentSong,
                    isOfflineCache = isOfflineCache
                )
                val cacheKeyToInvalidateBeforeResolve = if (shouldInvalidateCache) {
                    currentPlaybackCacheKeyForRecovery()
                } else {
                    null
                }
                val shouldBypassRefreshCooldown = (
                    pendingSeekPositionOrNull() != null &&
                        YouTubeSeekRefreshPolicy.shouldRefreshUrlBeforeSeek(
                            currentSong,
                            currentUrl
                        )
                    ) || cacheKeyToInvalidateBeforeResolve != null ||
                    isLocalFileMissingRecovery
                val resumePositionMs = pendingSeekPositionOrNull()
                    ?: maxOf(
                        player.currentPosition.coerceAtLeast(0L),
                        _playbackPositionMs.value.coerceAtLeast(0L)
                    )
                refreshCurrentSongUrl(
                    resumePositionMs = resumePositionMs,
                    allowFallback = false,
                    reason = "playback_error_${error.errorCodeName}",
                    bypassCooldown = shouldBypassRefreshCooldown,
                    fallbackSeekPositionMs = resumePositionMs,
                    resumePlaybackAfterRefresh = shouldResumeAfterRecovery,
                    resumedPlaybackCommandSource = activePlaybackCommandSource,
                    youtubeRecoveryStrategy = youtubeRecoveryStrategy,
                    cacheKeyToInvalidateBeforeResolve = cacheKeyToInvalidateBeforeResolve,
                    allowLocalSongRecovery = isLocalFileMissingRecovery
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
                    if (shouldInvalidateCacheAfterPlaybackFailure(
                            shouldInvalidateCache = shouldInvalidateCache,
                            isOfflineCache = isOfflineCache
                        )
                    ) {
                        currentPlaybackCacheKeyForRecovery()?.let { cacheKey ->
                            invalidateCachedResourceForPlaybackRecovery(
                                cacheKey = cacheKey,
                                reason = "unrecoverable_playback_${error.errorCodeName}"
                            )
                        }
                    }
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
            logPlaybackStateTransition("playback_state_changed:${playbackStateName(state)}")
            _playerPlaybackStateFlow.value = state
            if (state == Player.STATE_BUFFERING && player.playWhenReady) {
                schedulePlaybackStartupWatchdog(reason = "state_buffering")
                schedulePlaybackRuntimeWatchdog(reason = "state_buffering")
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
                    schedulePlaybackRuntimeWatchdog(reason = "state_ready")
                }
            }
            if (state == Player.STATE_ENDED) {
                if (shouldAcceptPlayerCallback(
                        playbackRequestToken,
                        loadedMediaRequestToken,
                        isPendingMediaLoadActive()
                    )
                ) {
                    cancelPlaybackStartupWatchdog(reason = "state_ended")
                    resetPlaybackRuntimeWatchdog(reason = "state_ended")
                    handleTrackEndedIfNeeded(source = "playback_state_changed")
                }
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (!isPlayerInitialized()) return
            if (!shouldExposePlayerCallbackState(
                    playbackRequestToken,
                    loadedMediaRequestToken,
                    isPendingMediaLoadActive()
                )
            ) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Ignoring stale playback suppression callback during pending media load: " +
                        "requestToken=$playbackRequestToken, " +
                        "loadedToken=$loadedMediaRequestToken, " +
                        "suppression=${playbackSuppressionReasonName(playbackSuppressionReason)}"
                )
                return
            }
            logPlaybackStateTransition(
                "playback_suppression_changed:" +
                    playbackSuppressionReasonName(playbackSuppressionReason)
            )
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                resetPlaybackRuntimeWatchdog(reason = "playback_suppression_entered")
            } else if (player.playWhenReady && playbackProgressAdvanceReported) {
                schedulePlaybackRuntimeWatchdog(reason = "suppression_cleared")
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
            logPlaybackStateTransition("is_playing_changed:$isPlaying")
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
                schedulePlaybackRuntimeWatchdog(reason = "is_playing_true")
            } else if (player.playWhenReady) {
                schedulePlaybackRuntimeWatchdog(reason = "is_playing_false")
            } else {
                stopProgressUpdates()
                resetPlaybackRuntimeWatchdog(reason = "is_playing_false_without_intent")
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
            logPlaybackStateTransition(
                "play_when_ready_changed:$playWhenReady:" +
                    playWhenReadyChangeReasonName(reason)
            )
            _playWhenReadyFlow.value = playWhenReady
            if (playWhenReady) {
                startProgressUpdates()
                schedulePlaybackStartupWatchdog(reason = "play_when_ready_true")
                schedulePlaybackRuntimeWatchdog(reason = "play_when_ready_true")
            } else {
                cancelPlaybackStartupWatchdog(reason = "play_when_ready_false")
                resetPlaybackRuntimeWatchdog(reason = "play_when_ready_false")
                if (!player.isPlaying) {
                    stopProgressUpdates()
                }
            }
            if (!playWhenReady) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "playWhenReady=false, reason=${playWhenReadyChangeReasonName(reason)}, state=${playbackStateName(player.playbackState)}, mediaId=${player.currentMediaItem?.mediaId}, stack=[${debugStackHint()}]"
                )
                if (shouldResumeSilentlyForListenTogetherNoisyPause(
                        playWhenReady = playWhenReady,
                        playWhenReadyChangeReason = reason,
                        muteListenTogetherListenerForAudioRouteLoss =
                            shouldMuteListenTogetherListenerForAudioRouteLoss()
                    )
                ) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "restore Listen Together listener playWhenReady after noisy route by muting locally"
                    )
                    suppressPlaybackForAudioRouteLoss(
                        reason = "listen_together_exoplayer_becoming_noisy"
                    )
                    playImpl(
                        commandSource = PlaybackCommandSource.LOCAL_SAFETY,
                        allowFadeIn = false
                    )
                    return
                }
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
            if (!playWhenReady && !resumePlaybackRequested) {
                PlaybackTransitionWakeLock.release(
                    playbackRequestToken,
                    "play_when_ready_false"
                )
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
            if (timeline.isEmpty) {
                resetPlaybackRuntimeWatchdog(reason = "timeline_empty")
            }
            if (player.playWhenReady || player.isPlaying) {
                startProgressUpdates()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlaybackVolumeNormalizationState.resetForNewTrack()
            resetPlaybackRuntimeWatchdog(reason = "media_item_transition")
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

}

private fun PlayerManager.createPlaybackDataSourceFactory(
    app: Application,
    maxCacheSize: Long,
    upstream: ConditionalHttpDataSourceFactory
): androidx.media3.datasource.DataSource.Factory {
    if (!shouldUsePlaybackMediaCache(maxCacheSize)) {
        NPLogger.d("NERI-Player", "Cache disabled by user setting (size=0).")
        return androidx.media3.datasource.DefaultDataSource.Factory(app, upstream)
    }
    return createCachedPlaybackDataSourceFactory(app, maxCacheSize, upstream)
}

internal fun shouldUsePlaybackMediaCache(maxCacheSize: Long): Boolean {
    if (maxCacheSize > 0) return true
    return maxCacheSize == CacheSizePolicy.UNLIMITED_CACHE_SIZE_BYTES
}

internal fun shouldEnableFloatPlaybackOutput(highResolutionOutput: Boolean, usbExclusive: Boolean): Boolean =
    highResolutionOutput && !usbExclusive

private fun PlayerManager.createCachedPlaybackDataSourceFactory(
    app: Application,
    maxCacheSize: Long,
    upstream: ConditionalHttpDataSourceFactory
): androidx.media3.datasource.DataSource.Factory {
    val mediaCache = createVerifiedMediaCache(
        app = app,
        maxCacheSize = maxCacheSize,
        databaseProvider = StandaloneDatabaseProvider(app)
    )
    if (mediaCache == null) {
        cache = null
        return androidx.media3.datasource.DefaultDataSource.Factory(app, upstream)
    }
    cache = mediaCache
    val cacheDsFactory = CacheDataSource.Factory()
        .setCache(mediaCache)
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(
            CacheDataSource.FLAG_BLOCK_ON_CACHE or
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
        )
        .setEventListener(object : CacheDataSource.EventListener {
            override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                AppContainer.trafficStatsRepo.recordCacheHitBytes(cachedBytesRead)
            }

            override fun onCacheIgnored(reason: Int) {
                if (reason == CacheDataSource.CACHE_IGNORED_REASON_ERROR) {
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "cache read failed; bypassing cache for the next data source cycle"
                    )
                }
            }
        })
    return androidx.media3.datasource.DefaultDataSource.Factory(app, cacheDsFactory)
}

private fun PlayerManager.observePlaybackSettings() {
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
                cancelLyriconUpdate()
            }
        }
    }
    ioScope.launch {
        settingsRepo.amllLyricsEnabledFlow.collect { enabled ->
            val changed = amllLyricsEnabled != enabled
            amllLyricsEnabled = enabled
            if (changed) {
                evictLyricCachesForSourcePreferenceChange()
            }
        }
    }
    ioScope.launch {
        settingsRepo.preferWordTimedLyricsFlow.collect { enabled ->
            val changed = preferWordTimedLyrics != enabled
            preferWordTimedLyrics = enabled
            if (changed) {
                evictLyricCachesForSourcePreferenceChange()
            }
        }
    }
    ioScope.launch {
        settingsRepo.defaultLyricSourceFlow.collect { source ->
            val changed = defaultLyricSource != source
            defaultLyricSource = source
            if (changed) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "默认歌词源设置更新: ${source.storageValue}"
                )
                evictLyricCachesForSourcePreferenceChange()
                syncLyriconSong(_currentSongFlow.value)
                syncExternalBluetoothLyrics(_currentSongFlow.value)
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
        settingsRepo.externalBluetoothTranslationEnabledFlow.collect { enabled ->
            externalBluetoothTranslationEnabled = enabled
            syncExternalTranslatedLyrics(_currentSongFlow.value)
        }
    }
    ioScope.launch {
        settingsRepo.dynamicIslandLyricsEnabledFlow.collect { enabled ->
            dynamicIslandLyricsEnabled = enabled
            syncExternalBluetoothLyrics(_currentSongFlow.value)
        }
    }
    ioScope.launch {
        settingsRepo
            .settingFlow(AutoSettingsSchema.general.biliSkipSegmentPromptEnabled)
            .collect { enabled ->
                biliSkipSegmentPromptEnabled = enabled
            }
    }
    FloatingLyricsOverlayManager.setPositionChangeListener { positionX, positionY, isLandscape ->
        ioScope.launch {
            settingsRepo.setFloatingLyricsPosition(positionX, positionY, isLandscape)
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
                showTranslationChanged -> syncExternalTranslatedLyrics(_currentSongFlow.value)
            }
        }
    }
    mainScope.launch {
        _isPlayingFlow.collect { isPlaying ->
            FloatingLyricsOverlayManager.updatePlaybackState(isPlaying)
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
            updateLyriconLyricOffset()
        }
    }
    ioScope.launch {
        settingsRepo.qqMusicLyricDefaultOffsetMsFlow.collect { offsetMs ->
            qqMusicLyricDefaultOffsetMs = offsetMs
            updateExternalBluetoothLyricLine(_playbackPositionMs.value)
            updateLyriconLyricOffset()
        }
    }
    ioScope.launch {
        settingsRepo.kugouLyricDefaultOffsetMsFlow.collect { offsetMs ->
            kugouLyricDefaultOffsetMs = offsetMs
            updateExternalBluetoothLyricLine(_playbackPositionMs.value)
            updateLyriconLyricOffset()
        }
    }
    ioScope.launch {
        settingsRepo.lrclibLyricDefaultOffsetMsFlow.collect { offsetMs ->
            lrclibLyricDefaultOffsetMs = offsetMs
            updateExternalBluetoothLyricLine(_playbackPositionMs.value)
            updateLyriconLyricOffset()
        }
    }
    ioScope.launch {
        settingsRepo.amllTtmlLyricDefaultOffsetMsFlow.collect { offsetMs ->
            amllTtmlLyricDefaultOffsetMs = offsetMs
            updateExternalBluetoothLyricLine(_playbackPositionMs.value)
            updateLyriconLyricOffset()
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
        settingsRepo.playbackVolumeNormalizationEnabledFlow.collect { enabled ->
            applyPlaybackSoundConfigIfChanged(
                playbackSoundConfig.copy(volumeNormalizationEnabled = enabled)
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
        settingsRepo.rememberLongFormPlaybackProgressFlow.collect { enabled ->
            rememberLongFormPlaybackProgressEnabled = enabled
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
            val previousEnabled = neteaseAutoSourceSwitchEnabled
            neteaseAutoSourceSwitchEnabled = enabled
            if (!previousEnabled && enabled) {
                scheduleQualityRefresh(
                    source = PlaybackAudioSource.NETEASE,
                    reason = "netease_auto_source_switch_enabled"
                )
            }
        }
    }
    ioScope.launch {
        settingsRepo.neteaseLocalSourceFallbackFlow.collect { enabled ->
            val previousEnabled = neteaseLocalSourceFallbackEnabled
            neteaseLocalSourceFallbackEnabled = enabled
            if (!previousEnabled && enabled) {
                scheduleQualityRefresh(
                    source = PlaybackAudioSource.NETEASE,
                    reason = "netease_local_source_fallback_enabled"
                )
            }
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
        val repository = localRepo
        if (!repository.awaitInitialized()) return@launch
        repository.playlists.collect { repoLists ->
            _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(repoLists)
            _localPlaylistsReadyFlow.value = true
        }
    }

}

private fun PlayerManager.completeInitialization(
    restoredStateSnapshot: RestoredPlayerStateSnapshot?,
    effectiveMaxCacheSize: Long
) {
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
        "initialize(): success, cacheSize=$effectiveMaxCacheSize, restoredQueueSize=${currentPlaylist.size}, currentIndex=$currentIndex, currentDevice=${initialAudioDeviceDescription()}"
    )
}

private fun PlayerManager.initialAudioDeviceDescription(): String {
    val device = _currentAudioDevice.value
    if (device == null) return "null:null"
    return "${device.type}:${device.name}"
}

private fun PlayerManager.rollbackInitialization(e: Throwable, effectiveMaxCacheSize: Long) {
    statePersistenceCoordinator.close()
    statePersistenceWriter.invalidate()
    urlRefreshController.cancelCurrent()
    NPLogger.e(
        "NERI-PlayerManager",
        "initialize(): failed, cacheSize=$effectiveMaxCacheSize, currentPlaylistSize=${currentPlaylist.size}, currentIndex=$currentIndex",
        e
    )
    NPLogger.w(
        "NERI-PlayerManager",
        "initialize(): rollback begin, playerInitialized=${isPlayerInitialized()}, cacheInitialized=${isCacheInitialized()}, conditionalFactoryPresent=${conditionalHttpFactory != null}"
    )
    rollbackInitializationStep("unregistered audio device callback", "unregister audio callback") {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
    }
    rollbackInitializationStep("closed conditional http factory", "close conditional factory") {
        conditionalHttpFactory?.close()
    }
    conditionalHttpFactory = null
    rollbackInitializationStep("released player", "release player") {
        releasePlayerAfterFailedInitialization()
    }
    rollbackInitializationStep("released playback effects", "release effects") {
        _playbackSoundState.value = playbackEffectsController.release()
    }
    rollbackInitializationStep("released cache", "release cache") { releaseMediaCache() }
    rollbackInitializationStep("cancelled mainScope", "cancel mainScope") { mainScope.cancel() }
    rollbackInitializationStep("cancelled ioScope", "cancel ioScope") { ioScope.cancel() }
    rollbackInitializationStep("released lyricon", "release lyricon") { LyriconManager.release() }
    initialized = false
}

private fun PlayerManager.releasePlayerAfterFailedInitialization() {
    if (isPlayerInitialized()) player.release()
}

private fun rollbackInitializationStep(
    successMessage: String,
    failureMessage: String,
    release: () -> Unit
) {
    runCatching {
        release()
        NPLogger.d("NERI-PlayerManager", "initialize(): rollback $successMessage")
    }.onFailure { error ->
        NPLogger.w("NERI-PlayerManager", "initialize(): rollback $failureMessage failed: ${error.message}")
    }
}

private fun PlayerManager.finishInitializationAttempt() {
    if (!initialized) {
        statePersistenceCoordinator.close()
        statePersistenceWriter.invalidate()
        urlRefreshController.cancelCurrent()
    }
    synchronized(initializationLock) {
        initializationInProgress = false
    }
}

internal suspend fun PlayerManager.clearCacheImpl(
    clearAudio: Boolean = true,
    clearImage: Boolean = true
): Pair<Boolean, String> {
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        var apiRemovedCount = 0

        try {
            if (clearAudio) {
                val mediaCache = cache
                if (mediaCache != null) {
                    val keysSnapshot = HashSet(mediaCache.keys)
                    keysSnapshot.forEach { key ->
                        try {
                            mediaCache.removeResource(key)
                            apiRemovedCount++
                        } catch (_: Exception) {
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
                "Cache Clear: removed $apiRemovedCount resources through SimpleCache."
            )

            val msg = if (apiRemovedCount > 0 || clearImage) {
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
    val pcmRequirements = pcmAudioRequirements(
        usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled,
        playbackSpeed = playbackSoundConfig.speed,
        playbackPitch = playbackSoundConfig.pitch,
        equalizerEnabled = playbackSoundConfig.equalizerEnabled,
        loudnessGainMb = playbackSoundConfig.loudnessGainMb,
        volumeBalance = playbackSoundConfig.volumeBalance,
        volumeNormalizationEnabled = playbackSoundConfig.volumeNormalizationEnabled,
        highResolutionOutputEnabled = playbackHighResolutionOutputEnabled,
        audioReactiveActive = AudioReactive.enabled,
        audioSource = _currentPlaybackAudioInfo.value?.source,
        listenTogetherPlaybackRate = listenTogetherSyncPlaybackRate,
    )
    val requiresPcmProcessing = pcmRequirements.isNotEmpty()
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
        "audio offload preference updated: enabled=${!requiresPcmProcessing} " +
            "reason=$reason pcmRequirements=${pcmRequirements.joinToString()}"
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
                } == true,
                outputDeviceRemoved = removedDevices?.any {
                    it.isSink && isHeadsetLikeOutput(it.type)
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
    val currentDevice = _currentAudioDevice.value
    val playbackActive = _isPlayingFlow.value || _playWhenReadyFlow.value || resumePlaybackRequested
    if (shouldMuteListenTogetherListenerForAudioRouteLoss()) {
        NPLogger.d(
            "NERI-PlayerManager",
            "handleAudioBecomingNoisy(): mute Listen Together listener without pausing"
        )
        suppressPlaybackForAudioRouteLoss(reason = "listen_together_becoming_noisy")
        return true
    }
    val nativeState = UsbExclusiveSessionController.state.value
    val nativePlayerPcmActive = nativeState.opened && nativeState.source == "player_pcm"
    if (
        shouldDeferUsbExclusiveNoisyRouteToNativePath(
            usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled,
            allowMixedPlaybackEnabled = allowMixedPlaybackEnabled,
            routeIsUsbOutput = currentDevice?.type?.let(::isUsbOutputType) == true,
            nativePlayerPcmActive = nativePlayerPcmActive
        )
    ) {
        NPLogger.d(
            "NERI-UsbExclusive",
            "defer noisy-route broadcast to active native USB path: " +
                "streaming=${nativeState.streaming} handle=${nativeState.handle}"
        )
        return false
    }
    if (!_isPlayingFlow.value) {
        if (shouldStopForUsbExclusiveNoisyRoute(currentDevice, playbackActive)) {
            stopPlaybackAfterUsbExclusiveNoisyRoute(currentDevice)
            return true
        }
        NPLogger.d("NERI-PlayerManager", "handleAudioBecomingNoisy(): ignored because playback is already paused")
        return false
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "handleAudioBecomingNoisy(): currentDevice=${currentDevice?.type}:${currentDevice?.name}, isPlaying=${_isPlayingFlow.value}"
    )
    if (shouldStopForUsbExclusiveNoisyRoute(currentDevice, playbackActive)) {
        stopPlaybackAfterUsbExclusiveNoisyRoute(currentDevice)
        return true
    }
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
            "handleAudioBecomingNoisy(): mute while confirming disconnect for " +
                "device=${currentDevice.type}:${currentDevice.name}"
        )
        suppressPlaybackForAudioRouteLoss(reason = "bluetooth_disconnect_pending")
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

private fun PlayerManager.shouldStopForUsbExclusiveNoisyRoute(
    currentDevice: AudioDevice?,
    playbackActive: Boolean
): Boolean {
    return shouldStopUsbExclusivePlaybackForNoisyRoute(
        usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled,
        allowMixedPlaybackEnabled = allowMixedPlaybackEnabled,
        routeIsUsbOutput = currentDevice?.type?.let(::isUsbOutputType) == true,
        playbackActive = playbackActive
    )
}

private fun PlayerManager.stopPlaybackAfterUsbExclusiveNoisyRoute(currentDevice: AudioDevice?) {
    NPLogger.w(
        "NERI-UsbExclusive",
        "stop USB exclusive playback after noisy route event: " +
            "device=${currentDevice?.type}:${currentDevice?.name} " +
            "playWhenReady=${_playWhenReadyFlow.value} isPlaying=${_isPlayingFlow.value}"
    )
    stopPlaybackAfterUsbExclusiveNativeFailure("usb_audio_route_noisy")
}

private fun PlayerManager.handleDeviceChange(
    audioManager: AudioManager,
    usbTopologyChanged: Boolean,
    outputDeviceRemoved: Boolean = false
) {
    val previousDevice = _currentAudioDevice.value
    val newDevice = getCurrentAudioDevice(audioManager)
    _currentAudioDevice.value = newDevice
    val usbRouteChanged = previousDevice == null ||
        previousDevice.type != newDevice.type ||
        previousDevice.name != newDevice.name
    val nativeOpenGate = UsbExclusiveSessionController.playerPcmOpenGateReason()
    val nextRouteIsUsbOutput = isUsbOutputType(newDevice.type)
    val listenTogetherOutputDisconnected =
        shouldMuteListenTogetherListenerForOutputDisconnect(
            listenTogetherActive = isListenTogetherActive(),
            isCurrentUserController = isCurrentUserControllerInListenTogether(),
            previousRouteWasHeadsetLike =
                previousDevice?.type?.let(::isHeadsetLikeOutput) == true,
            newRouteIsBuiltinSpeaker =
                newDevice.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            outputDeviceRemoved = outputDeviceRemoved,
            routeChanged = usbRouteChanged
        )
    if (listenTogetherOutputDisconnected) {
        bluetoothDisconnectPauseJob?.cancel()
        bluetoothDisconnectPauseJob = null
        NPLogger.d(
            "NERI-PlayerManager",
            "Detected Listen Together listener output disconnect " +
                "(${previousDevice?.type} -> ${newDevice.type}), muting without pausing."
        )
        suppressPlaybackForAudioRouteLoss(reason = "listen_together_output_disconnect")
    }
    if (
        usbExclusivePlaybackEnabled &&
        nativeOpenGate?.contains("usb_device_detached", ignoreCase = true) == true &&
        !nextRouteIsUsbOutput
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
    val interruptedPlaybackCanResumeOnNewUsbRoute =
        usbExclusivePlaybackEnabled &&
            !allowMixedPlaybackEnabled &&
            usbExclusiveInterruptedPlaybackIntent != null &&
            resumePlaybackRequested &&
            nextRouteIsUsbOutput
    if (interruptedPlaybackCanResumeOnNewUsbRoute) {
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        applyUsbExclusivePlaybackPolicy(
            reconfigureAudioSink = usbRouteChanged || usbTopologyChanged
        )
        scheduleUsbExclusivePlaybackResumeAfterDeviceAttach("audio_device_added")
        UsbExclusiveDebugLogger.logSnapshot(
            context = application,
            audioManager = audioManager,
            reason = "usb_device_reattach",
            enabled = usbExclusivePlaybackEnabled
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
    if (listenTogetherOutputDisconnected) return
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
    handleUsbExclusivePreferencesChangedOnMain(preferences)
}

private fun PlayerManager.handleUsbExclusivePreferencesChangedOnMain(preferences: UsbExclusivePreferences) {
    val previousPreferences = usbExclusivePreferences
    usbExclusivePreferences = preferences
    if (!shouldApplyUsbExclusivePreferenceChange(previousPreferences, preferences, usbExclusivePlaybackEnabled)) return

    applyUsbExclusivePreferenceChange(previousPreferences, preferences)
}

private fun shouldApplyUsbExclusivePreferenceChange(
    previous: UsbExclusivePreferences,
    current: UsbExclusivePreferences,
    playbackEnabled: Boolean
): Boolean {
    if (previous == current) return false
    return playbackEnabled
}

private fun PlayerManager.applyUsbExclusivePreferenceChange(
    previousPreferences: UsbExclusivePreferences,
    preferences: UsbExclusivePreferences
) {
    if (isPlaybackActiveForUsbExclusiveSwitch()) {
        applyActiveUsbExclusivePreferenceChange(previousPreferences, preferences)
        return
    }

    UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    retryUsbExclusivePlayback("usb_output_preferences_changed")
}

private fun PlayerManager.applyActiveUsbExclusivePreferenceChange(
    previousPreferences: UsbExclusivePreferences,
    preferences: UsbExclusivePreferences
) {
    usbExclusiveLivenessOwner.applyActiveBuffer("preferences_changed")
    val routeReconfigurationRequired =
        previousPreferences.requiresUsbExclusiveRouteReconfiguration(preferences)
    pendingUsbExclusivePreferenceReconfigure = routeReconfigurationRequired
    NPLogger.i("NERI-UsbExclusive", "USB preferences saved; deferRoute=$routeReconfigurationRequired")
    if (routeReconfigurationRequired) {
        deferUsbExclusiveReconfigurationUntilPlaybackStops("usb_output_preferences_changed")
    }
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
    val effectiveReleaseMediaItemIndex = releaseMediaItemIndex
    val effectiveReleasePositionMs = releasePositionMs ?: interruptedIntent?.positionMs
    cancelUsbExclusiveRecovery("release:$reason")
    usbExclusiveSystemAudioResumeJob?.cancel()
    usbExclusiveSystemAudioResumeJob = null
    usbExclusiveSystemAudioWatchdogJob?.cancel()
    usbExclusiveSystemAudioWatchdogJob = null
    cancelUsbAudioSinkReconfiguration()
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
    cancelUsbAudioSinkReconfiguration()
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
        val queueIndex = currentQueueIndexForUsbExclusiveInterruptedPlayback()
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val shouldKeepPlaybackIntent = shouldKeepPlaybackIntentAfterUsbNativeFailure()
        val keepPlaybackIntent = shouldKeepPlaybackIntent && queueIndex != null
        if (keepPlaybackIntent) {
            rememberUsbExclusiveInterruptedPlaybackIntent(
                reason = reason,
                queueIndex = checkNotNull(queueIndex),
                positionMs = positionMs
            )
        } else if (shouldKeepPlaybackIntent) {
            NPLogger.w(
                "NERI-UsbExclusive",
                "drop USB interrupted playback intent because the current queue item is unavailable: " +
                    "reason=$reason currentIndex=$currentIndex queueSize=${currentPlaylist.size}"
            )
        } else {
            clearUsbExclusiveInterruptedPlaybackIntent("native_failure_idle:$reason")
            updateResumePlaybackRequested(false)
        }
        NPLogger.w(
            "NERI-UsbExclusive",
            "stop playback after native USB failure: reason=$reason queueIndex=$queueIndex " +
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
        currentQueueIndexForUsbExclusiveInterruptedPlayback()?.let { queueIndex ->
            rememberUsbExclusiveInterruptedPlaybackIntent(
                reason = "manual_play_no_usb:$reason",
                queueIndex = queueIndex,
                positionMs = player.currentPosition.coerceAtLeast(0L)
            )
        }
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
    cancelUsbAudioSinkReconfiguration()
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
    usbExclusiveLivenessOwner.updateForegroundState(foreground, reason)
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
    val reconfiguration = usbAudioSinkReconfigurationSnapshot()
    if (
        shouldDeferUsbExclusiveRecoveryForPendingReconfiguration(
            reconfigurationActive = reconfiguration.pending,
            reconfigurationReason = reconfiguration.reason
        )
    ) {
        NPLogger.i(
            "NERI-UsbExclusive",
            "defer duplicate USB recovery while immediate reconfiguration is active: " +
                "reason=$reason pendingReason=${reconfiguration.reason}"
        )
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
    usbExclusiveLivenessOwner.recoverOnForeground(reason)
}

private fun PlayerManager.cancelUsbExclusiveRecovery(reason: String) {
    usbExclusiveRecoveryJob?.cancel()
    usbExclusiveRecoveryJob = null
    usbExclusiveLivenessOwner.cancelJobs()
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

internal fun PlayerManager.resumeInterruptedUsbExclusivePlaybackIfNeeded(reason: String): Boolean =
    usbInterruptedPlaybackOwner.resumeOnSystemRouteIfNeeded(reason)

internal fun PlayerManager.scheduleUsbExclusivePlaybackResumeAfterDeviceAttach(reason: String) {
    usbInterruptedPlaybackOwner.scheduleResumeAfterDeviceAttach(reason)
}

private fun PlayerManager.shouldKeepPlaybackIntentAfterUsbNativeFailure(): Boolean =
    usbInterruptedPlaybackOwner.shouldKeepIntentAfterNativeFailure()

private fun PlayerManager.rememberUsbExclusiveInterruptedPlaybackIntent(
    reason: String,
    queueIndex: Int,
    positionMs: Long
) {
    usbInterruptedPlaybackOwner.remember(reason, queueIndex, positionMs)
}

internal fun PlayerManager.clearUsbExclusiveInterruptedPlaybackIntent(reason: String) {
    usbInterruptedPlaybackOwner.clear(reason)
}

private fun PlayerManager.currentQueueIndexForUsbExclusiveInterruptedPlayback(): Int? =
    usbInterruptedPlaybackOwner.queueIndexForInterruption()

private const val USB_EXCLUSIVE_RECONFIGURE_DEBOUNCE_MS = 120L
private const val USB_EXCLUSIVE_RECONFIGURE_COOLDOWN_MS = 2_500L
private const val USB_EXCLUSIVE_OPEN_GATE_RETRY_DELAY_MS = 3_800L
private const val USB_EXCLUSIVE_OPEN_GATE_WAIT_TIMEOUT_MS = 8_000L
private const val USB_EXCLUSIVE_OPEN_GATE_WAIT_POLL_MS = 100L
private const val USB_EXCLUSIVE_SAFE_SWITCH_POLL_MS = 800L
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
    NPLogger.d(
        "NERI-PlayerManager",
        "schedulePauseForBluetoothDisconnect(): device=${previousDevice.type}:${previousDevice.name}, reason=$reason, samples=$BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT"
    )
    bluetoothDisconnectPauseJob = mainScope.launch {
        val sampledRoutesAreBluetooth = mutableListOf<Boolean>()
        repeat(BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT) { sampleIndex ->
            delay(
                if (sampleIndex == 0) {
                    BLUETOOTH_DISCONNECT_CONFIRM_INITIAL_DELAY_MS
                } else {
                    BLUETOOTH_DISCONNECT_CONFIRM_SAMPLE_INTERVAL_MS
                }
            )
            if (!stopOnBluetoothDisconnectEnabled || !_isPlayingFlow.value) {
                restorePlaybackAfterTransientAudioRouteLoss(
                    reason = "bluetooth_disconnect_canceled:$reason"
                )
                bluetoothDisconnectPauseJob = null
                return@launch
            }
            val audioManager: AudioManager =
                application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val confirmedDevice = getCurrentAudioDevice(audioManager)
            _currentAudioDevice.value = confirmedDevice
            if (!isBluetoothOutputType(confirmedDevice.type) &&
                confirmedDevice.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            ) {
                restorePlaybackAfterTransientAudioRouteLoss(
                    reason = "bluetooth_disconnect_rerouted:${confirmedDevice.type}"
                )
                bluetoothDisconnectPauseJob = null
                return@launch
            }
            sampledRoutesAreBluetooth += isBluetoothOutputType(confirmedDevice.type)
        }
        if (!shouldConfirmBluetoothDisconnect(
                stopOnBluetoothDisconnectEnabled = stopOnBluetoothDisconnectEnabled,
                playbackActive = _isPlayingFlow.value,
                previousRouteWasBluetooth = requiresDisconnectConfirmation(previousDevice.type),
                sampledRoutesAreBluetooth = sampledRoutesAreBluetooth
            )
        ) {
            NPLogger.d(
                "NERI-PlayerManager",
                "Ignored transient bluetooth route change ($reason): samples=$sampledRoutesAreBluetooth"
            )
            restorePlaybackAfterTransientAudioRouteLoss(
                reason = "bluetooth_disconnect_transient:$reason"
            )
            bluetoothDisconnectPauseJob = null
            return@launch
        }
        NPLogger.d(
            "NERI-PlayerManager",
            "Confirmed bluetooth disconnect ($reason), pausing playback."
        )
        suppressPlaybackForAudioRouteLoss(reason = "bluetooth_disconnect_confirmed:$reason")
        pauseForAudioRouteLoss(reason = "bluetooth_disconnect_confirmed:$reason")
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

internal fun PlayerManager.applyUsbExclusivePlaybackPolicy(
    reconfigureAudioSink: Boolean = false,
    reconfigureReason: String = "usb_policy_changed",
    allowReconfigureWhilePlaying: Boolean = false
) {
    if (!isPlayerInitialized()) return
    updateAudioOffloadPreferences("usb_exclusive_policy")
    val audioManager: AudioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    prepareUsbExclusivePolicyRoute(reconfigureAudioSink)
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
        applyScheduledUsbExclusivePolicy(
            audioManager, preferredDevice, policyGeneration,
            reconfigureAudioSink, reconfigureReason, allowReconfigureWhilePlaying
        )
    }
}

private fun PlayerManager.prepareUsbExclusivePolicyRoute(reconfigureAudioSink: Boolean) {
    if (usbExclusivePlaybackEnabled) {
        prepareEnabledUsbExclusivePolicyRoute(reconfigureAudioSink)
    } else {
        UsbExclusiveSessionController.stopPlayerPcmSession("apply_policy_disabled")
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(application, "apply_policy_disabled")
    }
}

private fun PlayerManager.prepareEnabledUsbExclusivePolicyRoute(reconfigureAudioSink: Boolean) {
    if (reconfigureAudioSink) {
        usbExclusiveRouteGeneration += 1L
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
    }
    UsbExclusiveDiagnostics.ensureUsbPermissionIfNeeded(context = application, reason = "apply_policy")
}

private fun PlayerManager.applyScheduledUsbExclusivePolicy(
    audioManager: AudioManager,
    preferredDevice: AudioDeviceInfo?,
    policyGeneration: Long,
    reconfigureAudioSink: Boolean,
    reconfigureReason: String,
    allowReconfigureWhilePlaying: Boolean
) {
    if (!isPlayerInitialized()) return
    applyCurrentUsbExclusivePolicy(
        audioManager, preferredDevice, policyGeneration,
        reconfigureAudioSink, reconfigureReason, allowReconfigureWhilePlaying
    )
}

private fun PlayerManager.applyCurrentUsbExclusivePolicy(
    audioManager: AudioManager,
    preferredDevice: AudioDeviceInfo?,
    policyGeneration: Long,
    reconfigureAudioSink: Boolean,
    reconfigureReason: String,
    allowReconfigureWhilePlaying: Boolean
) {
    if (usbExclusiveRouteGeneration != policyGeneration) {
        NPLogger.d(
            "NERI-UsbExclusive",
            "skip stale USB route policy: generation=$policyGeneration current=$usbExclusiveRouteGeneration"
        )
        return
    }
    applyPreferredUsbAudioDevice(audioManager, preferredDevice)
    scheduleUsbSinkAfterPolicyIfRequested(reconfigureAudioSink, reconfigureReason, allowReconfigureWhilePlaying)
}

private fun PlayerManager.applyPreferredUsbAudioDevice(
    audioManager: AudioManager,
    preferredDevice: AudioDeviceInfo?
) {
    val error = runCatching { player.setPreferredAudioDevice(preferredDevice) }.exceptionOrNull()
    logPreferredUsbAudioDeviceResult(audioManager, preferredDevice, error)
}

private fun PlayerManager.logPreferredUsbAudioDeviceResult(
    audioManager: AudioManager,
    preferredDevice: AudioDeviceInfo?,
    error: Throwable?
) {
    if (error != null) {
        NPLogger.w(
            "NERI-UsbExclusive",
            "applyUsbExclusivePlaybackPolicy(): setPreferredAudioDevice failed, enabled=$usbExclusivePlaybackEnabled, target=${preferredDevice.describeForLog()}",
            error
        )
        return
    }
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
}

private fun PlayerManager.scheduleUsbSinkAfterPolicyIfRequested(
    reconfigureAudioSink: Boolean,
    reconfigureReason: String,
    allowReconfigureWhilePlaying: Boolean
) {
    if (reconfigureAudioSink) {
        scheduleUsbAudioSinkReconfiguration(
            reason = reconfigureReason,
            allowWhilePlaybackActive = allowReconfigureWhilePlaying
        )
    }
}

internal fun PlayerManager.scheduleUsbAudioSinkReconfiguration(
    reason: String,
    allowWhilePlaybackActive: Boolean = false,
    bypassCooldown: Boolean = false
) {
    val scheduledGeneration = usbExclusiveRouteGeneration
    val requestToken = beginUsbAudioSinkReconfiguration(reason)
    val reconfigureJob = mainScope.launch(start = CoroutineStart.LAZY) reconfigure@{
        if (!isLatestUsbAudioSinkReconfiguration(requestToken)) return@reconfigure
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
        if (!isLatestUsbAudioSinkReconfiguration(requestToken)) return@reconfigure
        if (!isPlayerInitialized() || player.currentMediaItem == null) return@reconfigure
        if (usbExclusiveRouteGeneration != scheduledGeneration) {
            NPLogger.d(
                "NERI-UsbExclusive",
                "skip stale USB reconfiguration after delay: reason=$reason generation=$scheduledGeneration current=$usbExclusiveRouteGeneration"
            )
            return@reconfigure
        }
        if (
            shouldSkipRedundantUsbExclusiveReconfiguration(
                reason = reason,
                usbExclusiveEnabled = usbExclusivePlaybackEnabled,
                hasHealthyPlayerPcmSession =
                    UsbExclusiveSessionController.hasHealthyPlayerPcmSession()
            )
        ) {
            pendingUsbExclusivePreferenceReconfigure = false
            usbExclusiveToggleTransitionActive = false
            usbExclusiveToggleTransitionReason = ""
            markUsbExclusivePlaybackPreparing(false, "native_route_already_ready:$reason")
            NPLogger.i(
                "NERI-UsbExclusive",
                "skip redundant USB reconfiguration because native player session is ready: " +
                    "reason=$reason generation=$scheduledGeneration"
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
        if (!isLatestUsbAudioSinkReconfiguration(requestToken)) return@reconfigure
        val stopResult = runCatching {
            restorePlaybackAfterTransientAudioRouteLoss(reason = "usb_reconfigure:$reason")
            player.playWhenReady = false
            player.stop()
        }
        if (!isLatestUsbAudioSinkReconfiguration(requestToken)) return@reconfigure
        val stopped = stopResult.onFailure { error ->
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
            if (!isLatestUsbAudioSinkReconfiguration(requestToken)) return@reconfigure
            if (!isPlayerInitialized() || player.currentMediaItem == null) return@reconfigure
            if (usbExclusiveRouteGeneration != scheduledGeneration) {
                NPLogger.d(
                    "NERI-UsbExclusive",
                    "skip stale USB reconfiguration after system release delay: reason=$reason generation=$scheduledGeneration current=$usbExclusiveRouteGeneration"
                )
                return@reconfigure
            }
        }
        if (!isLatestUsbAudioSinkReconfiguration(requestToken)) return@reconfigure
        val prepareResult = runCatching {
            player.seekTo(mediaItemIndex, positionMs)
            player.prepare()
            updateResumePlaybackRequested(resumePlayback)
            player.playWhenReady = resumePlayback
            if (resumePlayback) {
                player.play()
            }
        }
        if (!isLatestUsbAudioSinkReconfiguration(requestToken)) return@reconfigure
        prepareResult.onSuccess {
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
    if (!installUsbAudioSinkReconfiguration(requestToken, reconfigureJob)) {
        reconfigureJob.cancel()
        abandonUsbAudioSinkReconfiguration(requestToken)
        return
    }
    reconfigureJob.invokeOnCompletion {
        finishUsbAudioSinkReconfiguration(requestToken, reconfigureJob)
    }
    reconfigureJob.start()
}

private fun PlayerManager.deferUsbExclusiveReconfigurationUntilPlaybackStops(reason: String) {
    pendingUsbExclusivePreferenceReconfigure = true
    val requestToken = beginUsbAudioSinkReconfiguration("deferred:$reason")
    val reconfigureJob = mainScope.launch(start = CoroutineStart.LAZY) reconfigure@{
        if (!isLatestUsbAudioSinkReconfiguration(requestToken)) return@reconfigure
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
        if (!isLatestUsbAudioSinkReconfiguration(requestToken)) return@reconfigure
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        pendingUsbExclusivePreferenceReconfigure = false
        scheduleUsbAudioSinkReconfiguration("deferred:$reason")
    }
    if (!installUsbAudioSinkReconfiguration(requestToken, reconfigureJob)) {
        reconfigureJob.cancel()
        abandonUsbAudioSinkReconfiguration(requestToken)
        return
    }
    reconfigureJob.invokeOnCompletion {
        finishUsbAudioSinkReconfiguration(requestToken, reconfigureJob)
    }
    reconfigureJob.start()
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
    if (reason.contains("feedback_scheduler", ignoreCase = true)) return false
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
    if (usbExclusiveErrorCode().isRecoverableTransportFailure) return true
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
    return usbExclusiveErrorCode() == UsbExclusiveErrorCode.TransferFirstCompletionTimeout ||
        contains("event_loop_first_completion_timeout", ignoreCase = true)
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
        return previousDevice.type != newDevice.type || previousDevice.name != newDevice.name
    }
    return previousUsb != newUsb
}

internal fun PlayerManager.releaseImpl() {
    if (!initialized) {
        NPLogger.d("NERI-PlayerManager", "release(): ignored because manager is already released")
        return
    }
    logPlaybackReleaseBegin()
    statePersistenceCoordinator.close()
    statePersistenceWriter.invalidate()
    urlRefreshController.cancelCurrent()
    clearRestoredPlayback()
    try {
        preparePlaybackRelease()
        stopPlaybackForRelease()
        releaseUsbSessionsAndJobs()
        releaseMediaJobsAndLyrics()
        releasePlayerEngine()
        releasePlaybackScopes()
        clearPlaybackStateAfterRelease()
        NPLogger.d("NERI-PlayerManager", "release(): completed")
    } finally {
        finishPlaybackRelease()
    }
}

private fun PlayerManager.logPlaybackReleaseBegin() {
    NPLogger.d(
        "NERI-PlayerManager",
        "release(): begin, currentSong=${_currentSongFlow.value?.name}, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, isPlaying=${_isPlayingFlow.value}, mediaUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
}

private fun cancelJobForRelease(job: Job?) {
    job?.cancel()
}

private fun PlayerManager.preparePlaybackRelease() {
    updateResumePlaybackRequested(false)
    lastAutoTrackAdvanceAtMs = 0L
    cancelPlaybackStartupWatchdog(reason = "release")
    resetPlaybackRuntimeWatchdog(reason = "release")
    clearActivePlaybackCandidates()
    StartupAudioFocusController.release("player_release")

    try {
        val audioManager: AudioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
    } catch (e: Exception) {
        NPLogger.w("NERI-PlayerManager", "release(): unregisterAudioDeviceCallback failed", e)
    }
    audioDeviceCallback = null
}

private fun PlayerManager.stopPlaybackForRelease() {
    stopProgressUpdates()
    cancelVolumeFade(resetToFull = true)
    clearAudioRouteMuteSuppression(
        reason = "release",
        preserveExplicitRestore = false
    )
    cancelPendingPauseRequest(resetVolumeToFull = true)
    cancelJobForRelease(bluetoothDisconnectPauseJob)
    bluetoothDisconnectPauseJob = null
    flushPlaybackStatsAsync("release", stopTracking = true)
}

private fun PlayerManager.releaseUsbSessionsAndJobs() {
    cancelJobForRelease(playbackSoundPersistJob)
    playbackSoundPersistJob = null
    cancelUsbAudioSinkReconfiguration()
    cancelJobForRelease(usbExclusiveSystemAudioReleaseJob)
    usbExclusiveSystemAudioReleaseJob = null
    cancelJobForRelease(usbExclusiveSystemAudioResumeJob)
    usbExclusiveSystemAudioResumeJob = null
    cancelJobForRelease(usbExclusiveSystemAudioWatchdogJob)
    usbExclusiveSystemAudioWatchdogJob = null
    cancelJobForRelease(usbExclusiveToggleTransitionJob)
    usbExclusiveToggleTransitionJob = null
    usbExclusiveSystemAudioReleaseInProgress = false
    usbExclusiveToggleTransitionActive = false
    usbExclusiveToggleTransitionReason = ""
    markUsbExclusivePlaybackPreparing(false, "player_release")
    cancelJobForRelease(usbExclusiveRecoveryJob)
    usbExclusiveRecoveryJob = null
    usbExclusiveLivenessOwner.cancelJobs()
    usbInterruptedPlaybackOwner.cancelReattach()
    UsbExclusiveSessionController.forceStopAllSessions("player_release")
    PlaybackTransitionWakeLock.releaseAll("player_release")
    UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(application, "player_release")
}

private fun PlayerManager.releaseMediaJobsAndLyrics() {
    cancelJobForRelease(playJob)
    playJob = null
    cancelJobForRelease(currentGenericUrlPrefetchJob)
    currentGenericUrlPrefetchJob = null
    currentGenericUrlPrefetchKey = null
    genericUrlPrefetchCache.clear()
    cancelLyriconUpdate()
    cancelJobForRelease(externalBluetoothLyricsLoadJob)
    externalBluetoothLyricsLoadJob = null
    cancelJobForRelease(externalBluetoothTranslationLoadJob)
    externalBluetoothTranslationLoadJob = null
    externalBluetoothLyrics = emptyList()
    externalBluetoothPreferredLyricSource = null
    floatingTranslatedLyrics = emptyList()
    floatingTranslationMatchesByIndex = emptyMap()
    externalBluetoothLyricsSongKey = null
    externalBluetoothLyricsEnabled = false
    externalBluetoothTranslationEnabled = false
    dynamicIslandLyricsEnabled = false
    floatingLyricsEnabled = false
    floatingLyricsShowTranslation = true
    statusBarLyricsEnable = false
    clearExternalBluetoothLyricLine()
    FloatingLyricsOverlayManager.release()
    LyriconManager.release()
}

private fun PlayerManager.releasePlayerEngine() {
    releasePlayerIfInitialized()
    _playbackSoundState.value = playbackEffectsController.release()
    _playWhenReadyFlow.value = false
    _playerPlaybackStateFlow.value = Player.STATE_IDLE
    releaseMediaCache()
    closeConditionalHttpFactory()
    conditionalHttpFactory = null
}

private fun PlayerManager.releasePlayerIfInitialized() {
    if (isPlayerInitialized()) {
        runCatching { player.stop() }
        player.release()
    }
}

private fun PlayerManager.closeConditionalHttpFactory() {
    conditionalHttpFactory?.close()
}

private fun PlayerManager.releasePlaybackScopes() {
    mainScope.cancel()
    playbackStatsOwner.cancelSharedScopeAfterWrites()
}

private fun PlayerManager.clearPlaybackStateAfterRelease() {
    _isPlayingFlow.value = false
    _currentMediaUrl.value = null
    _currentPlaybackAudioInfo.value = null
    currentMediaUrlResolvedAtMs = 0L
    setCurrentSongForPlayback(null)
    publishCurrentQueue(emptyList(), -1)
    shuffleRestorePlaylistReference = null
    shuffleRestoreCurrentIndex = -1
    clearPendingSeekPosition()
    _playbackPositionMs.value = 0L

    consecutivePlayFailures = 0
}

private fun PlayerManager.finishPlaybackRelease() {
    initialized = false
    _localPlaylistsReadyFlow.value = false
    AudioReactive.onEnabledChanged = null
    lastRequiresPcmAudioProcessing = null
    runCatching { closeConditionalHttpFactory() }
        .onFailure { NPLogger.w("NERI-PlayerManager", "release(): final conditional factory close failed", it) }
    conditionalHttpFactory = null
    UsbExclusiveSessionController.forceStopAllSessions("player_release_finally")
    UsbExclusiveSystemSoundGuard.forceRelease(application, "player_release_finally")
    StartupAudioFocusController.forceRelease("player_release_finally")
}

/**
 * 歌词来源相关设置变更后必须清空全部歌词缓存。
 *
 * 这些缓存都没有 TTL, 只靠设置变化时主动失效来维持一致, 否则改完设置后
 * 当前歌曲会继续沿用旧来源的歌词, 表现为"设置没生效"。
 */
private fun PlayerManager.evictLyricCachesForSourcePreferenceChange() {
    PlayerLyricsProvider.clearLyricsCaches(neteaseLyricsCache, ytMusicLyricsCache)
    _lyricsPreferenceRevisionFlow.update { it + 1L }
    syncExternalBluetoothLyrics(_currentSongFlow.value)
}
