@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

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
 * File: moe.ouom.neriplayer.core.player/PlayerManager
 * Updated: 2025/8/16
 */


import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.core.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.effects.AudioReactive
import moe.ouom.neriplayer.core.player.effects.PlaybackEffectsController
import moe.ouom.neriplayer.core.player.engine.datasource.ConditionalHttpDataSourceFactory
import moe.ouom.neriplayer.core.player.lifecycle.clearCacheImpl
import moe.ouom.neriplayer.core.player.lifecycle.ensureInitializedImpl
import moe.ouom.neriplayer.core.player.lifecycle.handleAudioBecomingNoisyImpl
import moe.ouom.neriplayer.core.player.lifecycle.initializeImpl
import moe.ouom.neriplayer.core.player.lifecycle.releaseImpl
import moe.ouom.neriplayer.core.player.lifecycle.scheduleUsbAudioSinkReconfiguration
import moe.ouom.neriplayer.core.player.lifecycle.updateAudioOffloadPreferences
import moe.ouom.neriplayer.core.player.lyrics.LyriconUpdateCoordinator
import moe.ouom.neriplayer.core.player.media.LocalPlaybackMediaResolver
import moe.ouom.neriplayer.core.player.media.PlaybackMediaItemFactory
import moe.ouom.neriplayer.core.player.lyrics.syncExternalBluetoothLyrics
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.core.player.model.DEFAULT_PLAYBACK_VOLUME_NORMALIZATION_ENABLED
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PreferredQualityKeys
import moe.ouom.neriplayer.core.player.model.forSource
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.PlaybackSoundState
import moe.ouom.neriplayer.core.player.model.PlayerQueueDisplayState
import moe.ouom.neriplayer.core.player.model.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.model.PlayerQueueStateStore
import moe.ouom.neriplayer.core.player.model.RestoredPlaybackState
import moe.ouom.neriplayer.core.player.model.PlaybackUrlCandidate
import moe.ouom.neriplayer.core.player.model.PlayerEvent
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.model.buildPlayerQueueDisplayState
import moe.ouom.neriplayer.core.player.policy.progress.LONG_FORM_PLAYBACK_MIN_DURATION_MS
import moe.ouom.neriplayer.core.player.policy.progress.resolveLongFormPlaybackPositionForPersistence
import moe.ouom.neriplayer.core.player.policy.progress.resolveLongFormPlaybackResumePosition
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.metadata.NeteaseLyricsCacheEntry
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.metadata.YouTubeMusicLyricsCacheEntry
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.core.player.model.normalizePlaybackVolumeBalance
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDebugLogger
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommand
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.command.LocalRoomControlRestriction
import moe.ouom.neriplayer.core.player.policy.command.resolveLocalRoomControlRestriction
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshInFlightController
import moe.ouom.neriplayer.core.player.policy.storage.RestorableLocalMediaState
import moe.ouom.neriplayer.core.player.policy.usb.UsbAudioSinkReconfigurationSnapshot
import moe.ouom.neriplayer.core.player.prefetch.GenericUrlPrefetchCache
import moe.ouom.neriplayer.core.player.prefetch.PlaybackDemandArbiter
import moe.ouom.neriplayer.core.player.prefetch.clearPlaybackDemandCacheKey
import moe.ouom.neriplayer.core.player.prefetch.prefetchYouTubePlayableUrlWindowImpl
import moe.ouom.neriplayer.core.player.prefetch.prefetchYouTubeQueueWindowImpl
import moe.ouom.neriplayer.core.player.policy.refresh.YouTubePlaybackRecoveryStrategy
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingMediaLoadPosition
import moe.ouom.neriplayer.core.player.policy.command.resolvePlaybackSoundConfigForEngine
import moe.ouom.neriplayer.core.player.policy.command.resolveExoRepeatMode
import moe.ouom.neriplayer.core.player.policy.wake.DEFAULT_PLAYBACK_WAKE_MODE
import moe.ouom.neriplayer.core.player.policy.wake.resolvePlaybackWakeMode
import moe.ouom.neriplayer.core.player.policy.command.shouldShowPauseButtonForPlaybackControls
import moe.ouom.neriplayer.core.player.policy.command.shouldBootstrapPlaybackServiceOnAppLaunch
import moe.ouom.neriplayer.core.player.policy.command.shouldRunPlaybackServiceInForeground
import moe.ouom.neriplayer.core.player.playback.applyListenTogetherPlaybackModeImpl
import moe.ouom.neriplayer.core.player.playback.cancelPendingPauseRequestImpl
import moe.ouom.neriplayer.core.player.playback.cancelVolumeFadeImpl
import moe.ouom.neriplayer.core.player.playback.cycleRepeatModeImpl
import moe.ouom.neriplayer.core.player.playback.handleTrackEndedIfNeededImpl
import moe.ouom.neriplayer.core.player.playback.nextImpl
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.core.player.quality.effectiveYouTubeQuality
import moe.ouom.neriplayer.core.player.playback.PlaybackStatsOwner
import moe.ouom.neriplayer.core.player.playback.AppPlaybackStatsWritePort
import moe.ouom.neriplayer.core.player.playback.playBiliVideoPartsImpl
import moe.ouom.neriplayer.core.player.playback.playImpl
import moe.ouom.neriplayer.core.player.playback.playPlaylistImpl
import moe.ouom.neriplayer.core.player.playback.previousImpl
import moe.ouom.neriplayer.core.player.playback.restoreAudioRouteMuteImpl
import moe.ouom.neriplayer.core.player.playback.seekToImpl
import moe.ouom.neriplayer.core.player.playback.setShuffleImpl
import moe.ouom.neriplayer.core.player.playback.stopPlaybackPreservingQueueImpl
import moe.ouom.neriplayer.core.player.playback.stopProgressUpdatesImpl
import moe.ouom.neriplayer.core.player.playback.togglePlayPauseImpl
import moe.ouom.neriplayer.core.player.playback.trackEndDeduplicationKey
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.addCurrentToFavoritesImpl
import moe.ouom.neriplayer.core.player.persistence.addCurrentToPlaylistImpl
import moe.ouom.neriplayer.core.player.persistence.addToQueueEndImpl
import moe.ouom.neriplayer.core.player.persistence.addToQueueNextImpl
import moe.ouom.neriplayer.core.player.persistence.applyRemoteQueueUpdateImpl
import moe.ouom.neriplayer.core.player.persistence.getLyricsImpl
import moe.ouom.neriplayer.core.player.persistence.getPreferredLyricSourceResultImpl
import moe.ouom.neriplayer.core.player.persistence.getNeteaseLyricsImpl
import moe.ouom.neriplayer.core.player.persistence.getNeteaseRomanizedLyricsImpl
import moe.ouom.neriplayer.core.player.persistence.getNeteaseTranslatedLyricsImpl
import moe.ouom.neriplayer.core.player.persistence.getPreferredNeteaseLyricContentImpl
import moe.ouom.neriplayer.core.player.persistence.getPreferredNeteaseRomanizedLyricContentImpl
import moe.ouom.neriplayer.core.player.persistence.getRomanizedLyricsImpl
import moe.ouom.neriplayer.core.player.persistence.getTranslatedLyricsImpl
import moe.ouom.neriplayer.core.player.persistence.hasItemsImpl
import moe.ouom.neriplayer.core.player.persistence.hydrateSongMetadataImpl
import moe.ouom.neriplayer.core.player.persistence.persistStateImpl
import moe.ouom.neriplayer.core.player.persistence.PlaybackStatePersistenceCoordinator
import moe.ouom.neriplayer.core.player.persistence.PlaybackStatePersistenceSnapshot
import moe.ouom.neriplayer.core.player.persistence.PlaybackStateWriter
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.persistence.playBiliVideoAsAudioImpl
import moe.ouom.neriplayer.core.player.persistence.playFromQueueImpl
import moe.ouom.neriplayer.core.player.persistence.rebaseUserLyricOffsetsForSourceImpl
import moe.ouom.neriplayer.core.player.persistence.removeCurrentFromFavoritesImpl
import moe.ouom.neriplayer.core.player.persistence.removeQueueItemImpl
import moe.ouom.neriplayer.core.player.persistence.replaceCurrentInQueueAndPlayImpl
import moe.ouom.neriplayer.core.player.persistence.replaceMetadataFromSearchImpl
import moe.ouom.neriplayer.core.player.persistence.resumeRestoredPlaybackIfNeededImpl
import moe.ouom.neriplayer.core.player.persistence.moveQueueItemImpl
import moe.ouom.neriplayer.core.player.persistence.reorderQueueImpl
import moe.ouom.neriplayer.core.player.persistence.suppressFutureAutoResumeForCurrentSessionImpl
import moe.ouom.neriplayer.core.player.persistence.toggleCurrentFavoriteImpl
import moe.ouom.neriplayer.core.player.persistence.updateSongCustomInfoImpl
import moe.ouom.neriplayer.core.player.persistence.updateSongLyricsAndTranslationImpl
import moe.ouom.neriplayer.core.player.persistence.updateSongLyricsImpl
import moe.ouom.neriplayer.core.player.persistence.updateSongTranslatedLyricsImpl
import moe.ouom.neriplayer.core.player.persistence.updateUserLyricOffsetImpl
import moe.ouom.neriplayer.core.player.timer.SleepTimerManager
import moe.ouom.neriplayer.core.player.timer.SleepTimerMode
import moe.ouom.neriplayer.core.player.url.YOUTUBE_PLAYBACK_PREFER_M4A
import moe.ouom.neriplayer.core.player.url.refreshCurrentSongUrlImpl
import moe.ouom.neriplayer.core.player.url.safeCustomPlaybackCacheKey
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.confirmation.UsbExclusiveLoudPlaybackConfirmation
import moe.ouom.neriplayer.core.player.usb.confirmation.UsbExclusiveLoudPlaybackConfirmationOwner
import moe.ouom.neriplayer.core.player.usb.confirmation.UsbExclusiveLoudPlaybackSignals
import moe.ouom.neriplayer.core.player.usb.confirmation.UsbExclusiveLoudPlaybackSnapshot
import moe.ouom.neriplayer.core.player.usb.confirmation.UsbExclusiveLoudPlaybackSnapshotSource
import moe.ouom.neriplayer.core.player.usb.recovery.PlayerManagerUsbExclusiveLivenessPort
import moe.ouom.neriplayer.core.player.usb.recovery.PlayerManagerUsbInterruptedPlaybackPort
import moe.ouom.neriplayer.core.player.usb.recovery.UsbExclusiveLivenessOwner
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbSinkRoutePort
import moe.ouom.neriplayer.core.player.usb.route.UsbSinkRouteOwner
import moe.ouom.neriplayer.core.player.usb.route.UsbRouteTransitionOwner
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbSystemAudioRoutePort
import moe.ouom.neriplayer.core.player.usb.route.UsbSystemAudioRouteOwner
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbPlaybackRoutePort
import moe.ouom.neriplayer.core.player.usb.route.UsbPlaybackRouteOwner
import moe.ouom.neriplayer.core.player.usb.route.AndroidUsbPlaybackNativeRoutePort
import moe.ouom.neriplayer.core.player.audio.route.AudioDeviceRouteOwner
import moe.ouom.neriplayer.core.player.audio.route.PlayerManagerAudioDeviceRoutePort
import moe.ouom.neriplayer.core.player.usb.recovery.UsbInterruptedPlaybackIntent
import moe.ouom.neriplayer.core.player.usb.recovery.UsbInterruptedPlaybackOwner
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics
import moe.ouom.neriplayer.core.player.watchdog.cancelPlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.clearActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.shouldTreatReadyAtStartAsUnhealthyPrepared
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_KUGOU_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_LRCLIB_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.LyricSourcePreference
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.resolveEffectiveLyricOffsetMs
import moe.ouom.neriplayer.listentogether.mapping.buildStableTrackKey
import moe.ouom.neriplayer.listentogether.mapping.resolvedAudioId
import moe.ouom.neriplayer.listentogether.mapping.resolvedChannelId
import moe.ouom.neriplayer.listentogether.mapping.resolvedPlaylistContextId
import moe.ouom.neriplayer.listentogether.mapping.resolvedSubAudioId
import moe.ouom.neriplayer.listentogether.playback.authoritativeStreamUrlForCurrentTrack
import moe.ouom.neriplayer.listentogether.playback.currentStableKey
import moe.ouom.neriplayer.listentogether.playback.shouldHoldListenTogetherPlaybackForSafetyPause
import moe.ouom.neriplayer.listentogether.playback.shouldMuteListenTogetherListenerForAudioRouteLoss
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherSessionRole
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.playback.stopPlaybackImmediatelyImpl
import moe.ouom.neriplayer.util.platform.LanguageManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference


internal const val PLAYBACK_PROGRESS_UPDATE_INTERVAL_MS = 80L

internal data class LocalPlaylistPlaybackSource(
    val playlistId: Long,
    val songKeys: Set<String>
) {
    fun contains(song: SongItem?): Boolean {
        return song?.stableKey() in songKeys
    }
}

