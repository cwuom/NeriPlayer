package moe.ouom.neriplayer.ui.settings.route

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScaleTarget
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.settings.owner.AppLyricOffsetSettingsOwner
import moe.ouom.neriplayer.ui.settings.owner.AppSettingsCacheClearOwner

internal class AppAppearanceSettingsActions(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val onBackgroundImageAlphaPreview: (Float) -> Unit
) {
    val onDynamicColorChange: (Boolean) -> Unit = { scope.launch { repo.setDynamicColor(it) } }

    val onInternationalizationEnabledChange: (Boolean) -> Unit = { enabled ->
        scope.launch { repo.setInternationalizationEnabled(enabled) }
    }

    val onSeedColorChange: (String) -> Unit = { hex ->
            scope.launch { repo.setThemeSeedColor(hex) }
        }

    val onAddColorToPalette: (String) -> Unit = { hex ->
            scope.launch { repo.addThemePaletteColor(hex) }
        }

    val onRemoveColorFromPalette: (String) -> Unit = { hex ->
            scope.launch { repo.removeThemePaletteColor(hex) }
        }

    val onThemePaletteStyleChange: (String) -> Unit = { style ->
            scope.launch { repo.setThemePaletteStyle(style) }
        }

    val onThemeColorSpecChange: (String) -> Unit = { spec ->
            scope.launch { repo.setThemeColorSpec(spec) }
        }

    val onDevModeChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setDevModeEnabled(enabled) }
        }

    val onAdvancedBlurEnabledChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setAdvancedBlurEnabled(enabled) }
        }

    val onEnhancedAdvancedBlurEnabledChange: (Boolean) -> Unit = { enabled ->
            scope.launch {
                repo.setEnhancedAdvancedBlurEnabled(enabled)
            }
        }

    val onEnhancedAdvancedBlurRadiusDpChange: (Float) -> Unit = { radiusDp ->
            scope.launch {
                repo.setEnhancedAdvancedBlurRadiusDp(radiusDp)
            }
        }

    val onAdvancedBlurQualityChange: (AdvancedBlurQuality) -> Unit = { quality ->
            scope.launch { repo.setAdvancedBlurQuality(quality) }
        }

    val onNowPlayingAudioReactiveEnabledChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setNowPlayingAudioReactiveEnabled(enabled) }
        }

    val onNowPlayingDynamicBackgroundEnabledChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setNowPlayingDynamicBackgroundEnabled(enabled) }
        }

    val onNowPlayingCoverBlurBackgroundEnabledChange: (Boolean) -> Unit = { enabled ->
            scope.launch {
                repo.setNowPlayingCoverBlurBackgroundEnabled(enabled)
            }
        }

    val onNowPlayingCoverBlurAmountChange: (Float) -> Unit = { amount ->
            scope.launch { repo.setNowPlayingCoverBlurAmount(amount) }
        }

    val onNowPlayingCoverBlurDarkenChange: (Float) -> Unit = { amount ->
            scope.launch { repo.setNowPlayingCoverBlurDarken(amount) }
        }

    val onUiDensityScaleChange: (Float) -> Unit = { scale ->
            scope.launch { repo.setUiDensityScale(scale) }
        }

    val onBackgroundImageChange: (Uri?) -> Unit = { uri ->
            scope.launch { repo.setBackgroundImageUri(uri?.toString()) }
        }

    val onBackgroundImageBlurChange: (Float) -> Unit = {}

    val onBackgroundImageBlurChangeFinished: (Float) -> Unit = { blur ->
            scope.launch { repo.setBackgroundImageBlur(blur) }
        }

    val onBackgroundImageAlphaChange: (Float) -> Unit = { alpha ->
            onBackgroundImageAlphaPreview(alpha)
        }

    val onBackgroundImageAlphaChangeFinished: (Float) -> Unit = { alpha ->
            onBackgroundImageAlphaPreview(alpha)
            scope.launch { repo.setBackgroundImageAlpha(alpha) }
        }
}

