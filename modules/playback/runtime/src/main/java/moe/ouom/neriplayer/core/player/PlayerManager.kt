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

import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsTracker
import moe.ouom.neriplayer.core.player.presentation.command.errorResId
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.audio.reactive.AudioReactive
import moe.ouom.neriplayer.core.player.audio.output.PlaybackSoundOwner
import moe.ouom.neriplayer.core.player.audio.output.PlayerManagerPlaybackSoundPort
import moe.ouom.neriplayer.core.player.runtime.quality.PlaybackQualityOwner
import moe.ouom.neriplayer.core.player.audio.output.PlayerManagerPlaybackQualityPort
import moe.ouom.neriplayer.core.player.runtime.transport.PlaybackTransportOwner
import moe.ouom.neriplayer.core.player.audio.output.PlayerManagerPlaybackTransportPort
import moe.ouom.neriplayer.core.player.engine.datasource.ConditionalHttpDataSourceFactory
import moe.ouom.neriplayer.core.player.lifecycle.clearCacheImpl
import moe.ouom.neriplayer.core.player.lifecycle.ensureInitializedImpl
import moe.ouom.neriplayer.core.player.lifecycle.handleAudioBecomingNoisyImpl
import moe.ouom.neriplayer.core.player.lifecycle.initializeImpl
import moe.ouom.neriplayer.core.player.lifecycle.releaseImpl
import moe.ouom.neriplayer.core.player.integration.lyrics.PlayerManagerLyriconLyricsLoader
import moe.ouom.neriplayer.lyrics.output.LyriconPlaybackOutput
import moe.ouom.neriplayer.lyrics.output.LyriconPreferences
import moe.ouom.neriplayer.core.player.media.LocalPlaybackMediaResolver
import moe.ouom.neriplayer.core.player.media.PlaybackMediaItemFactory
import moe.ouom.neriplayer.core.player.lyrics.syncExternalBluetoothLyrics
import moe.ouom.neriplayer.data.model.playback.AudioDevice
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PreferredQualityKeys
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundConfig
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueDisplayState
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.session.PlayerQueueSessionBindings
import moe.ouom.neriplayer.core.player.queue.state.PlayerQueueStateStore
import moe.ouom.neriplayer.data.model.playback.RestoredPlaybackState
import moe.ouom.neriplayer.data.model.playback.PlaybackUrlCandidate
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.core.player.queue.policy.buildPlayerQueueDisplayState
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.metadata.NeteaseLyricsCacheEntry
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.metadata.YouTubeMusicLyricsCacheEntry
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.command.LocalRoomControlRestriction
import moe.ouom.neriplayer.core.player.policy.command.resolveLocalRoomControlRestriction
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshInFlightController
import moe.ouom.neriplayer.core.player.policy.storage.RestorableLocalMediaState
import moe.ouom.neriplayer.core.player.policy.usb.UsbAudioSinkReconfigurationSnapshot
import moe.ouom.neriplayer.core.player.runtime.prefetch.GenericUrlPrefetchCache
import moe.ouom.neriplayer.core.player.runtime.prefetch.PlaybackDemandArbiter
import moe.ouom.neriplayer.core.player.prefetch.clearPlaybackDemandCacheKey
import moe.ouom.neriplayer.core.player.prefetch.prefetchYouTubePlayableUrlWindowImpl
import moe.ouom.neriplayer.core.player.prefetch.prefetchYouTubeQueueWindowImpl
import moe.ouom.neriplayer.core.player.runtime.refresh.YouTubePlaybackRecoveryStrategy
import moe.ouom.neriplayer.core.player.policy.command.resolveExoRepeatMode
import moe.ouom.neriplayer.core.player.policy.command.shouldShowPauseButtonForPlaybackControls
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
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsOwner
import moe.ouom.neriplayer.core.player.playback.AppPlaybackStatsWritePort
import moe.ouom.neriplayer.core.player.runtime.progress.PlaybackProgressOwner
import moe.ouom.neriplayer.core.player.playback.PlayerManagerPlaybackProgressPort
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
import moe.ouom.neriplayer.core.player.runtime.stats.trackEndDeduplicationKey
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
import moe.ouom.neriplayer.core.player.runtime.persistence.PlaybackStatePersistenceCoordinator
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
import moe.ouom.neriplayer.data.model.playback.SleepTimerMode
import moe.ouom.neriplayer.core.player.url.YOUTUBE_PLAYBACK_PREFER_M4A
import moe.ouom.neriplayer.core.player.url.refreshCurrentSongUrlImpl
import moe.ouom.neriplayer.core.player.url.safeCustomPlaybackCacheKey
import moe.ouom.neriplayer.core.player.usb.confirmation.UsbExclusiveLoudPlaybackConfirmation
import moe.ouom.neriplayer.core.player.usb.confirmation.UsbExclusiveLoudPlaybackConfirmationOwner
import moe.ouom.neriplayer.core.player.usb.confirmation.PlayerManagerUsbLoudPlaybackSnapshotPort
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
import moe.ouom.neriplayer.core.player.watchdog.cancelPlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.clearActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.shouldTreatReadyAtStartAsUnhealthyPrepared
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_KUGOU_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_LRCLIB_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.DEFAULT_AMLL_TTML_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusivePreferences
import moe.ouom.neriplayer.data.ltw.mapping.buildStableTrackKey
import moe.ouom.neriplayer.core.player.ltw.resolvedAudioId
import moe.ouom.neriplayer.core.player.ltw.resolvedChannelId
import moe.ouom.neriplayer.core.player.ltw.resolvedPlaylistContextId
import moe.ouom.neriplayer.core.player.ltw.resolvedSubAudioId
import moe.ouom.neriplayer.data.ltw.playback.authoritativeStreamUrlForCurrentTrack
import moe.ouom.neriplayer.data.ltw.playback.currentStableKey
import moe.ouom.neriplayer.data.ltw.playback.shouldHoldListenTogetherPlaybackForSafetyPause
import moe.ouom.neriplayer.data.ltw.playback.shouldMuteListenTogetherListenerForAudioRouteLoss
import moe.ouom.neriplayer.data.ltw.session.membership.resolveListenTogetherSessionRole
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.playback.stopPlaybackImmediatelyImpl
import moe.ouom.neriplayer.common.locale.LanguageManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import moe.ouom.neriplayer.core.player.queue.policy.PlayerQueueEditOwner
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity

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
    const val NETEASE_SOURCE_TAG = moe.ouom.neriplayer.data.model.SongSourceTags.NETEASE

    @Volatile
    internal var initialized = false

    @Volatile
    internal var initializationInProgress = false
    internal val initializationLock = Any()
    internal lateinit var application: Application
    internal lateinit var player: ExoPlayer
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
    internal var playbackStatsOwner = PlaybackStatsOwner(
        ioScope, AppPlaybackStatsWritePort, PlaybackStatsTracker(AppQueueSongIdentity::stableKey)
    )
    @Volatile
    internal var usbExclusiveLivenessOwner = UsbExclusiveLivenessOwner(mainScope, PlayerManagerUsbExclusiveLivenessPort)
    internal var usbInterruptedPlaybackOwner = UsbInterruptedPlaybackOwner(mainScope, PlayerManagerUsbInterruptedPlaybackPort)
    internal var progressJob: Job? = null
    internal var playbackRuntimeWatchdogJob: Job? = null
    @Volatile
    internal var playbackRuntimeWatchdogToken = 0L
    private val lyriconOutput = LyriconPlaybackOutput(
        loader = PlayerManagerLyriconLyricsLoader,
        stableKey = { it.stableKey() },
        sameIdentity = { song, currentSong -> currentSong?.sameIdentityAs(song) == true },
    )
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
    internal var playbackSoundOwner = PlaybackSoundOwner(mainScope, ioScope, PlayerManagerPlaybackSoundPort)
    internal var playbackTransportOwner = PlaybackTransportOwner(
        mainScope, PlayerManagerPlaybackTransportPort, SystemClock::elapsedRealtime
    )
    internal val playbackProgressOwner = PlaybackProgressOwner(
        PlayerManagerPlaybackProgressPort,
        AppQueueSongIdentity::sameIdentity,
        SystemClock::elapsedRealtime
    )
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
    internal var playbackQualityOwner = PlaybackQualityOwner(ioScope, PlayerManagerPlaybackQualityPort)

    internal val localRepo: LocalPlaylistRepository
        get() = LocalPlaylistRepository.getInstance(application)

    internal lateinit var stateFile: File
    internal lateinit var playbackStateFile: File

    internal var preferredQuality: String
        get() = playbackQualityOwner.neteasePreferredQuality
        set(value) = playbackQualityOwner.setPreferredQuality(PlaybackAudioSource.NETEASE, value)
    internal var youtubePreferredQuality: String
        get() = playbackQualityOwner.youtubePreferredQuality
        set(value) = playbackQualityOwner.setPreferredQuality(PlaybackAudioSource.YOUTUBE_MUSIC, value)
    internal var biliPreferredQuality: String
        get() = playbackQualityOwner.biliPreferredQuality
        set(value) = playbackQualityOwner.setPreferredQuality(PlaybackAudioSource.BILIBILI, value)

    /**
     * 各平台的音质偏好, 供切换弹窗回显
     *
     * 播放页弹窗改的是全局偏好, 选中项不能拿当前流实测出来的档位充数
     * 否则平台只发得出低码率时弹窗会显示低档, 用户点一下就把默认设置改掉了
     */
    val preferredQualityKeys: StateFlow<PreferredQualityKeys>
        get() = playbackQualityOwner.preferredKeys
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
    internal val playbackSoundConfig: PlaybackSoundConfig
        get() = playbackSoundOwner.config
    internal val playbackHighResolutionOutputEnabled: Boolean
        get() = playbackSoundOwner.highResolutionEnabled
    internal var lyriconEnabled = false
    @Volatile
    internal var amllLyricsEnabled = false
    @Volatile
    internal var preferWordTimedLyrics = true
    @Volatile
    var defaultLyricSource = LyricSourcePreference.Automatic
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
    var usbExclusivePlaybackEnabled = false
    internal val usbExclusiveAppInForeground: Boolean
        get() = usbExclusiveLivenessOwner.appInForeground
    @Volatile
    internal var usbExclusivePreferences = UsbExclusivePreferences()
    internal var allowMixedPlaybackEnabled = false

    internal val queueStore = PlayerQueueStateStore(AppQueueSongIdentity)
    internal val queueEdits = PlayerQueueEditOwner(AppQueueSongIdentity)
    internal val queueSessionBindings = PlayerQueueSessionBindings(queueStore)
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
    internal val shuffleRestorePlaylistReference: List<SongItem>?
        get() = queueStore.sessionSnapshot().shuffleRestore?.playlist

    @Volatile
    internal var consecutivePlayFailures = 0
    internal const val MAX_CONSECUTIVE_FAILURES = 10
    internal const val MEDIA_URL_STALE_MS = 10 * 60 * 1000L
    internal const val URL_REFRESH_COOLDOWN_MS = 10 * 1000L
    internal const val STATE_PERSIST_INTERVAL_MS = 15 * 1000L
    internal const val STATE_PERSIST_DEBOUNCE_MS = 250L
    internal const val DEFAULT_FADE_DURATION_MS = 500L
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
    internal const val MIN_FADE_STEPS = 4
    internal const val MAX_FADE_STEPS = 30
    internal data class UrlRefreshOperation(
        val deferred: CompletableDeferred<SongUrlResult>,
        val job: Job
    )
    internal val urlRefreshController = RefreshInFlightController<UrlRefreshOperation>()
    internal val expeditedYouTubeSeekRecoveryPending: Boolean
        get() = playbackProgressOwner.expeditedYouTubeSeekRecoveryPending
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
    @Volatile
    internal var resumePlaybackRequested = false
        private set(value) {
            field = value
            urlRefreshController.cancelIfPlaybackIntentChanged(value)
            syncPlaybackControlPlayingState()
        }
    @Volatile
    internal var suppressAutoResumeForCurrentSession = false
    internal val listenTogetherSyncPlaybackRate: Float
        get() = playbackSoundOwner.listenTogetherSyncRate

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

    val playbackDurationFlow: StateFlow<Long>
        get() = playbackProgressOwner.duration

    internal val _usbExclusivePlaybackPreparingFlow = MutableStateFlow(false)
    val usbExclusivePlaybackPreparingFlow: StateFlow<Boolean> =
        _usbExclusivePlaybackPreparingFlow

    val shuffleModeFlow: StateFlow<Boolean> = queueStore.shuffleModeFlow

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
    val usbExclusiveLoudPlaybackConfirmationFlow:
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
    val currentPlaybackAudioInfoFlow: StateFlow<PlaybackAudioInfo?> = _currentPlaybackAudioInfo

    val playbackSoundStateFlow: StateFlow<PlaybackSoundState>
        get() = playbackSoundOwner.state

    /** 本地歌单快照, 供收藏状态和歌单选择弹窗使用 */
    internal val _playlistsFlow = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlistsFlow: StateFlow<List<LocalPlaylist>> = _playlistsFlow
    internal val _localPlaylistsReadyFlow = MutableStateFlow(false)
    val localPlaylistsReadyFlow: StateFlow<Boolean> = _localPlaylistsReadyFlow
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

    val biliRepo by lazy { PlayerDependencies.repositories.biliPlaybackRepository }
    val biliClient by lazy { PlayerDependencies.repositories.biliClient }
    val neteaseClient by lazy { PlayerDependencies.repositories.neteaseClient }
    val youtubeMusicPlaybackRepository by lazy { PlayerDependencies.repositories.youtubeMusicPlaybackRepository }
    val youtubeMusicClient by lazy { PlayerDependencies.repositories.youtubeMusicClient }

    val cloudMusicSearchApi by lazy { PlayerDependencies.repositories.cloudMusicSearchApi }
    val qqMusicSearchApi by lazy { PlayerDependencies.repositories.qqMusicSearchApi }
    val lrcLibClient by lazy { PlayerDependencies.repositories.lrcLibClient }
    val amllTtmlClient by lazy { PlayerDependencies.repositories.amllTtmlClient }

    // YouTube Music 歌词缓存, 避免短时间内重复请求
    val ytMusicLyricsCache = android.util.LruCache<String, YouTubeMusicLyricsCacheEntry>(20)
    // 网易云歌词缓存, 避免原文/翻译和编辑器回退重复打接口
    val neteaseLyricsCache = android.util.LruCache<Long, NeteaseLyricsCacheEntry>(20)

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
        playbackProgressOwner.persistPreviousSongProgress(previousSong, song)
        publishCurrentSong(song)
        if (previousSong !== song) syncChangedSongOutputs(song, syncLyricon)
    }

    private fun publishCurrentSong(song: SongItem?) {
        _currentSongFlow.value = song
        lyriconOutput.publishSong(song)
        playbackProgressOwner.onCurrentSongPublished(song)
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

    internal fun resolveRememberedLongFormPlaybackStartPosition(
        song: SongItem,
        requestedPositionMs: Long,
        allowRememberedPosition: Boolean
    ): Long = playbackProgressOwner.resolveRememberedStartPosition(
        song, requestedPositionMs, allowRememberedPosition
    )

    internal fun persistLongFormPlaybackProgress(song: SongItem?, positionMs: Long, durationMs: Long) {
        playbackProgressOwner.persistLongFormProgress(song, positionMs, durationMs)
    }

    internal fun persistCurrentLongFormPlaybackProgress() {
        playbackProgressOwner.persistCurrentLongFormProgress()
    }

    internal fun syncLyriconSong(
        song: SongItem?,
        lyricOffsetOverrideMs: Long? = null,
    ) {
        lyriconOutput.syncSong(ioScope, song, lyriconPreferencesSnapshot(), lyricOffsetOverrideMs)
    }

    internal fun cancelLyriconUpdate() {
        lyriconOutput.cancel()
    }

    internal fun hasPendingLyriconUpdate(): Boolean {
        return lyriconOutput.hasPendingUpdate()
    }

    internal fun updateLyriconLyricOffset(song: SongItem? = _currentSongFlow.value) {
        lyriconOutput.updateOffset(song, lyriconPreferencesSnapshot(), _playbackPositionMs.value)
    }

    private fun lyriconPreferencesSnapshot(): LyriconPreferences {
        return LyriconPreferences(
            enabled = lyriconEnabled,
            preferredSource = defaultLyricSource,
            cloudMusicOffsetMs = cloudMusicLyricDefaultOffsetMs,
            qqMusicOffsetMs = qqMusicLyricDefaultOffsetMs,
            kugouOffsetMs = kugouLyricDefaultOffsetMs,
            lrcLibOffsetMs = lrclibLyricDefaultOffsetMs,
            amllTtmlOffsetMs = amllTtmlLyricDefaultOffsetMs,
        )
    }

    internal fun isApplicationInitialized(): Boolean = this::application.isInitialized

    fun bindApplication(app: Application) {
        if (isApplicationInitialized()) return
        synchronized(initializationLock) {
            if (!isApplicationInitialized()) {
                application = app
            }
        }
    }

    fun isPlayerInitialized(): Boolean = this::player.isInitialized

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

    fun isTransportActive(): Boolean = playbackTransportOwner.isTransportActive()

    internal fun isTransportActiveWithoutInitialization(): Boolean =
        playbackTransportOwner.isTransportActiveWithoutInitialization()

    fun shouldRunPlaybackServiceInForeground(): Boolean =
        playbackTransportOwner.shouldRunForegroundService()

    fun shouldBootstrapPlaybackServiceOnAppLaunch(): Boolean =
        playbackTransportOwner.shouldBootstrapService()

    fun isTransportBuffering(): Boolean = playbackTransportOwner.isBuffering()

    fun shouldIgnoreExternalPauseCommand(source: String): Boolean =
        playbackTransportOwner.shouldIgnoreExternalPause(source)

    internal fun markUsbExclusiveFocusDisrupted(change: Int) {
        playbackTransportOwner.markUsbFocusDisrupted(change)
    }

    internal fun pauseForUsbExclusiveFocusLoss(change: Int) {
        playbackTransportOwner.pauseForUsbFocusLoss(change)
    }

    fun markUsbExclusiveShortDisruption(reason: String) {
        playbackTransportOwner.markUsbShortDisruption(reason)
    }

    internal fun isRecentUsbExclusiveFocusDisruption(): Boolean =
        playbackTransportOwner.isRecentUsbFocusDisruption()

    internal fun markAutoTrackAdvance() {
        playbackTransportOwner.markAutoTrackAdvance()
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
        playbackTransportOwner.applyAudioFocusPolicy()
    }

    internal fun applyAudioFocusPolicyOnMainThread() {
        playbackTransportOwner.applyAudioFocusPolicyOnMainThread()
    }

    fun shouldUseUsbExclusiveFocusGuard(): Boolean =
        playbackTransportOwner.shouldUseUsbFocusGuard()

    internal fun shouldBypassPlatformAudioFocusForUsbExclusive(): Boolean =
        playbackTransportOwner.shouldBypassPlatformFocus()

    fun isUsbExclusiveNativePlaybackStable(): Boolean =
        playbackTransportOwner.isUsbNativePlaybackStable()

    fun isUsbExclusivePlaybackActiveForForegroundService(): Boolean =
        playbackTransportOwner.isUsbPlaybackActiveForForegroundService()

    internal fun isPreparedInPlayer(): Boolean =
        player.currentMediaItem != null &&
            player.playbackState == Player.STATE_READY &&
            !shouldTreatReadyAtStartAsUnhealthyPrepared()

    fun setListenTogetherSyncPlaybackRate(rate: Float) {
        ensureInitialized()
        playbackSoundOwner.setListenTogetherSyncRate(rate)
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
        consecutivePlayFailures = 0
        NPLogger.d("NERI-PlayerManager", "resetForListenTogetherJoin(): state cleared")
        scheduleStatePersist(positionMs = 0L, shouldResumePlayback = false, debounceMs = 0L)
    }

    internal fun pendingSeekPositionOrNull(): Long? =
        playbackProgressOwner.pendingSeekPositionOrNull()

    internal fun rememberPendingSeekPosition(positionMs: Long) {
        playbackProgressOwner.rememberPendingSeekPosition(positionMs)
    }

    internal fun clearPendingSeekPosition() {
        playbackProgressOwner.clearPendingSeekPosition()
    }

    internal fun resolveDisplayedPlaybackPosition(actualPositionMs: Long): Long =
        playbackProgressOwner.resolveDisplayedPosition(actualPositionMs)

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

    internal fun activeListenTogetherRoomState() = PlayerDependencies.listenTogether.roomState.value

    internal fun activeListenTogetherSessionState() = PlayerDependencies.listenTogether.sessionState.value

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
        PlayerDependencies.listenTogether.resumeListenerAfterSafetyPause()
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
        return moe.ouom.neriplayer.data.ltw.playback
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
        return PlayerDependencies.listenTogether.isControllerAudioLinkUnavailable(
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
                getLocalizedString(CoreCommonR.string.settings_usb_exclusive_status_transitioning)
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
            CoreCommonR.string.listen_together_error_local_playback_blocked,
            debugReason = "local_song_switch_blocked:${song.stableKey()}"
        )
    }

    internal fun isYouTubeMusicTrack(song: SongItem): Boolean {
        return PlaybackMediaItemFactory.isYouTubeSource(song)
    }

    fun isBiliTrack(song: SongItem): Boolean {
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
        playbackProgressOwner.maybeUpdateSongDuration(song, durationMs)
    }

    internal fun maybeBackfillCurrentSongDurationFromPlayer() {
        playbackProgressOwner.maybeBackfillCurrentSongDurationFromPlayer()
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
        playbackQualityOwner.changeCurrentPlaybackQuality(optionKey)
    }

    fun setPlaybackSpeed(speed: Float, persist: Boolean = true) {
        ensureInitialized()
        playbackSoundOwner.setSpeed(speed, persist)
    }

    fun setPlaybackPitch(pitch: Float, persist: Boolean = true) {
        ensureInitialized()
        playbackSoundOwner.setPitch(pitch, persist)
    }

    fun setPlaybackLoudnessGain(levelMb: Int, persist: Boolean = true) {
        ensureInitialized()
        playbackSoundOwner.setLoudnessGain(levelMb, persist)
    }

    fun setPlaybackVolumeBalance(balance: Float, persist: Boolean = true) {
        ensureInitialized()
        playbackSoundOwner.setVolumeBalance(balance, persist)
    }

    fun setPlaybackVolumeNormalizationEnabled(enabled: Boolean, persist: Boolean = true) {
        ensureInitialized()
        playbackSoundOwner.setVolumeNormalizationEnabled(enabled, persist)
    }

    fun setPlaybackHighResolutionOutputEnabled(enabled: Boolean, persist: Boolean = true) {
        ensureInitialized()
        playbackSoundOwner.setHighResolutionEnabled(enabled, persist)
    }

    fun setPlaybackEqualizerEnabled(enabled: Boolean, persist: Boolean = true) {
        ensureInitialized()
        playbackSoundOwner.setEqualizerEnabled(enabled, persist)
    }

    fun selectPlaybackEqualizerPreset(presetId: String, persist: Boolean = true) {
        ensureInitialized()
        playbackSoundOwner.selectEqualizerPreset(presetId, persist)
    }

    fun updatePlaybackEqualizerBandLevel(
        index: Int,
        levelMb: Int,
        persist: Boolean = true
    ) {
        ensureInitialized()
        playbackSoundOwner.updateEqualizerBandLevel(index, levelMb, persist)
    }

    fun resetPlaybackSoundSettings(persist: Boolean = true) {
        ensureInitialized()
        playbackSoundOwner.reset(persist)
    }

    internal fun applyPlaybackSoundConfig(newConfig: PlaybackSoundConfig, persist: Boolean) {
        playbackSoundOwner.applyConfig(newConfig, persist)
    }

    internal fun schedulePlaybackSoundConfigApply(
        previousConfig: PlaybackSoundConfig,
        newConfig: PlaybackSoundConfig
    ) {
        playbackSoundOwner.scheduleApply(previousConfig, newConfig)
    }

    internal fun applyPlaybackSoundConfigIfChanged(newConfig: PlaybackSoundConfig) {
        playbackSoundOwner.applyConfigIfChanged(newConfig)
    }

    internal fun scheduleQualityRefresh(
        source: PlaybackAudioSource,
        reason: String
    ) {
        playbackQualityOwner.scheduleRefresh(source, reason)
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
        snapshot = PlayerManagerUsbLoudPlaybackSnapshotPort::capture,
        continuePlayback = continuePlayback,
        cancelPlayback = cancelPlayback
    )

    fun confirmUsbExclusiveLoudPlayback(confirmationId: Long) {
        usbExclusiveLoudPlaybackConfirmationOwner.confirm(confirmationId)
    }

    fun cancelUsbExclusiveLoudPlayback(confirmationId: Long) {
        usbExclusiveLoudPlaybackConfirmationOwner.cancel(confirmationId)
    }

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
        playbackTransportOwner.applyWakeModeForUrl(url)
    }

    internal fun applyInitialPlaybackWakeMode() {
        playbackTransportOwner.applyInitialWakeMode()
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

    fun initializePreloaded(
        app: Application,
        startupPlaybackPreferences: PlaybackPreferenceSnapshot,
        restoredStateSnapshot: RestoredPlayerStateSnapshot? = null
    ) = initializeImpl(
        app = app,
        maxCacheSize = startupPlaybackPreferences.maxCacheSizeBytes,
        startupPlaybackPreferences = startupPlaybackPreferences,
        restoredStateSnapshot = restoredStateSnapshot
    )

    suspend fun clearCache(
        clearAudio: Boolean = true,
        clearImage: Boolean = true
    ): Pair<Boolean, String> = clearCacheImpl(clearAudio, clearImage)

    internal fun ensureInitialized() = ensureInitializedImpl()

    fun prefetchYouTubeQueueWindow(
        playlist: List<SongItem>,
        startIndex: Int,
        source: String = "manual"
    ) = prefetchYouTubeQueueWindowImpl(
        playlist = playlist,
        startIndex = startIndex,
        source = source
    )

    fun prefetchYouTubePlayableUrlWindow(
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

    fun flushPlaybackStatsAsync(
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

    fun playBiliVideoParts(videoInfo: VideoBasicInfo, startIndex: Int, coverUrl: String) =
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

    suspend fun getPreferredLyricSourceResult(
        song: SongItem,
        preference: LyricSourcePreference
    ): PreferredLyricSourceResult? = getPreferredLyricSourceResultImpl(song, preference)

    fun getCachedPreferredLyricSourceResult(
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
