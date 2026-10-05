package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountAuthorizationSnapshot
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountCardUiState
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountCardsContent
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountPlatform
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountProfileRequest
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountProfileState
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsYouTubeAccountAuthorizationSnapshot
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.hasValidSavedCookieHealth
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.hasValidYouTubeAuthHealth
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.loadBiliAccountProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.loadNeteaseAccountProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.loadYouTubeAccountProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.rememberSettingsAccountProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.component.InlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthUiState
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthUiState
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthUiState

@Composable
internal fun StartupPlatformAccounts(
    biliState: BiliAuthUiState,
    neteaseState: NeteaseAuthUiState,
    youTubeState: YouTubeAuthUiState,
    isActive: Boolean,
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit,
    onLogin: (SettingsAccountPlatform) -> Unit,
    onManageSaved: (SettingsAccountPlatform) -> Unit
) {
    // 加密授权仓库首次访问留在 IO，避免平台步骤首次显示时阻塞绘制
    val biliAuthorizationFlow = remember {
        flow { emitAll(AppContainer.biliCookieRepo.cookieFlow) }
            .map { SettingsAccountAuthorizationSnapshot(it) }
            .distinctUntilChanged().flowOn(Dispatchers.IO)
    }
    val neteaseAuthorizationFlow = remember {
        flow { emitAll(AppContainer.neteaseCookieRepo.cookieFlow) }
            .map { SettingsAccountAuthorizationSnapshot(it) }
            .distinctUntilChanged().flowOn(Dispatchers.IO)
    }
    val youtubeAuthorizationFlow = remember {
        flow { emitAll(AppContainer.youtubeAuthRepo.authFlow) }
            .map { SettingsYouTubeAccountAuthorizationSnapshot(it) }
            .distinctUntilChanged().flowOn(Dispatchers.IO)
    }
    val emptyAuthorization = remember { SettingsAccountAuthorizationSnapshot(emptyMap()) }
    val emptyYouTubeAuthorization = remember { SettingsYouTubeAccountAuthorizationSnapshot(YouTubeAuthBundle()) }
    val biliAuthorization by biliAuthorizationFlow.collectAsStateWithLifecycle(initialValue = emptyAuthorization)
    val neteaseAuthorization by neteaseAuthorizationFlow.collectAsStateWithLifecycle(initialValue = emptyAuthorization)
    val youtubeAuthorization by youtubeAuthorizationFlow.collectAsStateWithLifecycle(initialValue = emptyYouTubeAuthorization)
    val youtubeEnabled by AppContainer.settingsRepo.youtubeEnabledFlow.collectAsStateWithLifecycle(
        initialValue = YouTubeFeatureGate.isEnabled()
    )
    val profiles = rememberStartupAccountProfiles(
        neteaseRequest = SettingsAccountProfileRequest(
            neteaseState.hasSavedCookies, neteaseState.health.savedAt, neteaseAuthorization.identity
        ),
        biliRequest = SettingsAccountProfileRequest(
            biliState.hasSavedCookies, biliState.health.savedAt, biliAuthorization.identity
        ),
        youtubeRequest = SettingsAccountProfileRequest(
            youTubeState.hasSavedAuth, youtubeAuthorization.savedAt, youtubeAuthorization.identity
        ),
        isActive = isActive,
        youtubeEnabled = youtubeEnabled
    ) { platform ->
        when (platform) {
            SettingsAccountPlatform.Netease -> loadNeteaseAccountProfile(neteaseAuthorization)
            SettingsAccountPlatform.Bilibili -> loadBiliAccountProfile(biliAuthorization)
            SettingsAccountPlatform.YouTube -> loadYouTubeAccountProfile(youtubeAuthorization)
            SettingsAccountPlatform.QqMusic -> null
        }
    }
    StartupPlatformAccountsContent(
        accounts = listOf(
            SettingsAccountCardUiState(
                platform = SettingsAccountPlatform.Netease,
                hasSavedAuthorization = neteaseState.hasSavedCookies,
                authorizationComplete = hasValidSavedCookieHealth(neteaseState.health.state),
                savedAtLabel = startupAccountSavedAtText(neteaseState.health.savedAt),
                profile = profiles.netease.profile,
                profileLoading = profiles.netease.loading
            ),
            SettingsAccountCardUiState(
                platform = SettingsAccountPlatform.Bilibili,
                hasSavedAuthorization = biliState.hasSavedCookies,
                authorizationComplete = hasValidSavedCookieHealth(biliState.health.state),
                savedAtLabel = startupAccountSavedAtText(biliState.health.savedAt),
                profile = profiles.bili.profile,
                profileLoading = profiles.bili.loading
            ),
            SettingsAccountCardUiState(
                platform = SettingsAccountPlatform.YouTube,
                hasSavedAuthorization = youTubeState.hasSavedAuth,
                authorizationComplete = hasValidYouTubeAuthHealth(youTubeState.health.state),
                savedAtLabel = startupAccountSavedAtText(youTubeState.health.savedAt),
                profile = profiles.youtube.profile,
                profileLoading = profiles.youtube.loading
            ),
            SettingsAccountCardUiState(platform = SettingsAccountPlatform.QqMusic)
        ),
        inlineMessage = inlineMessage,
        onInlineMessageChange = onInlineMessageChange,
        onLogin = onLogin,
        onManageSaved = onManageSaved
    )
}

