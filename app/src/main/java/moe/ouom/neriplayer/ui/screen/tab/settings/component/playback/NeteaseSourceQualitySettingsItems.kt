package moe.ouom.neriplayer.ui.screen.tab.settings.component.playback

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.ui.settings.AutoSettingsListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.common.R as CoreCommonR

/**
 * 换源相关的两个补充开关：音质优先探测、专辑分 P 绑定优先播放。
 *
 * 与「网易云自动换源」放在同一段，方便一起开关。
 */
@Composable
fun NeteaseSourceQualitySettingsItems(
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val settingsRepo = AppContainer.settingsRepo
    val qualityProbeEnabled by settingsRepo.neteaseAutoSourceQualityProbeFlow
        .collectAsState(initial = true)
    val albumBindingPriorityEnabled by settingsRepo.biliAlbumBindingPriorityFlow
        .collectAsState(initial = true)

    AutoSettingsListItem(
        setting = AutoSettingsMetadata.requireSetting(
            AutoSettingsKeys.NETEASE_AUTO_SOURCE_QUALITY_PROBE
        ),
        leadingContent = {
            Icon(
                painter = painterResource(CoreCommonR.drawable.ic_bilibili),
                contentDescription = stringResource(
                    CoreCommonR.string.settings_netease_auto_source_quality_probe
                ),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = qualityProbeEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { settingsRepo.setNeteaseAutoSourceQualityProbe(enabled) }
                }
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = {
            scope.launch { settingsRepo.setNeteaseAutoSourceQualityProbe(!qualityProbeEnabled) }
        }
    )

    AutoSettingsListItem(
        setting = AutoSettingsMetadata.requireSetting(
            AutoSettingsKeys.BILI_COLLECTION_BINDING_PRIORITY
        ),
        leadingContent = {
            Icon(
                painter = painterResource(CoreCommonR.drawable.ic_bilibili),
                contentDescription = stringResource(
                    CoreCommonR.string.settings_bili_collection_binding_priority
                ),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = albumBindingPriorityEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { settingsRepo.setBiliCollectionBindingPriority(enabled) }
                }
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = {
            scope.launch { settingsRepo.setBiliCollectionBindingPriority(!albumBindingPriorityEnabled) }
        }
    )
}
