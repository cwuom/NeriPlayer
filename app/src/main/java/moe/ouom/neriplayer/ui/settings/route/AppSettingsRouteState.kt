package moe.ouom.neriplayer.ui.settings.route

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import moe.ouom.neriplayer.data.settings.AdvancedBlurQuality
import moe.ouom.neriplayer.data.settings.DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.LyricFontScales
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences

internal data class AppThemeSettingsState(
    val dynamicColorEnabled: Boolean,
    val devModeEnabled: Boolean,
    val themeSeedColor: String,
    val themeColorPalette: List<String>,
    val themePaletteStyleValue: String
)

internal data class AppVisualBlurSettingsState(
    val themeColorSpecValue: String,
    val advancedBlurEnabled: Boolean,
    val enhancedAdvancedBlurEnabled: Boolean,
    val enhancedAdvancedBlurRadiusDp: Float,
    val advancedBlurQuality: AdvancedBlurQuality
)

internal data class AppVisualBackgroundSettingsState(
    val uiDensityScale: Float,
    val backgroundImageUri: String?,
    val backgroundImageBlur: Float
)

internal data class AppLyricPresentationSettingsState(
    val lyricBlurEnabled: Boolean,
    val lyricBlurAmount: Float,
    val floatingLyricsPreferences: FloatingLyricsPreferences,
    val lyricFontScales: LyricFontScales
)

internal data class AppLyricOffsetsSettingsState(
    val cloudMusicLyricDefaultOffsetMs: Long,
    val qqMusicLyricDefaultOffsetMs: Long,
    val kugouLyricDefaultOffsetMs: Long,
    val lrclibLyricDefaultOffsetMs: Long,
    val amllTtmlLyricDefaultOffsetMs: Long
)

internal data class AppNowPlayingVisualSettingsState(
    val nowPlayingAudioReactiveEnabled: Boolean,
    val nowPlayingDynamicBackgroundEnabled: Boolean,
    val nowPlayingCoverBlurBackgroundEnabled: Boolean,
    val nowPlayingCoverBlurAmount: Float,
    val nowPlayingCoverBlurDarken: Float
)

internal data class AppPlaybackFadeSettingsState(
    val playbackFadeIn: Boolean,
    val playbackCrossfadeNext: Boolean,
    val playbackFadeInDurationMs: Long,
    val playbackFadeOutDurationMs: Long,
    val playbackCrossfadeInDurationMs: Long
)

internal data class AppPlaybackOutputSettingsState(
    val playbackCrossfadeOutDurationMs: Long,
    val playbackVolumeNormalizationEnabled: Boolean,
    val playbackHighResolutionOutputEnabled: Boolean,
    val playbackVolumeBalance: Float,
    val usbExclusivePlayback: Boolean
)

internal data class AppPlaybackContinuitySettingsState(
    val sleepTimerFinishCurrentOnExpiry: Boolean,
    val keepLastPlaybackProgress: Boolean,
    val rememberLongFormPlaybackProgress: Boolean,
    val keepPlaybackModeState: Boolean,
    val stopOnBluetoothDisconnect: Boolean
)

internal data class AppPlaybackSourcesSettingsState(
    val neteaseAutoSourceSwitch: Boolean,
    val neteaseLocalSourceFallback: Boolean,
    val allowMixedPlayback: Boolean,
    val preemptAudioFocus: Boolean
)

internal data class AppDefaultAudioQualitySettingsState(
    val preferredQuality: String,
    val youtubePreferredQuality: String,
    val biliPreferredQuality: String,
    val mobileDataFollowDefaultAudioQuality: Boolean
)

internal data class AppMobileAudioQualitySettingsState(
    val mobileDataNeteaseAudioQuality: String,
    val mobileDataYouTubeAudioQuality: String,
    val mobileDataBiliAudioQuality: String
)

internal data class AppHomeCardsSettingsState(
    val showHomeContinueCard: Boolean,
    val showHomeTrendingCard: Boolean,
    val showHomeRadarCard: Boolean,
    val showHomeRecommendedCard: Boolean
)

internal data class AppStorageSettingsState(
    val bypassProxy: Boolean,
    val downloadDirectoryUri: String?,
    val downloadFileNameTemplate: String?,
    val maxCacheSizeBytes: Long
)

