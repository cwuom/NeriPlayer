package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.ksp.annotations.AutoSettingSpec
import moe.ouom.neriplayer.ui.screen.tab.settings.component.AutoSettingSpecSwitchItem

/**
 * 播放记录同步开关跟账号放在一起，未登录的平台只展示不可操作的开关，
 * 避免用户以为已经开始同步。
 */
@Composable
internal fun SettingsPlayHistorySyncCard(
    neteaseSignedIn: Boolean,
    biliSignedIn: Boolean,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    SettingsAccountCardSurface(modifier = Modifier.testTag("settingsPlayHistorySyncCard")) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(CoreCommonR.string.settings_play_history_sync_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(CoreCommonR.string.settings_play_history_sync_desc),
                modifier = Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PlayHistorySyncSwitch(
                setting = AutoSettingsSchema.playback.neteasePlayHistorySyncEnabled,
                signedIn = neteaseSignedIn,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            PlayHistorySyncSwitch(
                setting = AutoSettingsSchema.playback.biliPlayHistorySyncEnabled,
                signedIn = biliSignedIn,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
        }
    }
}

@Composable
private fun PlayHistorySyncSwitch(
    setting: AutoSettingSpec<Boolean>,
    signedIn: Boolean,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    val description = if (signedIn) setting.descriptionRes else CoreCommonR.string.settings_play_history_sync_sign_in_required
    AutoSettingSpecSwitchItem(
        setting = setting,
        modifier = Modifier.testTag("settingsPlayHistorySync:${setting.key}"),
        enabled = signedIn,
        supportingContent = { Text(stringResource(description)) },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}
