package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun DialogActionTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    if (colors == null && LocalDialogButtonSlot.current == DialogButtonSlot.Confirm && !NeriDesign.isClean) {
        FilledDialogActionButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        return
    }
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors ?: ButtonDefaults.textButtonColors(),
        contentPadding = contentPadding,
        content = content
    )
}

private val MaterialDialogActionShape = RoundedCornerShape(16.dp)

@Composable
internal fun FilledDialogActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.dialogSlotButtonSize(44.dp),
        enabled = enabled,
        shape = if (NeriDesign.isClean) CircleShape else MaterialDialogActionShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        content = content
    )
}

/** Inside a clean dialog action slot the button becomes the whole pill */
@Composable
internal fun Modifier.dialogSlotButtonSize(defaultHeight: Dp): Modifier =
    if (NeriDesign.isClean && LocalDialogButtonSlot.current != DialogButtonSlot.None) {
        fillMaxWidth().heightIn(min = 46.dp)
    } else {
        heightIn(min = defaultHeight)
    }
