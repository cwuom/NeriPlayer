package moe.ouom.neriplayer.ui.component.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.NeriDesign

/**
 * Style-aware switch: Material shows the expressive check glyph inside the thumb, Clean keeps a
 * full-size white knob in both states so the toggle reads like a physical switch
 */
@Composable
fun NeriSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val clean = NeriDesign.isClean
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        // 非空 thumbContent 会让 M3 滑块在两种状态下都保持满尺寸
        thumbContent = if (clean) {
            {}
        } else if (checked) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        } else {
            null
        },
        colors = if (clean) cleanSwitchColors() else materialSwitchColors()
    )
}

@Composable
private fun materialSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedIconColor = MaterialTheme.colorScheme.primary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledCheckedThumbColor = MaterialTheme.colorScheme.surface,
    disabledCheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.12f)
)

// 主题已配对的前景色能在浅色强调轨道上保持滑块可辨认
@Composable
private fun cleanSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
    uncheckedBorderColor = Color.Transparent,
    disabledCheckedThumbColor = Color.White.copy(alpha = 0.8f),
    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
    disabledCheckedBorderColor = Color.Transparent,
    disabledUncheckedThumbColor = Color.White.copy(alpha = 0.7f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    disabledUncheckedBorderColor = Color.Transparent
)

/** Sheet grabber: Clean uses a short soft capsule, Material keeps the stock handle */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeriSheetDragHandle(modifier: Modifier = Modifier) {
    if (NeriDesign.isClean) {
        BottomSheetDefaults.DragHandle(
            modifier = modifier,
            width = 36.dp,
            height = 5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
        )
    } else {
        BottomSheetDefaults.DragHandle(modifier = modifier)
    }
}
