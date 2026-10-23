package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics.coloros

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsAccessMode
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigResult
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigStatus
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

internal enum class ColorOsConfigurationAction { CHECK, APPLY, RESTORE }

@Composable
internal fun ColorOsConfigurationContent(
    mode: ColorOsAccessMode,
    busy: Boolean,
    result: ColorOsConfigResult?,
    restoreAvailable: Boolean,
    onModeChanged: (ColorOsAccessMode) -> Unit,
    onExecute: (ColorOsConfigurationAction) -> Unit,
) {
    Column(
        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(CoreCommonR.string.settings_coloros_configuration_help))
        Text(
            stringResource(CoreCommonR.string.settings_coloros_bluetooth_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(CoreCommonR.string.settings_coloros_recovery_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ColorOsAccessMode.entries.forEach { option ->
            MiuixSettingsChoiceRow(
                title = stringResource(if (option == ColorOsAccessMode.ROOT) CoreCommonR.string.settings_coloros_root else CoreCommonR.string.settings_coloros_shizuku),
                selected = option == mode,
                enabled = !busy,
                onClick = { onModeChanged(option) },
            )
        }
        Text(stringResource(if (busy) CoreCommonR.string.settings_coloros_working else resultLabel(result)))
        result?.uid?.let { Text(stringResource(CoreCommonR.string.settings_coloros_uid, it)) }
        MiuixSettingsTextButton(
            onClick = { onExecute(ColorOsConfigurationAction.CHECK) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(CoreCommonR.string.settings_coloros_check)) }
        Row {
            MiuixSettingsTextButton(
                onClick = { onExecute(ColorOsConfigurationAction.APPLY) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(CoreCommonR.string.settings_coloros_apply)) }
            MiuixSettingsTextButton(
                onClick = { onExecute(ColorOsConfigurationAction.RESTORE) },
                enabled = !busy && restoreAvailable,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(CoreCommonR.string.settings_coloros_restore)) }
        }
    }
}

private fun resultLabel(result: ColorOsConfigResult?): Int {
    if (result == null) return CoreCommonR.string.settings_coloros_not_checked
    if (result.detailCode == "row_creation_unsupported") return CoreCommonR.string.settings_coloros_not_configured
    return when (result.status) {
        ColorOsConfigStatus.READY -> CoreCommonR.string.settings_coloros_ready
        ColorOsConfigStatus.ENABLED -> CoreCommonR.string.settings_coloros_enabled
        ColorOsConfigStatus.APPLIED_RELOAD_REQUESTED -> CoreCommonR.string.settings_coloros_applied
        ColorOsConfigStatus.RESTORED_RELOAD_REQUESTED -> CoreCommonR.string.settings_coloros_restored
        ColorOsConfigStatus.NOT_CONFIGURED -> CoreCommonR.string.settings_coloros_not_configured
        ColorOsConfigStatus.UNSUPPORTED -> CoreCommonR.string.settings_coloros_unsupported
        ColorOsConfigStatus.PERMISSION_DENIED -> CoreCommonR.string.settings_coloros_permission_denied
        ColorOsConfigStatus.CONFLICT -> CoreCommonR.string.settings_coloros_conflict
        ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN -> CoreCommonR.string.settings_coloros_unknown
        ColorOsConfigStatus.READBACK_MISMATCH -> CoreCommonR.string.settings_coloros_readback_mismatch
        ColorOsConfigStatus.RELOAD_FAILED -> CoreCommonR.string.settings_coloros_reload_failed
        ColorOsConfigStatus.ERROR -> CoreCommonR.string.settings_coloros_error
    }
}
