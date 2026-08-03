package moe.ouom.neriplayer.ui.screen.tab

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
 * File: moe.ouom.neriplayer.ui.screen.tab/SettingsScreen
 * Created: 2025/8/8
 */

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.LyricFontScaleTarget
import moe.ouom.neriplayer.data.settings.LyricFontScales
import moe.ouom.neriplayer.data.settings.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.settings.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.settings.PlaybackControlSize
import moe.ouom.neriplayer.data.settings.generated.AutoSettingInfo
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import moe.ouom.neriplayer.data.settings.ThemeMode
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.MAX_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.MIN_LYRIC_FONT_SCALE
import moe.ouom.neriplayer.data.settings.background.BackgroundImageStorage
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSwitchItems
import moe.ouom.neriplayer.data.settings.normalizeMobileDataBiliAudioQuality
import moe.ouom.neriplayer.data.settings.normalizeMobileDataNeteaseAudioQuality
import moe.ouom.neriplayer.data.settings.normalizeMobileDataYouTubeAudioQuality
import moe.ouom.neriplayer.data.settings.normalizeLyricFontScale
import moe.ouom.neriplayer.data.settings.scaledLyricFontSize
import moe.ouom.neriplayer.data.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.storage.StorageUsageSummary
import moe.ouom.neriplayer.listentogether.invite.configuredListenTogetherBaseUrlOrNull
import moe.ouom.neriplayer.listentogether.invite.isDefaultListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.invite.resolveListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.validation.validateListenTogetherNickname
import moe.ouom.neriplayer.ui.component.settings.LanguageSettingItem
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassNavigationHandoff
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassScene
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.isolatedAdvancedGlassHorizontalTransition
import moe.ouom.neriplayer.ui.screen.tab.settings.about.SettingsAboutContent
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.LoginSuccessDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsBiliAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsNeteaseAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsYouTubeAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.component.LazyAnimatedVisibility
import moe.ouom.neriplayer.ui.screen.tab.settings.component.PlaybackServiceIdleShutdownSetting
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsAudioQualitySection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsBackupRestoreSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsDownloadSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsLyricsSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsMotionSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsPlaybackSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsStorageCacheSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsTrafficManagementSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeSeedListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.component.UsbExclusiveSettingsSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsGitHubDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsPreferenceDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsWebDavDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsHeader
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsHomeScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsPageGroupCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsResponsiveDetailScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsHomePageGroups
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsSearchEntry
import moe.ouom.neriplayer.ui.screen.tab.settings.page.backTargetPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.buildSettingsSearchEntries
import moe.ouom.neriplayer.ui.screen.tab.settings.page.resolveSettingsSearchHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.page.miuixSettingsSectionCardItem
import moe.ouom.neriplayer.ui.screen.tab.settings.page.searchSettingsEntries
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsSearchScrollAnchor
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreViewModel
import moe.ouom.neriplayer.ui.viewmodel.ConfigTransferViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private data class PendingDownloadDirectoryChange(
    val previousUri: String?,
    val targetUri: String?,
    val targetSummary: String,
    val releaseTargetPermissionOnCancel: Boolean
) {
    val shouldReleasePreviousPermission: Boolean
        get() = !previousUri.isNullOrBlank() &&
            !ManagedDownloadStorage.areEquivalentDirectoryUris(previousUri, targetUri)
}

private data class PendingSettingsSearchNavigation(
    val page: SettingsPage,
    val targetId: String,
    val requestId: Int
)

private fun isForwardSettingsPageTransition(
    initialPage: SettingsPage?,
    targetPage: SettingsPage?
): Boolean {
    if (targetPage == null) return false
    if (initialPage == null) return true
    if (targetPage.backTargetPage() == initialPage) return true
    if (initialPage.backTargetPage() == targetPage) return false
    return targetPage.ordinal >= initialPage.ordinal
}

private fun Context.neteaseQualityLabel(value: String): String {
    return when (value) {
        "standard" -> getString(R.string.settings_audio_quality_standard)
        "higher" -> getString(R.string.settings_audio_quality_higher)
        "exhigh" -> getString(R.string.settings_audio_quality_exhigh)
        "lossless" -> getString(R.string.settings_audio_quality_lossless)
        "hires" -> getString(R.string.quality_hires)
        "jyeffect" -> getString(R.string.settings_audio_quality_jyeffect)
        "sky" -> getString(R.string.settings_audio_quality_sky)
        "jymaster" -> getString(R.string.settings_audio_quality_jymaster)
        else -> value
    }
}

private fun Context.youtubeQualityLabel(value: String): String {
    return when (value) {
        "low" -> getString(R.string.settings_audio_quality_standard)
        "medium" -> getString(R.string.settings_audio_quality_medium)
        "high" -> getString(R.string.settings_audio_quality_high)
        "very_high" -> getString(R.string.quality_very_high)
        else -> value
    }
}

private fun Context.biliQualityLabel(value: String): String {
    return when (value) {
        "dolby" -> getString(R.string.settings_audio_quality_dolby)
        "hires" -> getString(R.string.quality_hires)
        "lossless" -> getString(R.string.settings_audio_quality_lossless)
        "high" -> getString(R.string.settings_audio_quality_high)
        "medium" -> getString(R.string.settings_audio_quality_medium)
        "low" -> getString(R.string.settings_audio_quality_low)
        else -> value
    }
}

@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(16.dp)

    AdvancedGlassSurface(
        role = AdvancedGlassRole.SettingsSection,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = shape,
        fallbackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.62f),
        tintColor = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                ),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(R.string.settings_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsSearchResultsCard(
    query: String,
    results: List<SettingsSearchEntry>,
    onResultClick: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    if (query.isBlank()) {
        return
    }

    MiuixSettingsSectionCard(modifier = modifier) {
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_search_empty),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            results.forEach { entry ->
                SettingsSearchResultRow(
                    entry = entry,
                    onClick = { onResultClick(entry) }
                )
            }
        }
    }
}