internal data class StartupAccountProfiles(
    val netease: SettingsAccountProfileState,
    val bili: SettingsAccountProfileState,
    val youtube: SettingsAccountProfileState
)

@Composable
internal fun rememberStartupAccountProfiles(
    neteaseRequest: SettingsAccountProfileRequest,
    biliRequest: SettingsAccountProfileRequest,
    youtubeRequest: SettingsAccountProfileRequest,
    isActive: Boolean,
    youtubeEnabled: Boolean,
    loadProfile: suspend (SettingsAccountPlatform) -> SettingsAccountProfile?
): StartupAccountProfiles {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = isActive && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    return StartupAccountProfiles(
        netease = rememberSettingsAccountProfile(neteaseRequest, active) {
            loadProfile(SettingsAccountPlatform.Netease)
        },
        bili = rememberSettingsAccountProfile(biliRequest, active) {
            loadProfile(SettingsAccountPlatform.Bilibili)
        },
        youtube = rememberSettingsAccountProfile(youtubeRequest, active && youtubeEnabled) {
            loadProfile(SettingsAccountPlatform.YouTube)
        }
    )
}

@Composable
internal fun StartupPlatformAccountsContent(
    accounts: List<SettingsAccountCardUiState>,
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit,
    onLogin: (SettingsAccountPlatform) -> Unit,
    onManageSaved: (SettingsAccountPlatform) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().testTag("startupPlatformAccounts")) {
        StepHeader(
            icon = Icons.Outlined.Tune,
            title = stringResource(CoreCommonR.string.onboarding_platforms_title),
            description = stringResource(CoreCommonR.string.onboarding_platforms_desc)
        )
        Spacer(Modifier.height(18.dp))
        inlineMessage?.let {
            InlineMessage(text = it, onClose = { onInlineMessageChange(null) })
            Spacer(Modifier.height(14.dp))
        }
        SettingsAccountCardsContent(
            accounts = accounts,
            onLogin = onLogin,
            onManageSaved = onManageSaved,
            suppressInactiveNavigationSurface = true,
            fallbackColor = MaterialTheme.colorScheme.surfaceContainer
        )
        Spacer(Modifier.height(18.dp))
        HintCard(body = stringResource(CoreCommonR.string.onboarding_platforms_hint))
    }
}

@Composable
private fun startupAccountSavedAtText(savedAt: Long): String =
    if (savedAt > 0L) formatSyncTime(savedAt) else stringResource(CoreCommonR.string.time_just_now)
