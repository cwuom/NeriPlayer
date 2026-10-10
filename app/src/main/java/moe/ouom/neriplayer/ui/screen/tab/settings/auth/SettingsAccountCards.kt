package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.common.R as CoreCommonR

internal enum class SettingsAccountPlatform(val key: String, val iconRes: Int, val titleRes: Int) {
    Netease("netease", CoreCommonR.drawable.ic_netease_cloud_music, CoreCommonR.string.platform_netease),
    Bilibili("bilibili", CoreCommonR.drawable.ic_bilibili, CoreCommonR.string.platform_bilibili),
    YouTube("youtube", CoreCommonR.drawable.ic_youtube, CoreCommonR.string.common_youtube),
    QqMusic("qq", R.drawable.ic_qq_music, CoreCommonR.string.settings_qq_music)
}

internal data class SettingsAccountCardUiState(
    val platform: SettingsAccountPlatform,
    val hasSavedAuthorization: Boolean = false,
    val authorizationComplete: Boolean = false,
    val savedAtLabel: String? = null,
    val profile: SettingsAccountProfile? = null,
    val profileLoading: Boolean = false
)

@Composable
internal fun SettingsAccountCardsContent(
    accounts: List<SettingsAccountCardUiState>,
    onLogin: (SettingsAccountPlatform) -> Unit,
    onManageSaved: (SettingsAccountPlatform) -> Unit,
    modifier: Modifier = Modifier,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null,
    suppressInactiveNavigationSurface: Boolean = false,
    fallbackColor: Color? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("settingsAccountCards"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        accounts.forEachIndexed { index, account ->
            SettingsAccountCard(
                account = account,
                onLogin = { onLogin(account.platform) },
                onManageSaved = { onManageSaved(account.platform) },
                targetId = if (index == 0 && highlightTargetId == "page:Accounts") {
                    "page:Accounts"
                } else when (account.platform) {
                    SettingsAccountPlatform.Netease -> "manual:netease_login"
                    SettingsAccountPlatform.Bilibili -> "manual:bili_login"
                    SettingsAccountPlatform.YouTube -> "manual:youtube_login"
                    SettingsAccountPlatform.QqMusic -> null
                },
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished,
                suppressInactiveNavigationSurface = suppressInactiveNavigationSurface,
                fallbackColor = fallbackColor
            )
        }
    }
}

@Composable
private fun SettingsAccountCard(
    account: SettingsAccountCardUiState,
    onLogin: () -> Unit,
    onManageSaved: () -> Unit,
    targetId: String?,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?,
    suppressInactiveNavigationSurface: Boolean,
    fallbackColor: Color?
) {
    val supported = account.platform != SettingsAccountPlatform.QqMusic
    val key = account.platform.key
    val cardShape = RoundedCornerShape(24.dp)
    val interactionModifier = if (supported) {
        Modifier.clip(cardShape).clickable(
            onClick = SettingsAccountEntryAction(account.hasSavedAuthorization, onManageSaved, onLogin).onClick
        )
    } else Modifier
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("settingsAccountCard:$key").then(interactionModifier),
        shape = cardShape,
        color = Color.Transparent
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.SettingsSection,
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            fallbackColor = fallbackColor ?: MaterialTheme.colorScheme.surfaceContainerLow,
            tintColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            suppressInactiveNavigationSurface = suppressInactiveNavigationSurface
        ) {
            val highlightModifier = if (targetId != null) {
                Modifier.settingsHighlightTarget(targetId, highlightTargetId, highlightPulse, onHighlightFinished)
            } else Modifier
            BoxWithConstraints(Modifier.fillMaxWidth().then(highlightModifier).padding(20.dp)) {
                val inlineActions = maxWidth >= (560f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
                if (inlineActions && supported) {
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("settingsAccountInline:$key"),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsAccountIdentity(
                            account = account,
                            modifier = Modifier.weight(1f)
                        )
                        SettingsAccountActions(account, onLogin, onManageSaved)
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("settingsAccountStacked:$key"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsAccountIdentity(account = account)
                        if (supported) {
                            SettingsAccountActions(account, onLogin, onManageSaved, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsAccountIdentity(
    account: SettingsAccountCardUiState,
    modifier: Modifier = Modifier
) {
    val platformName = stringResource(account.platform.titleRes)
    val supported = account.platform != SettingsAccountPlatform.QqMusic
    Row(
        modifier = modifier.fillMaxWidth().testTag("settingsAccountIdentity:${account.platform.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier.size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(account.platform.iconRes),
                contentDescription = null,
                modifier = Modifier.size(25.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = platformName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (supported) {
                Text(
                    text = account.profile?.nickname ?: stringResource(
                        when {
                            account.profileLoading -> CoreCommonR.string.settings_account_profile_loading
                            account.hasSavedAuthorization -> CoreCommonR.string.settings_account_profile_unavailable
                            else -> CoreCommonR.string.settings_account_sign_in_hint
                        }
                    ),
                    modifier = Modifier.testTag("settingsAccountNickname:${account.platform.key}"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                SettingsAccountAuthorization(account)
            } else {
                Text(
                    text = stringResource(CoreCommonR.string.common_coming_soon),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (supported && account.hasSavedAuthorization) {
            SettingsAccountAvatar(account, platformName)
        }
    }
}

@Composable
private fun SettingsAccountAuthorization(account: SettingsAccountCardUiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape).background(
                if (account.hasSavedAuthorization) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Text(
            text = stringResource(
                if (account.hasSavedAuthorization) CoreCommonR.string.settings_account_authorization_saved
                else CoreCommonR.string.settings_account_authorization_missing
            ),
            modifier = Modifier.testTag("settingsAccountAuthorization:${account.platform.key}"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (account.hasSavedAuthorization) {
        val detail = if (!account.authorizationComplete) {
            stringResource(CoreCommonR.string.settings_account_authorization_incomplete)
        } else account.savedAtLabel?.let {
            stringResource(CoreCommonR.string.settings_account_last_updated, it)
        }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsAccountAvatar(account: SettingsAccountCardUiState, platformName: String) {
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .testTag("settingsAccountAvatar:${account.platform.key}"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp)
        )
        account.profile?.avatarUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = stringResource(CoreCommonR.string.settings_account_avatar, platformName),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsAccountActions(
    account: SettingsAccountCardUiState,
    onLogin: () -> Unit,
    onManageSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (account.hasSavedAuthorization) {
            TextButton(
                onClick = onManageSaved,
                modifier = Modifier.testTag("settingsAccountLogout:${account.platform.key}"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(CoreCommonR.string.settings_saved_cookie_logout),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            TextButton(
                onClick = onLogin,
                modifier = Modifier.testTag("settingsAccountLogin:${account.platform.key}")
            ) {
                Text(stringResource(CoreCommonR.string.settings_account_sign_in_again))
            }
        } else {
            Button(
                onClick = onLogin,
                modifier = Modifier.testTag("settingsAccountLogin:${account.platform.key}")
            ) {
                Text(stringResource(CoreCommonR.string.login_title))
            }
        }
    }
}