@Composable
private fun SettingsSearchResultRow(
    entry: SettingsSearchEntry,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = entry.page.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(entry.title) },
        supportingContent = {
            Text(stringResource(entry.page.titleRes))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun SettingsScreen(
    listState: androidx.compose.foundation.lazy.LazyListState,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    themeMode: ThemeMode,
    onThemeToggleRequest: (Offset, Float) -> Unit,
    onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit,
    preferredQuality: String,
    onQualityChange: (String) -> Unit,
    youtubePreferredQuality: String,
    onYouTubeQualityChange: (String) -> Unit,
    biliPreferredQuality: String,
    onBiliQualityChange: (String) -> Unit,
    mobileDataFollowDefaultAudioQuality: Boolean,
    onMobileDataFollowDefaultAudioQualityChange: (Boolean) -> Unit,
    mobileDataNeteaseAudioQuality: String,
    onMobileDataNeteaseAudioQualityChange: (String) -> Unit,
    mobileDataYouTubeAudioQuality: String,
    onMobileDataYouTubeAudioQualityChange: (String) -> Unit,
    mobileDataBiliAudioQuality: String,
    onMobileDataBiliAudioQualityChange: (String) -> Unit,
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    seedColorHex: String,
    onSeedColorChange: (String) -> Unit,
    themeColorPalette: List<String>,
    onAddColorToPalette: (String) -> Unit,
    onRemoveColorFromPalette: (String) -> Unit,
    themePaletteStyle: String,
    onThemePaletteStyleChange: (String) -> Unit,
    themeColorSpec: String,
    onThemeColorSpecChange: (String) -> Unit,
    lyricBlurEnabled: Boolean,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
    lyricBlurAmount: Float,
    onLyricBlurAmountChange: (Float) -> Unit,
    cloudMusicLyricDefaultOffsetMs: Long,
    onCloudMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    qqMusicLyricDefaultOffsetMs: Long,
    onQqMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    floatingLyricsPreferences: FloatingLyricsPreferences,
    onFloatingLyricsPreferencesChange: (FloatingLyricsPreferences) -> Unit,
    advancedBlurEnabled: Boolean,
    onAdvancedBlurEnabledChange: (Boolean) -> Unit,
    enhancedAdvancedBlurEnabled: Boolean,
    onEnhancedAdvancedBlurEnabledChange: (Boolean) -> Unit,
    enhancedAdvancedBlurRadiusDp: Float,
    onEnhancedAdvancedBlurRadiusDpChange: (Float) -> Unit,
    nowPlayingAudioReactiveEnabled: Boolean,
    onNowPlayingAudioReactiveEnabledChange: (Boolean) -> Unit,
    nowPlayingDynamicBackgroundEnabled: Boolean,
    onNowPlayingDynamicBackgroundEnabledChange: (Boolean) -> Unit,
    nowPlayingCoverBlurBackgroundEnabled: Boolean,
    onNowPlayingCoverBlurBackgroundEnabledChange: (Boolean) -> Unit,
    nowPlayingCoverBlurAmount: Float,
    onNowPlayingCoverBlurAmountChange: (Float) -> Unit,
    nowPlayingCoverBlurDarken: Float,
    onNowPlayingCoverBlurDarkenChange: (Float) -> Unit,
    lyricFontScales: LyricFontScales,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    uiDensityScale: Float,
    onUiDensityScaleChange: (Float) -> Unit,
    bypassProxy: Boolean,
    onBypassProxyChange: (Boolean) -> Unit,
    backgroundImageUri: String?,
    onBackgroundImageChange: (Uri?) -> Unit,
    downloadDirectoryUri: String?,
    downloadFileNameTemplate: String?,
    onDownloadDirectoryUriChange: (String?, String?) -> Unit,
    onDownloadFileNameTemplateChange: (String?) -> Unit,
    backgroundImageBlur: Float,
    onBackgroundImageBlurChange: (Float) -> Unit,
    onBackgroundImageBlurChangeFinished: (Float) -> Unit,
    backgroundImageAlpha: Float,
    onBackgroundImageAlphaChange: (Float) -> Unit,
    onBackgroundImageAlphaChangeFinished: (Float) -> Unit,
    defaultStartDestination: String,
    onDefaultStartDestinationChange: (String) -> Unit,
    showHomeContinueCard: Boolean,
    onShowHomeContinueCardChange: (Boolean) -> Unit,
    showHomeTrendingCard: Boolean,
    onShowHomeTrendingCardChange: (Boolean) -> Unit,
    showHomeRadarCard: Boolean,
    onShowHomeRadarCardChange: (Boolean) -> Unit,
    showHomeRecommendedCard: Boolean,
    onShowHomeRecommendedCardChange: (Boolean) -> Unit,
    homeHasRecentUsage: Boolean,
    playbackFadeIn: Boolean,
    onPlaybackFadeInChange: (Boolean) -> Unit,
    playbackCrossfadeNext: Boolean,
    onPlaybackCrossfadeNextChange: (Boolean) -> Unit,
    sleepTimerFinishCurrentOnExpiry: Boolean,
    onSleepTimerFinishCurrentOnExpiryChange: (Boolean) -> Unit,
    playbackFadeInDurationMs: Long,
    onPlaybackFadeInDurationMsChange: (Long) -> Unit,
    playbackFadeOutDurationMs: Long,
    onPlaybackFadeOutDurationMsChange: (Long) -> Unit,
    playbackCrossfadeInDurationMs: Long,
    onPlaybackCrossfadeInDurationMsChange: (Long) -> Unit,
    playbackCrossfadeOutDurationMs: Long,
    onPlaybackCrossfadeOutDurationMsChange: (Long) -> Unit,
    playbackVolumeNormalizationEnabled: Boolean,
    onPlaybackVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    playbackHighResolutionOutputEnabled: Boolean,
    onPlaybackHighResolutionOutputEnabledChange: (Boolean) -> Unit,
    playbackVolumeBalance: Float,
    onPlaybackVolumeBalanceChange: (Float) -> Unit,
    keepLastPlaybackProgress: Boolean,
    onKeepLastPlaybackProgressChange: (Boolean) -> Unit,
    rememberLongFormPlaybackProgress: Boolean,
    onRememberLongFormPlaybackProgressChange: (Boolean) -> Unit,
    keepPlaybackModeState: Boolean,
    onKeepPlaybackModeStateChange: (Boolean) -> Unit,
    neteaseAutoSourceSwitch: Boolean,
    onNeteaseAutoSourceSwitchChange: (Boolean) -> Unit,
    neteaseLocalSourceFallback: Boolean,
    onNeteaseLocalSourceFallbackChange: (Boolean) -> Unit,
    stopOnBluetoothDisconnect: Boolean,
    onStopOnBluetoothDisconnectChange: (Boolean) -> Unit,
    usbExclusivePlayback: Boolean,
    onUsbExclusivePlaybackChange: (Boolean) -> Unit,
    allowMixedPlayback: Boolean,
    onAllowMixedPlaybackChange: (Boolean) -> Unit,
    preemptAudioFocus: Boolean,
    onPreemptAudioFocusChange: (Boolean) -> Unit,
    onNavigateToDownloadManager: () -> Unit = {},
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onClearCacheClick: (StorageCacheClearOptions) -> Unit,
    onBeforeLanguageRestart: () -> Unit = {},
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val autoSettingsRepository = remember(context) { AutoSettingsRepository(context) }
    val listenTogetherPreferences = remember { AppContainer.listenTogetherPreferences }
    val listenTogetherApi = remember { AppContainer.listenTogetherApi }
    val listenTogetherSessionManager = remember { AppContainer.listenTogetherSessionManager }
    val listenTogetherSessionState by listenTogetherSessionManager.sessionState.collectAsState()
    val listenTogetherWorkerBaseUrlInput by listenTogetherPreferences.workerBaseUrlInputFlow.collectAsState(initial = "")
    val listenTogetherNickname by listenTogetherPreferences.nicknameFlow.collectAsState(initial = "")
    var pendingBackgroundImageBlur by rememberSaveable(backgroundImageUri) {
        mutableFloatStateOf(backgroundImageBlur)
    }
    var pendingBackgroundImageAlpha by rememberSaveable(backgroundImageUri) {
        mutableFloatStateOf(backgroundImageAlpha)
    }

    LaunchedEffect(backgroundImageBlur, backgroundImageUri) {
        if ((pendingBackgroundImageBlur - backgroundImageBlur).absoluteValue > 0.001f) {
            pendingBackgroundImageBlur = backgroundImageBlur
        }
    }
    LaunchedEffect(backgroundImageAlpha, backgroundImageUri) {
        if ((pendingBackgroundImageAlpha - backgroundImageAlpha).absoluteValue > 0.001f) {
            pendingBackgroundImageAlpha = backgroundImageAlpha
        }
    }

    val internationalEnabled by AppContainer.settingsRepo.internationalizationEnabledFlow
        .collectAsState(initial = false)
    val usbExclusivePreferences by AppContainer.settingsRepo.usbExclusivePreferencesFlow
        .collectAsState(initial = UsbExclusivePreferences())

    LaunchedEffect(nowPlayingDynamicBackgroundEnabled, nowPlayingCoverBlurBackgroundEnabled) {
        if (nowPlayingCoverBlurBackgroundEnabled) {
            if (nowPlayingDynamicBackgroundEnabled) {
                onNowPlayingDynamicBackgroundEnabledChange(false)
            }
            if (nowPlayingAudioReactiveEnabled) {
                onNowPlayingAudioReactiveEnabledChange(false)
            }
        } else if (!nowPlayingDynamicBackgroundEnabled && nowPlayingAudioReactiveEnabled) {
            onNowPlayingAudioReactiveEnabledChange(false)
        }
    }

    // 缓存设置的状态
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // 缓存类型选择状态
    var clearAudioCache by remember { mutableStateOf(true) }
    var clearImageCache by remember { mutableStateOf(true) }
    var clearDownloadStagingCache by remember { mutableStateOf(false) }
    var clearSharedMediaCache by remember { mutableStateOf(false) }
    var clearPlatformListCache by remember { mutableStateOf(false) }

    // 存储占用详情状态
    var showStorageDetails by remember { mutableStateOf(false) }
    var storageDetails by remember { mutableStateOf(StorageUsageSummary.Empty) }


    // 各种对话框和弹窗的显示状态 //
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNeteaseSheet by remember { mutableStateOf(false) }
    var showYouTubeQualityDialog by remember { mutableStateOf(false) }
    var showBiliQualityDialog by remember { mutableStateOf(false) }
    var showMobileDataNeteaseQualityDialog by remember { mutableStateOf(false) }
    var showMobileDataYouTubeQualityDialog by remember { mutableStateOf(false) }
    var showMobileDataBiliQualityDialog by remember { mutableStateOf(false) }
    var showDefaultStartDestinationDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showNeteaseSavedCookieDialog by remember { mutableStateOf(false) }
    var showBiliSheet by remember { mutableStateOf(false) }
    var showBiliSavedCookieDialog by remember { mutableStateOf(false) }
    var showYouTubeSheet by remember { mutableStateOf(false) }
    var showYouTubeSavedCookieDialog by remember { mutableStateOf(false) }

    var showColorPickerDialog by remember { mutableStateOf(false) }
    var showDpiDialog by remember { mutableStateOf(false) }
    var showGitHubConfigDialog by remember { mutableStateOf(false) }
    var showClearGitHubConfigDialog by remember { mutableStateOf(false) }
    var showWebDavConfigDialog by remember { mutableStateOf(false) }
    var showClearWebDavConfigDialog by remember { mutableStateOf(false) }
    var showListenTogetherResetUuidDialog by remember { mutableStateOf(false) }
    var showListenTogetherServerDialog by remember { mutableStateOf(false) }
    var showListenTogetherNicknameDialog by remember { mutableStateOf(false) }
    var listenTogetherServerInput by rememberSaveable { mutableStateOf("") }
    var listenTogetherNicknameInput by rememberSaveable { mutableStateOf("") }
    var listenTogetherNicknameError by remember { mutableStateOf<String?>(null) }
    var listenTogetherServerTesting by remember { mutableStateOf(false) }
    var listenTogetherServerTestMessage by remember { mutableStateOf<String?>(null) }
    // ------------------------------------

    val neteaseVm: NeteaseAuthViewModel = viewModel()
    var inlineMsg by remember { mutableStateOf<String?>(null) }
    var loginSuccessTitle by remember { mutableStateOf<String?>(null) }
    var showDownloadDirectorySwitchWarningDialog by remember { mutableStateOf(false) }
    var pendingDownloadDirectoryChange by remember { mutableStateOf<PendingDownloadDirectoryChange?>(null) }
    var isMigratingDownloadDirectory by remember { mutableStateOf(false) }
    val migrationProgress by ManagedDownloadStorage.migrationProgressFlow.collectAsState()
    val hasActiveDownloadOperations by GlobalDownloadManager.activeDownloadOperationsFlow.collectAsState()
    var confirmPhoneMasked by remember { mutableStateOf<String?>(null) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    val biliVm: BiliAuthViewModel = viewModel()
    var biliSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var neteaseSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    val youtubeVm: YouTubeAuthViewModel = viewModel()
    var youtubeSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    
    // 备份与恢复
    val backupRestoreVm: BackupRestoreViewModel = viewModel()
    val backupRestoreUiState by backupRestoreVm.uiState.collectAsStateWithLifecycleCompat()
    val configTransferVm: ConfigTransferViewModel = viewModel()
    val configTransferUiState by configTransferVm.uiState.collectAsStateWithLifecycleCompat()
    val localPlaylistCount = backupRestoreUiState.currentPlaylistCount
    val defaultDownloadDirectorySummary = composeResources.getString(R.string.settings_download_directory_default_label)
    val downloadDirectoryChangeEnabled = !hasActiveDownloadOperations && !isMigratingDownloadDirectory

    fun showSettingsMessage(message: String) {
        AppFeedback.show(context = context, message = message)
    }

    fun showListenTogetherMessage(message: String) {
        AppFeedback.showToast(context = context, message = message)
    }

    fun guardDownloadDirectoryChange(
        targetUri: String? = null,
        releaseTargetPermissionOnBlock: Boolean = false
    ): Boolean {
        if (!GlobalDownloadManager.hasActiveDownloadOperations()) {
            return false
        }
        if (
            releaseTargetPermissionOnBlock &&
            !targetUri.isNullOrBlank() &&
            !ManagedDownloadStorage.areEquivalentDirectoryUris(downloadDirectoryUri, targetUri)
        ) {
            ManagedDownloadStorage.releasePersistedDirectoryPermission(context, targetUri)
        }
        val message = composeResources.getString(
            R.string.settings_download_directory_change_blocked_active_download
        )
        inlineMsg = message
        showSettingsMessage(message)
        return true
    }

    fun applyDownloadDirectoryChange(
        targetUri: String?,
        targetSummary: String,
        previousUri: String?,
        shouldReleasePreviousPermission: Boolean,
        migrationResult: ManagedDownloadStorage.MigrationResult? = null
    ) {
        val targetLabel = targetSummary.takeIf { !targetUri.isNullOrBlank() }
        ManagedDownloadStorage.updateConfiguredTreeUri(targetUri)
        ManagedDownloadStorage.updateCustomDirectoryLabel(targetLabel)
        onDownloadDirectoryUriChange(targetUri, targetLabel)
        GlobalDownloadManager.scanLocalFiles(context, forceRefresh = true)
        if (shouldReleasePreviousPermission) {
            ManagedDownloadStorage.releasePersistedDirectoryPermission(context, previousUri)
        }
        inlineMsg = when {
            migrationResult != null && migrationResult.cleanupFailedFiles > 0 -> {
                resources.getQuantityString(
                    R.plurals.settings_download_directory_migrated_partial,
                    migrationResult.movedFiles,
                    migrationResult.movedFiles,
                    migrationResult.cleanupFailedFiles
                )
            }

            migrationResult != null -> {
                resources.getQuantityString(
                    R.plurals.settings_download_directory_migrated,
                    migrationResult.movedFiles,
                    migrationResult.movedFiles
                )
            }

            targetUri.isNullOrBlank() -> composeResources.getString(R.string.settings_download_directory_reset_done)
            else -> composeResources.getString(R.string.settings_download_directory_selected)
        }
    }

    suspend fun prepareDownloadDirectoryChange(
        targetUri: String?,
        targetSummary: String,
        releaseTargetPermissionOnCancel: Boolean
    ) {
        if (
            guardDownloadDirectoryChange(
                targetUri = targetUri,
                releaseTargetPermissionOnBlock = releaseTargetPermissionOnCancel
            )
        ) {
            return
        }
        val previousUri = downloadDirectoryUri?.takeIf { it.isNotBlank() }
        if (previousUri == targetUri) {
            inlineMsg = if (targetUri.isNullOrBlank()) {
                composeResources.getString(R.string.settings_download_directory_reset_done)
            } else {
                composeResources.getString(R.string.settings_download_directory_selected)
            }
            return
        }

        if (ManagedDownloadStorage.areEquivalentDirectoryUris(previousUri, targetUri)) {
            runCatching {
                applyDownloadDirectoryChange(
                    targetUri = targetUri,
                    targetSummary = targetSummary,
                    previousUri = previousUri,
                    shouldReleasePreviousPermission = false
                )
            }.onFailure {
                if (releaseTargetPermissionOnCancel) {
                    ManagedDownloadStorage.releasePersistedDirectoryPermission(context, targetUri)
                }
                inlineMsg = composeResources.getString(
                    R.string.settings_download_directory_pick_failed,
                    it.message ?: ""
                )
            }
            return
        }

        val hasMigratableDownloads = if (GlobalDownloadManager.downloadedSongs.value.isNotEmpty()) {
            true
        } else {
            ManagedDownloadStorage.mayHaveMigratableDownloads(context, previousUri)
        }
        if (hasMigratableDownloads) {
            pendingDownloadDirectoryChange = PendingDownloadDirectoryChange(
                previousUri = previousUri,
                targetUri = targetUri,
                targetSummary = targetSummary,
                releaseTargetPermissionOnCancel = releaseTargetPermissionOnCancel
            )
            return
        }

        runCatching {
            applyDownloadDirectoryChange(
                targetUri = targetUri,
                targetSummary = targetSummary,
                previousUri = previousUri,
                shouldReleasePreviousPermission = !previousUri.isNullOrBlank()
            )
        }.onFailure {
            if (releaseTargetPermissionOnCancel) {
                ManagedDownloadStorage.releasePersistedDirectoryPermission(context, targetUri)
            }
            inlineMsg = composeResources.getString(
                R.string.settings_download_directory_pick_failed,
                it.message ?: ""
            )
        }
    }

    // 照片选择器
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    val importedUri = BackgroundImageStorage.importFromUri(
                        context = context,
                        sourceUri = uri,
                        previousUriString = backgroundImageUri
                    )
                    if (importedUri != null) {
                        onBackgroundImageChange(importedUri)
                    }
                }
            }
        }
    )

    val downloadDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        if (guardDownloadDirectoryChange()) {
            return@rememberLauncherForActivityResult
        }
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val targetUri = uri.toString()
            scope.launch {
                val targetSummary = withContext(Dispatchers.IO) {
                    ManagedDownloadStorage.describeConfiguredDirectory(context, targetUri)
                }
                prepareDownloadDirectoryChange(
                    targetUri = targetUri,
                    targetSummary = targetSummary,
                    releaseTargetPermissionOnCancel = true
                )
            }
        } catch (e: SecurityException) {
            inlineMsg = composeResources.getString(
                R.string.settings_download_directory_pick_failed,
                e.message ?: ""
            )
        }
    }

    LaunchedEffect(listenTogetherWorkerBaseUrlInput) {
        if (listenTogetherServerInput != listenTogetherWorkerBaseUrlInput) {
            listenTogetherServerInput = listenTogetherWorkerBaseUrlInput
        }
    }
    LaunchedEffect(listenTogetherNickname, showListenTogetherNicknameDialog) {
        if (!showListenTogetherNicknameDialog) {
            listenTogetherNicknameInput = listenTogetherNickname
            listenTogetherNicknameError = null
        }
    }

    var downloadDirectorySummary by remember(downloadDirectoryUri, defaultDownloadDirectorySummary) {
        mutableStateOf(defaultDownloadDirectorySummary)
    }
    LaunchedEffect(downloadDirectoryUri, defaultDownloadDirectorySummary) {
        downloadDirectorySummary = if (downloadDirectoryUri.isNullOrBlank()) {
            defaultDownloadDirectorySummary
        } else {
            withContext(Dispatchers.IO) {
                ManagedDownloadStorage.describeConfiguredDirectory(context, downloadDirectoryUri)
            }
        }
    }
    val resetDownloadDirectory: () -> Unit = {
        if (!guardDownloadDirectoryChange()) {
            scope.launch {
                prepareDownloadDirectoryChange(
                    targetUri = null,
                    targetSummary = defaultDownloadDirectorySummary,
                    releaseTargetPermissionOnCancel = false
                )
            }
        }
    }

    // 备份与恢复的SAF启动器
    val exportPlaylistLauncher = rememberLauncherForActivityResult(
        contract = CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            backupRestoreVm.initialize(context)
            backupRestoreVm.exportPlaylists(uri)
        }
    }

    val importPlaylistLauncher = rememberLauncherForActivityResult(
        contract = OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupRestoreVm.initialize(context)
            backupRestoreVm.importPlaylists(uri)
        }
    }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            configTransferVm.initialize(context)
            configTransferVm.exportConfig(uri)
        }
    }

    val importConfigLauncher = rememberLauncherForActivityResult(
        contract = OpenDocument()
    ) { uri ->
        if (uri != null) {
            configTransferVm.initialize(context)
            configTransferVm.importConfig(uri)
        }
    }

    LaunchedEffect(configTransferUiState.importRequiresActivityRecreate) {
        if (!configTransferUiState.importRequiresActivityRecreate) {
            return@LaunchedEffect
        }
        onBeforeLanguageRestart()
        configTransferVm.consumeImportRecreateRequest()
        (context as? android.app.Activity)?.recreate()
    }

    val qualityLabel = remember(preferredQuality) {
        context.neteaseQualityLabel(preferredQuality)
    }

    val biliQualityLabel = remember(biliPreferredQuality) {
        context.biliQualityLabel(biliPreferredQuality)
    }

    val youtubeQualityLabel = remember(youtubePreferredQuality) {
        context.youtubeQualityLabel(youtubePreferredQuality)
    }

    val normalizedMobileDataNeteaseAudioQuality = remember(mobileDataNeteaseAudioQuality) {
        normalizeMobileDataNeteaseAudioQuality(mobileDataNeteaseAudioQuality)
    }
    val normalizedMobileDataYouTubeAudioQuality = remember(mobileDataYouTubeAudioQuality) {
        normalizeMobileDataYouTubeAudioQuality(mobileDataYouTubeAudioQuality)
    }
    val normalizedMobileDataBiliAudioQuality = remember(mobileDataBiliAudioQuality) {
        normalizeMobileDataBiliAudioQuality(mobileDataBiliAudioQuality)
    }
    val mobileDataNeteaseQualityLabel = remember(normalizedMobileDataNeteaseAudioQuality) {
        context.neteaseQualityLabel(normalizedMobileDataNeteaseAudioQuality)
    }
    val mobileDataYouTubeQualityLabel = remember(normalizedMobileDataYouTubeAudioQuality) {
        context.youtubeQualityLabel(normalizedMobileDataYouTubeAudioQuality)
    }
    val mobileDataBiliQualityLabel = remember(normalizedMobileDataBiliAudioQuality) {
        context.biliQualityLabel(normalizedMobileDataBiliAudioQuality)
    }
    val density = LocalDensity.current

    val homeStartAvailable =
        showHomeTrendingCard ||
            showHomeRadarCard ||
            showHomeRecommendedCard ||
            (showHomeContinueCard && homeHasRecentUsage)
    val homeTrendingLabelRes = if (internationalEnabled) {
        R.string.home_ytmusic_guess_you_like
    } else {
        R.string.recommend_trending
    }
    val homeRadarLabelRes = if (internationalEnabled) {
        R.string.home_ytmusic_daily_discover
    } else {
        R.string.recommend_radar
    }
    val homeRecommendedLabelRes = if (internationalEnabled) {
        R.string.home_ytmusic_more_recommendations
    } else {
        R.string.recommend_for_you
    }
    val homeTrendingSupportingRes = if (internationalEnabled) {
        R.string.settings_home_card_ytmusic_guess_you_like_desc
    } else {
        null
    }
    val homeRadarSupportingRes = if (internationalEnabled) {
        R.string.settings_home_card_ytmusic_daily_discover_desc
    } else {
        null
    }
    val homeRecommendedSupportingRes = if (internationalEnabled) {
        R.string.settings_home_card_ytmusic_more_recommendations_desc
    } else {
        null
    }
    val neteaseHomeCardAuthHealth by AppContainer.neteaseCookieRepo.authHealthFlow.collectAsStateWithLifecycleCompat()
    val neteaseHomeCardsEnabled = internationalEnabled ||
        neteaseHomeCardAuthHealth.state != SavedCookieAuthState.Missing
    val effectiveDefaultStartDestination = remember(defaultStartDestination, homeStartAvailable) {
        if (!homeStartAvailable && defaultStartDestination == "home") {
            "explore"
        } else {
            defaultStartDestination
        }
    }
    val defaultStartDestinationLabel = remember(effectiveDefaultStartDestination, context) {
        when (effectiveDefaultStartDestination) {
            "explore" -> composeResources.getString(R.string.nav_explore)
            "library" -> composeResources.getString(R.string.nav_library)
            "settings" -> composeResources.getString(R.string.nav_settings)
            else -> composeResources.getString(R.string.nav_home)
        }
    }
    LaunchedEffect(neteaseVm) {
        neteaseVm.events.collect { e ->
            when (e) {
                is NeteaseAuthEvent.ShowSnack -> {
                    inlineMsg = e.message
                }
                is NeteaseAuthEvent.AskConfirmSend -> {
                    confirmPhoneMasked = e.masked
                    showConfirmDialog = true
                }
                NeteaseAuthEvent.LoginSuccess -> {
                    showNeteaseSavedCookieDialog = false
                    inlineMsg = null
                    showNeteaseSheet = false
                    loginSuccessTitle = composeResources.getString(
                        R.string.settings_netease_login_success
                    )
                    neteaseVm.refreshAuthHealth()
                }
            }
        }
    }

    LaunchedEffect(biliVm) {
        biliVm.events.collect { e ->
            when (e) {
                is BiliAuthEvent.ShowSnack -> inlineMsg = e.message
                BiliAuthEvent.LoginSuccess -> {
                    showBiliSavedCookieDialog = false
                    inlineMsg = null
                    showBiliSheet = false
                    loginSuccessTitle = composeResources.getString(
                        R.string.settings_bili_login_success
                    )
                    biliVm.refreshAuthHealth()
                }
            }
        }
    }

    LaunchedEffect(youtubeVm) {
        youtubeVm.events.collect { e ->
            when (e) {
                is YouTubeAuthEvent.ShowSnack -> inlineMsg = e.message
                YouTubeAuthEvent.LoginSuccess -> {
                    showYouTubeSavedCookieDialog = false
                    inlineMsg = null
                    showYouTubeSheet = false
                    loginSuccessTitle = composeResources.getString(
                        R.string.settings_youtube_login_success
                    )
                    youtubeVm.refreshAuthHealth()
                }

            }
        }
    }

    val isSettingsSplitLayout = currentWindowWidthDp() >= 840.dp
    var activeSettingsPage by rememberSaveable {
        mutableStateOf<SettingsPage?>(if (isSettingsSplitLayout) SettingsPage.General else null)
    }
    LaunchedEffect(activeSettingsPage, context) {
        if (activeSettingsPage == SettingsPage.Backup) {
            backupRestoreVm.observePlaylistCount(context)
        }
    }
    val homeTopAppBarState = rememberTopAppBarState()
    val detailTopAppBarStates = SettingsPage.entries.associateWith { rememberTopAppBarState() }
    val detailListStates = SettingsPage.entries.associateWith {
        rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    }
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
    var settingsHighlightTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsHighlightPulse by rememberSaveable { mutableIntStateOf(0) }
    var settingsSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var pendingSettingsSearchNavigation by remember { mutableStateOf<PendingSettingsSearchNavigation?>(null) }
    val settingsSearchEntries = remember(context) { buildSettingsSearchEntries(context) }
    val settingsSearchResults = remember(settingsSearchEntries, settingsSearchQuery) {
        searchSettingsEntries(settingsSearchEntries, settingsSearchQuery, limit = 8)
    }
    val onSettingsHighlightFinished: () -> Unit = {
        settingsHighlightTargetId = null
    }

    LaunchedEffect(isSettingsSplitLayout) {
        if (isSettingsSplitLayout && activeSettingsPage == null) {
            activeSettingsPage = SettingsPage.General
        }
    }

    LaunchedEffect(settingsSearchQuery) {
        if (settingsSearchQuery.isNotBlank()) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(activeSettingsPage, pendingSettingsSearchNavigation) {
        val pending = pendingSettingsSearchNavigation ?: return@LaunchedEffect
        if (activeSettingsPage != pending.page) {
            return@LaunchedEffect
        }
        settingsHighlightTargetId = null
        val detailListState = detailListStates.getValue(pending.page)
        val anchor = settingsSearchScrollAnchor(
            page = pending.page,
            targetId = pending.targetId
        )
        withFrameNanos { }
        withFrameNanos { }
        snapshotFlow { detailListState.layoutInfo.totalItemsCount }
            .first { it > anchor.itemIndex }
        detailListState.scrollToItem(
            index = anchor.itemIndex,
            scrollOffset = with(density) { anchor.scrollOffset.toPx().roundToInt() }
        )
        withFrameNanos { }
        detailListState.scrollToItem(
            index = anchor.itemIndex,
            scrollOffset = with(density) { anchor.scrollOffset.toPx().roundToInt() }
        )
        settingsHighlightTargetId = pending.targetId
        settingsHighlightPulse = pending.requestId
        pendingSettingsSearchNavigation = null
    }

    fun navigateBackFromActiveSettingsPage() {
        activeSettingsPage = activeSettingsPage?.backTargetPage()
    }

    val settingsPageBackTarget = activeSettingsPage?.backTargetPage()
    val isolateAdvancedGlassTransitions = LocalAdvancedGlassController.current.isEnabled
    BackHandler(
        enabled = activeSettingsPage != null &&
            (!isSettingsSplitLayout || settingsPageBackTarget != null)
    ) {
        navigateBackFromActiveSettingsPage()
    }

    val settingsHomeTitle: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.settings_title))
            ThemeModeActionButton(
                isDarkTheme = isDarkTheme,
                onToggleRequest = onThemeToggleRequest
            )
        }
    }
    val onSettingsSearchResultClick: (SettingsSearchEntry) -> Unit = { entry ->
        val targetId = resolveSettingsSearchHighlightTarget(
            entry = entry,
            dynamicColor = dynamicColor,
            mobileDataFollowDefaultAudioQuality = mobileDataFollowDefaultAudioQuality,
            hasCustomBackground = backgroundImageUri != null
        )
        activeSettingsPage = entry.page
        settingsHighlightTargetId = null
        settingsSearchRequestId += 1
        pendingSettingsSearchNavigation = PendingSettingsSearchNavigation(
            page = entry.page,
            targetId = targetId,
            requestId = settingsSearchRequestId
        )
    }
    val settingsHomeContent: LazyListScope.() -> Unit = {
        item(key = "settings_search_field") {
            SettingsSearchField(
                query = settingsSearchQuery,
                onQueryChange = { settingsSearchQuery = it }
            )
        }
        if (settingsSearchQuery.isNotBlank()) {
            item(key = "settings_search_results") {
                SettingsSearchResultsCard(
                    query = settingsSearchQuery,
                    results = settingsSearchResults,
                    onResultClick = onSettingsSearchResultClick
                )
            }
        }
        SettingsHomePageGroups.forEachIndexed { groupIndex, pages ->
            item(key = "settings_group_$groupIndex") {
                MiuixSettingsPageGroupCard(
                    pages = pages,
                    onPageClick = { page -> activeSettingsPage = page },
                    selectedPage = activeSettingsPage?.backTargetPage() ?: activeSettingsPage,
                    modifier = Modifier.animateItem()
                )
            }
        }
    }

    AnimatedContent(
        targetState = activeSettingsPage,
        modifier = Modifier.fillMaxSize(),
        label = "settings_page_switch",
        transitionSpec = {
            if (isSettingsSplitLayout) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                isolatedAdvancedGlassHorizontalTransition(
                    forward = isForwardSettingsPageTransition(initialState, targetState)
                ).using(SizeTransform(clip = true))
            }
        }
    ) { selectedPage ->
        AdvancedGlassNavigationHandoff(
            enabled = isolateAdvancedGlassTransitions && transition.isRunning
        ) {
            AdvancedGlassScene(
                active = isolateAdvancedGlassTransitions || selectedPage == activeSettingsPage
            ) {
                if (selectedPage == null) {
                    MiuixSettingsHomeScaffold(
                        listState = listState,
                        topAppBarState = homeTopAppBarState,
                        title = settingsHomeTitle,
                        content = settingsHomeContent
                    )
                } else {
                    MiuixSettingsResponsiveDetailScaffold(
                title = stringResource(selectedPage.titleRes),
                onBack = ::navigateBackFromActiveSettingsPage,
                listState = detailListStates.getValue(selectedPage),
                topAppBarState = detailTopAppBarStates.getValue(selectedPage),
                splitLayout = isSettingsSplitLayout,
                showSplitDetailBackButton = settingsPageBackTarget != null,
                selectedPage = selectedPage,
                homeListState = listState,
                homeTopAppBarState = homeTopAppBarState,
                homeTitle = settingsHomeTitle,
                homeContent = settingsHomeContent
                ) {
                item(key = "${selectedPage.name}:header") {
                    MiuixSettingsHeader(
                        icon = selectedPage.icon,
                        title = stringResource(selectedPage.titleRes),
                        description = stringResource(selectedPage.descriptionRes),
                        modifier = Modifier
                            .animateItem()
                            .settingsHighlightTarget(
                                targetId = "page:${selectedPage.name}",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                    )
                }

                when (selectedPage) {
                SettingsPage.General -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        AutoSettingsSwitchItems(
                            repository = autoSettingsRepository,
                            scope = scope,
                            sectionScope = AutoSettingsScopes.general,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                        PlaybackServiceIdleShutdownSetting(
                            repository = autoSettingsRepository,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                        LanguageSettingItem(
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:language",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            ),
                            onBeforeRestart = onBeforeLanguageRestart
                        )
                        ListItem(
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:internationalization",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            ),
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_i18n),
                                    contentDescription = stringResource(R.string.settings_internationalization),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_internationalization)) },
                            supportingContent = {
                                Text(
                                    stringResource(R.string.settings_internationalization_desc)
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(
                                    checked = internationalEnabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            AppContainer.settingsRepo.setInternationalizationEnabled(enabled)
                                        }
                                    }
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        MiuixSettingsSectionIntro(
                            title = stringResource(R.string.settings_ui_scale),
                            description = stringResource(R.string.settings_ui_scale_global_desc)
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.UI_DENSITY_SCALE),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.ZoomInMap,
                                    contentDescription = stringResource(R.string.settings_ui_scale),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.settings_ui_scale_current,
                                        "%.2f".format(uiDensityScale)
                                    )
                                )
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = { showDpiDialog = true }
                        )
                    }
                }

                SettingsPage.Theme -> {
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:mode") {
                        MiuixSettingsSectionIntro(
                            title = stringResource(R.string.settings_theme_mode),
                            description = stringResource(R.string.settings_theme_mode_desc)
                        )
                        ThemeModeSelectorListItem(
                            isDarkTheme = isDarkTheme,
                            themeMode = themeMode,
                            onThemeModeRequest = onThemeModeRequest,
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:theme_mode",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        )
                        ThemeAutoModeListItem(
                            themeMode = themeMode,
                            isDarkTheme = isDarkTheme,
                            onThemeModeRequest = onThemeModeRequest
                        )
                    }
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:dynamic_color") {
                        MiuixSettingsSectionIntro(
                            title = stringResource(R.string.settings_theme_color_section),
                            description = stringResource(R.string.settings_theme_color_section_desc)
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DYNAMIC_COLOR),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Colorize,
                                    contentDescription = stringResource(R.string.settings_dynamic_color),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(checked = dynamicColor, onCheckedChange = onDynamicColorChange)
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = { onDynamicColorChange(!dynamicColor) }
                        )
                        LazyAnimatedVisibility(visible = !dynamicColor) {
                            ThemeSeedListItem(
                                seedColorHex = seedColorHex,
                                onClick = { showColorPickerDialog = true },
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_theme_palette_hint),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:palette_style") {
                        ThemePaletteStyleSelector(
                            selectedStyle = themePaletteStyle,
                            onStyleChange = onThemePaletteStyleChange,
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:theme_palette_style",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        )
                    }
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:color_spec") {
                        ThemeColorSpecSelector(
                            selectedSpec = themeColorSpec,
                            onSpecChange = onThemeColorSpecChange,
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:theme_color_spec",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        )
                    }
                }

                SettingsPage.Accounts -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsLoginExpandedContent(
                            biliVm = biliVm,
                            youtubeVm = youtubeVm,
                            neteaseVm = neteaseVm,
                            onOpenBiliSheet = { tab ->
                                inlineMsg = null
                                biliSheetInitialTab = tab
                                showBiliSheet = true
                            },
                            onOpenBiliSavedCookieDialog = {
                                inlineMsg = null
                                showBiliSavedCookieDialog = true
                            },
                            onOpenYouTubeSavedCookieDialog = {
                                inlineMsg = null
                                showYouTubeSavedCookieDialog = true
                            },
                            onOpenNeteaseSavedCookieDialog = {
                                inlineMsg = null
                                showNeteaseSavedCookieDialog = true
                            },
                            onOpenYouTubeSheet = {
                                inlineMsg = null
                                youtubeSheetInitialTab = 0
                                showYouTubeSheet = true
                            },
                            onOpenNeteaseSheet = {
                                inlineMsg = null
                                neteaseSheetInitialTab = 0
                                showNeteaseSheet = true
                            }
                        )
                    }
                }

                SettingsPage.Personalization -> {
                    for (cardIndex in 0..5) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsPersonalizationPageContent(
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                defaultStartDestinationLabel = defaultStartDestinationLabel,
                                onOpenDefaultStartDestination = { showDefaultStartDestinationDialog = true },
                                internationalEnabled = internationalEnabled,
                                homeTrendingLabelRes = homeTrendingLabelRes,
                                homeRadarLabelRes = homeRadarLabelRes,
                                homeRecommendedLabelRes = homeRecommendedLabelRes,
                                homeTrendingSupportingRes = homeTrendingSupportingRes,
                                homeRadarSupportingRes = homeRadarSupportingRes,
                                homeRecommendedSupportingRes = homeRecommendedSupportingRes,
                                neteaseHomeCardsEnabled = neteaseHomeCardsEnabled,
                                homeStartAvailable = homeStartAvailable,
                                showHomeContinueCard = showHomeContinueCard,
                                onShowHomeContinueCardChange = onShowHomeContinueCardChange,
                                showHomeTrendingCard = showHomeTrendingCard,
                                onShowHomeTrendingCardChange = onShowHomeTrendingCardChange,
                                showHomeRadarCard = showHomeRadarCard,
                                onShowHomeRadarCardChange = onShowHomeRadarCardChange,
                                showHomeRecommendedCard = showHomeRecommendedCard,
                                onShowHomeRecommendedCardChange = onShowHomeRecommendedCardChange,
                                lyricFontScales = lyricFontScales,
                                onLyricFontScaleChange = onLyricFontScaleChange,
                                backgroundImageUri = backgroundImageUri,
                                onPickBackgroundImage = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onClearBackgroundImage = {
                                    scope.launch {
                                        BackgroundImageStorage.deleteManagedBackground(
                                            context = context,
                                            uriString = backgroundImageUri
                                        )
                                        onBackgroundImageChange(null)
                                    }
                                },
                                pendingBackgroundImageBlur = pendingBackgroundImageBlur,
                                onPendingBackgroundImageBlurChange = { pendingBackgroundImageBlur = it },
                                onBackgroundImageBlurCommit = {
                                    onBackgroundImageBlurChange(pendingBackgroundImageBlur)
                                    onBackgroundImageBlurChangeFinished(pendingBackgroundImageBlur)
                                },
                                pendingBackgroundImageAlpha = pendingBackgroundImageAlpha,
                                onPendingBackgroundImageAlphaChange = {
                                    pendingBackgroundImageAlpha = it
                                    onBackgroundImageAlphaChange(it)
                                },
                                onBackgroundImageAlphaCommit = {
                                    onBackgroundImageAlphaChangeFinished(pendingBackgroundImageAlpha)
                                },
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.Motion -> {
                    for (cardIndex in 0..3) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsMotionSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                advancedBlurEnabled = advancedBlurEnabled,
                                onAdvancedBlurEnabledChange = onAdvancedBlurEnabledChange,
                                enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
                                onEnhancedAdvancedBlurEnabledChange =
                                    onEnhancedAdvancedBlurEnabledChange,
                                enhancedAdvancedBlurRadiusDp = enhancedAdvancedBlurRadiusDp,
                                onEnhancedAdvancedBlurRadiusDpChange =
                                    onEnhancedAdvancedBlurRadiusDpChange,
                                nowPlayingAudioReactiveEnabled = nowPlayingAudioReactiveEnabled,
                                onNowPlayingAudioReactiveEnabledChange =
                                    onNowPlayingAudioReactiveEnabledChange,
                                nowPlayingDynamicBackgroundEnabled =
                                    nowPlayingDynamicBackgroundEnabled,
                                onNowPlayingDynamicBackgroundEnabledChange =
                                    onNowPlayingDynamicBackgroundEnabledChange,
                                nowPlayingCoverBlurBackgroundEnabled =
                                    nowPlayingCoverBlurBackgroundEnabled,
                                onNowPlayingCoverBlurBackgroundEnabledChange =
                                    onNowPlayingCoverBlurBackgroundEnabledChange,
                                nowPlayingCoverBlurAmount = nowPlayingCoverBlurAmount,
                                onNowPlayingCoverBlurAmountChange = onNowPlayingCoverBlurAmountChange,
                                nowPlayingCoverBlurDarken = nowPlayingCoverBlurDarken,
                                onNowPlayingCoverBlurDarkenChange = onNowPlayingCoverBlurDarkenChange,
                                lyricBlurEnabled = lyricBlurEnabled,
                                onLyricBlurEnabledChange = onLyricBlurEnabledChange,
                                lyricBlurAmount = lyricBlurAmount,
                                onLyricBlurAmountChange = onLyricBlurAmountChange,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.Lyrics -> {
                    for (cardIndex in 0..2) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsLyricsSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                floatingLyricsPreferences = floatingLyricsPreferences,
                                onFloatingLyricsPreferencesChange = onFloatingLyricsPreferencesChange,
                                cloudMusicLyricDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
                                onCloudMusicLyricDefaultOffsetMsChange =
                                    onCloudMusicLyricDefaultOffsetMsChange,
                                qqMusicLyricDefaultOffsetMs = qqMusicLyricDefaultOffsetMs,
                                onQqMusicLyricDefaultOffsetMsChange =
                                    onQqMusicLyricDefaultOffsetMsChange,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.Network -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BYPASS_PROXY),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.AltRoute,
                                    contentDescription = stringResource(R.string.settings_bypass_proxy),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(checked = bypassProxy, onCheckedChange = onBypassProxyChange)
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = { onBypassProxyChange(!bypassProxy) }
                        )
                    }
                }

                SettingsPage.Playback -> {
                    for (cardIndex in 0..3) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsPlaybackSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                playbackFadeIn = playbackFadeIn,
                                onPlaybackFadeInChange = onPlaybackFadeInChange,
                                playbackCrossfadeNext = playbackCrossfadeNext,
                                onPlaybackCrossfadeNextChange = onPlaybackCrossfadeNextChange,
                                sleepTimerFinishCurrentOnExpiry = sleepTimerFinishCurrentOnExpiry,
                                onSleepTimerFinishCurrentOnExpiryChange =
                                    onSleepTimerFinishCurrentOnExpiryChange,
                                playbackFadeInDurationMs = playbackFadeInDurationMs,
                                onPlaybackFadeInDurationMsChange = onPlaybackFadeInDurationMsChange,
                                playbackFadeOutDurationMs = playbackFadeOutDurationMs,
                                onPlaybackFadeOutDurationMsChange = onPlaybackFadeOutDurationMsChange,
                                playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs,
                                onPlaybackCrossfadeInDurationMsChange =
                                    onPlaybackCrossfadeInDurationMsChange,
                                playbackCrossfadeOutDurationMs = playbackCrossfadeOutDurationMs,
                                onPlaybackCrossfadeOutDurationMsChange =
                                    onPlaybackCrossfadeOutDurationMsChange,
                                playbackVolumeNormalizationEnabled = playbackVolumeNormalizationEnabled,
                                onPlaybackVolumeNormalizationEnabledChange =
                                    onPlaybackVolumeNormalizationEnabledChange,
                                playbackHighResolutionOutputEnabled =
                                    playbackHighResolutionOutputEnabled,
                                onPlaybackHighResolutionOutputEnabledChange =
                                    onPlaybackHighResolutionOutputEnabledChange,
                                playbackVolumeBalance = playbackVolumeBalance,
                                onPlaybackVolumeBalanceChange = onPlaybackVolumeBalanceChange,
                                keepLastPlaybackProgress = keepLastPlaybackProgress,
                                onKeepLastPlaybackProgressChange = onKeepLastPlaybackProgressChange,
                                rememberLongFormPlaybackProgress = rememberLongFormPlaybackProgress,
                                onRememberLongFormPlaybackProgressChange =
                                    onRememberLongFormPlaybackProgressChange,
                                keepPlaybackModeState = keepPlaybackModeState,
                                onKeepPlaybackModeStateChange = onKeepPlaybackModeStateChange,
                                stopOnBluetoothDisconnect = stopOnBluetoothDisconnect,
                                onStopOnBluetoothDisconnectChange = onStopOnBluetoothDisconnectChange,
                                usbExclusivePlayback = usbExclusivePlayback,
                                onUsbExclusiveSettingsClick = {
                                    activeSettingsPage = SettingsPage.UsbExclusive
                                },
                                allowMixedPlayback = allowMixedPlayback,
                                onAllowMixedPlaybackChange = onAllowMixedPlaybackChange,
                                preemptAudioFocus = preemptAudioFocus,
                                onPreemptAudioFocusChange = onPreemptAudioFocusChange,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.UsbExclusive -> {
                    item(key = "${selectedPage.name}:content") {
                        UsbExclusiveSettingsSection(
                            usbExclusivePlayback = usbExclusivePlayback,
                            onUsbExclusivePlaybackChange = onUsbExclusivePlaybackChange,
                            preferences = usbExclusivePreferences,
                            onDeviceKeyChange = { deviceKey ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveDeviceKey(deviceKey)
                                }
                            },
                            onSampleRateModeChange = { mode ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveSampleRateMode(mode)
                                }
                            },
                            onBitDepthModeChange = { mode ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveBitDepthMode(mode)
                                }
                            },
                            onBitPerfectChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveBitPerfect(enabled)
                                }
                            },
                            onBufferProfileChange = { profile ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveBufferProfile(profile)
                                }
                            },
                            onUnsupportedFormatPolicyChange = { policy ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveUnsupportedFormatPolicy(policy)
                                }
                            },
                            onSampleRateCompatibilityChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveSampleRateCompatibility(enabled)
                                }
                            },
                            onBitDepthCompatibilityChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveBitDepthCompatibility(enabled)
                                }
                            },
                            onChannelCompatibilityChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveChannelCompatibility(enabled)
                                }
                            },
                            onForegroundBufferMsChange = { bufferMs ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveForegroundBufferMs(bufferMs)
                                }
                            },
                            onBackgroundBufferMsChange = { bufferMs ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveBackgroundBufferMs(bufferMs)
                                }
                            },
                            onVolumeRiskThresholdDbfsChange = { thresholdDbfs ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveVolumeRiskThresholdDbfs(thresholdDbfs)
                                }
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                SettingsPage.PlaybackSource -> {
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:content") {
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(
                                AutoSettingsKeys.NETEASE_LOCAL_SOURCE_FALLBACK
                            ),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.LibraryMusic,
                                    contentDescription = stringResource(
                                        R.string.settings_netease_local_source_fallback
                                    ),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(
                                    checked = neteaseLocalSourceFallback,
                                    onCheckedChange = onNeteaseLocalSourceFallbackChange
                                )
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = {
                                onNeteaseLocalSourceFallbackChange(!neteaseLocalSourceFallback)
                            }
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(
                                AutoSettingsKeys.NETEASE_AUTO_SOURCE_SWITCH
                            ),
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bilibili),
                                    contentDescription = stringResource(
                                        R.string.settings_netease_auto_source_switch
                                    ),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(
                                    checked = neteaseAutoSourceSwitch,
                                    onCheckedChange = onNeteaseAutoSourceSwitchChange
                                )
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = {
                                onNeteaseAutoSourceSwitchChange(!neteaseAutoSourceSwitch)
                            }
                        )
                    }
                }

                SettingsPage.AudioQuality -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsAudioQualitySection(
                            expanded = true,
                            arrowRotation = 0f,
                            onExpandedChange = {},
                            showHeader = false,
                            qualityLabel = qualityLabel,
                            preferredQuality = preferredQuality,
                            onQualityChange = onQualityChange,
                            youtubeQualityLabel = youtubeQualityLabel,
                            youtubePreferredQuality = youtubePreferredQuality,
                            onYouTubeQualityChange = onYouTubeQualityChange,
                            biliQualityLabel = biliQualityLabel,
                            biliPreferredQuality = biliPreferredQuality,
                            onBiliQualityChange = onBiliQualityChange,
                            mobileDataFollowDefaultAudioQuality = mobileDataFollowDefaultAudioQuality,
                            onMobileDataFollowDefaultAudioQualityChange =
                                onMobileDataFollowDefaultAudioQualityChange,
                            mobileDataNeteaseQualityLabel = mobileDataNeteaseQualityLabel,
                            mobileDataNeteaseAudioQuality = normalizedMobileDataNeteaseAudioQuality,
                            onMobileDataNeteaseAudioQualityChange =
                                onMobileDataNeteaseAudioQualityChange,
                            mobileDataYouTubeQualityLabel = mobileDataYouTubeQualityLabel,
                            mobileDataYouTubeAudioQuality = normalizedMobileDataYouTubeAudioQuality,
                            onMobileDataYouTubeAudioQualityChange =
                                onMobileDataYouTubeAudioQualityChange,
                            mobileDataBiliQualityLabel = mobileDataBiliQualityLabel,
                            mobileDataBiliAudioQuality = normalizedMobileDataBiliAudioQuality,
                            onMobileDataBiliAudioQualityChange = onMobileDataBiliAudioQualityChange,
                            showQualityDialog = showQualityDialog,
                            onShowQualityDialogChange = { showQualityDialog = it },
                            showYouTubeQualityDialog = showYouTubeQualityDialog,
                            onShowYouTubeQualityDialogChange = { showYouTubeQualityDialog = it },
                            showBiliQualityDialog = showBiliQualityDialog,
                            onShowBiliQualityDialogChange = { showBiliQualityDialog = it },
                            showMobileDataNeteaseQualityDialog = showMobileDataNeteaseQualityDialog,
                            onShowMobileDataNeteaseQualityDialogChange = {
                                showMobileDataNeteaseQualityDialog = it
                            },
                            showMobileDataYouTubeQualityDialog = showMobileDataYouTubeQualityDialog,
                            onShowMobileDataYouTubeQualityDialogChange = {
                                showMobileDataYouTubeQualityDialog = it
                            },
                            showMobileDataBiliQualityDialog = showMobileDataBiliQualityDialog,
                            onShowMobileDataBiliQualityDialogChange = {
                                showMobileDataBiliQualityDialog = it
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                }

                SettingsPage.Storage -> {
                    for (cardIndex in 0..3) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsStorageCacheSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                currentDownloadDirectorySummary = downloadDirectorySummary,
                                isCustomDownloadDirectory = !downloadDirectoryUri.isNullOrBlank(),
                                downloadDirectoryChangeEnabled = downloadDirectoryChangeEnabled,
                                onPickDownloadDirectory = {
                                    if (!guardDownloadDirectoryChange()) {
                                        showDownloadDirectorySwitchWarningDialog = true
                                    }
                                },
                                onResetDownloadDirectory = resetDownloadDirectory,
                                downloadFileNameTemplate = downloadFileNameTemplate,
                                onDownloadFileNameTemplateChange = onDownloadFileNameTemplateChange,
                                maxCacheSizeBytes = maxCacheSizeBytes,
                                onMaxCacheSizeBytesChange = onMaxCacheSizeBytesChange,
                                showStorageDetails = showStorageDetails,
                                onShowStorageDetailsChange = { showStorageDetails = it },
                                storageDetails = storageDetails,
                                onStorageDetailsChange = { storageDetails = it },
                                showClearCacheDialog = showClearCacheDialog,
                                onShowClearCacheDialogChange = { showClearCacheDialog = it },
                                clearAudioCache = clearAudioCache,
                                onClearAudioCacheChange = { clearAudioCache = it },
                                clearImageCache = clearImageCache,
                                onClearImageCacheChange = { clearImageCache = it },
                                clearDownloadStagingCache = clearDownloadStagingCache,
                                onClearDownloadStagingCacheChange = { clearDownloadStagingCache = it },
                                clearSharedMediaCache = clearSharedMediaCache,
                                onClearSharedMediaCacheChange = { clearSharedMediaCache = it },
                                clearPlatformListCache = clearPlatformListCache,
                                onClearPlatformListCacheChange = { clearPlatformListCache = it },
                                downloadStagingClearEnabled = !hasActiveDownloadOperations,
                                onClearCacheClick = onClearCacheClick,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.Downloads -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsDownloadSection(
                            expanded = true,
                            arrowRotation = 0f,
                            onExpandedChange = {},
                            showHeader = false,
                            onNavigateToDownloadManager = onNavigateToDownloadManager,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                }

                SettingsPage.TrafficManagement -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsTrafficManagementSection()
                    }
                }

                SettingsPage.Backup -> {
                    for (cardIndex in 0..4) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsBackupRestoreSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                currentPlaylistCount = localPlaylistCount,
                                backupRestoreUiState = backupRestoreUiState,
                                configTransferUiState = configTransferUiState,
                                onExportClick = {
                                    if (!backupRestoreUiState.isExporting) {
                                        backupRestoreVm.initialize(context)
                                        exportPlaylistLauncher.launch(
                                            backupRestoreVm.generateBackupFileName()
                                        )
                                    }
                                },
                                onImportClick = {
                                    if (!backupRestoreUiState.isImporting) {
                                        importPlaylistLauncher.launch(arrayOf("*/*"))
                                    }
                                },
                                onExportConfigClick = {
                                    if (!configTransferUiState.isExporting) {
                                        configTransferVm.initialize(context)
                                        exportConfigLauncher.launch(
                                            configTransferVm.generateConfigFileName()
                                        )
                                    }
                                },
                                onImportConfigClick = {
                                    if (!configTransferUiState.isImporting) {
                                        importConfigLauncher.launch(arrayOf("*/*"))
                                    }
                                },
                                onClearExportStatus = backupRestoreVm::clearExportStatus,
                                onClearImportStatus = backupRestoreVm::clearImportStatus,
                                onClearConfigExportStatus = configTransferVm::clearExportStatus,
                                onClearConfigImportStatus = configTransferVm::clearImportStatus,
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                showGitHubConfigDialog = showGitHubConfigDialog,
                                showWebDavConfigDialog = showWebDavConfigDialog,
                                onOpenGitHubConfig = { showGitHubConfigDialog = true },
                                onOpenClearGitHubConfig = { showClearGitHubConfigDialog = true },
                                onOpenWebDavConfig = { showWebDavConfigDialog = true },
                                onOpenClearWebDavConfig = { showClearWebDavConfigDialog = true },
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.ListenTogether -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        ListenTogetherSettingsSection(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Transparent),
                            isUsingDefaultServer = listenTogetherServerInput.isBlank() ||
                                configuredListenTogetherBaseUrlOrNull(listenTogetherServerInput)?.let(
                                    ::isDefaultListenTogetherBaseUrl
                                ) == true,
                            isInRoom = !listenTogetherSessionState.roomId.isNullOrBlank(),
                            nickname = listenTogetherNickname,
                            onOpenServerDialog = {
                                listenTogetherServerTestMessage = null
                                showListenTogetherServerDialog = true
                            },
                            onResetIdentity = {
                                if (listenTogetherSessionState.roomId.isNullOrBlank()) {
                                    showListenTogetherResetUuidDialog = true
                                }
                            },
                            onOpenNicknameDialog = {
                                if (listenTogetherSessionState.roomId.isNullOrBlank()) {
                                    listenTogetherNicknameInput = listenTogetherNickname
                                    listenTogetherNicknameError = null
                                    showListenTogetherNicknameDialog = true
                                }
                            }
                        )
                    }
                }

                SettingsPage.About -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsAboutContent(
                            devModeEnabled = devModeEnabled,
                            onVersionClick = {
                                if (!devModeEnabled) {
                                    versionTapCount++
                                    if (versionTapCount >= 7) {
                                        onDevModeChange(true)
                                        inlineMsg = composeResources.getString(R.string.debug_mode_opened)
                                        versionTapCount = 0
                                    }
                                } else {
                                    inlineMsg = composeResources.getString(R.string.debug_mode_enabled)
                                }
                            },
                            onCopyValue = { value ->
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(
                                        ClipData.newPlainText("settings_build_value", value)
                                    )
                                val copiedMessage = composeResources.getString(R.string.toast_copied)
                                inlineMsg = copiedMessage
                                showSettingsMessage(copiedMessage)
                            },
                            onOpenGitHubRepo = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/cwuom/NeriPlayer".toUri()
                                )
                                context.startActivity(intent)
                            }
                        )
                    }
                }
                    }
                }
            }
        }
    }
    }

    SettingsNeteaseAuthDialogs(
        showSheet = showNeteaseSheet,
        initialTab = neteaseSheetInitialTab,
        onDismissSheet = { showNeteaseSheet = false },
        inlineMsg = inlineMsg,
        onInlineMsgChange = { inlineMsg = it },
        showConfirmDialog = showConfirmDialog,
        confirmPhoneMasked = confirmPhoneMasked,
        onDismissConfirmDialog = { showConfirmDialog = false },
        vm = neteaseVm,
        showSavedCookieDialog = showNeteaseSavedCookieDialog,
        onDismissSavedCookieDialog = { showNeteaseSavedCookieDialog = false },
        onOpenSheetAtTab = { tab ->
            inlineMsg = null
            neteaseSheetInitialTab = tab
            showNeteaseSheet = true
        },
        onLogout = {
            showNeteaseSavedCookieDialog = false
            neteaseVm.clearCookies()
        },
        onBrowserLogin = null
    )

    SettingsBiliAuthDialogs(
        showSheet = showBiliSheet,
        initialTab = biliSheetInitialTab,
        onDismissSheet = { showBiliSheet = false },
        inlineMsg = inlineMsg,
        onInlineMsgChange = { inlineMsg = it },
        vm = biliVm,
        showSavedCookieDialog = showBiliSavedCookieDialog,
        onDismissSavedCookieDialog = { showBiliSavedCookieDialog = false },
        onOpenSheetAtTab = { tab ->
            inlineMsg = null
            biliSheetInitialTab = tab
            showBiliSheet = true
        },
        onLogout = {
            showBiliSavedCookieDialog = false
            biliVm.clearCookies()
        },
        onBrowserLogin = null
    )

    SettingsYouTubeAuthDialogs(
        showSheet = showYouTubeSheet,
        initialTab = youtubeSheetInitialTab,
        onDismissSheet = { showYouTubeSheet = false },
        inlineMsg = inlineMsg,
        onInlineMsgChange = { inlineMsg = it },
        vm = youtubeVm,
        showSavedCookieDialog = showYouTubeSavedCookieDialog,
        onDismissSavedCookieDialog = { showYouTubeSavedCookieDialog = false },
        onOpenSheetAtTab = { tab ->
            inlineMsg = null
            youtubeSheetInitialTab = tab
            showYouTubeSheet = true
        },
        onLogout = {
            showYouTubeSavedCookieDialog = false
            youtubeVm.clearAuth()
        }
    )
    loginSuccessTitle?.let { title ->
        LoginSuccessDialog(
            title = title,
            onDismiss = { loginSuccessTitle = null }
        )
    }
    SettingsPreferenceDialogs(
        showDefaultStartDestinationDialog = showDefaultStartDestinationDialog,
        onShowDefaultStartDestinationDialogChange = { showDefaultStartDestinationDialog = it },
        homeStartAvailable = homeStartAvailable,
        effectiveDefaultStartDestination = effectiveDefaultStartDestination,
        onDefaultStartDestinationChange = onDefaultStartDestinationChange,
        showColorPickerDialog = showColorPickerDialog,
        onShowColorPickerDialogChange = { showColorPickerDialog = it },
        seedColorHex = seedColorHex,
        themeColorPalette = themeColorPalette,
        onSeedColorChange = onSeedColorChange,
        onAddColorToPalette = onAddColorToPalette,
        onRemoveColorFromPalette = onRemoveColorFromPalette,
        showDpiDialog = showDpiDialog,
        onShowDpiDialogChange = { showDpiDialog = it },
        uiDensityScale = uiDensityScale,
        onUiDensityScaleChange = onUiDensityScaleChange
    )

    if (showListenTogetherResetUuidDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { showListenTogetherResetUuidDialog = false },
            title = { Text(stringResource(R.string.listen_together_reset_uuid)) },
            text = { Text(stringResource(R.string.listen_together_reset_uuid_confirm)) },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        scope.launch {
                            listenTogetherPreferences.resetUserUuid()
                            showListenTogetherResetUuidDialog = false
                            showListenTogetherMessage(
                                composeResources.getString(
                                    R.string.listen_together_reset_uuid_done
                                )
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { showListenTogetherResetUuidDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (showListenTogetherNicknameDialog) {
        MiuixSettingsDialog(
            onDismissRequest = {
                showListenTogetherNicknameDialog = false
                listenTogetherNicknameInput = listenTogetherNickname
                listenTogetherNicknameError = null
            },
            title = { Text(stringResource(R.string.settings_listen_together_default_nickname_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiuixSettingsTextField(
                        value = listenTogetherNicknameInput,
                        onValueChange = {
                            listenTogetherNicknameInput = it.take(24)
                            listenTogetherNicknameError = validateListenTogetherNickname(
                                listenTogetherNicknameInput
                            )?.format(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = {
                            Text(
                                stringResource(
                                    R.string.settings_listen_together_default_nickname_input_label
                                )
                            )
                        }
                    )
                    listenTogetherNicknameError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        val nickname = listenTogetherNicknameInput.trim()
                        val validationError = validateListenTogetherNickname(nickname)
                        if (validationError != null) {
                            listenTogetherNicknameError = validationError.format(context)
                            return@MiuixSettingsTextButton
                        }
                        scope.launch {
                            listenTogetherPreferences.setNickname(nickname)
                            showListenTogetherNicknameDialog = false
                            listenTogetherNicknameError = null
                            showListenTogetherMessage(
                                composeResources.getString(
                                    R.string.settings_listen_together_default_nickname_saved
                                )
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        showListenTogetherNicknameDialog = false
                        listenTogetherNicknameInput = listenTogetherNickname
                        listenTogetherNicknameError = null
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (showListenTogetherServerDialog) {
        MiuixSettingsDialog(
            onDismissRequest = {
                if (!listenTogetherServerTesting) {
                    showListenTogetherServerDialog = false
                    listenTogetherServerInput = listenTogetherWorkerBaseUrlInput
                    listenTogetherServerTestMessage = null
                }
            },
            title = { Text(stringResource(R.string.settings_listen_together_server_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (listenTogetherServerInput.isBlank() ||
                            configuredListenTogetherBaseUrlOrNull(listenTogetherServerInput)?.let(
                                ::isDefaultListenTogetherBaseUrl
                            ) == true
                        ) {
                            stringResource(R.string.settings_listen_together_server_default_desc)
                        } else {
                            stringResource(R.string.settings_listen_together_server_custom_desc)
                        }
                    )
                    MiuixSettingsTextField(
                        value = listenTogetherServerInput,
                        onValueChange = {
                            listenTogetherServerInput = it
                            listenTogetherServerTestMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_listen_together_server_input_label)) },
                        placeholder = { Text(stringResource(R.string.settings_listen_together_server_input_placeholder)) }
                    )
                    if (listenTogetherServerTesting) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.settings_listen_together_server_testing),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        listenTogetherServerTestMessage?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiuixSettingsOutlinedButton(
                            onClick = {
                                scope.launch {
                                    val normalizedCustomServer =
                                        configuredListenTogetherBaseUrlOrNull(listenTogetherServerInput)
                                    if (listenTogetherServerInput.isNotBlank() && normalizedCustomServer == null) {
                                        listenTogetherServerTestMessage = composeResources.getString(
                                            R.string.settings_listen_together_server_input_invalid
                                        )
                                        return@launch
                                    }
                                    listenTogetherServerTesting = true
                                    val usingDefaultServer = normalizedCustomServer == null
                                    val result = listenTogetherApi.testServerAvailability(
                                        normalizedCustomServer ?: resolveListenTogetherBaseUrl(null)
                                    )
                                    listenTogetherServerTesting = false
                                    listenTogetherServerTestMessage = when {
                                        result.ok && usingDefaultServer ->
                                            composeResources.getString(R.string.settings_listen_together_server_test_success_default)
                                        result.ok ->
                                            composeResources.getString(R.string.settings_listen_together_server_test_success_custom)
                                        result.message == "invalid_response" ->
                                            composeResources.getString(R.string.settings_listen_together_server_test_invalid)
                                        else ->
                                            composeResources.getString(
                                                R.string.settings_listen_together_server_test_failed,
                                                result.message
                                            )
                                    }
                                }
                            },
                            enabled = !listenTogetherServerTesting
                        ) {
                            Text(stringResource(R.string.settings_listen_together_server_test))
                        }
                        MiuixSettingsTextButton(
                            onClick = {
                                listenTogetherServerInput = ""
                                listenTogetherServerTestMessage = composeResources.getString(
                                    R.string.settings_listen_together_server_reset_done
                                )
                            },
                            enabled = !listenTogetherServerTesting
                        ) {
                            Text(stringResource(R.string.action_reset))
                        }
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        scope.launch {
                            val normalizedInput =
                                configuredListenTogetherBaseUrlOrNull(listenTogetherServerInput)
                            if (listenTogetherServerInput.isNotBlank() && normalizedInput == null) {
                                listenTogetherServerTestMessage = composeResources.getString(
                                    R.string.settings_listen_together_server_input_invalid
                                )
                                return@launch
                            }
                            listenTogetherPreferences.setWorkerBaseUrl(normalizedInput.orEmpty())
                            listenTogetherPreferences.setWorkerBaseUrlInput(normalizedInput.orEmpty())
                            listenTogetherServerInput = normalizedInput.orEmpty()
                            showListenTogetherServerDialog = false
                            listenTogetherServerTestMessage = null
                            showListenTogetherMessage(
                                composeResources.getString(
                                    R.string.settings_listen_together_server_saved
                                )
                            )
                        }
                    },
                    enabled = !listenTogetherServerTesting
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        showListenTogetherServerDialog = false
                        listenTogetherServerInput = listenTogetherWorkerBaseUrlInput
                        listenTogetherServerTestMessage = null
                    },
                    enabled = !listenTogetherServerTesting
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    SettingsGitHubDialogs(
        showGitHubConfigDialog = showGitHubConfigDialog,
        onShowGitHubConfigDialogChange = { showGitHubConfigDialog = it },
        showClearGitHubConfigDialog = showClearGitHubConfigDialog,
        onShowClearGitHubConfigDialogChange = { showClearGitHubConfigDialog = it }
    )

    SettingsWebDavDialogs(
        showWebDavConfigDialog = showWebDavConfigDialog,
        onShowWebDavConfigDialogChange = { showWebDavConfigDialog = it },
        showClearWebDavConfigDialog = showClearWebDavConfigDialog,
        onShowClearWebDavConfigDialogChange = { showClearWebDavConfigDialog = it }
    )

    if (showDownloadDirectorySwitchWarningDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { showDownloadDirectorySwitchWarningDialog = false },
            title = {
                Text(stringResource(R.string.settings_download_directory_switch_warning_title))
            },
            text = {
                Text(stringResource(R.string.settings_download_directory_switch_warning_message))
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    enabled = downloadDirectoryChangeEnabled,
                    onClick = {
                        if (guardDownloadDirectoryChange()) {
                            showDownloadDirectorySwitchWarningDialog = false
                            return@MiuixSettingsTextButton
                        }
                        showDownloadDirectorySwitchWarningDialog = false
                        downloadDirectoryLauncher.launch(null)
                    }
                ) {
                    Text(stringResource(R.string.settings_download_directory_switch_warning_confirm))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(
                    onClick = { showDownloadDirectorySwitchWarningDialog = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingDownloadDirectoryChange?.let { pendingChange ->
        MiuixSettingsDialog(
            onDismissRequest = {
                pendingDownloadDirectoryChange = null
                if (pendingChange.releaseTargetPermissionOnCancel) {
                    ManagedDownloadStorage.releasePersistedDirectoryPermission(
                        context,
                        pendingChange.targetUri
                    )
                }
            },
            title = { Text(stringResource(R.string.settings_download_directory_migrate_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_download_directory_migrate_message,
                        pendingChange.targetSummary
                    )
                )
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    enabled = downloadDirectoryChangeEnabled,
                    onClick = {
                        if (
                            guardDownloadDirectoryChange(
                                targetUri = pendingChange.targetUri,
                                releaseTargetPermissionOnBlock = pendingChange.releaseTargetPermissionOnCancel
                            )
                        ) {
                            pendingDownloadDirectoryChange = null
                            return@MiuixSettingsTextButton
                        }
                        pendingDownloadDirectoryChange = null
                        scope.launch {
                            isMigratingDownloadDirectory = true
                            try {
                                runCatching {
                                    val migrationResult = ManagedDownloadStorage.migrateManagedDownloads(
                                        context = context,
                                        fromDirectoryUri = pendingChange.previousUri,
                                        toDirectoryUri = pendingChange.targetUri
                                    )
                                    if (!migrationResult.canSwitchDirectory) {
                                        if (pendingChange.releaseTargetPermissionOnCancel) {
                                            ManagedDownloadStorage.releasePersistedDirectoryPermission(
                                                context,
                                                pendingChange.targetUri
                                            )
                                        }
                                        inlineMsg = resources.getQuantityString(
                                            R.plurals.settings_download_directory_migrate_failed,
                                            migrationResult.skippedFiles,
                                            migrationResult.skippedFiles
                                        )
                                    } else {
                                        applyDownloadDirectoryChange(
                                            targetUri = pendingChange.targetUri,
                                            targetSummary = pendingChange.targetSummary,
                                            previousUri = pendingChange.previousUri,
                                            shouldReleasePreviousPermission = pendingChange.shouldReleasePreviousPermission,
                                            migrationResult = migrationResult
                                        )
                                    }
                                }.onFailure {
                                    if (pendingChange.releaseTargetPermissionOnCancel) {
                                        ManagedDownloadStorage.releasePersistedDirectoryPermission(
                                            context,
                                            pendingChange.targetUri
                                        )
                                    }
                                    inlineMsg = composeResources.getString(
                                        R.string.settings_download_directory_pick_failed,
                                        it.message ?: ""
                                    )
                                }
                            } finally {
                                isMigratingDownloadDirectory = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_download_directory_migrate_confirm))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(
                    enabled = downloadDirectoryChangeEnabled,
                    onClick = {
                        if (
                            guardDownloadDirectoryChange(
                                targetUri = pendingChange.targetUri,
                                releaseTargetPermissionOnBlock = pendingChange.releaseTargetPermissionOnCancel
                            )
                        ) {
                            pendingDownloadDirectoryChange = null
                            return@MiuixSettingsTextButton
                        }
                        pendingDownloadDirectoryChange = null
                        scope.launch {
                            runCatching {
                                applyDownloadDirectoryChange(
                                    targetUri = pendingChange.targetUri,
                                    targetSummary = pendingChange.targetSummary,
                                    previousUri = pendingChange.previousUri,
                                    shouldReleasePreviousPermission = pendingChange.shouldReleasePreviousPermission
                                )
                            }.onFailure {
                                if (pendingChange.releaseTargetPermissionOnCancel) {
                                    ManagedDownloadStorage.releasePersistedDirectoryPermission(
                                        context,
                                        pendingChange.targetUri
                                    )
                                }
                                inlineMsg = composeResources.getString(
                                    R.string.settings_download_directory_pick_failed,
                                    it.message ?: ""
                                )
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_download_directory_migrate_skip))
                }
            }
        )
    }

    if (isMigratingDownloadDirectory) {
        val activeMigrationProgress = migrationProgress
        val stageText = when (activeMigrationProgress?.stage) {
            ManagedDownloadStorage.MigrationStage.PREPARING ->
                stringResource(R.string.settings_download_directory_migrating_stage_preparing)
            ManagedDownloadStorage.MigrationStage.COPYING ->
                stringResource(R.string.settings_download_directory_migrating_stage_copying)
            ManagedDownloadStorage.MigrationStage.REWRITING_METADATA ->
                stringResource(R.string.settings_download_directory_migrating_stage_rewriting)
            ManagedDownloadStorage.MigrationStage.CLEANING_UP ->
                stringResource(R.string.settings_download_directory_migrating_stage_cleanup)
            ManagedDownloadStorage.MigrationStage.FINALIZING ->
                stringResource(R.string.settings_download_directory_migrating)
            null -> stringResource(R.string.settings_download_directory_migrating_desc)
        }
        val progressFraction = activeMigrationProgress?.fraction?.coerceIn(0f, 1f) ?: 0f
        val processedSummary = activeMigrationProgress?.let { progress ->
            resources.getQuantityString(
                R.plurals.settings_download_directory_migrating_progress_files,
                progress.stageTotal.coerceAtLeast(0),
                progress.stageProcessed.coerceAtLeast(0),
                progress.stageTotal.coerceAtLeast(0)
            )
        }
        val copiedBytesSummary = activeMigrationProgress
            ?.takeIf { it.totalBytes > 0L }
            ?.let { progress ->
                composeResources.getString(
                    R.string.settings_download_directory_migrating_progress_bytes,
                    Formatter.formatShortFileSize(context, progress.copiedBytes.coerceAtLeast(0L)),
                    Formatter.formatShortFileSize(context, progress.totalBytes)
                )
            }
        val currentFileSummary = activeMigrationProgress?.currentFileName
            ?.takeIf(String::isNotBlank)
            ?.let { fileName ->
                composeResources.getString(R.string.settings_download_directory_migrating_current, fileName)
            }
        MiuixSettingsDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.settings_download_directory_migrating)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(stageText)
                    }
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    processedSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    copiedBytesSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    currentFileSummary?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

}

private data class ThemeOption(
    val value: String,
    val labelRes: Int,
    val descriptionRes: Int
)

@Composable
private fun ThemeModeSelectorListItem(
    isDarkTheme: Boolean,
    themeMode: ThemeMode,
    onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var tabsTopLeftInWindow by remember { mutableStateOf(Offset.Zero) }
    var tabsWidthPx by remember { mutableFloatStateOf(0f) }
    var tabsHeightPx by remember { mutableFloatStateOf(0f) }
    val selectedIndex = if (isDarkTheme) 1 else 0

    ListItem(
        modifier = modifier,
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Brightness4,
                contentDescription = stringResource(R.string.settings_theme_mode),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_theme_mode)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_theme_mode_desc))
                MiuixSettingsSegmentedTabs(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        tabsTopLeftInWindow = coordinates.positionInWindow()
                        tabsWidthPx = coordinates.size.width.toFloat()
                        tabsHeightPx = coordinates.size.height.toFloat()
                    },
                    labels = listOf(
                        stringResource(R.string.settings_theme_mode_light),
                        stringResource(R.string.settings_theme_mode_dark)
                    ),
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { index ->
                        val targetMode = if (index == 0) {
                            ThemeMode.LIGHT
                        } else {
                            ThemeMode.DARK
                        }
                        if (targetMode != themeMode) {
                            val tabWidth = tabsWidthPx / 2f
                            val origin = if (tabWidth > 0f && tabsHeightPx > 0f) {
                                tabsTopLeftInWindow + Offset(
                                    x = tabWidth * (index + 0.5f),
                                    y = tabsHeightPx / 2f
                                )
                            } else {
                                Offset.Zero
                            }
                            onThemeModeRequest(targetMode, origin, 1f)
                        }
                    }
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun ThemeAutoModeListItem(
    themeMode: ThemeMode,
    isDarkTheme: Boolean,
    onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit
) {
    val autoEnabled = themeMode == ThemeMode.AUTO
    var switchCenterInWindow by remember { mutableStateOf<Offset?>(null) }
    var revealStartRadiusPx by remember { mutableFloatStateOf(18f) }

    fun requestAutoMode(enabled: Boolean) {
        if (enabled == autoEnabled) {
            return
        }
        val targetMode = when {
            enabled -> ThemeMode.AUTO
            isDarkTheme -> ThemeMode.DARK
            else -> ThemeMode.LIGHT
        }
        onThemeModeRequest(
            targetMode,
            switchCenterInWindow ?: Offset.Zero,
            revealStartRadiusPx
        )
    }

    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.BrightnessAuto,
                contentDescription = stringResource(R.string.settings_theme_mode_auto),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_theme_mode_auto)) },
        supportingContent = {
            Text(stringResource(R.string.settings_theme_mode_auto_desc))
        },
        trailingContent = {
            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    revealStartRadiusPx = maxOf(coordinates.size.width, coordinates.size.height) / 2f
                    switchCenterInWindow = coordinates.positionInWindow() + Offset(
                        x = coordinates.size.width / 2f,
                        y = coordinates.size.height / 2f
                    )
                }
                    .size(width = 56.dp, height = 40.dp)
                    .settingsItemClickable(onClick = {
                        requestAutoMode(!autoEnabled)
                    }),
                contentAlignment = Alignment.Center
            ) {
                MiuixSettingsSwitch(
                    checked = autoEnabled,
                    onCheckedChange = null
                )
            }
        },
        modifier = Modifier.settingsItemClickable(onClick = {
            requestAutoMode(!autoEnabled)
        }),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun ThemePaletteStyleSelector(
    selectedStyle: String,
    onStyleChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val normalizedStyle = ThemeDefaults.normalizePaletteStyle(selectedStyle)
    val options = listOf(
        ThemeOption("TonalSpot", R.string.settings_theme_style_tonal_spot, R.string.settings_theme_style_tonal_spot_desc),
        ThemeOption("Neutral", R.string.settings_theme_style_neutral, R.string.settings_theme_style_neutral_desc),
        ThemeOption("Vibrant", R.string.settings_theme_style_vibrant, R.string.settings_theme_style_vibrant_desc),
        ThemeOption("Expressive", R.string.settings_theme_style_expressive, R.string.settings_theme_style_expressive_desc),
        ThemeOption("Monochrome", R.string.settings_theme_style_monochrome, R.string.settings_theme_style_monochrome_desc),
        ThemeOption("Fidelity", R.string.settings_theme_style_fidelity, R.string.settings_theme_style_fidelity_desc)
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_theme_palette_style),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.settings_theme_palette_style_desc),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        options.forEach { option ->
            MiuixSettingsChoiceRow(
                title = stringResource(option.labelRes),
                subtitle = stringResource(option.descriptionRes),
                selected = normalizedStyle == option.value,
                onClick = { onStyleChange(option.value) }
            )
        }
    }
}

@Composable
private fun ThemeColorSpecSelector(
    selectedSpec: String,
    onSpecChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val normalizedSpec = ThemeDefaults.normalizeColorSpec(selectedSpec)
    val options = listOf(
        ThemeDefaults.COLOR_SPECS[0] to stringResource(R.string.settings_theme_color_spec_2021),
        ThemeDefaults.COLOR_SPECS[1] to stringResource(R.string.settings_theme_color_spec_2025)
    )

    ListItem(
        modifier = modifier,
        headlineContent = { Text(stringResource(R.string.settings_theme_color_spec)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_theme_color_spec_desc))
                MiuixSettingsSegmentedTabs(
                    labels = options.map { it.second },
                    selectedIndex = options.indexOfFirst { it.first == normalizedSpec }.coerceAtLeast(0),
                    onSelectedIndexChange = { index -> onSpecChange(options[index].first) }
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsPersonalizationPageContent(
    autoSettingsRepository: AutoSettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    defaultStartDestinationLabel: String,
    onOpenDefaultStartDestination: () -> Unit,
    internationalEnabled: Boolean,
    homeTrendingLabelRes: Int,
    homeRadarLabelRes: Int,
    homeRecommendedLabelRes: Int,
    homeTrendingSupportingRes: Int?,
    homeRadarSupportingRes: Int?,
    homeRecommendedSupportingRes: Int?,
    neteaseHomeCardsEnabled: Boolean,
    homeStartAvailable: Boolean,
    showHomeContinueCard: Boolean,
    onShowHomeContinueCardChange: (Boolean) -> Unit,
    showHomeTrendingCard: Boolean,
    onShowHomeTrendingCardChange: (Boolean) -> Unit,
    showHomeRadarCard: Boolean,
    onShowHomeRadarCardChange: (Boolean) -> Unit,
    showHomeRecommendedCard: Boolean,
    onShowHomeRecommendedCardChange: (Boolean) -> Unit,
    lyricFontScales: LyricFontScales,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    backgroundImageUri: String?,
    onPickBackgroundImage: () -> Unit,
    onClearBackgroundImage: () -> Unit,
    pendingBackgroundImageBlur: Float,
    onPendingBackgroundImageBlurChange: (Float) -> Unit,
    onBackgroundImageBlurCommit: () -> Unit,
    pendingBackgroundImageAlpha: Float,
    onPendingBackgroundImageAlphaChange: (Float) -> Unit,
    onBackgroundImageAlphaCommit: () -> Unit,
    cardIndex: Int? = null,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    fun shouldShowCard(index: Int): Boolean = cardIndex == null || cardIndex == index

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val autoShowKeyboard by autoSettingsRepository.autoShowKeyboardFlow.collectAsState(initial = false)
        val showCoverSourceBadge by autoSettingsRepository.showCoverSourceBadgeFlow.collectAsState(initial = true)
        val alwaysUseNewTabStyle by autoSettingsRepository.alwaysUseNewTabStyleFlow.collectAsState(initial = true)
        val nowPlayingShowTitle by autoSettingsRepository.nowPlayingShowTitleFlow.collectAsState(initial = true)
        val nowPlayingSongTitleMarqueeEnabled by autoSettingsRepository
            .nowPlayingSongTitleMarqueeEnabledFlow
            .collectAsState(initial = false)
        val nowPlayingKeepScreenOn by autoSettingsRepository.nowPlayingKeepScreenOnFlow.collectAsState(initial = true)
        val nowPlayingToolbarDockEnabled by autoSettingsRepository.nowPlayingToolbarDockEnabledFlow.collectAsState(
            initial = true
        )
        val playbackControlLayoutPreferences by AppContainer.settingsRepo
            .playbackControlLayoutPreferencesFlow
            .collectAsState(initial = PlaybackControlLayoutPreferences())
        val nowPlayingCoverLyricsEnabled by autoSettingsRepository.nowPlayingCoverLyricsEnabledFlow.collectAsState(
            initial = true
        )
        val nowPlayingProgressShowQualitySwitch by autoSettingsRepository
            .nowPlayingProgressShowQualitySwitchFlow
            .collectAsState(initial = true)
        val nowPlayingProgressShowAudioCodec by autoSettingsRepository
            .nowPlayingProgressShowAudioCodecFlow
            .collectAsState(initial = true)
        val nowPlayingProgressShowAudioSpec by autoSettingsRepository
            .nowPlayingProgressShowAudioSpecFlow
            .collectAsState(initial = true)
        val showLyricTranslation by autoSettingsRepository.showLyricTranslationFlow.collectAsState(initial = true)
        val lyricTranslationUsePhonetic by autoSettingsRepository.lyricTranslationUsePhoneticFlow.collectAsState(
            initial = false
        )

        if (shouldShowCard(0)) PersonalizationDetailCard(
            highlighted = false,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_personalization_start_section),
                description = stringResource(R.string.settings_personalization_start_section_desc)
            )
            AutoSettingsListItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DEFAULT_START_DESTINATION),
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Home,
                        contentDescription = stringResource(R.string.settings_default_start_screen),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.settings_default_start_screen_desc,
                            defaultStartDestinationLabel
                        )
                    )
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished,
                onClick = onOpenDefaultStartDestination
            )

            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.AUTO_SHOW_KEYBOARD),
                checked = autoShowKeyboard,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setAutoShowKeyboard(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
        }

        if (shouldShowCard(1)) PersonalizationDetailCard(
            highlighted = false,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_personalization_home_section),
                description = stringResource(R.string.settings_personalization_home_section_desc)
            )
            if (!neteaseHomeCardsEnabled && !internationalEnabled) {
                Text(
                    text = stringResource(R.string.settings_home_card_netease_login_required),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            SettingsHomeCardSwitch(
                title = stringResource(R.string.player_continue),
                icon = Icons.Outlined.History,
                checked = showHomeContinueCard,
                onCheckedChange = onShowHomeContinueCardChange,
                targetId = "setting:home_card_continue",
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )

            SettingsHomeCardSwitch(
                title = stringResource(homeTrendingLabelRes),
                description = homeTrendingSupportingRes?.let { stringResource(it) },
                icon = Icons.Outlined.Bolt,
                checked = showHomeTrendingCard,
                onCheckedChange = onShowHomeTrendingCardChange,
                enabled = neteaseHomeCardsEnabled,
                targetId = "setting:home_card_trending",
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )

            SettingsHomeCardSwitch(
                title = stringResource(homeRadarLabelRes),
                description = homeRadarSupportingRes?.let { stringResource(it) },
                icon = if (internationalEnabled) Icons.Outlined.Explore else Icons.Outlined.Radar,
                checked = showHomeRadarCard,
                onCheckedChange = onShowHomeRadarCardChange,
                enabled = neteaseHomeCardsEnabled,
                targetId = "setting:home_card_radar",
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )

            SettingsHomeCardSwitch(
                title = stringResource(homeRecommendedLabelRes),
                description = homeRecommendedSupportingRes?.let { stringResource(it) },
                icon = Icons.Outlined.Star,
                checked = showHomeRecommendedCard,
                onCheckedChange = onShowHomeRecommendedCardChange,
                targetId = "setting:home_card_recommended",
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )

            LazyAnimatedVisibility(visible = !homeStartAvailable) {
                Text(
                    text = stringResource(R.string.settings_home_hidden_notice),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (shouldShowCard(2)) PersonalizationDetailCard(
            highlighted = false,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_personalization_playback_info_section),
                description = stringResource(R.string.settings_personalization_playback_info_section_desc)
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.SHOW_COVER_SOURCE_BADGE),
                checked = showCoverSourceBadge,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setShowCoverSourceBadge(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_SHOW_TITLE),
                checked = nowPlayingShowTitle,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setNowPlayingShowTitle(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(
                    AutoSettingsKeys.NOW_PLAYING_SONG_TITLE_MARQUEE_ENABLED
                ),
                checked = nowPlayingSongTitleMarqueeEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        autoSettingsRepository.setNowPlayingSongTitleMarqueeEnabled(enabled)
                    }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOW_PLAYING_COVER_LYRICS_ENABLED),
                checked = nowPlayingCoverLyricsEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setNowPlayingCoverLyricsEnabled(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(
                    AutoSettingsKeys.NOWPLAYING_PROGRESS_SHOW_QUALITY_SWITCH
                ),
                checked = nowPlayingProgressShowQualitySwitch,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setNowPlayingProgressShowQualitySwitch(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_PROGRESS_SHOW_AUDIO_CODEC),
                checked = nowPlayingProgressShowAudioCodec,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setNowPlayingProgressShowAudioCodec(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_PROGRESS_SHOW_AUDIO_SPEC),
                checked = nowPlayingProgressShowAudioSpec,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setNowPlayingProgressShowAudioSpec(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
        }

        if (shouldShowCard(3)) PersonalizationDetailCard(
            highlighted = false,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_personalization_playback_controls_section),
                description = stringResource(R.string.settings_personalization_playback_controls_section_desc)
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.ALWAYS_USE_NEW_TAB_STYLE),
                checked = alwaysUseNewTabStyle,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setAlwaysUseNewTabStyle(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_KEEP_SCREEN_ON),
                checked = nowPlayingKeepScreenOn,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setNowPlayingKeepScreenOn(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            val nowPlayingControlsAtBottom =
                playbackControlLayoutPreferences.nowPlayingPlacement.placesControlsAtBottom
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.NOWPLAYING_TOOLBAR_DOCK_ENABLED),
                checked = nowPlayingToolbarDockEnabled && !nowPlayingControlsAtBottom,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setNowPlayingToolbarDockEnabled(enabled) }
                },
                enabled = !nowPlayingControlsAtBottom,
                supportingContent = if (nowPlayingControlsAtBottom) {
                    {
                        Text(
                            stringResource(
                                R.string.settings_nowplaying_toolbar_dock_disabled_by_control_position
                            )
                        )
                    }
                } else {
                    null
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PlaybackControlLayoutSettings(
                preferences = playbackControlLayoutPreferences,
                onPreferencesChange = { preferences ->
                    scope.launch {
                        AppContainer.settingsRepo.setPlaybackControlLayoutPreferences(preferences)
                    }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
        }

        if (shouldShowCard(4)) PersonalizationDetailCard(
            highlighted = false,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_personalization_lyrics_scale_section),
                description = stringResource(R.string.settings_personalization_lyrics_scale_section_desc)
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.SHOW_LYRIC_TRANSLATION),
                checked = showLyricTranslation,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setShowLyricTranslation(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PersonalizationSwitchItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.LYRIC_TRANSLATION_USE_PHONETIC),
                checked = lyricTranslationUsePhonetic,
                onCheckedChange = { enabled ->
                    scope.launch { autoSettingsRepository.setLyricTranslationUsePhonetic(enabled) }
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_lyrics_cover_page_section),
                description = stringResource(R.string.settings_lyrics_cover_page_section_desc)
            )
            LyricFontScaleSettingsItem(
                setting = AutoSettingsMetadata.requireSetting(
                    AutoSettingsKeys.NOWPLAYING_COVER_LYRIC_FONT_SCALE
                ),
                currentScale = lyricFontScales.coverLyric,
                onScaleCommit = { scale ->
                    onLyricFontScaleChange(LyricFontScaleTarget.COVER_LYRIC, scale)
                },
                sampleText = stringResource(R.string.settings_lyrics_sample),
                sampleBaseSizeSp = 18f,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            LyricFontScaleSettingsItem(
                setting = AutoSettingsMetadata.requireSetting(
                    AutoSettingsKeys.NOWPLAYING_COVER_TRANSLATION_FONT_SCALE
                ),
                currentScale = lyricFontScales.coverTranslation,
                onScaleCommit = { scale ->
                    onLyricFontScaleChange(LyricFontScaleTarget.COVER_TRANSLATION, scale)
                },
                sampleText = stringResource(R.string.settings_lyrics_translation_sample),
                sampleBaseSizeSp = 14f,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_lyrics_page_section),
                description = stringResource(R.string.settings_lyrics_page_section_desc)
            )
            LyricFontScaleSettingsItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.LYRICS_PAGE_LYRIC_FONT_SCALE),
                currentScale = lyricFontScales.lyricsPageLyric,
                onScaleCommit = { scale ->
                    onLyricFontScaleChange(LyricFontScaleTarget.LYRICS_PAGE_LYRIC, scale)
                },
                sampleText = stringResource(R.string.settings_lyrics_sample),
                sampleBaseSizeSp = 20f,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            LyricFontScaleSettingsItem(
                setting = AutoSettingsMetadata.requireSetting(
                    AutoSettingsKeys.LYRICS_PAGE_TRANSLATION_FONT_SCALE
                ),
                currentScale = lyricFontScales.lyricsPageTranslation,
                onScaleCommit = { scale ->
                    onLyricFontScaleChange(LyricFontScaleTarget.LYRICS_PAGE_TRANSLATION, scale)
                },
                sampleText = stringResource(R.string.settings_lyrics_translation_sample),
                sampleBaseSizeSp = 16f,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )

        }

        if (shouldShowCard(5)) PersonalizationDetailCard(
            highlighted = false,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        ) {
            MiuixSettingsSectionIntro(
                title = stringResource(R.string.settings_personalization_background_section),
                description = stringResource(R.string.settings_personalization_background_section_desc)
            )
            AutoSettingsListItem(
                setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BACKGROUND_IMAGE_URI),
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Wallpaper,
                        contentDescription = stringResource(R.string.settings_custom_background),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                supportingContent = {
                    Text(
                        if (backgroundImageUri != null) {
                            stringResource(R.string.settings_background_change)
                        } else {
                            stringResource(R.string.settings_background_select)
                        }
                    )
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished,
                onClick = onPickBackgroundImage
            )

            LazyAnimatedVisibility(visible = backgroundImageUri != null) {
                Column {
                    MiuixSettingsTextButton(onClick = onClearBackgroundImage) {
                        Text(stringResource(R.string.background_clear))
                    }

                    AutoSettingsListItem(
                        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BACKGROUND_IMAGE_BLUR),
                        showDefaultIcon = false,
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished,
                        supportingContent = {
                            MiuixSettingsSlider(
                                value = pendingBackgroundImageBlur,
                                onValueChange = onPendingBackgroundImageBlurChange,
                                onValueChangeFinished = onBackgroundImageBlurCommit,
                                valueRange = 0f..25f
                            )
                        }
                    )

                    AutoSettingsListItem(
                        setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BACKGROUND_IMAGE_ALPHA),
                        showDefaultIcon = false,
                        highlightTargetId = highlightTargetId,
                        highlightPulse = highlightPulse,
                        onHighlightFinished = onHighlightFinished,
                        supportingContent = {
                            MiuixSettingsSlider(
                                value = pendingBackgroundImageAlpha,
                                onValueChange = onPendingBackgroundImageAlphaChange,
                                onValueChangeFinished = onBackgroundImageAlphaCommit,
                                valueRange = 0.1f..1.0f
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalizationDetailCard(
    highlighted: Boolean,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    MiuixSettingsSectionCard(
        highlighted = highlighted,
        highlightPulse = highlightPulse,
        onHighlightFinished = if (highlighted) onHighlightFinished else null,
        content = content
    )
}

private enum class PlaybackControlLayoutSetting {
    NOW_PLAYING_PLACEMENT,
    NOW_PLAYING_SIZE,
    LYRICS_SIZE
}

@Composable
private fun PlaybackControlLayoutSettings(
    preferences: PlaybackControlLayoutPreferences,
    onPreferencesChange: (PlaybackControlLayoutPreferences) -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    var selectedSetting by remember { mutableStateOf<PlaybackControlLayoutSetting?>(null) }

    PlaybackControlLayoutListItem(
        targetId = "setting:nowplaying_control_placement",
        icon = Icons.Outlined.DashboardCustomize,
        title = stringResource(R.string.settings_nowplaying_control_placement),
        description = stringResource(R.string.settings_nowplaying_control_placement_desc),
        value = nowPlayingControlPlacementLabel(preferences.nowPlayingPlacement),
        onClick = { selectedSetting = PlaybackControlLayoutSetting.NOW_PLAYING_PLACEMENT },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    PlaybackControlLayoutListItem(
        targetId = "setting:nowplaying_control_size",
        icon = Icons.Outlined.AspectRatio,
        title = stringResource(R.string.settings_nowplaying_control_size),
        description = stringResource(R.string.settings_nowplaying_control_size_desc),
        value = playbackControlSizeLabel(preferences.nowPlayingSize),
        onClick = { selectedSetting = PlaybackControlLayoutSetting.NOW_PLAYING_SIZE },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    PlaybackControlLayoutListItem(
        targetId = "setting:lyrics_control_size",
        icon = Icons.Outlined.TextFields,
        title = stringResource(R.string.settings_lyrics_control_size),
        description = stringResource(R.string.settings_lyrics_control_size_desc),
        value = playbackControlSizeLabel(preferences.lyricsSize),
        onClick = { selectedSetting = PlaybackControlLayoutSetting.LYRICS_SIZE },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )

    val setting = selectedSetting ?: return
    val title = when (setting) {
        PlaybackControlLayoutSetting.NOW_PLAYING_PLACEMENT ->
            stringResource(R.string.settings_nowplaying_control_placement)
        PlaybackControlLayoutSetting.NOW_PLAYING_SIZE ->
            stringResource(R.string.settings_nowplaying_control_size)
        PlaybackControlLayoutSetting.LYRICS_SIZE ->
            stringResource(R.string.settings_lyrics_control_size)
    }
    MiuixSettingsDialog(
        onDismissRequest = { selectedSetting = null },
        title = { Text(title) },
        text = {
            Column {
                when (setting) {
                    PlaybackControlLayoutSetting.NOW_PLAYING_PLACEMENT -> {
                        NowPlayingControlPlacement.values().forEach { placement ->
                            MiuixSettingsChoiceRow(
                                title = nowPlayingControlPlacementLabel(placement),
                                subtitle = if (placement.placesControlsAtBottom) {
                                    stringResource(
                                        R.string.settings_nowplaying_toolbar_dock_disabled_by_control_position
                                    )
                                } else {
                                    null
                                },
                                selected = placement == preferences.nowPlayingPlacement,
                                onClick = {
                                    onPreferencesChange(
                                        preferences.copy(nowPlayingPlacement = placement)
                                    )
                                    selectedSetting = null
                                }
                            )
                        }
                    }

                    PlaybackControlLayoutSetting.NOW_PLAYING_SIZE,
                    PlaybackControlLayoutSetting.LYRICS_SIZE -> {
                        PlaybackControlSize.values().forEach { size ->
                            val selected = when (setting) {
                                PlaybackControlLayoutSetting.NOW_PLAYING_SIZE ->
                                    size == preferences.nowPlayingSize
                                PlaybackControlLayoutSetting.LYRICS_SIZE ->
                                    size == preferences.lyricsSize
                                PlaybackControlLayoutSetting.NOW_PLAYING_PLACEMENT -> false
                            }
                            MiuixSettingsChoiceRow(
                                title = playbackControlSizeLabel(size),
                                selected = selected,
                                onClick = {
                                    onPreferencesChange(
                                        when (setting) {
                                            PlaybackControlLayoutSetting.NOW_PLAYING_SIZE ->
                                                preferences.copy(nowPlayingSize = size)
                                            PlaybackControlLayoutSetting.LYRICS_SIZE ->
                                                preferences.copy(lyricsSize = size)
                                            PlaybackControlLayoutSetting.NOW_PLAYING_PLACEMENT ->
                                                preferences
                                        }
                                    )
                                    selectedSetting = null
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = { selectedSetting = null },
                text = { Text(stringResource(R.string.action_close)) }
            )
        }
    )
}

@Composable
private fun PlaybackControlLayoutListItem(
    targetId: String,
    icon: ImageVector,
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    ListItem(
        modifier = Modifier
            .settingsHighlightTarget(
                targetId = targetId,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            .settingsItemClickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun nowPlayingControlPlacementLabel(
    placement: NowPlayingControlPlacement
): String = when (placement) {
    NowPlayingControlPlacement.LOWER ->
        stringResource(R.string.settings_nowplaying_control_placement_lower)
    NowPlayingControlPlacement.BOTTOM ->
        stringResource(R.string.settings_nowplaying_control_placement_bottom)
    NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS ->
        stringResource(R.string.settings_nowplaying_control_placement_bottom_with_progress)
}

@Composable
private fun playbackControlSizeLabel(size: PlaybackControlSize): String = when (size) {
    PlaybackControlSize.SMALL -> stringResource(R.string.settings_playback_control_size_small)
    PlaybackControlSize.MEDIUM -> stringResource(R.string.settings_playback_control_size_medium)
    PlaybackControlSize.LARGE -> stringResource(R.string.settings_playback_control_size_large)
}

@Composable
private fun PersonalizationSwitchItem(
    setting: AutoSettingInfo,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    AutoSettingsListItem(
        setting = setting,
        enabled = enabled,
        supportingContent = supportingContent,
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = if (enabled) {
            { onCheckedChange(!checked) }
        } else {
            null
        }
    )
}

@Composable
private fun LyricFontScaleSettingsItem(
    setting: AutoSettingInfo,
    currentScale: Float,
    onScaleCommit: (Float) -> Unit,
    sampleText: String,
    sampleBaseSizeSp: Float,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    var pendingScale by remember(setting.keyName) {
        mutableFloatStateOf(normalizeLyricFontScale(currentScale))
    }

    LaunchedEffect(currentScale) {
        val normalizedScale = normalizeLyricFontScale(currentScale)
        if ((pendingScale - normalizedScale).absoluteValue > 0.001f) {
            pendingScale = normalizedScale
        }
    }

    AutoSettingsListItem(
        setting = setting,
        showDefaultIcon = true,
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.settings_lyrics_font_scale_value,
                        (pendingScale * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MiuixSettingsSlider(
                    value = pendingScale,
                    onValueChange = { pendingScale = it },
                    onValueChangeFinished = {
                        onScaleCommit(normalizeLyricFontScale(pendingScale))
                    },
                    valueRange = MIN_LYRIC_FONT_SCALE..MAX_LYRIC_FONT_SCALE,
                    steps = 10
                )
                Text(
                    text = sampleText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                    fontSize = scaledLyricFontSize(sampleBaseSizeSp, pendingScale).sp
                )
            }
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
private fun SettingsHomeCardSwitch(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    targetId: String,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    ListItem(
        modifier = Modifier
            .settingsHighlightTarget(
                targetId = targetId,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            .then(
                if (enabled) {
                    Modifier.settingsItemClickable {
                        onCheckedChange(!checked)
                    }
                } else {
                    Modifier.alpha(0.5f)
                }
            ),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = description?.let { text ->
            {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun ListenTogetherSettingsSection(
    modifier: Modifier = Modifier,
    isUsingDefaultServer: Boolean,
    isInRoom: Boolean,
    nickname: String,
    onOpenServerDialog: () -> Unit,
    onResetIdentity: () -> Unit,
    onOpenNicknameDialog: () -> Unit
) {
    val identityItemModifier = if (isInRoom) {
        Modifier.alpha(0.5f)
    } else {
        Modifier.settingsItemClickable(onClick = onResetIdentity)
    }
    val nicknameItemModifier = if (isInRoom) {
        Modifier.alpha(0.5f)
    } else {
        Modifier.settingsItemClickable(onClick = onOpenNicknameDialog)
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ListItem(
            modifier = Modifier.settingsItemClickable(onClick = onOpenServerDialog),
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = stringResource(R.string.settings_listen_together_server_title),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_listen_together_server_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isUsingDefaultServer) {
                            stringResource(R.string.settings_listen_together_server_default_desc)
                        } else {
                            stringResource(R.string.settings_listen_together_server_custom_desc)
                        }
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            modifier = nicknameItemModifier,
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.FormatSize,
                    contentDescription = stringResource(
                        R.string.settings_listen_together_default_nickname_title
                    ),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = {
                Text(stringResource(R.string.settings_listen_together_default_nickname_title))
            },
            supportingContent = {
                Text(
                    if (isInRoom) {
                        stringResource(R.string.settings_listen_together_default_nickname_disabled)
                    } else nickname.ifBlank {
                        stringResource(R.string.settings_listen_together_default_nickname_unset)
                    }
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            modifier = identityItemModifier,
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = stringResource(R.string.listen_together_reset_uuid),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.listen_together_reset_uuid)) },
            supportingContent = {
                Text(
                    if (isInRoom) {
                        stringResource(R.string.listen_together_reset_uuid_disabled)
                    } else {
                        stringResource(R.string.settings_listen_together_reset_identity_desc)
                    }
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun SettingsLoginExpandedContent(
    biliVm: BiliAuthViewModel,
    youtubeVm: YouTubeAuthViewModel,
    neteaseVm: NeteaseAuthViewModel,
    onOpenBiliSheet: (Int) -> Unit,
    onOpenBiliSavedCookieDialog: () -> Unit,
    onOpenYouTubeSavedCookieDialog: () -> Unit,
    onOpenNeteaseSavedCookieDialog: () -> Unit,
    onOpenYouTubeSheet: () -> Unit,
    onOpenNeteaseSheet: () -> Unit,
) {
    val biliAuthUiState by biliVm.uiState.collectAsStateWithLifecycleCompat()
    val youtubeAuthUiState by youtubeVm.uiState.collectAsStateWithLifecycleCompat()
    val neteaseAuthUiState by neteaseVm.uiState.collectAsStateWithLifecycleCompat()

    LaunchedEffect(biliVm, youtubeVm, neteaseVm) {
        biliVm.refreshAuthHealth()
        neteaseVm.refreshAuthHealth()
        youtubeVm.refreshAuthHealth()
    }

    val biliStatusText = when (biliAuthUiState.health.state) {
        SavedCookieAuthState.Valid -> {
            val relativeTime = biliAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { formatSyncTime(it) }
                ?: stringResource(R.string.time_just_now)
            stringResource(R.string.settings_bili_status_valid, relativeTime)
        }
        SavedCookieAuthState.Checking -> {
            if (biliAuthUiState.hasSavedCookies) {
                stringResource(R.string.settings_bili_status_saved_invalid)
            } else {
                stringResource(R.string.settings_bili_status_missing)
            }
        }
        SavedCookieAuthState.Missing -> {
            if (biliAuthUiState.hasSavedCookies) {
                stringResource(R.string.settings_bili_status_saved_invalid)
            } else {
                stringResource(R.string.settings_bili_status_missing)
            }
        }
    }
    val neteaseStatusText = when (neteaseAuthUiState.health.state) {
        SavedCookieAuthState.Valid -> {
            val relativeTime = neteaseAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { formatSyncTime(it) }
                ?: stringResource(R.string.time_just_now)
            stringResource(R.string.settings_netease_status_valid, relativeTime)
        }
        SavedCookieAuthState.Checking -> {
            if (neteaseAuthUiState.hasSavedCookies) {
                stringResource(R.string.settings_netease_status_saved_invalid)
            } else {
                stringResource(R.string.settings_netease_status_missing)
            }
        }
        SavedCookieAuthState.Missing -> {
            if (neteaseAuthUiState.hasSavedCookies) {
                stringResource(R.string.settings_netease_status_saved_invalid)
            } else {
                stringResource(R.string.settings_netease_status_missing)
            }
        }
    }
    val youtubeStatusText = when (youtubeAuthUiState.health.state) {
        YouTubeAuthState.Valid -> {
            val relativeTime = youtubeAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { formatSyncTime(it) }
                ?: stringResource(R.string.time_just_now)
            stringResource(R.string.settings_youtube_status_valid, relativeTime)
        }
        YouTubeAuthState.Missing -> {
            if (youtubeAuthUiState.hasSavedAuth) {
                stringResource(R.string.settings_youtube_status_saved_invalid)
            } else {
                stringResource(R.string.settings_youtube_status_missing)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_bilibili),
                    contentDescription = stringResource(R.string.settings_bilibili),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.platform_bilibili)) },
            supportingContent = { Text(biliStatusText) },
            modifier = Modifier.settingsItemClickable(
                onClick = {
                    if (biliAuthUiState.hasSavedCookies) {
                        onOpenBiliSavedCookieDialog()
                    } else {
                        onOpenBiliSheet(0)
                    }
                }
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            leadingContent = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_youtube),
                    contentDescription = stringResource(R.string.common_youtube),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.common_youtube)) },
            supportingContent = { Text(youtubeStatusText) },
            modifier = Modifier.settingsItemClickable(
                onClick = {
                    if (youtubeAuthUiState.hasSavedAuth) {
                        onOpenYouTubeSavedCookieDialog()
                    } else {
                        onOpenYouTubeSheet()
                    }
                }
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            leadingContent = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_netease_cloud_music),
                    contentDescription = stringResource(R.string.settings_netease),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.platform_netease)) },
            supportingContent = { Text(neteaseStatusText) },
            modifier = Modifier.settingsItemClickable(
                onClick = {
                    if (neteaseAuthUiState.hasSavedCookies) {
                        onOpenNeteaseSavedCookieDialog()
                    } else {
                        onOpenNeteaseSheet()
                    }
                }
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            leadingContent = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_qq_music),
                    contentDescription = stringResource(R.string.settings_qq_music),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_qq_music)) },
            supportingContent = { Text(stringResource(R.string.common_coming_soon)) },
            modifier = Modifier.settingsItemClickable { },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
