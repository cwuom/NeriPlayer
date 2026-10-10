package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthState
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime

internal fun accountStatusText(
    resources: Resources,
    valid: Boolean,
    hasSaved: Boolean,
    relativeTime: String,
    validRes: Int,
    savedInvalidRes: Int,
    missingRes: Int
): String {
    if (valid) return resources.getString(validRes, relativeTime)
    val statusRes = if (hasSaved) savedInvalidRes else missingRes
    return resources.getString(statusRes)
}

@Composable
private fun accountSavedAtText(savedAt: Long): String =
    if (savedAt > 0L) formatSyncTime(savedAt) else stringResource(CoreCommonR.string.time_just_now)

internal fun hasValidSavedCookieHealth(state: SavedCookieAuthState): Boolean =
    state == SavedCookieAuthState.Valid

internal fun hasValidYouTubeAuthHealth(state: YouTubeAuthState): Boolean =
    state == YouTubeAuthState.Valid

internal fun shouldLoadSettingsAccountProfiles(
    screenActive: Boolean,
    settingsVisible: Boolean,
    activePage: SettingsPage?
): Boolean = screenActive && settingsVisible && activePage == SettingsPage.Accounts

@Composable
internal fun SettingsLoginExpandedContent(
    controller: SettingsAccountAuthController,
    onOpenMusicServers: () -> Unit,
    isActive: Boolean = true,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val biliVm = controller.biliVm
    val youtubeVm = controller.youtubeVm
    val neteaseVm = controller.neteaseVm
    val bili by biliVm.uiState.collectAsStateWithLifecycleCompat()
    val youtube by youtubeVm.uiState.collectAsStateWithLifecycleCompat()
    val netease by neteaseVm.uiState.collectAsStateWithLifecycleCompat()
    val biliAuthorizationFlow = remember {
        AppContainer.biliCookieRepo.cookieFlow.map { cookies ->
            SettingsAccountAuthorizationSnapshot(cookies)
        }.distinctUntilChanged()
    }
    val neteaseAuthorizationFlow = remember {
        AppContainer.neteaseCookieRepo.cookieFlow.map { cookies ->
            SettingsAccountAuthorizationSnapshot(cookies)
        }.distinctUntilChanged()
    }
    val youtubeAuthorizationFlow = remember {
        AppContainer.youtubeAuthRepo.authFlow.map { auth ->
            SettingsYouTubeAccountAuthorizationSnapshot(auth)
        }.distinctUntilChanged()
    }
    val emptyAuthorization = remember { SettingsAccountAuthorizationSnapshot(emptyMap()) }
    val emptyYouTubeAuthorization = remember { SettingsYouTubeAccountAuthorizationSnapshot(YouTubeAuthBundle()) }
    val biliAuthorization by biliAuthorizationFlow.collectAsStateWithLifecycle(initialValue = emptyAuthorization)
    val neteaseAuthorization by neteaseAuthorizationFlow.collectAsStateWithLifecycle(initialValue = emptyAuthorization)
    val youtubeAuthorization by youtubeAuthorizationFlow.collectAsStateWithLifecycle(initialValue = emptyYouTubeAuthorization)
    val youtubeEnabled by AppContainer.settingsRepo.youtubeEnabledFlow.collectAsStateWithLifecycle(
        initialValue = YouTubeFeatureGate.isEnabled()
    )
    val biliProfile = rememberSettingsAccountProfile(
        request = SettingsAccountProfileRequest(bili.hasSavedCookies, bili.health.savedAt, biliAuthorization.identity),
        isActive = isActive
    ) { loadBiliAccountProfile(biliAuthorization) }
    val neteaseProfile = rememberSettingsAccountProfile(
        request = SettingsAccountProfileRequest(netease.hasSavedCookies, netease.health.savedAt, neteaseAuthorization.identity),
        isActive = isActive
    ) { loadNeteaseAccountProfile(neteaseAuthorization) }
    val youtubeProfile = rememberSettingsAccountProfile(
        request = SettingsAccountProfileRequest(youtube.hasSavedAuth, youtubeAuthorization.savedAt, youtubeAuthorization.identity),
        isActive = isActive && youtubeEnabled
    ) { loadYouTubeAccountProfile(youtubeAuthorization) }

    LaunchedEffect(biliVm, youtubeVm, neteaseVm) {
        biliVm.refreshAuthHealth()
        neteaseVm.refreshAuthHealth()
        youtubeVm.refreshAuthHealth()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsAccountCardsContent(
            accounts = listOf(
                SettingsAccountCardUiState(
                    platform = SettingsAccountPlatform.Netease,
                    hasSavedAuthorization = netease.hasSavedCookies,
                    authorizationComplete = hasValidSavedCookieHealth(netease.health.state),
                    savedAtLabel = accountSavedAtText(netease.health.savedAt),
                    profile = neteaseProfile.profile,
                    profileLoading = neteaseProfile.loading
                ),
                SettingsAccountCardUiState(
                    platform = SettingsAccountPlatform.Bilibili,
                    hasSavedAuthorization = bili.hasSavedCookies,
                    authorizationComplete = hasValidSavedCookieHealth(bili.health.state),
                    savedAtLabel = accountSavedAtText(bili.health.savedAt),
                    profile = biliProfile.profile,
                    profileLoading = biliProfile.loading
                ),
                SettingsAccountCardUiState(
                    platform = SettingsAccountPlatform.YouTube,
                    hasSavedAuthorization = youtube.hasSavedAuth,
                    authorizationComplete = hasValidYouTubeAuthHealth(youtube.health.state),
                    savedAtLabel = accountSavedAtText(youtube.health.savedAt),
                    profile = youtubeProfile.profile,
                    profileLoading = youtubeProfile.loading
                ),
                SettingsAccountCardUiState(platform = SettingsAccountPlatform.QqMusic)
            ),
            onLogin = controller.actions::openPlatformLogin,
            onManageSaved = controller.actions::openPlatformSavedAuthorization,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished
        )
        SettingsMusicServerCard(onOpenMusicServers)
    }
}

internal class SettingsAccountEntryAction(
    private val hasSaved: Boolean,
    private val onOpenSaved: () -> Unit,
    private val onOpenSheet: () -> Unit
) {
    val onClick: () -> Unit = {
        if (hasSaved) onOpenSaved() else onOpenSheet()
    }
}