internal data class AppAppearanceSettingsState(
    val theme: AppThemeSettingsState,
    val visualBlur: AppVisualBlurSettingsState,
    val visualBackground: AppVisualBackgroundSettingsState,
    val nowPlayingVisual: AppNowPlayingVisualSettingsState
)

internal data class AppLyricsSettingsState(
    val lyricPresentation: AppLyricPresentationSettingsState,
    val lyricOffsets: AppLyricOffsetsSettingsState
)

internal data class AppPlaybackSettingsState(
    val playbackFade: AppPlaybackFadeSettingsState,
    val playbackOutput: AppPlaybackOutputSettingsState,
    val playbackContinuity: AppPlaybackContinuitySettingsState,
    val playbackSources: AppPlaybackSourcesSettingsState,
    val defaultAudioQuality: AppDefaultAudioQualitySettingsState,
    val mobileAudioQuality: AppMobileAudioQualitySettingsState,
    val usbExclusivePreferences: UsbExclusivePreferences
)

internal data class AppOtherSettingsState(
    val homeCards: AppHomeCardsSettingsState,
    val storage: AppStorageSettingsState,
    val internationalizationEnabled: Boolean
)

private data class AppPlaybackSettingsCore(
    val fade: AppPlaybackFadeSettingsState,
    val output: AppPlaybackOutputSettingsState,
    val continuity: AppPlaybackContinuitySettingsState,
    val sources: AppPlaybackSourcesSettingsState,
    val quality: AppDefaultAudioQualitySettingsState
)

internal data class AppSettingsRouteState(
    val appearance: AppAppearanceSettingsState,
    val lyrics: AppLyricsSettingsState,
    val playback: AppPlaybackSettingsState,
    val other: AppOtherSettingsState
)

private fun themeSettingsFlow(repo: SettingsRepository): Flow<AppThemeSettingsState> =
    combine(
        repo.dynamicColorFlow,
        repo.devModeEnabledFlow,
        repo.themeSeedColorFlow,
        repo.themeColorPaletteFlow,
        repo.themePaletteStyleFlow
    ) { v0, v1, v2, v3, v4 ->
        AppThemeSettingsState(v0, v1, v2, v3, v4)
    }

private fun visualBlurSettingsFlow(repo: SettingsRepository): Flow<AppVisualBlurSettingsState> =
    combine(
        repo.themeColorSpecFlow,
        repo.advancedBlurEnabledFlow,
        repo.enhancedAdvancedBlurEnabledFlow,
        repo.enhancedAdvancedBlurRadiusDpFlow,
        repo.advancedBlurQualityFlow
    ) { v0, v1, v2, v3, v4 ->
        AppVisualBlurSettingsState(v0, v1, v2, v3, v4)
    }

private fun visualBackgroundSettingsFlow(repo: SettingsRepository): Flow<AppVisualBackgroundSettingsState> =
    combine(
        repo.uiDensityScaleFlow,
        repo.backgroundImageUriFlow,
        repo.backgroundImageBlurFlow
    ) { v0, v1, v2 ->
        AppVisualBackgroundSettingsState(v0, v1, v2)
    }

private fun lyricPresentationSettingsFlow(repo: SettingsRepository): Flow<AppLyricPresentationSettingsState> =
    combine(
        repo.lyricBlurEnabledFlow,
        repo.lyricBlurAmountFlow,
        repo.floatingLyricsPreferencesFlow,
        repo.lyricFontScalesFlow
    ) { v0, v1, v2, v3 ->
        AppLyricPresentationSettingsState(v0, v1, v2, v3)
    }

private fun lyricOffsetsSettingsFlow(repo: SettingsRepository): Flow<AppLyricOffsetsSettingsState> =
    combine(
        repo.cloudMusicLyricDefaultOffsetMsFlow,
        repo.qqMusicLyricDefaultOffsetMsFlow,
        repo.kugouLyricDefaultOffsetMsFlow,
        repo.lrclibLyricDefaultOffsetMsFlow,
        repo.amllTtmlLyricDefaultOffsetMsFlow
    ) { v0, v1, v2, v3, v4 ->
        AppLyricOffsetsSettingsState(v0, v1, v2, v3, v4)
    }

