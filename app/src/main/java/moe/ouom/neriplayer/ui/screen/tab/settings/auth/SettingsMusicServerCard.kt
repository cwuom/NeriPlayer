package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton

// 与第三方账号共用外壳，避免入口和管理页的卡片间距分叉
@Composable
internal fun SettingsMusicServerCard(onOpenMusicServers: () -> Unit) {
    SettingsAccountCardSurface(
        modifier = Modifier.testTag("settingsMusicServerCard"),
        onClick = onOpenMusicServers
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("settingsMusicServerContent")) {
            val inlineActions = maxWidth >= (560f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
            if (inlineActions) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MusicServerIdentity(Modifier.weight(1f))
                    MiuixSettingsButton(onClick = onOpenMusicServers) {
                        Text(stringResource(CoreCommonR.string.server_manage_accounts))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MusicServerIdentity()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        MiuixSettingsButton(onClick = onOpenMusicServers) {
                            Text(stringResource(CoreCommonR.string.server_manage_accounts))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicServerIdentity(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(25.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(CoreCommonR.string.server_manage),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(CoreCommonR.string.server_settings_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
