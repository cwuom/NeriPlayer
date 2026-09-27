package moe.ouom.neriplayer.ui

import androidx.compose.ui.geometry.Offset
import moe.ouom.neriplayer.data.settings.ThemeMode
import moe.ouom.neriplayer.util.platform.LanguageManager

internal class AppSettingsHostEnvironment(
    val isDarkTheme: Boolean,
    val themeMode: ThemeMode,
    val onThemeToggleRequest: (Offset, Float) -> Unit,
    val onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit,
    val backgroundImageAlpha: Float,
    val defaultStartDestination: String,
    val homeHasRecentUsage: Boolean,
    val onBeforeLanguageRestart: () -> Unit,
    val onLanguageChanged: (LanguageManager.Language) -> Unit,
    val coherentFeedbackEnabled: Boolean
)

internal class AppSettingsHostBindings(
    val state: AppSettingsRouteState,
    val appearanceActions: AppAppearanceSettingsActions,
    val lyricsActions: AppLyricSettingsActions,
    val qualityActions: AppAudioQualitySettingsActions,
    val playbackActions: AppPlaybackSettingsActions,
    val homeActions: AppHomeSettingsActions,
    val storageActions: AppStorageSettingsActions,
    val environment: AppSettingsHostEnvironment
)
