package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR

@Composable
internal fun SyncProtocolUpgradeWarning(
    state: SyncProtocolUpgradeUiState,
    onConfirm: () -> Unit
) {
    if (state.approved != false || state.challenge == null) return
    val orange = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFFB74D) else Color(0xFF934600)
    Surface(
        onClick = onConfirm,
        enabled = !state.isSaving,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFFFF9800).copy(alpha = 0.12f),
        contentColor = orange,
        border = BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(CoreCommonR.string.sync_upgrade_warning_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(CoreCommonR.string.sync_upgrade_warning_message), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(CoreCommonR.string.sync_upgrade_confirm), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
