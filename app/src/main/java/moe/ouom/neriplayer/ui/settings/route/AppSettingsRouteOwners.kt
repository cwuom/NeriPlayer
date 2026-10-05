package moe.ouom.neriplayer.ui.settings.route

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.settings.appearance.AdvancedBlurQualityPreference
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.appearance.isCurrentBuildDimensity
import moe.ouom.neriplayer.data.local.storage.clearExtraStorageCaches
import moe.ouom.neriplayer.ui.settings.owner.AppLyricOffsetSettingsOwner
import moe.ouom.neriplayer.ui.settings.owner.AppSettingsCacheClearOwner
import moe.ouom.neriplayer.ui.settings.owner.AppUsbExclusiveSettingsActions
import moe.ouom.neriplayer.ui.settings.owner.formatExtraCacheClearResult
import moe.ouom.neriplayer.common.format.formatFileSize

@Composable
private fun rememberInitialAdvancedBlurQuality() = remember {
    AdvancedBlurQualityPreference.defaultForDevice(isCurrentBuildDimensity())
}

@Composable
private fun rememberInitialSettingsRouteState(
    theme: ThemePreferenceSnapshot,
    playback: PlaybackPreferenceSnapshot,
    blurQuality: AdvancedBlurQuality,
    lyricFontScales: LyricFontScales
): AppSettingsRouteState = remember(listOf(theme, playback, blurQuality, lyricFontScales)) {
    initialAppSettingsRouteState(theme, playback, blurQuality, lyricFontScales)
}

@Composable
private fun rememberSettingsRouteFlow(repo: SettingsRepository) = key(repo) {
    remember { appSettingsRouteStateFlow(repo) }
}

@Composable
internal fun rememberAppSettingsRouteState(
    repo: SettingsRepository,
    theme: ThemePreferenceSnapshot,
    playback: PlaybackPreferenceSnapshot
): AppSettingsRouteState {
    val initialState = rememberInitialSettingsRouteState(
        theme, playback, rememberInitialAdvancedBlurQuality(), repo.defaultLyricFontScales
    )
    return rememberSettingsRouteFlow(repo).collectAsStateWithLifecycle(
        initialValue = initialState
    ).value
}

@Composable
internal fun rememberAppLyricOffsetSettingsOwner(repo: SettingsRepository) = key(repo) {
    remember {
        AppLyricOffsetSettingsOwner(
            rebase = { source, previous, next ->
                PlayerManager.rebaseUserLyricOffsetsForSource(source, previous, next)
            },
            saveCloudOffset = repo::setCloudMusicLyricDefaultOffsetMs,
            saveQqOffset = repo::setQqMusicLyricDefaultOffsetMs,
            resetOffsets = repo::resetLyricDefaultOffsets
        )
    }
}

@Composable
internal fun rememberAppSettingsCacheClearOwner(application: Application): AppSettingsCacheClearOwner {
    val context = LocalContext.current
    val resources = LocalResources.current
    return remember(Triple(context, application, resources)) {
        AppSettingsCacheClearOwner(
            clearPlayerCache = { options ->
                PlayerManager.clearCache(
                    clearAudio = options.audioCache,
                    clearImage = options.imageCache
                ).second
            },
            clearLyricsCache = {
                PlayerLyricsProvider.clearLyricsCaches(
                    neteaseLyricsCache = PlayerManager.neteaseLyricsCache,
                    ytMusicLyricsCache = PlayerManager.ytMusicLyricsCache
                )
                withContext(Dispatchers.IO) {
                    PlayerLyricsProvider.clearPersistentLyricCache(application)
                }
            },
            clearExtraCaches = { options -> clearExtraStorageCaches(context, options) },
            formatExtraResult = { result ->
                formatExtraCacheClearResult(
                    result = result,
                    partialMessage = {
                        resources.getString(CoreCommonR.string.storage_extra_cache_clear_partial)
                    },
                    roomCompleteMessage = { freed, reusable ->
                        resources.getString(
                            CoreCommonR.string.storage_extra_cache_clear_room_complete,
                            formatFileSize(freed),
                            formatFileSize(reusable)
                        )
                    },
                    completeMessage = { freed ->
                        resources.getString(
                            CoreCommonR.string.storage_extra_cache_clear_complete,
                            formatFileSize(freed)
                        )
                    }
                )
            }
        )
    }
}

@Composable
internal fun rememberAppAppearanceSettingsActions(
    repo: SettingsRepository,
    scope: CoroutineScope,
    onBackgroundImageAlphaPreview: (Float) -> Unit
): AppAppearanceSettingsActions = remember(
    Triple(repo, scope, onBackgroundImageAlphaPreview)
) {
    AppAppearanceSettingsActions(repo, scope, onBackgroundImageAlphaPreview)
}

@Composable
internal fun rememberAppLyricSettingsActions(
    repo: SettingsRepository,
    scope: CoroutineScope,
    lyricOffsetOwner: AppLyricOffsetSettingsOwner,
    currentCloudOffset: State<Long>,
    currentQqOffset: State<Long>
): AppLyricSettingsActions = remember(
    listOf(repo, scope, lyricOffsetOwner, currentCloudOffset, currentQqOffset)
) {
    AppLyricSettingsActions(repo, scope, lyricOffsetOwner, currentCloudOffset, currentQqOffset)
}

@Composable
internal fun rememberAppAudioQualitySettingsActions(
    repo: SettingsRepository,
    scope: CoroutineScope
): AppAudioQualitySettingsActions = remember(repo to scope) {
    AppAudioQualitySettingsActions(repo, scope)
}

@Composable
internal fun rememberAppPlaybackSettingsActions(
    repo: SettingsRepository,
    scope: CoroutineScope
): AppPlaybackSettingsActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    return remember(listOf(repo, scope, context, resources)) {
        AppPlaybackSettingsActions(repo, scope, context, resources)
    }
}

@Composable
internal fun rememberAppUsbExclusiveSettingsActions(
    repo: SettingsRepository,
    scope: CoroutineScope
): AppUsbExclusiveSettingsActions = remember(repo to scope) {
    AppUsbExclusiveSettingsActions(repo, scope)
}

@Composable
internal fun rememberAppHomeSettingsActions(
    repo: SettingsRepository,
    scope: CoroutineScope
): AppHomeSettingsActions = remember(repo to scope) {
    AppHomeSettingsActions(repo, scope)
}

@Composable
internal fun rememberAppStorageSettingsActions(
    repo: SettingsRepository,
    scope: CoroutineScope,
    cacheClearOwner: AppSettingsCacheClearOwner,
    snackbarHostState: SnackbarHostState
): AppStorageSettingsActions = remember(
    listOf(repo, scope, cacheClearOwner, snackbarHostState)
) {
    AppStorageSettingsActions(repo, scope, cacheClearOwner, snackbarHostState)
}