internal class AppLyricSettingsActions(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val lyricOffsetOwner: AppLyricOffsetSettingsOwner,
    private val cloudOffset: State<Long>,
    private val qqOffset: State<Long>
) {
    val onLyricBlurEnabledChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setLyricBlurEnabled(enabled) }
        }

    val onLyricBlurAmountChange: (Float) -> Unit = { amount ->
            scope.launch { repo.setLyricBlurAmount(amount) }
        }

    val onCloudMusicLyricDefaultOffsetMsChange: (Long) -> Unit = { offsetMs ->
            scope.launch {
                lyricOffsetOwner.changeCloudOffset(cloudOffset.value, offsetMs)
            }
        }

    val onQqMusicLyricDefaultOffsetMsChange: (Long) -> Unit = { offsetMs ->
            scope.launch {
                lyricOffsetOwner.changeQqOffset(qqOffset.value, offsetMs)
            }
        }

    val onKugouLyricDefaultOffsetMsChange: (Long) -> Unit = { offsetMs ->
            scope.launch { repo.setKugouLyricDefaultOffsetMs(offsetMs) }
        }

    val onLrclibLyricDefaultOffsetMsChange: (Long) -> Unit = { offsetMs ->
            scope.launch { repo.setLrclibLyricDefaultOffsetMs(offsetMs) }
        }

    val onAmllTtmlLyricDefaultOffsetMsChange: (Long) -> Unit = { offsetMs ->
            scope.launch { repo.setAmllTtmlLyricDefaultOffsetMs(offsetMs) }
        }

    val onResetAllLyricDefaultOffsets: () -> Unit = {
            scope.launch {
                lyricOffsetOwner.resetCloudAndQqOffsets(
                    cloudOffset.value, qqOffset.value
                )
            }
        }

    val onFloatingLyricsPreferencesChange: (FloatingLyricsPreferences) -> Unit = { preferences ->
            scope.launch { repo.setFloatingLyricsPreferences(preferences) }
        }

    val onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit = { target: LyricFontScaleTarget, scale ->
            scope.launch { repo.setLyricFontScale(target, scale) }
        }
}

internal class AppAudioQualitySettingsActions(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope
) {
    val onQualityChange: (String) -> Unit = { scope.launch { repo.setAudioQuality(it) } }

    val onYouTubeQualityChange: (String) -> Unit = {
            scope.launch { repo.setYouTubeAudioQuality(it) }
        }

    val onBiliQualityChange: (String) -> Unit = { scope.launch { repo.setBiliAudioQuality(it) } }

    val onMobileDataFollowDefaultAudioQualityChange: (Boolean) -> Unit = { enabled ->
            scope.launch {
                repo.setMobileDataFollowDefaultAudioQuality(enabled)
            }
        }

    val onMobileDataNeteaseAudioQualityChange: (String) -> Unit = { quality ->
            scope.launch {
                repo.setMobileDataNeteaseAudioQuality(quality)
            }
        }

    val onMobileDataYouTubeAudioQualityChange: (String) -> Unit = { quality ->
            scope.launch {
                repo.setMobileDataYouTubeAudioQuality(quality)
            }
        }

    val onMobileDataBiliAudioQualityChange: (String) -> Unit = { quality ->
            scope.launch {
                repo.setMobileDataBiliAudioQuality(quality)
            }
        }
}