internal fun previousSongForLongFormProgress(previousSong: SongItem?, nextSong: SongItem?): SongItem? {
    if (previousSong == null) return null
    if (previousSong.sameIdentityAs(nextSong)) return null
    return previousSong
}

internal fun localPlaylistIdForSong(source: LocalPlaylistPlaybackSource?, song: SongItem?): Long? {
    val playlist = source ?: return null
    return if (playlist.contains(song)) playlist.playlistId else null
}

@Suppress("ObjectPropertyName", "ktlint:standard:property-naming")
object PlayerManager {
    const val BILI_SOURCE_TAG = PlaybackMediaItemFactory.BILI_SOURCE_TAG
    const val NETEASE_SOURCE_TAG = "Netease"

    @Volatile
    internal var initialized = false

    @Volatile
    internal var initializationInProgress = false
    internal val initializationLock = Any()
    internal lateinit var application: Application
    internal lateinit var player: ExoPlayer
    private var currentWakeMode: Int = DEFAULT_PLAYBACK_WAKE_MODE

    @Volatile
    internal var interactiveNowPlayingVisible: Boolean = false

    @Volatile
    internal var cache: Cache? = null
    internal var conditionalHttpFactory: ConditionalHttpDataSourceFactory? = null

    // Helper function to get localized string
    internal fun getLocalizedString(resId: Int, vararg formatArgs: Any): String {
        val context = LanguageManager.applyLanguage(application)
        return context.getString(resId, *formatArgs)
    }

