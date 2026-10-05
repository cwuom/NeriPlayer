package moe.ouom.neriplayer.ui.settings.route

import androidx.compose.ui.geometry.Offset
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.model.settings.appearance.ThemeMode
import moe.ouom.neriplayer.data.ltw.ListenTogetherSessionManager
import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.ui.settings.owner.AppUsbExclusiveSettingsActions
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.navigation.Destinations

internal fun isAppSettingsVisible(selectedMainTabRoute: String, playbackOpen: Boolean): Boolean =
    selectedMainTabRoute == Destinations.Settings.route && !playbackOpen

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
    val coherentFeedbackEnabled: Boolean,
    val settingsVisible: Boolean = true
)

internal class AppSettingsHostBindings(
    val repository: SettingsRepository,
    val listenTogether: AppSettingsListenTogetherDependencies,
    val state: AppSettingsRouteState,
    val appearanceActions: AppAppearanceSettingsActions,
    val lyricsActions: AppLyricSettingsActions,
    val qualityActions: AppAudioQualitySettingsActions,
    val playbackActions: AppPlaybackSettingsActions,
    val usbActions: AppUsbExclusiveSettingsActions,
    val homeActions: AppHomeSettingsActions,
    val storageActions: AppStorageSettingsActions,
    val environment: AppSettingsHostEnvironment
)

internal class AppSettingsListenTogetherDependencies(
    val preferences: ListenTogetherPreferences,
    val api: ListenTogetherApi,
    val sessionManager: ListenTogetherSessionManager
)
