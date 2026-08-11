package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun DynamicIslandLyricsSetting(
    repository: SettingsRepository,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val setting = AutoSettingsSchema.lyrics.dynamicIslandLyricsEnabled
    val enabled by repository.dynamicIslandLyricsEnabledFlow.collectAsState(
        initial = setting.defaultValue
    )
    val bluetoothLyricsEnabled by repository.externalBluetoothLyricsEnabledFlow.collectAsState(
        initial = true
    )
    val showDependencyDialog = remember { mutableStateOf(false) }

    fun requestEnabledState(nextEnabled: Boolean) {
        if (!nextEnabled) {
            scope.launch { repository.setDynamicIslandLyricsEnabled(false) }
            return
        }
        if (bluetoothLyricsEnabled) {
            scope.launch { repository.setDynamicIslandLyricsEnabled(true) }
            return
        }
        showDependencyDialog.value = true
    }

    AutoSettingSpecListItem(
        setting = setting,
        trailingContent = {
            MiuixSettingsSwitch(
                checked = enabled,
                onCheckedChange = { requestEnabledState(it) }
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = { requestEnabledState(!enabled) }
    )

    if (!showDependencyDialog.value) {
        return
    }

    MiuixSettingsDialog(
        onDismissRequest = { showDependencyDialog.value = false },
        title = { Text(stringResource(R.string.settings_dynamic_island_lyrics_dependency_title)) },
        text = {
            Text(stringResource(R.string.settings_dynamic_island_lyrics_dependency_message))
        },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = {
                    scope.launch {
                        repository.setDynamicIslandLyricsEnabled(true)
                    }
                    showDependencyDialog.value = false
                }
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(
                onClick = { showDependencyDialog.value = false }
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