    internal fun debugStackHint(
        skipFrames: Int = 2,
        maxFrames: Int = 6
    ): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "main"
        }
        return Throwable().stackTrace
            .drop(skipFrames)
            .take(maxFrames)
            .joinToString(" <- ") { frame -> "${frame.fileName}:${frame.lineNumber}" }
    }

    internal fun newIoScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal fun newMainScope() = CoroutineScope(Dispatchers.Main + SupervisorJob())

    internal var ioScope = newIoScope()
    internal var mainScope = newMainScope()
    internal var playbackStatsOwner = PlaybackStatsOwner(ioScope, AppPlaybackStatsWritePort)
    @Volatile
    internal var usbExclusiveLivenessOwner = UsbExclusiveLivenessOwner(mainScope, PlayerManagerUsbExclusiveLivenessPort)
    internal var usbInterruptedPlaybackOwner = UsbInterruptedPlaybackOwner(mainScope, PlayerManagerUsbInterruptedPlaybackPort)
    internal var progressJob: Job? = null
    internal var playbackRuntimeWatchdogJob: Job? = null
    @Volatile
    internal var playbackRuntimeWatchdogToken = 0L
    private val lyriconUpdateCoordinator = LyriconUpdateCoordinator()
    internal var externalBluetoothLyricsLoadJob: Job? = null
    internal var externalBluetoothTranslationLoadJob: Job? = null
    internal var volumeFadeJob: Job? = null
    internal var pendingPauseJob: Job? = null
        set(value) {
            field = value
            syncPlaybackControlPlayingState()
        }
    internal var playbackStartupWatchdogJob: Job? = null
    @Volatile
    internal var playbackStartupWatchdogToken = 0L
    internal var audioDeviceRouteOwner = AudioDeviceRouteOwner(mainScope, PlayerManagerAudioDeviceRoutePort)
    @Volatile
    internal var audioRouteMuteRestoreVolume: Float? = null
    @Volatile
    internal var audioRouteMuteRequiresExplicitRestore = false
    internal val _audioRouteMuteSuppressedFlow = MutableStateFlow(false)
    val audioRouteMuteSuppressedFlow: StateFlow<Boolean> = _audioRouteMuteSuppressedFlow
    @Volatile
    internal var listenTogetherSafetyPausePendingResume = false
    @Volatile
    internal var listenTogetherSafetyResumeInFlight = false
    internal var playbackSoundPersistJob: Job? = null
    internal var playbackSoundApplyJob: Job? = null
    internal var lastRequiresPcmAudioProcessing: Boolean? = null
    internal var usbSinkRouteOwner = UsbSinkRouteOwner(
        mainScope,
        PlayerManagerUsbSinkRoutePort,
        SystemClock::elapsedRealtime
    )
    internal var usbRouteTransitionOwner = UsbRouteTransitionOwner(
        mainScope,
        onToggleTimeout = { reason -> markUsbExclusivePlaybackPreparing(false, reason) }
    )
    internal var usbSystemAudioRouteOwner = UsbSystemAudioRouteOwner(
        usbRouteTransitionOwner,
        usbSinkRouteOwner,
        PlayerManagerUsbSystemAudioRoutePort,
        SystemClock::elapsedRealtime
    )
    internal var usbPlaybackRouteOwner = UsbPlaybackRouteOwner(
        mainScope,
        usbRouteTransitionOwner,
        usbSinkRouteOwner,
        usbSystemAudioRouteOwner,
        PlayerManagerUsbPlaybackRoutePort,
        AndroidUsbPlaybackNativeRoutePort
    )
    internal val usbExclusiveInterruptedPlaybackIntent: UsbInterruptedPlaybackIntent?
        get() = usbInterruptedPlaybackOwner.intent
    internal var pendingPlaybackSoundConfig: PlaybackSoundConfig? = null
    internal var neteaseQualityRefreshJob: Job? = null
    internal var youtubeQualityRefreshJob: Job? = null
    internal var biliQualityRefreshJob: Job? = null

    internal val localRepo: LocalPlaylistRepository
        get() = LocalPlaylistRepository.getInstance(application)

    internal lateinit var stateFile: File
    internal lateinit var playbackStateFile: File

    internal var preferredQuality: String = "exhigh"
        set(value) {
            field = value
            publishPreferredQualityKeys()
        }
    internal var youtubePreferredQuality: String = "high"
        set(value) {
            field = value
            publishPreferredQualityKeys()
        }
    internal var biliPreferredQuality: String = "high"
        set(value) {
            field = value
            publishPreferredQualityKeys()
        }

    private val _preferredQualityKeys = MutableStateFlow(PreferredQualityKeys())

    /**
     * 各平台的音质偏好, 供切换弹窗回显
     *
     * 播放页弹窗改的是全局偏好, 选中项不能拿当前流实测出来的档位充数
     * 否则平台只发得出低码率时弹窗会显示低档, 用户点一下就把默认设置改掉了
     */
    val preferredQualityKeys: StateFlow<PreferredQualityKeys> =
        _preferredQualityKeys.asStateFlow()

    private fun publishPreferredQualityKeys() {
        _preferredQualityKeys.value = PreferredQualityKeys(
            netease = preferredQuality,
            youtube = youtubePreferredQuality,
            bili = biliPreferredQuality
        )
    }
    internal var mobileDataFollowDefaultAudioQuality = true
    internal var mobileDataNeteaseAudioQuality: String = "standard"
    internal var mobileDataYouTubeAudioQuality: String = "low"
    internal var mobileDataBiliAudioQuality: String = "low"
    internal var playbackFadeInEnabled = true
    internal var playbackCrossfadeNextEnabled = true
    internal var playbackFadeInDurationMs = DEFAULT_FADE_DURATION_MS
    internal var playbackFadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    internal var playbackCrossfadeInDurationMs = DEFAULT_FADE_DURATION_MS
    internal var playbackCrossfadeOutDurationMs = DEFAULT_FADE_DURATION_MS
    @Volatile
    internal var playbackSoundConfig = PlaybackSoundConfig()
    internal var playbackHighResolutionOutputEnabled = false
    internal var lyriconEnabled = false
    @Volatile
    internal var amllLyricsEnabled = false
    @Volatile
    internal var preferWordTimedLyrics = true
    @Volatile
    internal var defaultLyricSource = LyricSourcePreference.Automatic
    internal var statusBarLyricsEnable = false
    internal var externalBluetoothLyricsEnabled = false
    internal var externalBluetoothTranslationEnabled = false
    internal var dynamicIslandLyricsEnabled = false
    internal var floatingLyricsEnabled = false
    internal var floatingLyricsShowTranslation = true
    internal var cloudMusicLyricDefaultOffsetMs = DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
    internal var qqMusicLyricDefaultOffsetMs = DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
    internal var kugouLyricDefaultOffsetMs = DEFAULT_KUGOU_LYRIC_OFFSET_MS
    internal var lrclibLyricDefaultOffsetMs = DEFAULT_LRCLIB_LYRIC_OFFSET_MS
    internal var amllTtmlLyricDefaultOffsetMs = DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS
    @Volatile
    internal var biliSkipSegmentPromptEnabled = false
    internal var keepLastPlaybackProgressEnabled = true
    internal var rememberLongFormPlaybackProgressEnabled = true
    internal var keepPlaybackModeStateEnabled = true
    @Volatile
    internal var neteaseAutoSourceSwitchEnabled = false
    @Volatile
    internal var neteaseLocalSourceFallbackEnabled = false
    internal var stopOnBluetoothDisconnectEnabled = true
    @Volatile
    internal var usbExclusivePlaybackEnabled = false
    internal val usbExclusiveAppInForeground: Boolean
        get() = usbExclusiveLivenessOwner.appInForeground
    @Volatile
    internal var usbExclusivePreferences = UsbExclusivePreferences()
    internal var allowMixedPlaybackEnabled = false

    private val queueStore = PlayerQueueStateStore()
    internal val currentPlaylist: List<SongItem>
        get() = queueStore.snapshot().playlist
    internal var currentIndex: Int
        get() = queueStore.snapshot().currentIndex
        set(value) {
            queueStore.select(value)
        }
    internal fun currentQueueSnapshot(): PlayerQueueSnapshot = queueStore.snapshot()

    internal fun publishCurrentQueue(
        playlist: List<SongItem>,
        currentIndex: Int,
        bumpDisplayRevision: Boolean = false
    ) {
        queueStore.publish(playlist, currentIndex)
        if (bumpDisplayRevision) bumpCurrentQueueDisplayRevision()
    }

    internal fun updateCurrentQueueSongs(
        transform: (List<SongItem>) -> List<SongItem>?
    ): PlayerQueueSnapshot? = queueStore.updatePlaylist(transform)

    internal fun updateCurrentQueue(
        bumpDisplayRevision: Boolean = false,
        transform: (PlayerQueueSnapshot) -> PlayerQueueSnapshot?
    ): PlayerQueueSnapshot? {
        val updated = queueStore.update(transform)
        if (updated != null && bumpDisplayRevision) bumpCurrentQueueDisplayRevision()
        return updated
    }

    internal fun updateQueuedSong(
        song: SongItem,
        transform: (SongItem) -> SongItem?
    ): SongItem? = queueStore.updateSongMatching(song, transform)
    @Volatile
    internal var shuffleRestorePlaylistReference: List<SongItem>? = null
    @Volatile
    internal var shuffleRestoreCurrentIndex = -1

    @Volatile
    internal var consecutivePlayFailures = 0
    internal const val MAX_CONSECUTIVE_FAILURES = 10
    internal const val MEDIA_URL_STALE_MS = 10 * 60 * 1000L
    internal const val URL_REFRESH_COOLDOWN_MS = 10 * 1000L
    internal const val STATE_PERSIST_INTERVAL_MS = 15 * 1000L
    internal const val STATE_PERSIST_DEBOUNCE_MS = 250L
    internal const val DEFAULT_FADE_DURATION_MS = 500L
    internal const val AUTO_TRANSITION_EXTERNAL_PAUSE_GUARD_MS = 2_000L
    internal const val AUTO_TRANSITION_BUFFER_POSITION_GUARD_MS = 1_500L
    internal const val USB_EXCLUSIVE_FOCUS_PAUSE_GUARD_MS = 3_000L
    internal const val PENDING_SEEK_POSITION_TOLERANCE_MS = 1_500L
    internal const val STARTUP_STALL_POSITION_TOLERANCE_MS = 500L
    internal const val STARTUP_STALL_LOCAL_TIMEOUT_MS = 5_000L
    internal const val STARTUP_STALL_REMOTE_TIMEOUT_MS = 10_000L
    internal const val STARTUP_STALL_YOUTUBE_TIMEOUT_MS = 25_000L
    internal const val STARTUP_STALL_YOUTUBE_DEEP_SEEK_TIMEOUT_MS = 5_000L
    internal const val STARTUP_STALL_READY_EARLY_TIMEOUT_MS = 5_000L
    internal const val STARTUP_STALL_BUFFERING_EARLY_TIMEOUT_MS = 5_000L
    internal const val STARTUP_STALL_BUFFERING_GRACE_MS = 2_000L
    internal const val STARTUP_STALL_USB_EARLY_TIMEOUT_MS = 4_000L
    internal const val STARTUP_STALL_MAX_RECOVERY_ATTEMPTS = 3
    internal const val QUALITY_CHANGE_REFRESH_DEBOUNCE_MS = 0L
    internal const val MIN_FADE_STEPS = 4
    internal const val MAX_FADE_STEPS = 30
    internal data class UrlRefreshOperation(
        val deferred: CompletableDeferred<SongUrlResult>,
        val job: Job
    )
    internal val urlRefreshController = RefreshInFlightController<UrlRefreshOperation>()
    @Volatile
    internal var pendingSeekPositionMs: Long = C.TIME_UNSET
    internal var expeditedYouTubeSeekRecoveryPending = false
    internal var playbackPositionGeneration: Long = 0L
    internal var lastUrlRefreshKey: String? = null
    internal var lastUrlRefreshAtMs: Long = 0L
    internal var currentMediaUrlResolvedAtMs: Long = 0L
    internal var currentPlaybackDemandCacheKey: String? = null
    private val restoredPlayback = AtomicReference<RestoredPlaybackState>(RestoredPlaybackState.None)
    internal val restoredResumePositionMs: Long
        get() = restoredPlayback.get().positionMs
    internal val restoredShouldResumePlayback: Boolean
        get() = restoredPlayback.get() is RestoredPlaybackState.ResumePending

    internal fun restoredPlaybackSnapshot(): RestoredPlaybackState = restoredPlayback.get()

    internal fun setRestoredPlayback(positionMs: Long, shouldResume: Boolean) {
        restoredPlayback.set(RestoredPlaybackState.from(positionMs, shouldResume))
    }

    internal fun clearRestoredPlayback() {
        restoredPlayback.set(RestoredPlaybackState.None)
    }

    internal fun consumeRestoredPlayback(expected: RestoredPlaybackState): Boolean =
        restoredPlayback.compareAndSet(expected, RestoredPlaybackState.None)

    internal fun suppressRestoredAutoResume() {
        restoredPlayback.updateAndGet { it.withoutAutoResume() }
    }

    @Volatile
    internal var lastStatePersistAtMs: Long = 0L
    internal val statePersistenceCoordinator = PlaybackStatePersistenceCoordinator<PlaybackStatePersistenceSnapshot>()
    internal val statePersistenceWriter = PlaybackStateWriter()
    internal var lastLongFormPlaybackProgressPersistAtMs: Long = 0L
    internal var lastAutoTrackAdvanceAtMs: Long = 0L
    @Volatile
    internal var lastUsbExclusiveFocusDisruptionAtMs: Long = 0L
    @Volatile
    internal var resumePlaybackRequested = false
        private set(value) {
            field = value
            urlRefreshController.cancelIfPlaybackIntentChanged(value)
            syncPlaybackControlPlayingState()
        }
    @Volatile
    internal var suppressAutoResumeForCurrentSession = false
    @Volatile
    internal var listenTogetherSyncPlaybackRate = 1f

    internal val _currentSongFlow = MutableStateFlow<SongItem?>(null)
    val currentSongFlow: StateFlow<SongItem?> = _currentSongFlow
    internal val _lyricsPreferenceRevisionFlow = MutableStateFlow(0L)
    val lyricsPreferenceRevisionFlow: StateFlow<Long> = _lyricsPreferenceRevisionFlow
    @Volatile
    internal var localPlaylistPlaybackSource: LocalPlaylistPlaybackSource? = null
    internal val playbackDemandArbiter = PlaybackDemandArbiter()

    val currentQueueFlow: StateFlow<List<SongItem>> = queueStore.playlistFlow
    internal val _currentQueueDisplayRevisionFlow = MutableStateFlow(0L)
    val currentQueueDisplayRevisionFlow: StateFlow<Long> = _currentQueueDisplayRevisionFlow

    internal val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

    /**
     * 播放/暂停按钮使用的视觉状态
     * 它跟随用户最近一次播放控制意图, 避免淡入/淡出时播放图标滞后
     */
    internal val _playbackControlPlayingFlow = MutableStateFlow(false)
    val playbackControlPlayingFlow: StateFlow<Boolean> = _playbackControlPlayingFlow

    internal val _playWhenReadyFlow = MutableStateFlow(false)
    val playWhenReadyFlow: StateFlow<Boolean> = _playWhenReadyFlow

    internal val _playerPlaybackStateFlow = MutableStateFlow(Player.STATE_IDLE)
    val playerPlaybackStateFlow: StateFlow<Int> = _playerPlaybackStateFlow

    internal val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionFlow: StateFlow<Long> = _playbackPositionMs

    internal val _playbackDurationMs = MutableStateFlow(0L)
    val playbackDurationFlow: StateFlow<Long> = _playbackDurationMs

    internal val _usbExclusivePlaybackPreparingFlow = MutableStateFlow(false)
    val usbExclusivePlaybackPreparingFlow: StateFlow<Boolean> =
        _usbExclusivePlaybackPreparingFlow

    internal val _shuffleModeFlow = MutableStateFlow(false)
    val shuffleModeFlow: StateFlow<Boolean> = _shuffleModeFlow

    internal val _repeatModeFlow = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatModeFlow: StateFlow<Int> = _repeatModeFlow
    internal var repeatModeSetting: Int = Player.REPEAT_MODE_OFF

    internal val _currentAudioDevice = MutableStateFlow<AudioDevice?>(null)
    val currentAudioDeviceFlow: StateFlow<AudioDevice?> = _currentAudioDevice

    @Volatile
    internal var externalBluetoothLyricsSongKey: String? = null
    @Volatile
    internal var externalBluetoothPreferredLyricSource: LyricSourcePreference? = null
    @Volatile
    private var lyriconPreferredLyricSourceSongKey: String? = null
    @Volatile
    private var lyriconPreferredLyricSource: LyricSourcePreference? = null
    @Volatile
    internal var externalBluetoothLyrics: List<LyricEntry> = emptyList()
    @Volatile
    internal var floatingTranslatedLyrics: List<LyricEntry> = emptyList()
    @Volatile
    internal var floatingTranslationMatchesByIndex: Map<Int, LyricEntry> = emptyMap()
    internal val _externalBluetoothLyricLineFlow = MutableStateFlow<String?>(null)
    val externalBluetoothLyricLineFlow: StateFlow<String?> = _externalBluetoothLyricLineFlow
    internal val _floatingTranslatedLyricLineFlow = MutableStateFlow<String?>(null)
    val floatingTranslatedLyricLineFlow: StateFlow<String?> = _floatingTranslatedLyricLineFlow
    internal val _externalBluetoothLyricPayloadFlow =
        MutableStateFlow(ExternalBluetoothLyricPayload())
    internal val externalBluetoothLyricPayloadFlow: StateFlow<ExternalBluetoothLyricPayload> =
        _externalBluetoothLyricPayloadFlow

    internal val _playerEventFlow = MutableSharedFlow<PlayerEvent>()
    val playerEventFlow: SharedFlow<PlayerEvent> = _playerEventFlow.asSharedFlow()

    private val usbExclusiveLoudPlaybackConfirmationOwner = UsbExclusiveLoudPlaybackConfirmationOwner()
    internal val usbExclusiveLoudPlaybackConfirmationFlow:
        StateFlow<UsbExclusiveLoudPlaybackConfirmation?> =
        usbExclusiveLoudPlaybackConfirmationOwner.confirmationFlow

    internal val _playbackCommandFlow = MutableSharedFlow<PlaybackCommand>(
        extraBufferCapacity = 32
    )
    val playbackCommandFlow: SharedFlow<PlaybackCommand> = _playbackCommandFlow.asSharedFlow()

    /** 当前曲目的解析后媒体地址, 供恢复播放和错误恢复使用 */
    internal val _currentMediaUrl = MutableStateFlow<String?>(null)
    val currentMediaUrlFlow: StateFlow<String?> = _currentMediaUrl

    internal val _currentPlaybackAudioInfo = MutableStateFlow<PlaybackAudioInfo?>(null)
    @Suppress("unused")
    val currentPlaybackAudioInfoFlow: StateFlow<PlaybackAudioInfo?> = _currentPlaybackAudioInfo

    internal val playbackEffectsController = PlaybackEffectsController()
    internal val _playbackSoundState = MutableStateFlow(PlaybackSoundState())
    val playbackSoundStateFlow: StateFlow<PlaybackSoundState> = _playbackSoundState

    /** 本地歌单快照, 供收藏状态和歌单选择弹窗使用 */
    internal val _playlistsFlow = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlistsFlow: StateFlow<List<LocalPlaylist>> = _playlistsFlow
    internal val _localPlaylistsReadyFlow = MutableStateFlow(false)
    internal val localPlaylistsReadyFlow: StateFlow<Boolean> = _localPlaylistsReadyFlow
    internal val localPlaylistsReady: Boolean
        get() = _localPlaylistsReadyFlow.value

    internal var playJob: Job? = null
    internal var currentYouTubePrefetchJob: Job? = null
    internal var currentYouTubePrefetchVideoIds: Set<String> = emptySet()
    internal val youtubeStreamWarmupJobs = ConcurrentHashMap<String, Job>()
    internal val genericUrlPrefetchCache = GenericUrlPrefetchCache()
    internal var currentGenericUrlPrefetchJob: Job? = null
    internal var currentGenericUrlPrefetchKey: String? = null
    @Volatile
    internal var playbackRequestToken = 0L
    @Volatile
    internal var loadedMediaRequestToken = 0L
    internal val _pendingMediaLoadFlow = MutableStateFlow(false)
    val pendingMediaLoadFlow: StateFlow<Boolean> = _pendingMediaLoadFlow
    @Volatile
    internal var pendingMediaLoadActive = false
        set(value) {
            field = value
            _pendingMediaLoadFlow.value = value
        }
    @Volatile
    internal var pendingMediaLoadPositionMs = 0L
    internal var activePlaybackCandidates: List<PlaybackUrlCandidate> = emptyList()
    internal var activePlaybackUrlIndex = 0
    internal var activePlaybackResumePositionMs = 0L
    internal var activePlaybackCommandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    internal var startupStallRecoveryAttempts = 0
    internal var playbackProgressBaselinePositionMs = 0L
    internal var playbackProgressAdvanceReported = false
    internal var playbackRuntimeStallRecoveryAttempts = 0
    internal var playbackRuntimeLastProgressPositionMs = 0L
    internal var playbackRuntimeLastProgressAtElapsedRealtimeMs = 0L
    internal var lastHandledTrackEndKey: String? = null
    internal var lastTrackEndHandledAtMs = 0L
    val audioLevelFlow get() = AudioReactive.level
    val beatImpulseFlow get() = AudioReactive.beat

    val biliRepo by lazy { AppContainer.biliPlaybackRepository }
    val biliClient by lazy { AppContainer.biliClient }
    val neteaseClient by lazy { AppContainer.neteaseClient }
    val youtubeMusicPlaybackRepository by lazy { AppContainer.youtubeMusicPlaybackRepository }
    val youtubeMusicClient by lazy { AppContainer.youtubeMusicClient }

    val cloudMusicSearchApi by lazy { AppContainer.cloudMusicSearchApi }
    val qqMusicSearchApi by lazy { AppContainer.qqMusicSearchApi }
    val lrcLibClient by lazy { AppContainer.lrcLibClient }
    val amllTtmlClient by lazy { AppContainer.amllTtmlClient }

    // YouTube Music 歌词缓存, 避免短时间内重复请求
    internal val ytMusicLyricsCache = android.util.LruCache<String, YouTubeMusicLyricsCacheEntry>(20)
    // 网易云歌词缓存, 避免原文/翻译和编辑器回退重复打接口
    internal val neteaseLyricsCache = android.util.LruCache<Long, NeteaseLyricsCacheEntry>(20)

    // 当前缓存上限, 设置变化后会据此重建缓存
    internal var currentCacheSize: Long = 1024L * 1024 * 1024

    var sleepTimerManager: SleepTimerManager = createSleepTimerManager()
        internal set

    internal fun createSleepTimerManager(): SleepTimerManager {
        return SleepTimerManager(
            scope = mainScope,
            onTimerExpired = {
                pause()
                sleepTimerManager.cancel()
            },
            onTimerStateChanged = {
                if (isPlayerInitialized()) {
                    syncExoRepeatMode()
                }
            }
        )
    }

    internal fun setCurrentSongForPlayback(song: SongItem?, syncLyricon: Boolean = true) {
        val previousSong = _currentSongFlow.value
        persistPreviousSongProgress(previousSong, song)
        publishCurrentSong(song)
        if (previousSong !== song) syncChangedSongOutputs(song, syncLyricon)
    }

    private fun publishCurrentSong(song: SongItem?) {
        _currentSongFlow.value = song
        _playbackDurationMs.value = song?.durationMs?.coerceAtLeast(0L) ?: 0L
    }

    private fun syncChangedSongOutputs(song: SongItem?, syncLyricon: Boolean) {
        if (syncLyricon) syncLyriconSong(song)
        syncExternalBluetoothLyrics(song)
        playbackStatsOwner.onSongChanged(
            song = song,
            localPlaylistId = currentSongLocalPlaylistId(song),
            writesEnabled = initialized
        )
    }

    private fun currentSongLocalPlaylistId(song: SongItem?): Long? {
        return localPlaylistIdForSong(localPlaylistPlaybackSource, song)
    }

    private fun persistPreviousSongProgress(previousSong: SongItem?, song: SongItem?) {
        val songToPersist = previousSongForLongFormProgress(previousSong, song) ?: return
        persistLongFormPlaybackProgress(
            song = songToPersist,
            positionMs = _playbackPositionMs.value,
            durationMs = _playbackDurationMs.value
        )
        lastLongFormPlaybackProgressPersistAtMs = 0L
    }

    internal fun resolveRememberedLongFormPlaybackStartPosition(
        song: SongItem,
        requestedPositionMs: Long,
        allowRememberedPosition: Boolean
    ): Long {
        val normalizedRequestedPositionMs = requestedPositionMs.coerceAtLeast(0L)
        if (
            !allowRememberedPosition ||
            !rememberLongFormPlaybackProgressEnabled ||
            song.durationMs < LONG_FORM_PLAYBACK_MIN_DURATION_MS
        ) {
            return normalizedRequestedPositionMs
        }
        return resolveLongFormPlaybackResumePosition(
            enabled = true,
            durationMs = song.durationMs,
            requestedPositionMs = normalizedRequestedPositionMs,
            rememberedPositionMs = AppContainer.playHistoryRepo.rememberedPlaybackPosition(song),
            allowRememberedPosition = allowRememberedPosition
        )
    }

    internal fun persistLongFormPlaybackProgress(
        song: SongItem?,
        positionMs: Long,
        durationMs: Long
    ) {
        val songToPersist = song ?: return
        val effectiveDurationMs = maxOf(
            songToPersist.durationMs.coerceAtLeast(0L),
            durationMs.coerceAtLeast(0L)
        )
        val rememberedPositionMs = resolveLongFormPlaybackPositionForPersistence(
            enabled = rememberLongFormPlaybackProgressEnabled,
            durationMs = effectiveDurationMs,
            positionMs = positionMs
        ) ?: return
        AppContainer.playHistoryRepo.updateRememberedPlaybackPosition(
            song = songToPersist,
            positionMs = rememberedPositionMs
        )
    }

    internal fun persistCurrentLongFormPlaybackProgress() {
        val song = _currentSongFlow.value ?: return
        val playerPositionMs = if (isPlayerInitialized()) {
            player.currentPositionMsOrZero
        } else {
            0L
        }
        val playerDurationMs = if (isPlayerInitialized()) {
            player.durationMsOrZero
        } else {
            0L
        }
        persistLongFormPlaybackProgress(
            song = song,
            positionMs = maxOf(_playbackPositionMs.value, playerPositionMs),
            durationMs = maxOf(_playbackDurationMs.value, playerDurationMs)
        )
    }

    internal fun syncLyriconSong(
        song: SongItem?,
        lyricOffsetOverrideMs: Long? = null,
    ) {
        val request = lyriconUpdateCoordinator.replace(
            createJob = { updateGeneration ->
                if (!lyriconEnabled || song == null) {
                    null
                } else {
                    ioScope.launch(start = CoroutineStart.LAZY) lyriconUpdate@{
                        val preferred = getPreferredLyricSourceResult(song, defaultLyricSource)
                        val lyrics = preferred?.lyrics ?: getLyrics(
                            song,
                            skipPreferredSource = true
                        )
                        currentCoroutineContext().ensureActive()
                        val translatedLyrics = preferred?.translatedLyrics
                            ?: getTranslatedLyrics(song, skipPreferredSource = true)
                        currentCoroutineContext().ensureActive()
                        val updateJob = currentCoroutineContext()[Job] ?: return@lyriconUpdate
                        lyriconUpdateCoordinator.runIfCurrent(
                            generation = updateGeneration,
                            job = updateJob,
                        ) {
                            val currentSong = _currentSongFlow.value
                            if (currentSong?.sameIdentityAs(song) == true) {
                                lyriconPreferredLyricSourceSongKey = currentSong.stableKey()
                                lyriconPreferredLyricSource = preferred?.source
                                LyriconManager.updateSong(
                                    song = currentSong,
                                    lyrics = lyrics,
                                    translatedLyrics = translatedLyrics,
                                    lyricOffsetMs = lyricOffsetOverrideMs
                                        ?: resolveLyriconLyricOffsetMs(
                                            currentSong,
                                            preferred?.source
                                        ),
                                )
                            }
                        }
                    }
                }
            },
            onPublished = {
                lyriconPreferredLyricSourceSongKey = null
                lyriconPreferredLyricSource = null
                when {
                    !lyriconEnabled -> LyriconManager.setPlaybackState(false)
                    song == null -> {
                        LyriconManager.setPlaybackState(false)
                        updateLyriconLyricOffset(null)
                        LyriconManager.setPosition(0L)
                    }
                    else -> LyriconManager.updateSong(
                        song = song,
                        lyrics = null,
                        translatedLyrics = null,
                        lyricOffsetMs = lyricOffsetOverrideMs
                            ?: resolveLyriconLyricOffsetMs(song),
                    )
                }
            },
        )
        request.job?.start()
    }

    internal fun cancelLyriconUpdate() {
        lyriconUpdateCoordinator.cancelActive()
    }

    internal fun hasPendingLyriconUpdate(): Boolean {
        return lyriconUpdateCoordinator.hasPendingJob()
    }

    internal fun updateLyriconLyricOffset(song: SongItem? = _currentSongFlow.value) {
        if (!lyriconEnabled) return
        val lyricOffsetMs = song?.let { resolveLyriconLyricOffsetMs(it) } ?: 0L
        LyriconManager.setLyricOffset(lyricOffsetMs)
        LyriconManager.setPosition(song?.let { _playbackPositionMs.value } ?: 0L)
    }

    private fun resolveLyriconLyricOffsetMs(
        song: SongItem,
        preferredSource: LyricSourcePreference? = lyriconPreferredLyricSource
            .takeIf { lyriconPreferredLyricSourceSongKey == song.stableKey() }
    ): Long {
        return resolveEffectiveLyricOffsetMs(
            lyricSource = song.matchedLyricSource,
            cloudMusicDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
            qqMusicDefaultOffsetMs = qqMusicLyricDefaultOffsetMs,
            userLyricOffsetMs = song.userLyricOffsetMs,
            kugouDefaultOffsetMs = kugouLyricDefaultOffsetMs,
            lrclibDefaultOffsetMs = lrclibLyricDefaultOffsetMs,
            amllTtmlDefaultOffsetMs = amllTtmlLyricDefaultOffsetMs,
            preferredLyricSource = preferredSource,
        )
    }

    internal fun isApplicationInitialized(): Boolean = this::application.isInitialized

    internal fun bindApplication(app: Application) {
        if (isApplicationInitialized()) return
        synchronized(initializationLock) {
            if (!isApplicationInitialized()) {
                application = app
            }
        }
    }

    internal fun isPlayerInitialized(): Boolean = this::player.isInitialized

    internal fun isCacheInitialized(): Boolean = cache != null

    internal fun syncPlaybackControlPlayingState() {
        _playbackControlPlayingFlow.value = shouldShowPauseButtonForPlaybackControls(
            resumePlaybackRequested = resumePlaybackRequested,
            pendingPauseJobActive = pendingPauseJob?.isActive == true
        )
    }

    internal fun updateResumePlaybackRequested(requested: Boolean) {
        resumePlaybackRequested = requested
    }

    fun isTransportActive(): Boolean {
        ensureInitialized()
        return isTransportActiveWithoutInitialization()
    }

    internal fun isTransportActiveWithoutInitialization(): Boolean {
        if (!initialized || _currentSongFlow.value == null) return false
        return resumePlaybackRequested ||
            playJob?.isActive == true ||
            pendingPauseJob?.isActive == true ||
            _playWhenReadyFlow.value ||
            _isPlayingFlow.value
    }

    fun shouldRunPlaybackServiceInForeground(): Boolean {
        ensureInitialized()
        if (!initialized || _currentSongFlow.value == null) return false
        return shouldRunPlaybackServiceInForeground(
            hasCurrentSong = _currentSongFlow.value != null,
            resumePlaybackRequested = resumePlaybackRequested,
            playJobActive = playJob?.isActive == true,
            pendingPauseJobActive = pendingPauseJob?.isActive == true,
            playWhenReady = _playWhenReadyFlow.value,
            isPlaying = _isPlayingFlow.value,
            playerPlaybackState = _playerPlaybackStateFlow.value
        )
    }

    fun shouldBootstrapPlaybackServiceOnAppLaunch(): Boolean {
        ensureInitialized()
        val currentSong = _currentSongFlow.value
        if (!initialized || currentSong == null) return false
        val canAutoResumeRestoredPlayback =
            restoredShouldResumePlayback &&
                (!isLocalSong(currentSong) || isRestorableLocalSong(currentSong))
        return shouldBootstrapPlaybackServiceOnAppLaunch(
            hasCurrentSong = true,
            hasPendingRestoredPlaybackResume = canAutoResumeRestoredPlayback,
            resumePlaybackRequested = resumePlaybackRequested,
            playJobActive = playJob?.isActive == true,
            pendingPauseJobActive = pendingPauseJob?.isActive == true,
            playWhenReady = _playWhenReadyFlow.value,
            isPlaying = _isPlayingFlow.value,
            playerPlaybackState = _playerPlaybackStateFlow.value
        )
    }

    fun isTransportBuffering(): Boolean {
        ensureInitialized()
        if (!initialized || !isTransportActive()) return false
        return playJob?.isActive == true || _playerPlaybackStateFlow.value == Player.STATE_BUFFERING
    }

    fun shouldIgnoreExternalPauseCommand(source: String): Boolean {
        ensureInitialized()
        if (!initialized || _currentSongFlow.value == null) return false
        if (source.isUserInitiatedExternalPlaybackCommand()) return false
        if (shouldIgnoreUsbExclusiveFocusPause(source)) return true
        if (!resumePlaybackRequested) return false

        val autoAdvanceAgeMs = SystemClock.elapsedRealtime() - lastAutoTrackAdvanceAtMs
        if (autoAdvanceAgeMs !in 0L..AUTO_TRANSITION_EXTERNAL_PAUSE_GUARD_MS) return false

        if (playJob?.isActive == true) {
            return true
        }

        val currentPositionMs = runCatching { player.currentPosition.coerceAtLeast(0L) }
            .getOrDefault(Long.MAX_VALUE)
        val playbackState = _playerPlaybackStateFlow.value
        if (playbackState == Player.STATE_ENDED) {
            return true
        }
        if (!_playWhenReadyFlow.value) {
            return false
        }
        return when (playbackState) {
            Player.STATE_BUFFERING,
            Player.STATE_READY -> currentPositionMs <= AUTO_TRANSITION_BUFFER_POSITION_GUARD_MS
            else -> false
        }
    }

    private fun String.isUserInitiatedExternalPlaybackCommand(): Boolean {
        return equals("intent_pause", ignoreCase = true) ||
            equals("intent_stop", ignoreCase = true) ||
            startsWith("media_session_", ignoreCase = true)
    }

    internal fun markUsbExclusiveFocusDisrupted(change: Int) {
        markUsbExclusiveShortDisruption("audio_focus:$change")
    }

    internal fun pauseForUsbExclusiveFocusLoss(change: Int) {
        if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled || !initialized) return
        if (!isPlayerInitialized()) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainScope.launch { pauseForUsbExclusiveFocusLoss(change) }
            return
        }
        if (!resumePlaybackRequested && !_playWhenReadyFlow.value && !_isPlayingFlow.value) {
            return
        }
        NPLogger.w(
            "NERI-PlayerManager",
            "pause USB exclusive playback after audio focus loss: change=$change " +
                "playWhenReady=${_playWhenReadyFlow.value} isPlaying=${_isPlayingFlow.value}"
        )
        pauseImpl(
            forcePersist = false,
            commandSource = PlaybackCommandSource.REMOTE_SYNC,
            allowFadeOut = false,
            debugReason = "usb_focus_loss:$change"
        )
    }

    fun markUsbExclusiveShortDisruption(reason: String) {
        if (!usbExclusivePlaybackEnabled) return
        lastUsbExclusiveFocusDisruptionAtMs = SystemClock.elapsedRealtime()
        val nativeState = UsbExclusiveSessionController.state.value
        val openGate = UsbExclusiveSessionController.playerPcmOpenGateReason() ?: "open"
        NPLogger.d(
            "NERI-PlayerManager",
            "USB exclusive short disruption noted: reason=$reason " +
                "enabled=$usbExclusivePlaybackEnabled allowMixed=$allowMixedPlaybackEnabled " +
                "resumeRequested=$resumePlaybackRequested playWhenReady=${_playWhenReadyFlow.value} " +
                "isPlaying=${_isPlayingFlow.value} nativeSource=${nativeState.source} " +
                "nativeOpened=${nativeState.opened} nativeStreaming=${nativeState.streaming} " +
                "openGate=$openGate"
        )
    }

    internal fun isRecentUsbExclusiveFocusDisruption(): Boolean {
        if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled) return false
        val ageMs = SystemClock.elapsedRealtime() - lastUsbExclusiveFocusDisruptionAtMs
        return ageMs in 0L..USB_EXCLUSIVE_FOCUS_PAUSE_GUARD_MS
    }

    private fun shouldIgnoreUsbExclusiveFocusPause(source: String): Boolean {
        if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled) return false
        if (source.contains("stop", ignoreCase = true)) return false
        if (!resumePlaybackRequested && !_playWhenReadyFlow.value && !_isPlayingFlow.value) return false
        return isRecentUsbExclusiveFocusDisruption()
    }

    internal fun markAutoTrackAdvance() {
        lastAutoTrackAdvanceAtMs = SystemClock.elapsedRealtime()
    }

    internal fun fadeStepsFor(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (durationMs / 40L).toInt().coerceIn(MIN_FADE_STEPS, MAX_FADE_STEPS)
    }

    internal fun runPlayerActionOnMainThread(action: () -> Unit) {
        if (!::player.isInitialized) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        mainScope.launch {
            if (!::player.isInitialized) return@launch
            action()
        }
    }

    internal fun applyAudioFocusPolicy() {
        if (!::player.isInitialized) return
        runPlayerActionOnMainThread {
            applyAudioFocusPolicyOnMainThread()
        }
    }

    internal fun applyAudioFocusPolicyOnMainThread() {
        if (!::player.isInitialized) return
        val useUsbExclusiveFocusGuard = shouldUseUsbExclusiveFocusGuard()
        val bypassPlatformFocus = shouldBypassPlatformAudioFocusForUsbExclusive()
        val handleFocus = !allowMixedPlaybackEnabled && !bypassPlatformFocus
        UsbExclusiveDebugLogger.logFocusPolicy(
            usbExclusivePlayback = usbExclusivePlaybackEnabled,
            allowMixedPlayback = allowMixedPlaybackEnabled,
            handleFocus = handleFocus
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(attributes, handleFocus)
        StartupAudioFocusController.updateForForeground(
            context = application,
            enabled = useUsbExclusiveFocusGuard,
            allowMixedPlayback = allowMixedPlaybackEnabled,
            usbExclusivePlayback = usbExclusivePlaybackEnabled,
            usbExclusiveNativeActive = useUsbExclusiveFocusGuard,
            transportActive = isTransportActiveWithoutInitialization(),
            reason = "apply_audio_focus_policy"
        )
    }

    internal fun shouldUseUsbExclusiveFocusGuard(): Boolean {
        if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled) return false
        val pathState = UsbExclusiveAudioPathTracker.state.value
        val nativeState = UsbExclusiveSessionController.state.value
        return pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB &&
            pathState.sinkPlaying &&
            nativeState.source == "player_pcm" &&
            nativeState.streaming
    }

    internal fun shouldBypassPlatformAudioFocusForUsbExclusive(): Boolean {
        if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled) return false
        val pathState = UsbExclusiveAudioPathTracker.state.value
        val nativeState = UsbExclusiveSessionController.state.value
        if (
            nativeState.transitioning ||
            (nativeState.opened && nativeState.source == "player_pcm") ||
            pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB
        ) {
            return true
        }
        val fallbackReason = pathState.fallbackReason ?: return true
        return fallbackReason.startsWith("native_open_deferred") ||
            fallbackReason.startsWith("native_reopen_cooling_down") ||
            fallbackReason.contains("transport", ignoreCase = true) ||
            fallbackReason.contains("start", ignoreCase = true) ||
            fallbackReason.contains("play", ignoreCase = true)
    }

    internal fun isUsbExclusiveNativePlaybackStable(): Boolean {
        if (!usbExclusivePlaybackEnabled || allowMixedPlaybackEnabled) return false
        val pathState = UsbExclusiveAudioPathTracker.state.value
        val nativeState = UsbExclusiveSessionController.state.value
        val metrics = nativeState.runtimeReport.usbRuntimeMetrics()
        return pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB &&
            pathState.sinkPlaying &&
            pathState.fallbackReason == null &&
            nativeState.source == "player_pcm" &&
            nativeState.opened &&
            nativeState.streaming &&
            !nativeState.transitioning &&
            metrics.hasHealthyTransport
    }

    internal fun isUsbExclusivePlaybackActiveForForegroundService(): Boolean {
        if (!isPlayerInitialized()) return false
        if (!usbExclusivePlaybackEnabled) return false
        if (!isTransportActiveWithoutInitialization()) return false
        val nativeState = UsbExclusiveSessionController.state.value
        val pathState = UsbExclusiveAudioPathTracker.state.value
        return nativeState.streaming ||
            nativeState.opened ||
            pathState.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB ||
            pathState.requestedPath == UsbExclusiveAudioPathState.REQUESTED_NATIVE_USB
    }

    internal fun isPreparedInPlayer(): Boolean =
        player.currentMediaItem != null &&
            player.playbackState == Player.STATE_READY &&
            !shouldTreatReadyAtStartAsUnhealthyPrepared()

    fun setListenTogetherSyncPlaybackRate(rate: Float) {
        ensureInitialized()
        val resolvedRate = rate.coerceIn(0.95f, 1.05f)
        if (kotlin.math.abs(listenTogetherSyncPlaybackRate - resolvedRate) < 0.001f) return
        NPLogger.d(
            "NERI-PlayerManager",
            "setListenTogetherSyncPlaybackRate(): old=$listenTogetherSyncPlaybackRate, new=$resolvedRate, stack=[${debugStackHint()}]"
        )
        listenTogetherSyncPlaybackRate = resolvedRate
        schedulePlaybackSoundConfigApply(
            previousConfig = playbackSoundConfig,
            newConfig = playbackSoundConfig
        )
    }

    fun resetListenTogetherSyncPlaybackRate() {
        setListenTogetherSyncPlaybackRate(1f)
    }

    @Suppress("unused")
    fun resetForListenTogetherJoin() {
        ensureInitialized()
        if (!initialized) return
        NPLogger.d(
            "NERI-PlayerManager",
            "resetForListenTogetherJoin(): currentSong=${_currentSongFlow.value?.name}, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, isPlaying=${_isPlayingFlow.value}, stack=[${debugStackHint()}]"
        )
        cancelPendingPauseRequest(resetVolumeToFull = true)
        clearListenTogetherSafetyPause()
        playbackRequestToken += 1
        urlRefreshController.cancelCurrent()
        cancelPlaybackStartupWatchdog(reason = "listen_together_reset")
        clearActivePlaybackCandidates()
        playJob?.cancel()
        playJob = null
        pendingMediaLoadActive = false
        currentYouTubePrefetchJob?.cancel()
        currentYouTubePrefetchJob = null
        currentYouTubePrefetchVideoIds = emptySet()
        updateResumePlaybackRequested(false)
        clearRestoredPlayback()
        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        persistCurrentLongFormPlaybackProgress()
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        _isPlayingFlow.value = false
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
        _currentMediaUrl.value = null
        currentMediaUrlResolvedAtMs = 0L
        setCurrentSongForPlayback(null)
        publishCurrentQueue(emptyList(), -1)
        shuffleRestorePlaylistReference = null
        shuffleRestoreCurrentIndex = -1
        consecutivePlayFailures = 0
        NPLogger.d("NERI-PlayerManager", "resetForListenTogetherJoin(): state cleared")
        scheduleStatePersist(positionMs = 0L, shouldResumePlayback = false, debounceMs = 0L)
    }

    internal fun pendingSeekPositionOrNull(): Long? {
        return pendingSeekPositionMs.takeIf { it != C.TIME_UNSET }
    }

    internal fun rememberPendingSeekPosition(positionMs: Long) {
        pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
    }

    internal fun clearPendingSeekPosition() {
        pendingSeekPositionMs = C.TIME_UNSET
        expeditedYouTubeSeekRecoveryPending = false
    }

    internal fun resolveDisplayedPlaybackPosition(actualPositionMs: Long): Long {
        val actual = resolvePendingMediaLoadPosition(
            pendingLoadActive = isPendingMediaLoadActive(),
            requestedPositionMs = pendingMediaLoadPositionMs,
            livePlayerPositionMs = actualPositionMs
        )
        if (isPendingMediaLoadActive()) return actual
        val pending = pendingSeekPositionOrNull() ?: return actual
        return if (kotlin.math.abs(actual - pending) <= PENDING_SEEK_POSITION_TOLERANCE_MS) {
            clearPendingSeekPosition()
            actual
        } else {
            pending
        }
    }

    internal fun isPendingMediaLoadActive(): Boolean {
        return pendingMediaLoadActive
    }

    internal val gson = Gson()

    internal fun isLocalSong(song: SongItem): Boolean = LocalSongSupport.isLocalSong(song, application)

    internal fun isDirectStreamUrl(url: String?): Boolean {
        val normalized = url?.trim().orEmpty()
        return normalized.startsWith("https://", ignoreCase = true) ||
            normalized.startsWith("http://", ignoreCase = true)
    }

    internal fun activeListenTogetherRoomState() = AppContainer.listenTogetherSessionManager.roomState.value

    internal fun activeListenTogetherSessionState() = AppContainer.listenTogetherSessionManager.sessionState.value

    internal fun isListenTogetherActive(): Boolean {
        return !activeListenTogetherSessionState().roomId.isNullOrBlank()
    }

    internal fun isCurrentUserControllerInListenTogether(): Boolean {
        val session = activeListenTogetherSessionState()
        val room = activeListenTogetherRoomState()
        return resolveListenTogetherSessionRole(
            sessionUserId = session.userUuid,
            fallbackRole = session.role,
            state = room
        ) == "controller"
    }

    internal fun shouldMuteListenTogetherListenerForAudioRouteLoss(): Boolean {
        return shouldMuteListenTogetherListenerForAudioRouteLoss(
            listenTogetherActive = isListenTogetherActive(),
            isCurrentUserController = isCurrentUserControllerInListenTogether()
        )
    }

    internal fun markListenTogetherSafetyPausePendingResume() {
        listenTogetherSafetyPausePendingResume = true
        listenTogetherSafetyResumeInFlight = false
    }

    internal fun shouldHoldListenTogetherPlaybackForSafetyPause(causeType: String?): Boolean {
        return shouldHoldListenTogetherPlaybackForSafetyPause(
            safetyPausePendingResume = listenTogetherSafetyPausePendingResume,
            causeType = causeType
        )
    }

    internal fun requestListenTogetherSafetyPauseResume(): Boolean {
        if (!listenTogetherSafetyPausePendingResume) return false
        if (!isListenTogetherActive() || isCurrentUserControllerInListenTogether()) {
            clearListenTogetherSafetyPause()
            return false
        }
        if (listenTogetherSafetyResumeInFlight) return true
        listenTogetherSafetyResumeInFlight = true
        AppContainer.listenTogetherSessionManager.resumeListenerAfterSafetyPause()
        return true
    }

    internal fun completeListenTogetherSafetyPauseResume() {
        listenTogetherSafetyPausePendingResume = false
        listenTogetherSafetyResumeInFlight = false
    }

    internal fun retryListenTogetherSafetyPauseResume() {
        listenTogetherSafetyResumeInFlight = false
    }

    internal fun clearListenTogetherSafetyPause() {
        listenTogetherSafetyPausePendingResume = false
        listenTogetherSafetyResumeInFlight = false
    }

    internal fun currentListenTogetherTargetStableKey(): String? {
        val room = activeListenTogetherRoomState() ?: return null
        return room.currentStableKey()
    }

    internal fun currentListenTogetherTargetStreamUrl(): String? {
        val room = activeListenTogetherRoomState() ?: return null
        return room.authoritativeStreamUrlForCurrentTrack()
    }

    internal fun SongItem.listenTogetherStableKeyOrNull(): String? {
        val channel = resolvedChannelId() ?: return null
        val audioId = resolvedAudioId() ?: return null
        return buildStableTrackKey(
            channelId = channel,
            audioId = audioId,
            subAudioId = resolvedSubAudioId(),
            playlistContextId = resolvedPlaylistContextId()
        )
    }

    internal fun shouldWaitForListenTogetherAuthoritativeStream(song: SongItem): Boolean {
        if (!isListenTogetherAuthoritativeStreamTarget(song)) return false
        if (isListenTogetherAuthoritativeStreamConfirmedUnavailable(song)) return false
        return !isDirectStreamUrl(currentListenTogetherTargetStreamUrl())
    }

    internal fun shouldAwaitListenTogetherSharedStreamFallback(
        song: SongItem,
        localResolutionRequiresSharedStream: Boolean
    ): Boolean {
        return moe.ouom.neriplayer.listentogether.playback
            .shouldAwaitListenTogetherSharedStreamFallback(
                listenerAudioLinkSharingActive = isListenTogetherAudioLinkFallbackEnabled(),
                localResolutionRequiresSharedStream = localResolutionRequiresSharedStream,
                controllerLinkConfirmedUnavailable =
                    isListenTogetherAuthoritativeStreamConfirmedUnavailable(song),
                hasAuthoritativeStream = isDirectStreamUrl(currentListenTogetherTargetStreamUrl())
            )
    }

    internal fun isListenTogetherAudioLinkFallbackEnabled(): Boolean {
        if (!isListenTogetherActive()) return false
        if (isCurrentUserControllerInListenTogether()) return false
        val room = activeListenTogetherRoomState() ?: return false
        return room.settings.shareAudioLinks && room.roomStatus == "active"
    }

    internal fun isListenTogetherLocalResolutionPendingFor(song: SongItem): Boolean {
        val targetStableKey = song.listenTogetherStableKeyOrNull() ?: return false
        return isPendingMediaLoadActive() &&
            playJob?.isActive == true &&
            _currentSongFlow.value?.listenTogetherStableKeyOrNull() == targetStableKey
    }

    internal fun isListenTogetherAuthoritativeStreamTarget(song: SongItem): Boolean {
        if (!isListenTogetherAudioLinkFallbackEnabled()) return false
        activeListenTogetherRoomState() ?: return false
        val targetStableKey = currentListenTogetherTargetStableKey() ?: return false
        val songStableKey = song.listenTogetherStableKeyOrNull() ?: return false
        return songStableKey == targetStableKey
    }

    internal fun isListenTogetherAuthoritativeStreamConfirmedUnavailable(song: SongItem): Boolean {
        if (!isListenTogetherAuthoritativeStreamTarget(song)) return false
        val room = activeListenTogetherRoomState() ?: return false
        val songStableKey = song.listenTogetherStableKeyOrNull() ?: return false
        return AppContainer.listenTogetherSessionManager.isControllerAudioLinkUnavailable(
            roomId = room.roomId,
            stableKey = songStableKey
        )
    }

    internal fun stopCurrentPlaybackForListenTogetherAwaitingStream() {
        NPLogger.d(
            "NERI-PlayerManager",
            "stopCurrentPlaybackForListenTogetherAwaitingStream(): currentSong=${_currentSongFlow.value?.name}, mediaUrl=${_currentMediaUrl.value}, targetStableKey=${currentListenTogetherTargetStableKey()}, stack=[${debugStackHint()}]"
        )
        cancelPendingPauseRequest(resetVolumeToFull = true)
        clearPlaybackDemandCacheKey(reason = "listen_together_awaiting_stream")
        cancelPlaybackStartupWatchdog(reason = "listen_together_awaiting_stream")
        clearActivePlaybackCandidates()
        stopProgressUpdates()
        cancelVolumeFade(resetToFull = true)
        persistCurrentLongFormPlaybackProgress()
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        _isPlayingFlow.value = false
        _currentMediaUrl.value = null
        currentMediaUrlResolvedAtMs = 0L
        pendingMediaLoadActive = false
        pendingMediaLoadPositionMs = 0L
        clearPendingSeekPosition()
        _playbackPositionMs.value = 0L
    }

    internal fun rejectListenTogetherControl(
        messageResId: Int,
        debugReason: String? = null
    ): Boolean {
        NPLogger.w(
            "NERI-PlayerManager",
            "rejectListenTogetherControl(): messageResId=$messageResId, reason=${debugReason ?: "unspecified"}, sessionRoomId=${activeListenTogetherSessionState().roomId}, roomStatus=${activeListenTogetherRoomState()?.roomStatus}, stack=[${debugStackHint()}]"
        )
        postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageResId)))
        return true
    }

    internal fun rejectUsbExclusiveToggleControl(): Boolean {
        NPLogger.w(
            "NERI-PlayerManager",
            "rejectUsbExclusiveToggleControl(): reason=${usbRouteTransitionOwner.toggleReason}, stack=[${debugStackHint()}]"
        )
        postPlayerEvent(
            PlayerEvent.ShowError(
                getLocalizedString(R.string.settings_usb_exclusive_status_transitioning)
            )
        )
        return true
    }

    fun beginUsbExclusiveToggleTransitionFromUi(targetEnabled: Boolean): Boolean {
        if (usbRouteTransitionOwner.toggleActive) {
            rejectUsbExclusiveToggleControl()
            return false
        }
        usbRouteTransitionOwner.beginUiToggle(targetEnabled)
        markUsbExclusivePlaybackPreparing(true, usbRouteTransitionOwner.toggleReason)
        return true
    }

    internal fun shouldBlockLocalRoomControl(commandSource: PlaybackCommandSource): Boolean {
        if (commandSource != PlaybackCommandSource.LOCAL) return false
        if (usbRouteTransitionOwner.toggleActive) return rejectUsbExclusiveToggleControl()
        return rejectRoomControlRestriction()
    }

    private fun rejectRoomControlRestriction(): Boolean {
        val restriction = localRoomControlRestriction()
        val message = restriction.errorResId ?: return false
        return rejectListenTogetherControl(message, restriction.debugReason)
    }

    private fun localRoomControlRestriction(): LocalRoomControlRestriction {
        if (!isListenTogetherActive()) return LocalRoomControlRestriction.NONE
        return activeRoomControlRestriction()
    }

    private fun activeRoomControlRestriction(): LocalRoomControlRestriction {
        val room = activeListenTogetherRoomState() ?: return LocalRoomControlRestriction.NONE
        return resolveLocalRoomControlRestriction(
            room.roomStatus,
            room.settings.allowMemberControl,
            isCurrentUserControllerInListenTogether()
        )
    }

    internal fun shouldBlockLocalSongSwitch(song: SongItem, commandSource: PlaybackCommandSource): Boolean {
        if (commandSource != PlaybackCommandSource.LOCAL) return false
        if (!isListenTogetherActive()) return false
        if (!isLocalSong(song)) return false
        return rejectListenTogetherControl(
            R.string.listen_together_error_local_playback_blocked,
            debugReason = "local_song_switch_blocked:${song.stableKey()}"
        )
    }

    internal fun isYouTubeMusicTrack(song: SongItem): Boolean {
        return PlaybackMediaItemFactory.isYouTubeSource(song)
    }

    internal fun isBiliTrack(song: SongItem): Boolean {
        return PlaybackMediaItemFactory.isBiliSource(song)
    }
    internal fun queueIndexOf(song: SongItem, playlist: List<SongItem> = currentPlaylist): Int {
        return playlist.indexOfFirst { it.sameIdentityAs(song) }
    }

    fun currentQueueDisplaySnapshot(): PlayerQueueDisplayState {
        val snapshot = currentQueueSnapshot()
        return buildPlayerQueueDisplayState(
            playlist = snapshot.playlist,
            currentIndex = snapshot.currentIndex
        )
    }

    internal fun bumpCurrentQueueDisplayRevision() {
        _currentQueueDisplayRevisionFlow.value = _currentQueueDisplayRevisionFlow.value + 1
    }

    internal fun localMediaSource(song: SongItem): String? {
        return LocalPlaybackMediaResolver.source(song, application)
    }

    internal fun toPlayableLocalUrl(mediaUri: String?): String? =
        LocalPlaybackMediaResolver.playableUrl(mediaUri)

    internal fun isReadableLocalMediaUri(mediaUri: String?, context: Context = application): Boolean =
        LocalPlaybackMediaResolver.isReadable(mediaUri, context)

    internal fun restorableLocalMediaState(
        mediaUri: String?,
        context: Context = application,
    ): RestorableLocalMediaState = LocalPlaybackMediaResolver.restorableState(mediaUri, context)

    internal fun isRestorableLocalMediaUri(
        mediaUri: String?,
        context: Context = application,
    ): Boolean = LocalPlaybackMediaResolver.isRestorable(mediaUri, context)

    internal fun isRestorableLocalSong(song: SongItem, context: Context = application): Boolean =
        LocalPlaybackMediaResolver.isRestorableSong(song, context)

    @Suppress("unused")
    internal fun sanitizeRestoredPlaylist(playlist: List<SongItem>): List<SongItem> =
        LocalPlaybackMediaResolver.sanitizeRestoredPlaylist(playlist, application)

    internal fun isCurrentSong(song: SongItem): Boolean {
        return _currentSongFlow.value?.sameIdentityAs(song) == true
    }

    internal fun maybeUpdateSongDuration(song: SongItem, durationMs: Long) {
        val resolvedDurationMs = durationMs.takeIf { it > 0L } ?: return
        var changed = false

        val updatedQueueSong = updateQueuedSong(song) { queuedSong ->
            queuedSong.takeIf { it.durationMs <= 0L }
                ?.copy(durationMs = resolvedDurationMs)
        }
        if (updatedQueueSong != null) {
            changed = true
        }

        val currentSong = _currentSongFlow.value
        if (currentSong?.sameIdentityAs(song) == true && currentSong.durationMs <= 0L) {
            setCurrentSongForPlayback(currentSong.copy(durationMs = resolvedDurationMs))
            changed = true
        }
        if (currentSong?.sameIdentityAs(song) == true) {
            _playbackDurationMs.value = resolvedDurationMs
        }

        if (changed) {
            scheduleStatePersist(debounceMs = 0L)
        }
    }

    internal fun maybeBackfillCurrentSongDurationFromPlayer() {
        if (!::player.isInitialized) {
            return
        }
        val currentSong = _currentSongFlow.value ?: return
        val playerDurationMs = player.duration.takeIf { it > 0L } ?: return
        _playbackDurationMs.value = playerDurationMs
        maybeUpdateSongDuration(currentSong, playerDurationMs)
    }

    internal fun shouldStartUsbExclusiveTransportFromSink(): Boolean {
        if (!usbExclusivePlaybackEnabled) return false
        return resumePlaybackRequested ||
            _playWhenReadyFlow.value ||
            _playbackControlPlayingFlow.value
    }

    internal fun markUsbExclusivePlaybackPreparing(preparing: Boolean, reason: String) {
        if (_usbExclusivePlaybackPreparingFlow.value == preparing) return
        _usbExclusivePlaybackPreparingFlow.value = preparing
        NPLogger.d(
            "NERI-UsbExclusive",
            "playback preparing=$preparing reason=$reason"
        )
    }

    internal fun usbAudioSinkReconfigurationSnapshot():
        UsbAudioSinkReconfigurationSnapshot = usbSinkRouteOwner.snapshot()

    internal fun cancelUsbAudioSinkReconfiguration() {
        usbSinkRouteOwner.cancel()
    }

    fun changeCurrentPlaybackQuality(optionKey: String) {
        val normalizedKey = optionKey.trim().lowercase()
        if (normalizedKey.isBlank()) return
        val currentAudioInfo = _currentPlaybackAudioInfo.value ?: return
        // 和弹窗回显保持同一基准, 按偏好而不是当前流实测档位去重
        // 否则偏好和实测不一致时点实测档会被误判为未变化
        val preferredKey = _preferredQualityKeys.value.forSource(currentAudioInfo.source)
        if (normalizedKey == preferredKey) return

        ioScope.launch {
            when (currentAudioInfo.source) {
                PlaybackAudioSource.NETEASE -> settingsRepo.setAudioQuality(normalizedKey)
                PlaybackAudioSource.BILIBILI -> settingsRepo.setBiliAudioQuality(normalizedKey)
                PlaybackAudioSource.YOUTUBE_MUSIC -> settingsRepo.setYouTubeAudioQuality(normalizedKey)
                PlaybackAudioSource.LOCAL -> Unit
            }
        }
    }

    fun setPlaybackSpeed(speed: Float, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(speed = normalizePlaybackSpeed(speed)),
            persist = persist
        )
    }

    fun setPlaybackPitch(pitch: Float, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(pitch = normalizePlaybackPitch(pitch)),
            persist = persist
        )
    }

    fun setPlaybackLoudnessGain(levelMb: Int, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                loudnessGainMb = normalizePlaybackLoudnessGainMb(levelMb)
            ),
            persist = persist
        )
    }

    fun setPlaybackVolumeBalance(balance: Float, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                volumeBalance = normalizePlaybackVolumeBalance(balance)
            ),
            persist = persist
        )
    }

    fun setPlaybackVolumeNormalizationEnabled(enabled: Boolean, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(volumeNormalizationEnabled = enabled),
            persist = persist
        )
    }

    fun setPlaybackHighResolutionOutputEnabled(enabled: Boolean, persist: Boolean = true) {
        ensureInitialized()
        if (playbackHighResolutionOutputEnabled == enabled) return
        playbackHighResolutionOutputEnabled = enabled
        updateAudioOffloadPreferences("playback_high_resolution_output")
        if (persist) {
            ioScope.launch {
                settingsRepo.setPlaybackHighResolutionOutputEnabled(enabled)
            }
        }
        if (usbExclusivePlaybackEnabled) {
            scheduleUsbAudioSinkReconfiguration(
                reason = "playback_high_resolution_output_changed",
                allowWhilePlaybackActive = true,
                bypassCooldown = true
            )
        }
    }

    fun setPlaybackEqualizerEnabled(enabled: Boolean, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(equalizerEnabled = enabled),
            persist = persist
        )
    }

    fun selectPlaybackEqualizerPreset(presetId: String, persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                equalizerEnabled = true,
                presetId = presetId
            ),
            persist = persist
        )
    }

    fun updatePlaybackEqualizerBandLevel(
        index: Int,
        levelMb: Int,
        persist: Boolean = true
    ) {
        ensureInitialized()
        val currentBands = _playbackSoundState.value.bands
        if (index !in currentBands.indices) return
        val updatedLevels = currentBands.map { it.levelMb }.toMutableList()
        updatedLevels[index] = levelMb
        applyPlaybackSoundConfig(
            playbackSoundConfig.copy(
                equalizerEnabled = true,
                presetId = PlaybackEqualizerPresetId.CUSTOM,
                customBandLevelsMb = updatedLevels
            ),
            persist = persist
        )
    }

    fun resetPlaybackSoundSettings(persist: Boolean = true) {
        ensureInitialized()
        applyPlaybackSoundConfig(
            PlaybackSoundConfig(
                speed = DEFAULT_PLAYBACK_SPEED,
                pitch = DEFAULT_PLAYBACK_PITCH,
                loudnessGainMb = DEFAULT_PLAYBACK_LOUDNESS_GAIN_MB,
                volumeBalance = DEFAULT_PLAYBACK_VOLUME_BALANCE,
                volumeNormalizationEnabled = DEFAULT_PLAYBACK_VOLUME_NORMALIZATION_ENABLED,
                equalizerEnabled = false,
                presetId = PlaybackEqualizerPresetId.FLAT,
                customBandLevelsMb = emptyList()
            ),
            persist = persist
        )
    }

    internal fun applyPlaybackSoundConfig(
        newConfig: PlaybackSoundConfig,
        persist: Boolean
    ) {
        val previousConfig = playbackSoundConfig
        playbackSoundConfig = newConfig.copy(
            speed = normalizePlaybackSpeed(newConfig.speed),
            pitch = normalizePlaybackPitch(newConfig.pitch),
            loudnessGainMb = normalizePlaybackLoudnessGainMb(newConfig.loudnessGainMb),
            volumeBalance = normalizePlaybackVolumeBalance(newConfig.volumeBalance)
        )
        if (lyriconEnabled && previousConfig.speed != playbackSoundConfig.speed) {
            LyriconManager.setPlaybackSpeed(playbackSoundConfig.speed)
        }
        schedulePlaybackSoundConfigApply(
            previousConfig = previousConfig,
            newConfig = playbackSoundConfig
        )
        if (persist) {
            persistPlaybackSoundConfig(playbackSoundConfig)
        }
    }

    internal fun schedulePlaybackSoundConfigApply(
        previousConfig: PlaybackSoundConfig,
        newConfig: PlaybackSoundConfig
    ) {
        pendingPlaybackSoundConfig = resolvePlaybackSoundConfigForEngine(
            baseConfig = newConfig,
            listenTogetherSyncPlaybackRate = listenTogetherSyncPlaybackRate,
            usbExclusivePlaybackEnabled = usbExclusivePlaybackEnabled
        )
        playbackSoundApplyJob?.cancel()

        val debounceHeavyEffectUpdate =
            previousConfig.equalizerEnabled != newConfig.equalizerEnabled ||
                previousConfig.presetId != newConfig.presetId ||
                previousConfig.customBandLevelsMb != newConfig.customBandLevelsMb ||
                previousConfig.loudnessGainMb != newConfig.loudnessGainMb
        val applyDelayMs = if (debounceHeavyEffectUpdate) 48L else 0L

        playbackSoundApplyJob = mainScope.launch {
            if (applyDelayMs > 0L) {
                delay(applyDelayMs)
            }
            val latestConfig = pendingPlaybackSoundConfig ?: return@launch
            pendingPlaybackSoundConfig = null
            _playbackSoundState.value = playbackEffectsController.updateConfig(latestConfig)
            updateAudioOffloadPreferences("playback_sound_config")
        }
    }

    internal fun applyPlaybackSoundConfigIfChanged(newConfig: PlaybackSoundConfig) {
        val normalizedConfig = newConfig.copy(
            speed = normalizePlaybackSpeed(newConfig.speed),
            pitch = normalizePlaybackPitch(newConfig.pitch),
            loudnessGainMb = normalizePlaybackLoudnessGainMb(newConfig.loudnessGainMb),
            volumeBalance = normalizePlaybackVolumeBalance(newConfig.volumeBalance)
        )
        if (normalizedConfig == playbackSoundConfig) return
        applyPlaybackSoundConfig(normalizedConfig, persist = false)
    }

    internal fun persistPlaybackSoundConfig(config: PlaybackSoundConfig) {
        playbackSoundPersistJob?.cancel()
        playbackSoundPersistJob = ioScope.launch {
            delay(150)
            settingsRepo.setPlaybackSpeed(config.speed)
            settingsRepo.setPlaybackPitch(config.pitch)
            settingsRepo.setPlaybackLoudnessGainMb(config.loudnessGainMb)
            settingsRepo.setPlaybackVolumeBalance(config.volumeBalance)
            settingsRepo.setPlaybackVolumeNormalizationEnabled(config.volumeNormalizationEnabled)
            settingsRepo.setPlaybackEqualizerEnabled(config.equalizerEnabled)
            settingsRepo.setPlaybackEqualizerPreset(config.presetId)
            settingsRepo.setPlaybackEqualizerCustomBandLevels(config.customBandLevelsMb)
        }
    }

    internal fun scheduleQualityRefresh(
        source: PlaybackAudioSource,
        reason: String
    ) {
        val targetJob = when (source) {
            PlaybackAudioSource.NETEASE -> ::neteaseQualityRefreshJob
            PlaybackAudioSource.YOUTUBE_MUSIC -> ::youtubeQualityRefreshJob
            PlaybackAudioSource.BILIBILI -> ::biliQualityRefreshJob
            PlaybackAudioSource.LOCAL -> return
        }
        targetJob.get()?.cancel()
        targetJob.set(
            ioScope.launch {
                if (QUALITY_CHANGE_REFRESH_DEBOUNCE_MS > 0L) {
                    delay(QUALITY_CHANGE_REFRESH_DEBOUNCE_MS)
                }
                refreshCurrentSongForQualityChange(source = source, reason = reason)
            }
        )
    }

    internal suspend fun refreshCurrentSongForQualityChange(
        source: PlaybackAudioSource,
        reason: String
    ) {
        withContext(Dispatchers.Main) {
            val currentAudioInfo = _currentPlaybackAudioInfo.value ?: return@withContext
            if (currentAudioInfo.source != source) return@withContext
            val currentSong = _currentSongFlow.value ?: return@withContext
            if (isLocalSong(currentSong)) return@withContext

            // 捕获和签发请求在同一次主线程执行中完成，避免旧意图绑定到新的播放代次
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            refreshCurrentSongUrl(
                resumePositionMs = positionMs,
                allowFallback = true,
                reason = reason,
                bypassCooldown = true,
                fallbackSeekPositionMs = positionMs,
                resumePlaybackAfterRefresh = resumePlaybackRequested,
                resumedPlaybackCommandSource = activePlaybackCommandSource
            )
        }
    }

    internal fun postPlayerEvent(event: PlayerEvent) {
        ioScope.launch { _playerEventFlow.emit(event) }
    }

    internal fun requestUsbExclusiveLoudPlaybackConfirmation(
        commandSource: PlaybackCommandSource,
        bypassWarning: Boolean = false,
        continuePlayback: () -> Unit,
        cancelPlayback: (() -> Unit)? = null
    ): Boolean = usbExclusiveLoudPlaybackConfirmationOwner.request(
        commandSource = commandSource,
        bypassWarning = bypassWarning,
        snapshot = ::currentUsbExclusiveLoudPlaybackSnapshot,
        continuePlayback = continuePlayback,
        cancelPlayback = cancelPlayback
    )

    internal fun confirmUsbExclusiveLoudPlayback(confirmationId: Long) {
        usbExclusiveLoudPlaybackConfirmationOwner.confirm(confirmationId)
    }

    internal fun cancelUsbExclusiveLoudPlayback(confirmationId: Long) {
        usbExclusiveLoudPlaybackConfirmationOwner.cancel(confirmationId)
    }

    private fun currentUsbExclusiveLoudPlaybackSnapshot(): UsbExclusiveLoudPlaybackSnapshot =
        UsbExclusiveLoudPlaybackSnapshotSource.capture(
            UsbExclusiveLoudPlaybackSignals(
                context = application,
                usbExclusiveEnabled = usbExclusivePlaybackEnabled,
                appInForeground = usbExclusiveAppInForeground,
                currentDevice = _currentAudioDevice.value,
                reportedPlaying = _isPlayingFlow.value,
                playerInitialized = ::isPlayerInitialized,
                playerIsPlaying = { player.isPlaying },
                playerVolume = { player.volume },
                requestedVolume = { UsbExclusiveAudioPathTracker.state.value.requestedVolume },
                nativeState = { UsbExclusiveSessionController.state.value },
                bitPerfect = usbExclusivePreferences.bitPerfect,
                riskThresholdDbfs = usbExclusivePreferences.volumeRiskThresholdDbfs
            )
        )

    internal fun emitPlaybackCommand(
        type: String,
        source: PlaybackCommandSource,
        queue: List<SongItem>? = null,
        currentIndex: Int? = null,
        positionMs: Long? = null,
        shouldPlay: Boolean? = null,
        repeatMode: Int? = null,
        shuffleEnabled: Boolean? = null,
        force: Boolean = false
    ) {
        if (source != PlaybackCommandSource.LOCAL) return
        _playbackCommandFlow.tryEmit(
            PlaybackCommand(
                type = type,
                source = source,
                queue = queue,
                currentIndex = currentIndex,
                positionMs = positionMs,
                shouldPlay = shouldPlay,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                force = force
            )
        )
    }

    internal fun resetTrackEndDeduplicationState() {
        lastHandledTrackEndKey = null
        lastTrackEndHandledAtMs = 0L
    }

    internal fun markTrackEndHandledForStatsFallback() {
        lastHandledTrackEndKey = trackEndDeduplicationKey(
            mediaId = runCatching { player.currentMediaItem?.mediaId }.getOrNull(),
            fallbackSongKey = _currentSongFlow.value?.stableKey()
        )
        lastTrackEndHandledAtMs = SystemClock.elapsedRealtime()
    }

    internal fun syncPlaybackStatsPlayingState(
        playing: Boolean,
        reason: String
    ) {
        playbackStatsOwner.onPlayingChanged(playing, reason, writesEnabled = initialized)
    }

    internal fun drainPlaybackStatsPersistJobBlocking(reason: String) {
        playbackStatsOwner.drainBlocking(reason, writesEnabled = initialized)
    }

    internal fun flushPlaybackStatsBlockingImpl(
        reason: String,
        stopTracking: Boolean = false
    ) {
        playbackStatsOwner.flushBlocking(reason, stopTracking, writesEnabled = initialized)
    }

    internal fun flushPlaybackStatsAsyncImpl(
        reason: String,
        stopTracking: Boolean = false
    ) {
        playbackStatsOwner.flushAsync(reason, stopTracking, writesEnabled = initialized)
    }

    /**
     */
    internal fun syncExoRepeatMode() {
        val timerState = sleepTimerManager.timerState.value
        val shouldLetPlaybackEndForSleepTimer =
            timerState.isActive && timerState.mode == SleepTimerMode.FINISH_CURRENT
        val desired = resolveExoRepeatMode(
            repeatModeSetting = repeatModeSetting,
            shouldLetPlaybackEndForSleepTimer = shouldLetPlaybackEndForSleepTimer
        )
        if (player.repeatMode != desired) {
            player.repeatMode = desired
        }
    }

    internal fun shouldResumePlaybackSnapshot(): Boolean {
        return resumePlaybackRequested || playJob?.isActive == true
    }

    /**
     */
    internal fun computeCacheKey(
        song: SongItem,
        youtubeQualityOverride: String? = null,
        youtubePreferM4aOverride: Boolean? = null
    ): String = PlaybackMediaItemFactory.cacheKey(
        song = song,
        context = application,
        youtubeQualityOverride = youtubeQualityOverride,
        youtubePreferM4a = youtubePreferM4aOverride ?: YOUTUBE_PLAYBACK_PREFER_M4A,
        neteaseFallbackEnabled = { neteaseAutoSourceSwitchEnabled || neteaseLocalSourceFallbackEnabled },
        youtubeQuality = ::effectiveYouTubeQuality,
        biliQuality = ::effectiveBiliQuality,
        neteaseQuality = ::effectiveNeteaseQuality
    )

    internal fun buildNeteasePlaybackCacheKey(
        songId: Long,
        preferredQuality: String,
        useFallbackNamespace: Boolean
    ): String = PlaybackMediaItemFactory.neteaseCacheKey(
        songId,
        preferredQuality,
        useFallbackNamespace
    )

    internal fun buildNeteasePreviewCacheKey(
        songId: Long,
        preferredQuality: String
    ): String = PlaybackMediaItemFactory.neteasePreviewCacheKey(songId, preferredQuality)

    /**
     * 键必须在解析前确定, 预取与播放才能对齐同一份缓存
     * 所以不能并入解析后才知道的 itag, 同键下的表示变化由缓存失效兜底
     */
    internal fun computeYouTubeCacheKey(
        videoId: String,
        preferredQuality: String = effectiveYouTubeQuality(),
        preferM4a: Boolean = YOUTUBE_PLAYBACK_PREFER_M4A
    ): String = PlaybackMediaItemFactory.youtubeCacheKey(videoId, preferredQuality, preferM4a)

    internal fun buildMediaItem(
        song: SongItem,
        url: String,
        cacheKey: String,
        mimeType: String? = null,
        allowCustomCacheKey: Boolean = true
    ): MediaItem = PlaybackMediaItemFactory.mediaItem(
        song,
        url,
        cacheKey,
        mimeType,
        { if (allowCustomCacheKey) safeCustomPlaybackCacheKey(cacheKey) else null }
    )

    internal fun applyWakeModeForPlaybackUrl(url: String?) {
        val wakeMode = resolvePlaybackWakeMode(url)
        if (wakeMode == currentWakeMode) return
        player.setWakeMode(wakeMode)
        currentWakeMode = wakeMode
    }

    internal fun applyInitialPlaybackWakeMode() {
        player.setWakeMode(DEFAULT_PLAYBACK_WAKE_MODE)
        currentWakeMode = DEFAULT_PLAYBACK_WAKE_MODE
    }

    fun updateInteractiveNowPlayingVisible(visible: Boolean) {
        interactiveNowPlayingVisible = visible
    }

    internal fun cancelVolumeFade(resetToFull: Boolean = false) =
        this.cancelVolumeFadeImpl(resetToFull)

    internal fun cancelPendingPauseRequest(resetVolumeToFull: Boolean = false) =
        this.cancelPendingPauseRequestImpl(resetVolumeToFull)

    fun initialize(app: Application, maxCacheSize: Long = 1024L * 1024 * 1024) =
        initializeImpl(app, maxCacheSize)

    internal fun initializePreloaded(
        app: Application,
        startupPlaybackPreferences: PlaybackPreferenceSnapshot,
        restoredStateSnapshot: RestoredPlayerStateSnapshot? = null
    ) = initializeImpl(
        app = app,
        maxCacheSize = startupPlaybackPreferences.maxCacheSizeBytes,
        startupPlaybackPreferences = startupPlaybackPreferences,
        restoredStateSnapshot = restoredStateSnapshot
    )

    @Suppress("unused")
    suspend fun clearCache(
        clearAudio: Boolean = true,
        clearImage: Boolean = true
    ): Pair<Boolean, String> = clearCacheImpl(clearAudio, clearImage)

    internal fun ensureInitialized() = ensureInitializedImpl()

    internal fun prefetchYouTubeQueueWindow(
        playlist: List<SongItem>,
        startIndex: Int,
        source: String = "manual"
    ) = prefetchYouTubeQueueWindowImpl(
        playlist = playlist,
        startIndex = startIndex,
        source = source
    )

    internal fun prefetchYouTubePlayableUrlWindow(
        playlist: List<SongItem>,
        startIndex: Int,
        source: String = "manual_url_only"
    ) = prefetchYouTubePlayableUrlWindowImpl(
        playlist = playlist,
        startIndex = startIndex,
        source = source
    )

    fun handleAudioBecomingNoisy(): Boolean = handleAudioBecomingNoisyImpl()

    internal fun refreshCurrentSongUrl(
        resumePositionMs: Long,
        allowFallback: Boolean,
        reason: String,
        bypassCooldown: Boolean = false,
        fallbackSeekPositionMs: Long? = null,
        resumePlaybackAfterRefresh: Boolean = true,
        resumedPlaybackCommandSource: PlaybackCommandSource? = null,
        youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
        cacheKeyToInvalidateBeforeResolve: String? = null,
        allowLocalSongRecovery: Boolean = false
    ) = refreshCurrentSongUrlImpl(
        resumePositionMs = resumePositionMs,
        allowFallback = allowFallback,
        reason = reason,
        bypassCooldown = bypassCooldown,
        fallbackSeekPositionMs = fallbackSeekPositionMs,
        resumePlaybackAfterRefresh = resumePlaybackAfterRefresh,
        resumedPlaybackCommandSource = resumedPlaybackCommandSource,
        youtubeRecoveryStrategy = youtubeRecoveryStrategy,
        cacheKeyToInvalidateBeforeResolve = cacheKeyToInvalidateBeforeResolve,
        allowLocalSongRecovery = allowLocalSongRecovery
    )

    internal fun handleTrackEndedIfNeeded(source: String) =
        this.handleTrackEndedIfNeededImpl(source)

    internal fun flushPlaybackStatsBlocking(
        reason: String,
        stopTracking: Boolean = false
    ) = flushPlaybackStatsBlockingImpl(reason, stopTracking)

    internal fun flushPlaybackStatsAsync(
        reason: String,
        stopTracking: Boolean = false
    ) = flushPlaybackStatsAsyncImpl(reason, stopTracking)

    fun playPlaylist(
        songs: List<SongItem>,
        startIndex: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ): Unit = this.playPlaylistImpl(songs, startIndex, commandSource)

    fun playLocalPlaylist(
        playlistId: Long,
        songs: List<SongItem>,
        startIndex: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ): Unit = this.playPlaylistImpl(
        songs = songs,
        startIndex = startIndex,
        commandSource = commandSource,
        localPlaylistId = playlistId
    )

    fun playBiliVideoParts(videoInfo: BiliClient.VideoBasicInfo, startIndex: Int, coverUrl: String) =
        this.playBiliVideoPartsImpl(videoInfo, startIndex, coverUrl)

    fun play(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) =
        this.playImpl(commandSource)

    fun pause(
        forcePersist: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = this.pauseImpl(forcePersist, commandSource)

    fun togglePlayPause() = this.togglePlayPauseImpl()

    internal fun restoreAudioRouteMute() = this.restoreAudioRouteMuteImpl()

    fun togglePlayPauseWithoutFade() = this.togglePlayPauseImpl(allowFade = false)

    fun seekTo(
        positionMs: Long,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = this.seekToImpl(positionMs, commandSource)

    fun next(
        force: Boolean = false,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = this.nextImpl(force, commandSource)

    fun previous(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) =
        this.previousImpl(commandSource)

    fun cycleRepeatMode(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) =
        this.cycleRepeatModeImpl(commandSource)

    fun release() = releaseImpl()

    fun setShuffle(
        enabled: Boolean,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = this.setShuffleImpl(enabled, commandSource)

    fun applyListenTogetherPlaybackMode(
        repeatMode: Int?,
        shuffleEnabled: Boolean?
    ) = this.applyListenTogetherPlaybackModeImpl(repeatMode, shuffleEnabled)

    internal fun stopProgressUpdates() = this.stopProgressUpdatesImpl()

    internal fun stopPlaybackImmediately(
        reason: String,
        forcePersist: Boolean = true
    ) = this.stopPlaybackImmediatelyImpl(reason, forcePersist)

    internal fun stopPlaybackPreservingQueue(clearMediaUrl: Boolean = false) =
        this.stopPlaybackPreservingQueueImpl(clearMediaUrl)

    fun hasItems(): Boolean = hasItemsImpl()

    fun addCurrentToFavorites() = addCurrentToFavoritesImpl()

    fun removeCurrentFromFavorites() = removeCurrentFromFavoritesImpl()

    fun toggleCurrentFavorite() = toggleCurrentFavoriteImpl()

    internal suspend fun persistState(
        positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
        shouldResumePlayback: Boolean =
            currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot()
    ) = persistStateImpl(positionMs, shouldResumePlayback)

    fun addCurrentToPlaylist(playlistId: Long) = addCurrentToPlaylistImpl(playlistId)

    fun playBiliVideoAsAudio(videos: List<BiliVideoItem>, startIndex: Int) =
        playBiliVideoAsAudioImpl(videos, startIndex)

    @Suppress("unused")
    suspend fun getNeteaseLyrics(songId: Long): List<LyricEntry> =
        getNeteaseLyricsImpl(songId)

    @Suppress("unused")
    suspend fun getNeteaseTranslatedLyrics(songId: Long): List<LyricEntry> =
        getNeteaseTranslatedLyricsImpl(songId)

    @Suppress("unused")
    suspend fun getNeteaseRomanizedLyrics(songId: Long): List<LyricEntry> =
        getNeteaseRomanizedLyricsImpl(songId)

    suspend fun getPreferredNeteaseLyricContent(songId: Long): String =
        getPreferredNeteaseLyricContentImpl(songId)

    suspend fun getPreferredNeteaseRomanizedLyricContent(songId: Long): String =
        getPreferredNeteaseRomanizedLyricContentImpl(songId)

    suspend fun getTranslatedLyrics(
        song: SongItem,
        skipPreferredSource: Boolean = false
    ): List<LyricEntry> = getTranslatedLyricsImpl(song, skipPreferredSource)

    suspend fun getRomanizedLyrics(song: SongItem): List<LyricEntry> =
        getRomanizedLyricsImpl(song)

    suspend fun getLyrics(
        song: SongItem,
        skipPreferredSource: Boolean = false
    ): List<LyricEntry> = getLyricsImpl(song, skipPreferredSource)

    internal suspend fun getPreferredLyricSourceResult(
        song: SongItem,
        preference: LyricSourcePreference
    ): PreferredLyricSourceResult? = getPreferredLyricSourceResultImpl(song, preference)

    internal fun getCachedPreferredLyricSourceResult(
        song: SongItem,
        preference: LyricSourcePreference,
        preferWordTimed: Boolean
    ): PreferredLyricSourceResult? = PlayerLyricsProvider.peekPreferredLyricSourceResult(
        song = song,
        preference = preference,
        preferWordTimed = preferWordTimed
    )

    fun playFromQueue(
        index: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = playFromQueueImpl(index, commandSource)

    fun replaceCurrentInQueueAndPlay(
        song: SongItem,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) = replaceCurrentInQueueAndPlayImpl(song, commandSource)

    fun addToQueueNext(song: SongItem) = addToQueueNextImpl(song)

    fun addToQueueEnd(song: SongItem) = addToQueueEndImpl(song)

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = moveQueueItemImpl(fromIndex, toIndex)

    fun removeQueueItem(index: Int) = removeQueueItemImpl(index)

    fun reorderQueue(queue: List<SongItem>, currentIndexInQueue: Int) =
        reorderQueueImpl(queue, currentIndexInQueue)

    internal fun applyRemoteQueueUpdate(queue: List<SongItem>, currentIndexInQueue: Int) =
        applyRemoteQueueUpdateImpl(queue, currentIndexInQueue)

    fun resumeRestoredPlaybackIfNeeded(): Long? = resumeRestoredPlaybackIfNeededImpl()

    fun suppressFutureAutoResumeForCurrentSession(forcePersist: Boolean = false) =
        suppressFutureAutoResumeForCurrentSessionImpl(forcePersist)

    fun replaceMetadataFromSearch(
        originalSong: SongItem,
        selectedSong: SongSearchInfo,
        isAuto: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null
    ) = replaceMetadataFromSearchImpl(originalSong, selectedSong, isAuto, onComplete)

    suspend fun updateSongCustomInfo(
        originalSong: SongItem,
        customCoverUrl: String?,
        customName: String?,
        customArtist: String?,
        restoreBaseCover: Boolean = false,
        restoreBaseName: Boolean = false,
        restoreBaseArtist: Boolean = false,
        clearMatchedMetadata: Boolean = false,
        writeLocalMetadata: Boolean = false,
        writeLyrics: Boolean = false,
        persistManualRemoteCover: Boolean = false,
        restoreBaseLyrics: Boolean = false
    ) = updateSongCustomInfoImpl(
        originalSong,
        customCoverUrl,
        customName,
        customArtist,
        restoreBaseCover,
        restoreBaseName,
        restoreBaseArtist,
        clearMatchedMetadata,
        writeLocalMetadata,
        writeLyrics,
        persistManualRemoteCover,
        restoreBaseLyrics
    )

    fun hydrateSongMetadata(originalSong: SongItem, updatedSong: SongItem) =
        hydrateSongMetadataImpl(originalSong, updatedSong)

    suspend fun updateUserLyricOffset(songToUpdate: SongItem, newOffset: Long) =
        updateUserLyricOffsetImpl(songToUpdate, newOffset)

    suspend fun rebaseUserLyricOffsetsForSource(
        targetSource: MusicPlatform,
        previousDefaultOffsetMs: Long,
        newDefaultOffsetMs: Long
    ) = rebaseUserLyricOffsetsForSourceImpl(
        targetSource = targetSource,
        previousDefaultOffsetMs = previousDefaultOffsetMs,
        newDefaultOffsetMs = newDefaultOffsetMs
    )

    @Suppress("unused")
    suspend fun updateSongLyrics(songToUpdate: SongItem, newLyrics: String?) =
        this.updateSongLyricsImpl(songToUpdate, newLyrics)

    @Suppress("unused")
    suspend fun updateSongTranslatedLyrics(
        songToUpdate: SongItem,
        newTranslatedLyrics: String?
    ) = this.updateSongTranslatedLyricsImpl(songToUpdate, newTranslatedLyrics)

    suspend fun updateSongLyricsAndTranslation(
        songToUpdate: SongItem,
        newLyrics: String?,
        newTranslatedLyrics: String?,
        newRomanizedLyrics: String? = null,
        writeLocalMetadata: Boolean = false,
        persistLocalSidecars: Boolean = true,
        syncDownloadedMetadata: Boolean = true
    ): Boolean = updateSongLyricsAndTranslationImpl(
        songToUpdate = songToUpdate,
        newLyrics = newLyrics,
        newTranslatedLyrics = newTranslatedLyrics,
        newRomanizedLyrics = newRomanizedLyrics,
        writeLocalMetadata = writeLocalMetadata,
        persistLocalSidecars = persistLocalSidecars,
        syncDownloadedMetadata = syncDownloadedMetadata
    )
}
