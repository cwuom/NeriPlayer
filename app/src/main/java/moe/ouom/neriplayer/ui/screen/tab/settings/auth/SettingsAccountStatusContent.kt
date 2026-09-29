package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthState
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthUiState
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthUiState
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthUiState

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
    if (savedAt > 0L) formatSyncTime(savedAt) else stringResource(R.string.time_just_now)

private data class AccountStatusCopy(
    val bili: String,
    val youtube: String,
    val netease: String
)

internal fun hasValidSavedCookieHealth(state: SavedCookieAuthState): Boolean =
    state == SavedCookieAuthState.Valid

internal fun hasValidYouTubeAuthHealth(state: YouTubeAuthState): Boolean =
    state == YouTubeAuthState.Valid

private fun accountStatusCopy(
    resources: Resources,
    bili: BiliAuthUiState,
    youtube: YouTubeAuthUiState,
    netease: NeteaseAuthUiState,
    biliRelativeTime: String,
    youtubeRelativeTime: String,
    neteaseRelativeTime: String
): AccountStatusCopy = AccountStatusCopy(
    bili = accountStatusText(
        resources,
        hasValidSavedCookieHealth(bili.health.state),
        bili.hasSavedCookies,
        biliRelativeTime,
        R.string.settings_bili_status_valid,
        R.string.settings_bili_status_saved_invalid,
        R.string.settings_bili_status_missing
    ),
    youtube = accountStatusText(
        resources,
        hasValidYouTubeAuthHealth(youtube.health.state),
        youtube.hasSavedAuth,
        youtubeRelativeTime,
        R.string.settings_youtube_status_valid,
        R.string.settings_youtube_status_saved_invalid,
        R.string.settings_youtube_status_missing
    ),
    netease = accountStatusText(
        resources,
        hasValidSavedCookieHealth(netease.health.state),
        netease.hasSavedCookies,
        neteaseRelativeTime,
        R.string.settings_netease_status_valid,
        R.string.settings_netease_status_saved_invalid,
        R.string.settings_netease_status_missing
    )
)

@Composable
internal fun SettingsLoginExpandedContent(controller: SettingsAccountAuthController) {
    val biliVm = controller.biliVm
    val youtubeVm = controller.youtubeVm
    val neteaseVm = controller.neteaseVm
    val bili by biliVm.uiState.collectAsStateWithLifecycleCompat()
    val youtube by youtubeVm.uiState.collectAsStateWithLifecycleCompat()
    val netease by neteaseVm.uiState.collectAsStateWithLifecycleCompat()
    val copy = accountStatusCopy(
        LocalResources.current, bili, youtube, netease,
        accountSavedAtText(bili.health.savedAt),
        accountSavedAtText(youtube.health.savedAt),
        accountSavedAtText(netease.health.savedAt)
    )

    LaunchedEffect(biliVm, youtubeVm, neteaseVm) {
        biliVm.refreshAuthHealth()
        neteaseVm.refreshAuthHealth()
        youtubeVm.refreshAuthHealth()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
    ) {
        SettingsAccountPlatformRow(
            iconRes = R.drawable.ic_bilibili,
            contentDescriptionRes = R.string.settings_bilibili,
            titleRes = R.string.platform_bilibili,
            status = copy.bili,
            hasSaved = bili.hasSavedCookies,
            onOpenSaved = controller.actions.openBiliSavedCookieDialog,
            onOpenSheet = controller.actions.openBiliSheet
        )
        SettingsAccountPlatformRow(
            iconRes = R.drawable.ic_youtube,
            contentDescriptionRes = R.string.common_youtube,
            titleRes = R.string.common_youtube,
            status = copy.youtube,
            hasSaved = youtube.hasSavedAuth,
            onOpenSaved = controller.actions.openYouTubeSavedCookieDialog,
            onOpenSheet = controller.actions.openYouTubeSheet
        )
        SettingsAccountPlatformRow(
            iconRes = R.drawable.ic_netease_cloud_music,
            contentDescriptionRes = R.string.settings_netease,
            titleRes = R.string.platform_netease,
            status = copy.netease,
            hasSaved = netease.hasSavedCookies,
            onOpenSaved = controller.actions.openNeteaseSavedCookieDialog,
            onOpenSheet = controller.actions.openNeteaseSheet
        )
        SettingsQqAccountRow()
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

private fun Modifier.settingsAccountRowModifier(
    hasSaved: Boolean,
    onOpenSaved: () -> Unit,
    onOpenSheet: () -> Unit
): Modifier = settingsItemClickable(
    onClick = SettingsAccountEntryAction(hasSaved, onOpenSaved, onOpenSheet).onClick
)

@Composable
private fun SettingsAccountPlatformRow(
    iconRes: Int,
    contentDescriptionRes: Int,
    titleRes: Int,
    status: String,
    hasSaved: Boolean,
    onOpenSaved: () -> Unit,
    onOpenSheet: () -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = stringResource(contentDescriptionRes),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(titleRes)) },
        supportingContent = { Text(status) },
        modifier = Modifier.settingsAccountRowModifier(hasSaved, onOpenSaved, onOpenSheet),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsQqAccountRow() {
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
