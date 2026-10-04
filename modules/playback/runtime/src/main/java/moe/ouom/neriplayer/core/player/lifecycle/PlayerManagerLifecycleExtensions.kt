@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsTracker

import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity

import moe.ouom.neriplayer.data.settings.playback.toPlaybackSoundConfig
import moe.ouom.neriplayer.data.ltw.validation.format

import android.app.Application
import android.os.SystemClock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.biliCookieRepo
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.lyrics.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.currentPositionMsOr
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.audio.route.AudioDeviceRouteOwner
import moe.ouom.neriplayer.core.player.audio.route.PlayerManagerAudioDeviceRoutePort
import moe.ouom.neriplayer.core.player.debug.playWhenReadyChangeReasonName
import moe.ouom.neriplayer.core.player.debug.playbackSuppressionReasonName
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.audio.reactive.AudioReactive
import moe.ouom.neriplayer.core.player.audio.processing.PlaybackVolumeNormalizationState
import moe.ouom.neriplayer.core.player.engine.ReactiveRenderersFactory
import moe.ouom.neriplayer.core.player.engine.datasource.ConditionalHttpDataSourceFactory
import moe.ouom.neriplayer.core.player.lyrics.FloatingLyricsOverlayManager
import moe.ouom.neriplayer.core.player.lyrics.attachLiveLyricSurfaces
import moe.ouom.neriplayer.core.player.lyrics.clearExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.lyrics.releaseLiveLyricSurfaces
import moe.ouom.neriplayer.core.player.lyrics.syncExternalBluetoothLyrics
import moe.ouom.neriplayer.core.player.lyrics.syncExternalTranslatedLyrics
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.service.notification.FlymeStatusBarLyricSupport
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.applyRestoredStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.applyCommittedLyricOverrides
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.core.player.persistence.restoreState
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.AppPlaybackStatsWritePort
import moe.ouom.neriplayer.core.player.persistence.stats.AppPlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsOwner
import moe.ouom.neriplayer.core.player.playback.advanceAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.playback.clearAudioRouteMuteSuppression
import moe.ouom.neriplayer.core.player.playback.playImpl
import moe.ouom.neriplayer.core.player.playback.startProgressUpdates
import moe.ouom.neriplayer.core.player.playback.suppressPlaybackForAudioRouteLoss
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.command.shouldClearResumePlaybackRequestOnPlayWhenReadyPause
import moe.ouom.neriplayer.core.player.policy.command.shouldResumeSilentlyForListenTogetherNoisyPause
import moe.ouom.neriplayer.core.player.policy.offload.pcmAudioRequirements
import moe.ouom.neriplayer.core.player.policy.offload.shouldUpdateAudioOffloadForReactiveChange
import moe.ouom.neriplayer.core.player.policy.pending.shouldAcceptPlayerCallback
import moe.ouom.neriplayer.core.player.policy.pending.shouldExposePlayerCallbackState
import moe.ouom.neriplayer.core.player.audio.wake.PlaybackTransitionWakeLock
import moe.ouom.neriplayer.core.player.prefetch.prefetchNextGenericTrackUrl
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeSeekRefreshPolicy
import moe.ouom.neriplayer.core.player.url.currentPlaybackCacheKeyForRecovery
import moe.ouom.neriplayer.core.player.url.invalidateCachedResourceForPlaybackRecovery
import moe.ouom.neriplayer.core.player.url.shouldAdvanceAfterStuckTrackEnd
import moe.ouom.neriplayer.core.player.url.shouldAttemptUrlRefresh
import moe.ouom.neriplayer.core.player.url.shouldInvalidateCacheAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.url.shouldInvalidateCacheForPlaybackRecovery
import moe.ouom.neriplayer.core.player.url.shouldRecoverMissingLocalPlayback
import moe.ouom.neriplayer.core.player.url.shouldTreatPlaybackFailureAsTrackEnd
import moe.ouom.neriplayer.core.player.url.youtubePlaybackRecoveryStrategyForError
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.recovery.PlayerManagerUsbExclusiveLivenessPort
import moe.ouom.neriplayer.core.player.usb.recovery.PlayerManagerUsbInterruptedPlaybackPort
import moe.ouom.neriplayer.core.player.usb.recovery.UsbExclusiveLivenessOwner
import moe.ouom.neriplayer.core.player.usb.recovery.UsbInterruptedPlaybackOwner
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbSinkRoutePort
import moe.ouom.neriplayer.core.player.usb.route.UsbSinkRouteOwner
import moe.ouom.neriplayer.core.player.usb.route.UsbRouteTransitionOwner
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbSystemAudioRoutePort
import moe.ouom.neriplayer.core.player.usb.route.UsbSystemAudioRouteOwner
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbPlaybackRoutePort
import moe.ouom.neriplayer.core.player.usb.route.UsbPlaybackRouteOwner
import moe.ouom.neriplayer.core.player.usb.route.AndroidUsbPlaybackNativeRoutePort
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.core.player.watchdog.cancelPlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.clearActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackRuntimeWatchdog
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackRuntimeWatchdog
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.trySwitchToNextPlaybackCandidateForRecovery
import moe.ouom.neriplayer.data.settings.lyrics.LyricSourcePreferencePolicy
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.storage.CacheSizePolicy
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.usb.toUsbExclusivePreferences
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
            observeCommittedLyricOverrides()
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
    playbackSoundOwner.rebindScopes(mainScope, ioScope)
    playbackQualityOwner.rebindScope(ioScope)
    playbackTransportOwner.rebindScope(mainScope)

    stateFile = File(app.filesDir, "last_playlist.json")
    playbackStateFile = File(app.filesDir, "last_playback_state.json")
    statePersistenceWriter.invalidate()
    queueSessionBindings.prepareForNewEngine()
    lastStatePersistAtMs = 0L
    playbackProgressOwner.resetPersistenceClock()
    AppPlaybackStatsPendingWrites.bind(app)
    AppPlaybackStatsPendingWrites.queue.activate(AppPlaybackStatsWritePort)
    playbackStatsOwner = PlaybackStatsOwner(
        ioScope, AppPlaybackStatsWritePort, PlaybackStatsTracker(AppQueueSongIdentity::stableKey, readClearedAt = AppPlaybackStatsWritePort::clearedAt),
        AppPlaybackStatsPendingWrites.queue
    )
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
    val pendingUsbPreference = usbSinkRouteOwner.pendingPreferenceReconfiguration
    val lastUsbSinkReconfigurationAtMs = usbSinkRouteOwner.lastReconfiguredAtMs
    usbSinkRouteOwner.cancel()
    usbSinkRouteOwner = UsbSinkRouteOwner(
        mainScope,
        PlayerManagerUsbSinkRoutePort,
        SystemClock::elapsedRealtime,
        initialPendingPreferenceReconfiguration = pendingUsbPreference,
        initialLastReconfiguredAtMs = lastUsbSinkReconfigurationAtMs
    )
    val routeGeneration = usbRouteTransitionOwner.generation
    val recoveryAttempts = usbRouteTransitionOwner.recoveryAttempts
    usbRouteTransitionOwner.cancelRouteJobs()
    usbRouteTransitionOwner = UsbRouteTransitionOwner(
        mainScope,
        onToggleTimeout = { reason -> markUsbExclusivePlaybackPreparing(false, reason) },
        initialGeneration = routeGeneration,
        initialRecoveryAttempts = recoveryAttempts
    )
    usbSystemAudioRouteOwner = UsbSystemAudioRouteOwner(
        usbRouteTransitionOwner,
        usbSinkRouteOwner,
        PlayerManagerUsbSystemAudioRoutePort,
        SystemClock::elapsedRealtime
    )
    usbPlaybackRouteOwner = UsbPlaybackRouteOwner(
        mainScope,
        usbRouteTransitionOwner,
        usbSinkRouteOwner,
        usbSystemAudioRouteOwner,
        PlayerManagerUsbPlaybackRoutePort,
        AndroidUsbPlaybackNativeRoutePort
    )
    audioDeviceRouteOwner.release()
    audioDeviceRouteOwner = AudioDeviceRouteOwner(mainScope, PlayerManagerAudioDeviceRoutePort)
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
    playbackSoundOwner.restoreInitialPreferences(
        initialPlaybackPreferences.toPlaybackSoundConfig(),
        initialPlaybackPreferences.playbackHighResolutionOutputEnabled
    )
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
    val okHttpClient = PlayerDependencies.repositories.sharedOkHttpClient
    val upstreamFactory: HttpDataSource.Factory = OkHttpDataSource.Factory(okHttpClient)
    val conditionalFactory = ConditionalHttpDataSourceFactory(
        upstreamFactory,
        biliCookieRepo,
        PlayerDependencies.repositories.youtubeAuthRepo,
        trafficStatsRepository = PlayerDependencies.repositories.trafficStatsRepo
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
    playbackSoundOwner.attachPlayer(player)
    applyPlaybackSoundConfig(playbackSoundConfig, persist = false)
    applyAudioFocusPolicy()
    applyUsbExclusivePlaybackPolicy()
    _playWhenReadyFlow.value = player.playWhenReady
    _playerPlaybackStateFlow.value = player.playbackState

    AudioReactive.setEnabledChangeListener { enabled ->
        mainScope.launch {
            val currentAudioReactiveEnabled = AudioReactive.enabled
            val playbackActive = isTransportActiveWithoutInitialization()
            if (
                shouldUpdateAudioOffloadForReactiveChange(
                    audioReactiveEnabled = enabled,
                    playbackActive = playbackActive,
                    currentAudioReactiveEnabled = currentAudioReactiveEnabled
                )
            ) {
                updateAudioOffloadPreferences("audio_reactive_$enabled")
            } else {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "skip audio offload preference update: " +
                        "audioReactive=$enabled currentAudioReactive=$currentAudioReactiveEnabled " +
                        "playbackActive=$playbackActive"
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
                currentSong?.let(PlayerDependencies.downloads::invalidateCompletedAudioReference)
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
                        CoreCommonR.string.player_playback_failed_with_code,
                        error.errorCodeName
                    )
                }
                cause?.message?.contains("no protocol: null", ignoreCase = true) == true ->
                    getLocalizedString(CoreCommonR.string.player_playback_invalid_url)
                error.errorCode ==
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    getLocalizedString(CoreCommonR.string.player_playback_network_error)
                else ->
                    getLocalizedString(
                        CoreCommonR.string.player_playback_failed_with_code,
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
            queueStore.setShuffleMode(shuffleModeEnabled)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncExoRepeatMode()
            _repeatModeFlow.value = repeatModeSetting
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            playbackSoundOwner.onAudioSessionIdChanged(audioSessionId)
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
                PlayerDependencies.repositories.trafficStatsRepo.recordCacheHitBytes(cachedBytesRead)
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
    attachLiveLyricSurfaces()
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
            // 设备不支持时状态栏歌词整体失效：用户可能从备份里恢复出 enabled=true，
            // 若不在这里收口，歌词链路仍会加载并空转。
            statusBarLyricsEnable = enabled && FlymeStatusBarLyricSupport.isSupported
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

private fun PlayerManager.observeCommittedLyricOverrides() {
    ioScope.launch {
        try {
            val storage = SecureTokenStorage(application)
            storage.lyricOverridesVersion.collect {
                try {
                    applyCommittedLyricOverrides(storage)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    NPLogger.e("NERI-PlayerManager", "Failed to refresh committed lyric overrides", error)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.e("NERI-PlayerManager", "Failed to observe committed lyric overrides", error)
        }
    }
}

private fun PlayerManager.completeInitialization(
    restoredStateSnapshot: RestoredPlayerStateSnapshot?,
    effectiveMaxCacheSize: Long
) {
    audioDeviceRouteOwner.register()
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
        audioDeviceRouteOwner.release()
    }
    rollbackInitializationStep("closed conditional http factory", "close conditional factory") {
        conditionalHttpFactory?.close()
    }
    conditionalHttpFactory = null
    rollbackInitializationStep("released player", "release player") {
        releasePlayerAfterFailedInitialization()
    }
    rollbackInitializationStep("released playback effects", "release effects") {
        playbackSoundOwner.releaseEngine()
    }
    rollbackInitializationStep("released cache", "release cache") { releaseMediaCache() }
    rollbackInitializationStep("cancelled mainScope", "cancel mainScope") { mainScope.cancel() }
    rollbackInitializationStep("cancelled ioScope", "cancel ioScope") { ioScope.cancel() }
    rollbackInitializationStep("released lyricon", "release lyricon") { LyriconManager.release() }
    rollbackInitializationStep("released live lyrics", "release live lyrics") { releaseLiveLyricSurfaces() }
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
                getLocalizedString(CoreCommonR.string.cache_clear_complete)
            } else {
                getLocalizedString(CoreCommonR.string.settings_cache_empty)
            }
            Pair(true, msg)
        } catch (e: Exception) {
            NPLogger.e("NERI-Player", "Clear cache failed", e)
            Pair(
                false,
                getLocalizedString(
                    CoreCommonR.string.toast_cache_clear_error,
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

internal fun PlayerManager.handleAudioBecomingNoisyImpl(): Boolean =
    audioDeviceRouteOwner.onAudioBecomingNoisy()

private fun PlayerManager.handleUsbExclusivePlaybackSettingChanged(enabled: Boolean) {
    usbPlaybackRouteOwner.changeSetting(enabled)
}

private fun PlayerManager.handleUsbExclusivePreferencesChanged(preferences: UsbExclusivePreferences) {
    usbPlaybackRouteOwner.changePreferences(preferences)
}

internal fun PlayerManager.retryUsbExclusivePlayback(reason: String) {
    usbPlaybackRouteOwner.retry(reason)
}

internal fun PlayerManager.scheduleUsbExclusiveTransportRecovery(reason: String) {
    usbPlaybackRouteOwner.scheduleTransportRecovery(reason)
}

internal fun PlayerManager.markUsbExclusiveNativePathActive(reason: String) {
    usbPlaybackRouteOwner.markNativePathActive(reason)
}

internal fun PlayerManager.tryRecoverUsbExclusivePlaybackAfterNativeTransferFailure(
    reason: String,
    runtimeReport: String
): Boolean = usbPlaybackRouteOwner.recoverAfterTransferFailure(reason, runtimeReport)

internal fun PlayerManager.stopPlaybackAfterUsbExclusiveNativeFailure(reason: String) {
    usbPlaybackRouteOwner.stopAfterNativeFailure(reason)
}

internal fun PlayerManager.prepareUsbExclusiveRouteForManualPlayback(reason: String): Boolean =
    usbPlaybackRouteOwner.prepareManualPlayback(reason)

fun PlayerManager.updateUsbExclusiveForegroundState(
    foreground: Boolean,
    reason: String
) {
    usbExclusiveLivenessOwner.updateForegroundState(foreground, reason)
}

internal fun PlayerManager.recoverUsbExclusivePlaybackIfUnhealthy(
    reason: String,
    forceRecovery: Boolean = false
): Boolean = usbPlaybackRouteOwner.recoverIfUnhealthy(reason, forceRecovery)

fun PlayerManager.recoverUsbExclusivePlaybackOnForeground(reason: String) {
    usbExclusiveLivenessOwner.recoverOnForeground(reason)
}

internal fun PlayerManager.cancelUsbExclusiveRecovery(reason: String) {
    usbExclusiveLivenessOwner.cancelJobs()
    NPLogger.d("NERI-UsbExclusive", "cancelUsbExclusiveRecovery(): reason=$reason")
}

internal fun PlayerManager.resumeInterruptedUsbExclusivePlaybackIfNeeded(reason: String): Boolean =
    usbInterruptedPlaybackOwner.resumeOnSystemRouteIfNeeded(reason)

internal fun PlayerManager.scheduleUsbExclusivePlaybackResumeAfterDeviceAttach(reason: String) {
    usbInterruptedPlaybackOwner.scheduleResumeAfterDeviceAttach(reason)
}

internal fun PlayerManager.clearUsbExclusiveInterruptedPlaybackIntent(reason: String) {
    usbInterruptedPlaybackOwner.clear(reason)
}

internal fun PlayerManager.applyUsbExclusivePlaybackPolicy(
    reconfigureAudioSink: Boolean = false,
    reconfigureReason: String = "usb_policy_changed",
    allowReconfigureWhilePlaying: Boolean = false
) {
    usbPlaybackRouteOwner.applyPolicy(reconfigureAudioSink, reconfigureReason, allowReconfigureWhilePlaying)
}

internal fun PlayerManager.scheduleUsbAudioSinkReconfiguration(
    reason: String,
    allowWhilePlaybackActive: Boolean = false,
    bypassCooldown: Boolean = false
) {
    usbSinkRouteOwner.schedule(reason, allowWhilePlaybackActive, bypassCooldown)
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
    playbackTransportOwner.resetForRelease()
    cancelPlaybackStartupWatchdog(reason = "release")
    resetPlaybackRuntimeWatchdog(reason = "release")
    clearActivePlaybackCandidates()
    StartupAudioFocusController.release("player_release")

    audioDeviceRouteOwner.release()
}

private fun PlayerManager.stopPlaybackForRelease() {
    stopProgressUpdates()
    cancelVolumeFade(resetToFull = true)
    clearAudioRouteMuteSuppression(
        reason = "release",
        preserveExplicitRestore = false
    )
    cancelPendingPauseRequest(resetVolumeToFull = true)
    audioDeviceRouteOwner.cancelBluetoothDisconnectPause()
    flushPlaybackStatsAsync("release", stopTracking = true)
}

private fun PlayerManager.releaseUsbSessionsAndJobs() {
    playbackSoundOwner.cancelPersistenceForRelease()
    usbPlaybackRouteOwner.release()
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
    releaseLiveLyricSurfaces()
    FloatingLyricsOverlayManager.release()
    LyriconManager.release()
}

private fun PlayerManager.releasePlayerEngine() {
    releasePlayerIfInitialized()
    playbackSoundOwner.releaseEngine()
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
    clearPendingSeekPosition()
    _playbackPositionMs.value = 0L

    consecutivePlayFailures = 0
}

private fun PlayerManager.finishPlaybackRelease() {
    initialized = false
    _localPlaylistsReadyFlow.value = false
    AudioReactive.setEnabledChangeListener(null)
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