private fun nowPlayingVisualSettingsFlow(repo: SettingsRepository): Flow<AppNowPlayingVisualSettingsState> =
    combine(
        repo.nowPlayingAudioReactiveEnabledFlow,
        repo.nowPlayingDynamicBackgroundEnabledFlow,
        repo.nowPlayingCoverBlurBackgroundEnabledFlow,
        repo.nowPlayingCoverBlurAmountFlow,
        repo.nowPlayingCoverBlurDarkenFlow
    ) { v0, v1, v2, v3, v4 ->
        AppNowPlayingVisualSettingsState(v0, v1, v2, v3, v4)
    }

private fun playbackFadeSettingsFlow(repo: SettingsRepository): Flow<AppPlaybackFadeSettingsState> =
    combine(
        repo.playbackFadeInFlow,
        repo.playbackCrossfadeNextFlow,
        repo.playbackFadeInDurationMsFlow,
        repo.playbackFadeOutDurationMsFlow,
        repo.playbackCrossfadeInDurationMsFlow
    ) { v0, v1, v2, v3, v4 ->
        AppPlaybackFadeSettingsState(v0, v1, v2, v3, v4)
    }

private fun playbackOutputSettingsFlow(repo: SettingsRepository): Flow<AppPlaybackOutputSettingsState> =
    combine(
        repo.playbackCrossfadeOutDurationMsFlow,
        repo.playbackVolumeNormalizationEnabledFlow,
        repo.playbackHighResolutionOutputEnabledFlow,
        repo.playbackVolumeBalanceFlow,
        repo.usbExclusivePlaybackFlow
    ) { v0, v1, v2, v3, v4 ->
        AppPlaybackOutputSettingsState(v0, v1, v2, v3, v4)
    }

private fun playbackContinuitySettingsFlow(repo: SettingsRepository): Flow<AppPlaybackContinuitySettingsState> =
    combine(
        repo.sleepTimerFinishCurrentOnExpiryFlow,
        repo.keepLastPlaybackProgressFlow,
        repo.rememberLongFormPlaybackProgressFlow,
        repo.keepPlaybackModeStateFlow,
        repo.stopOnBluetoothDisconnectFlow
    ) { v0, v1, v2, v3, v4 ->
        AppPlaybackContinuitySettingsState(v0, v1, v2, v3, v4)
    }

private fun playbackSourcesSettingsFlow(repo: SettingsRepository): Flow<AppPlaybackSourcesSettingsState> =
    combine(
        repo.neteaseAutoSourceSwitchFlow,
        repo.neteaseLocalSourceFallbackFlow,
        repo.allowMixedPlaybackFlow,
        repo.preemptAudioFocusFlow
    ) { v0, v1, v2, v3 ->
        AppPlaybackSourcesSettingsState(v0, v1, v2, v3)
    }

private fun defaultAudioQualitySettingsFlow(repo: SettingsRepository): Flow<AppDefaultAudioQualitySettingsState> =
    combine(
        repo.audioQualityFlow,
        repo.youtubeAudioQualityFlow,
        repo.biliAudioQualityFlow,
        repo.mobileDataFollowDefaultAudioQualityFlow
    ) { v0, v1, v2, v3 ->
        AppDefaultAudioQualitySettingsState(v0, v1, v2, v3)
    }

private fun mobileAudioQualitySettingsFlow(repo: SettingsRepository): Flow<AppMobileAudioQualitySettingsState> =
    combine(
        repo.mobileDataNeteaseAudioQualityFlow,
        repo.mobileDataYouTubeAudioQualityFlow,
        repo.mobileDataBiliAudioQualityFlow
    ) { v0, v1, v2 ->
        AppMobileAudioQualitySettingsState(v0, v1, v2)
    }

private fun homeCardsSettingsFlow(repo: SettingsRepository): Flow<AppHomeCardsSettingsState> =
    combine(
        repo.homeCardContinueFlow,
        repo.homeCardTrendingFlow,
        repo.homeCardRadarFlow,
        repo.homeCardRecommendedFlow
    ) { v0, v1, v2, v3 ->
        AppHomeCardsSettingsState(v0, v1, v2, v3)
    }

