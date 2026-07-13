package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES
import moe.ouom.neriplayer.data.settings.PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTE_OPTIONS
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun PlaybackServiceIdleShutdownSetting(
    repository: AutoSettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val selectedMinutes by repository.playbackServiceIdleShutdownMinutesFlow.collectAsState(
        initial = DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES
    )
    var showDialog by remember { mutableStateOf(false) }

    AutoSettingSpecListItem(
        setting = AutoSettingsSchema.general.playbackServiceIdleShutdownMinutes,
        trailingContent = { Text(playbackServiceIdleShutdownLabel(selectedMinutes)) },
        onClick = { showDialog = true }
    )

    if (!showDialog) return

    MiuixSettingsDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(stringResource(R.string.settings_playback_idle_shutdown_dialog_title)) },
        text = {
            Column {
                PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTE_OPTIONS.forEach { minutes ->
                    MiuixSettingsChoiceRow(
                        title = playbackServiceIdleShutdownLabel(minutes),
                        selected = minutes == selectedMinutes,
                        onClick = {
                            scope.launch {
                                repository.setPlaybackServiceIdleShutdownMinutes(minutes)
                            }
                            showDialog = false
                        }
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = { showDialog = false },
                text = { Text(stringResource(R.string.action_close)) }
            )
        }
    )
}

@Composable
private fun playbackServiceIdleShutdownLabel(minutes: Int): String {
    return if (minutes == 0) {
        stringResource(R.string.settings_playback_idle_shutdown_off)
    } else {
        stringResource(R.string.settings_playback_idle_shutdown_minutes, minutes)
    }
}
