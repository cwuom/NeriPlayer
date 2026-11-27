package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.NeriDesign

/*
 * Drop-in replacements for the Material3 DropdownMenu / DropdownMenuItem pair. They stay stock
 * Material unless the clean style runs with enhanced visual effects; then the menu becomes a
 * frosted card that grows out of its anchor like a droplet, keeps icons on the trailing edge and
 * supports press-and-slide picking. Enhanced menus use their own geometry, so `shape` only
 * applies to the Material path
 */

@Composable
internal fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    shape: Shape = MenuDefaults.shape,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!NeriDesign.tokens.enhancedEffects) {
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            shape = shape,
            content = content
        )
        return
    }
    EnhancedDropdownMenu(expanded, onDismissRequest, modifier, offset, content)
}

@Composable
internal fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val picker = LocalEnhancedMenuPicker.current
    if (picker == null) {
        androidx.compose.material3.DropdownMenuItem(
            text = text,
            onClick = onClick,
            modifier = modifier,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled
        )
        return
    }
    EnhancedMenuItem(picker, text, onClick, modifier, leadingIcon, trailingIcon, enabled)
}