private fun storageSettingsFlow(repo: SettingsRepository): Flow<AppStorageSettingsState> =
    combine(
        repo.bypassProxyFlow,
        repo.downloadDirectoryUriFlow,
        repo.downloadFileNameTemplateFlow,
        repo.maxCacheSizeBytesFlow
    ) { v0, v1, v2, v3 ->
        AppStorageSettingsState(v0, v1, v2, v3)
    }

internal fun appSettingsRouteStateFlow(repo: SettingsRepository): Flow<AppSettingsRouteState> {
    val appearance = combine(themeSettingsFlow(repo), visualBlurSettingsFlow(repo), visualBackgroundSettingsFlow(repo), nowPlayingVisualSettingsFlow(repo)) { theme, visualBlur, visualBackground, nowPlayingVisual ->
        AppAppearanceSettingsState(theme, visualBlur, visualBackground, nowPlayingVisual)
    }
    val lyrics = combine(lyricPresentationSettingsFlow(repo), lyricOffsetsSettingsFlow(repo)) { lyricPresentation, lyricOffsets ->
        AppLyricsSettingsState(lyricPresentation, lyricOffsets)
    }
    val playbackCore = combine(
        playbackFadeSettingsFlow(repo), playbackOutputSettingsFlow(repo),
        playbackContinuitySettingsFlow(repo), playbackSourcesSettingsFlow(repo),
        defaultAudioQualitySettingsFlow(repo)
    ) { fade, output, continuity, sources, quality ->
        AppPlaybackSettingsCore(fade, output, continuity, sources, quality)
    }
    val playback = combine(
        playbackCore, mobileAudioQualitySettingsFlow(repo), repo.usbExclusivePreferencesFlow
    ) { core, mobile, usbPreferences ->
        AppPlaybackSettingsState(
            core.fade, core.output, core.continuity, core.sources, core.quality,
            mobile, usbPreferences
        )
    }
    val other = combine(
        homeCardsSettingsFlow(repo), storageSettingsFlow(repo), repo.internationalizationEnabledFlow
    ) { homeCards, storage, internationalizationEnabled ->
        AppOtherSettingsState(homeCards, storage, internationalizationEnabled)
    }
    return combine(appearance, lyrics, playback, other) { appearance, lyrics, playback, other ->
        AppSettingsRouteState(appearance, lyrics, playback, other)
    }
}

