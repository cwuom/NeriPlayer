package moe.ouom.neriplayer.ui

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import moe.ouom.neriplayer.ui.screen.host.SettingsHostScreen

@Composable
internal fun AppSettingsRoute(
    repo: SettingsRepository,
    application: Application,
    initialThemeSnapshot: ThemePreferenceSnapshot,
    startupPlaybackPreferences: PlaybackPreferenceSnapshot,
    environment: AppSettingsHostEnvironment,
    onBackgroundImageAlphaPreview: (Float) -> Unit,
    snackbarHostState: SnackbarHostState,
    renderScene: @Composable (Float, Float, Float, Int, @Composable () -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val settingsState = rememberAppSettingsRouteState(
        repo, initialThemeSnapshot, startupPlaybackPreferences
    )
    val lyricOffsetOwner = rememberAppLyricOffsetSettingsOwner(repo)
    val cacheClearOwner = rememberAppSettingsCacheClearOwner(application)
    val currentCloudOffset = rememberUpdatedState(
        settingsState.lyrics.lyricOffsets.cloudMusicLyricDefaultOffsetMs
    )
    val currentQqOffset = rememberUpdatedState(
        settingsState.lyrics.lyricOffsets.qqMusicLyricDefaultOffsetMs
    )
    SettingsHostScreen(
        bindings = AppSettingsHostBindings(
            repository = repo,
            listenTogether = AppSettingsListenTogetherDependencies(
                preferences = AppContainer.listenTogetherPreferences,
                api = AppContainer.listenTogetherApi,
                sessionManager = AppContainer.listenTogetherSessionManager
            ),
            state = settingsState,
            appearanceActions = rememberAppAppearanceSettingsActions(
                repo, scope, onBackgroundImageAlphaPreview
            ),
            lyricsActions = rememberAppLyricSettingsActions(
                repo, scope, lyricOffsetOwner, currentCloudOffset, currentQqOffset
            ),
            qualityActions = rememberAppAudioQualitySettingsActions(repo, scope),
            playbackActions = rememberAppPlaybackSettingsActions(repo, scope),
            usbActions = rememberAppUsbExclusiveSettingsActions(repo, scope),
            homeActions = rememberAppHomeSettingsActions(repo, scope),
            storageActions = rememberAppStorageSettingsActions(
                repo, scope, cacheClearOwner, snackbarHostState
            ),
            environment = environment
        ),
        renderScene = renderScene
    )
}