internal class AppPlaybackSettingsActions(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val context: Context,
    private val composeResources: Resources
) {
    val onPlaybackFadeInChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setPlaybackFadeIn(enabled) }
        }

    val onPlaybackCrossfadeNextChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setPlaybackCrossfadeNext(enabled) }
        }

    val onSleepTimerFinishCurrentOnExpiryChange: (Boolean) -> Unit = { enabled ->
            scope.launch {
                repo.setSleepTimerFinishCurrentOnExpiry(enabled)
            }
        }

    val onPlaybackFadeInDurationMsChange: (Long) -> Unit = { duration ->
            scope.launch { repo.setPlaybackFadeInDurationMs(duration) }
        }

    val onPlaybackFadeOutDurationMsChange: (Long) -> Unit = { duration ->
            scope.launch { repo.setPlaybackFadeOutDurationMs(duration) }
        }

    val onPlaybackCrossfadeInDurationMsChange: (Long) -> Unit = { duration ->
            scope.launch { repo.setPlaybackCrossfadeInDurationMs(duration) }
        }

    val onPlaybackCrossfadeOutDurationMsChange: (Long) -> Unit = { duration ->
            scope.launch { repo.setPlaybackCrossfadeOutDurationMs(duration) }
        }

    val onPlaybackVolumeNormalizationEnabledChange: (Boolean) -> Unit = { enabled ->
            PlayerManager.setPlaybackVolumeNormalizationEnabled(enabled)
        }

    val onPlaybackHighResolutionOutputEnabledChange: (Boolean) -> Unit = { enabled ->
            PlayerManager.setPlaybackHighResolutionOutputEnabled(enabled)
            AppFeedback.show(
                context = context,
                message = composeResources.getString(CoreCommonR.string.settings_restart_hint)
            )
        }

    val onPlaybackVolumeBalanceChange: (Float) -> Unit = { balance ->
            PlayerManager.setPlaybackVolumeBalance(balance)
        }

    val onKeepLastPlaybackProgressChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setKeepLastPlaybackProgress(enabled) }
        }

    val onRememberLongFormPlaybackProgressChange: (Boolean) -> Unit = { enabled ->
            scope.launch {
                repo.setRememberLongFormPlaybackProgress(enabled)
            }
        }

    val onKeepPlaybackModeStateChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setKeepPlaybackModeState(enabled) }
        }

    val onNeteaseAutoSourceSwitchChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setNeteaseAutoSourceSwitch(enabled) }
        }

    val onNeteaseLocalSourceFallbackChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setNeteaseLocalSourceFallback(enabled) }
        }

    val onStopOnBluetoothDisconnectChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setStopOnBluetoothDisconnect(enabled) }
        }

    val onUsbExclusivePlaybackChange: (Boolean) -> Unit = { enabled ->
            if (PlayerManager.beginUsbExclusiveToggleTransitionFromUi(enabled)) {
                scope.launch { repo.setUsbExclusivePlayback(enabled) }
            }
        }

    val onAllowMixedPlaybackChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setAllowMixedPlayback(enabled) }
        }

    val onPreemptAudioFocusChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setPreemptAudioFocus(enabled) }
        }
}

internal class AppHomeSettingsActions(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope
) {
    val onDefaultStartDestinationChange: (String) -> Unit = { route ->
            scope.launch { repo.setDefaultStartDestination(route) }
        }

    val onShowHomeContinueCardChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setHomeCardContinue(enabled) }
        }

    val onShowHomeTrendingCardChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setHomeCardTrending(enabled) }
        }

    val onShowHomeRadarCardChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setHomeCardRadar(enabled) }
        }

    val onShowHomeRecommendedCardChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setHomeCardRecommended(enabled) }
        }
}

internal class AppStorageSettingsActions(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val cacheClearOwner: AppSettingsCacheClearOwner,
    private val snackbarHostState: SnackbarHostState
) {
    val onBypassProxyChange: (Boolean) -> Unit = { enabled ->
            scope.launch { repo.setBypassProxy(enabled) }
        }

    val onDownloadDirectoryUriChange: (String?, String?) -> Unit = { uri, label ->
            scope.launch {
                repo.setDownloadDirectory(uri, label)
                ManagedDownloadStorage.updateConfiguredTreeUri(uri)
                ManagedDownloadStorage.updateCustomDirectoryLabel(label)
            }
        }

    val onDownloadFileNameTemplateChange: (String?) -> Unit = { template ->
            scope.launch { repo.setDownloadFileNameTemplate(template) }
        }

    val onMaxCacheSizeBytesChange: (Long) -> Unit = { size ->
            scope.launch { repo.setMaxCacheSizeBytes(size) }
        }

    val onClearCacheClick: (StorageCacheClearOptions) -> Unit = { options ->
            scope.launch {
                snackbarHostState.showNeriSnackbar(cacheClearOwner.clear(options))
            }
        }
}