internal fun initialAppSettingsRouteState(
    initialThemeSnapshot: ThemePreferenceSnapshot,
    startupPlaybackPreferences: PlaybackPreferenceSnapshot,
    initialAdvancedBlurQuality: AdvancedBlurQuality
): AppSettingsRouteState = AppSettingsRouteState(
    appearance = AppAppearanceSettingsState(
        theme = AppThemeSettingsState(
            dynamicColorEnabled = initialThemeSnapshot.dynamicColor,
            devModeEnabled = false,
            themeSeedColor = ThemeDefaults.DEFAULT_SEED_COLOR_HEX,
            themeColorPalette = ThemeDefaults.PRESET_COLORS,
            themePaletteStyleValue = ThemeDefaults.DEFAULT_PALETTE_STYLE
        ),
        visualBlur = AppVisualBlurSettingsState(
            themeColorSpecValue = ThemeDefaults.DEFAULT_COLOR_SPEC,
            advancedBlurEnabled = true,
            enhancedAdvancedBlurEnabled = false,
            enhancedAdvancedBlurRadiusDp = DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
            advancedBlurQuality = initialAdvancedBlurQuality
        ),
        visualBackground = AppVisualBackgroundSettingsState(
            uiDensityScale = 1.0f,
            backgroundImageUri = null,
            backgroundImageBlur = 0f
        ),
        nowPlayingVisual = AppNowPlayingVisualSettingsState(
            nowPlayingAudioReactiveEnabled = true,
            nowPlayingDynamicBackgroundEnabled = true,
            nowPlayingCoverBlurBackgroundEnabled = false,
            nowPlayingCoverBlurAmount = 1.5f,
            nowPlayingCoverBlurDarken = 0.2f
        )
    ),
    lyrics = AppLyricsSettingsState(
        lyricPresentation = AppLyricPresentationSettingsState(
            lyricBlurEnabled = true,
            lyricBlurAmount = 1.5f,
            floatingLyricsPreferences = FloatingLyricsPreferences(),
            lyricFontScales = LyricFontScales( coverLyric = 1.0f, coverTranslation = 1.0f, lyricsPageLyric = 1.0f, lyricsPageTranslation = 1.0f )
        ),
        lyricOffsets = AppLyricOffsetsSettingsState(
            cloudMusicLyricDefaultOffsetMs = startupPlaybackPreferences.cloudMusicLyricDefaultOffsetMs,
            qqMusicLyricDefaultOffsetMs = startupPlaybackPreferences.qqMusicLyricDefaultOffsetMs,
            kugouLyricDefaultOffsetMs = startupPlaybackPreferences.kugouLyricDefaultOffsetMs,
            lrclibLyricDefaultOffsetMs = startupPlaybackPreferences.lrclibLyricDefaultOffsetMs,
            amllTtmlLyricDefaultOffsetMs = startupPlaybackPreferences.amllTtmlLyricDefaultOffsetMs
        )
    ),
    playback = AppPlaybackSettingsState(
        playbackFade = AppPlaybackFadeSettingsState(
            playbackFadeIn = startupPlaybackPreferences.playbackFadeIn,
            playbackCrossfadeNext = startupPlaybackPreferences.playbackCrossfadeNext,
            playbackFadeInDurationMs = 500L,
            playbackFadeOutDurationMs = 500L,
            playbackCrossfadeInDurationMs = 500L
        ),
        playbackOutput = AppPlaybackOutputSettingsState(
            playbackCrossfadeOutDurationMs = 500L,
            playbackVolumeNormalizationEnabled = startupPlaybackPreferences.playbackVolumeNormalizationEnabled,
            playbackHighResolutionOutputEnabled = startupPlaybackPreferences.playbackHighResolutionOutputEnabled,
            playbackVolumeBalance = startupPlaybackPreferences.playbackVolumeBalance,
            usbExclusivePlayback = startupPlaybackPreferences.usbExclusivePlayback
        ),
        playbackContinuity = AppPlaybackContinuitySettingsState(
            sleepTimerFinishCurrentOnExpiry = startupPlaybackPreferences.sleepTimerFinishCurrentOnExpiry,
            keepLastPlaybackProgress = true,
            rememberLongFormPlaybackProgress = startupPlaybackPreferences.rememberLongFormPlaybackProgress,
            keepPlaybackModeState = true,
            stopOnBluetoothDisconnect = true
        ),
        playbackSources = AppPlaybackSourcesSettingsState(
            neteaseAutoSourceSwitch = startupPlaybackPreferences.neteaseAutoSourceSwitch,
            neteaseLocalSourceFallback = startupPlaybackPreferences.neteaseLocalSourceFallback,
            allowMixedPlayback = false,
            preemptAudioFocus = startupPlaybackPreferences.preemptAudioFocus
        ),
        defaultAudioQuality = AppDefaultAudioQualitySettingsState(
            preferredQuality = "exhigh",
            youtubePreferredQuality = "high",
            biliPreferredQuality = "high",
            mobileDataFollowDefaultAudioQuality = startupPlaybackPreferences.mobileDataFollowDefaultAudioQuality
        ),
        mobileAudioQuality = AppMobileAudioQualitySettingsState(
            mobileDataNeteaseAudioQuality = startupPlaybackPreferences.mobileDataNeteaseAudioQuality,
            mobileDataYouTubeAudioQuality = startupPlaybackPreferences.mobileDataYouTubeAudioQuality,
            mobileDataBiliAudioQuality = startupPlaybackPreferences.mobileDataBiliAudioQuality
        ),
        usbExclusivePreferences = UsbExclusivePreferences()
    ),
    other = AppOtherSettingsState(
        homeCards = AppHomeCardsSettingsState(
            showHomeContinueCard = true,
            showHomeTrendingCard = true,
            showHomeRadarCard = true,
            showHomeRecommendedCard = true
        ),
        storage = AppStorageSettingsState(
            bypassProxy = true,
            downloadDirectoryUri = null,
            downloadFileNameTemplate = null,
            maxCacheSizeBytes = startupPlaybackPreferences.maxCacheSizeBytes
        ),
        internationalizationEnabled = false
    )
)
