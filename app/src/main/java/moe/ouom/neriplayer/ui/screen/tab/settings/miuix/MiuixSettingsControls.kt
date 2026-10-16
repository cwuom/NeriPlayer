package moe.ouom.neriplayer.ui.screen.tab.settings.miuix

import moe.ouom.neriplayer.ui.theme.NeriMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Check
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import moe.ouom.neriplayer.ui.component.overlay.DialogButtonSlot
import moe.ouom.neriplayer.ui.component.overlay.LocalDialogButtonSlot
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign
import androidx.compose.ui.window.DialogProperties
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

private val MiuixControlShape = RoundedCornerShape(16.dp)
private val MiuixDialogShape = RoundedCornerShape(28.dp)
private val MiuixInnerShape = RoundedCornerShape(12.dp)
private val CleanFieldShape = ContinuousRoundedCornerShape(16.dp)

private val buttonShape: Shape
    @Composable get() = if (NeriDesign.isClean) CircleShape else MiuixControlShape

/** Inside a clean dialog action slot the button becomes the whole pill. */
@Composable
private fun Modifier.dialogSlotButtonSize(defaultHeight: Dp): Modifier =
    if (NeriDesign.isClean && LocalDialogButtonSlot.current != DialogButtonSlot.None) {
        fillMaxWidth().heightIn(min = 46.dp)
    } else {
        height(defaultHeight)
    }

@Composable
internal fun MiuixSettingsDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties()
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.widthIn(max = 340.dp),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = MiuixDialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        properties = properties
    )
}

@Composable
internal fun MiuixSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    Button(
        onClick = {
            context.performHapticFeedback()
            onClick()
        },
        modifier = modifier.dialogSlotButtonSize(44.dp),
        enabled = enabled,
        shape = buttonShape,
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

@Composable
internal fun MiuixSettingsOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            context.performHapticFeedback()
            onClick()
        },
        modifier = modifier.dialogSlotButtonSize(44.dp),
        enabled = enabled,
        shape = buttonShape,
        border = if (NeriDesign.isClean) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (NeriDesign.isClean) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.58f)
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        content = content
    )
}

@Composable
internal fun MiuixSettingsTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable () -> Unit
) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            context.performHapticFeedback()
            onClick()
        },
        modifier = modifier.dialogSlotButtonSize(40.dp),
        enabled = enabled,
        shape = buttonShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        text()
    }
}

@Composable
internal fun MiuixSettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    if (NeriDesign.isClean) {
        val interactionSource = remember { MutableInteractionSource() }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().cleanFieldFocusRing(interactionSource),
            enabled = enabled,
            label = label,
            placeholder = placeholder,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            shape = CleanFieldShape,
            colors = cleanTextFieldColors()
        )
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = MiuixControlShape
    )
}

@Composable
internal fun MiuixSettingsTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    if (NeriDesign.isClean) {
        val interactionSource = remember { MutableInteractionSource() }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().cleanFieldFocusRing(interactionSource),
            enabled = enabled,
            label = label,
            placeholder = placeholder,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            shape = CleanFieldShape,
            colors = cleanTextFieldColors()
        )
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = MiuixControlShape
    )
}

/**
 * Clean fields are filled with the label kept inside, so no outline notch ever cuts a dark
 * gap into the fill; focus is shown by a soft accent ring instead of an indicator line.
 */
@Composable
private fun cleanTextFieldColors(): TextFieldColors {
    val colors = MaterialTheme.colorScheme
    val fill = colors.onSurface.copy(alpha = 0.06f)
    return TextFieldDefaults.colors(
        focusedContainerColor = fill,
        unfocusedContainerColor = fill,
        disabledContainerColor = colors.onSurface.copy(alpha = 0.03f),
        errorContainerColor = fill,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent
    )
}

@Composable
private fun Modifier.cleanFieldFocusRing(interactionSource: MutableInteractionSource): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val ring by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = NeriMotion.smooth(),
        label = "clean_field_ring"
    )
    return border(1.dp, ring, CleanFieldShape)
}

@Composable
internal fun MiuixSettingsInlineMessage(
    message: String,
    isSuccess: Boolean,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    val containerColor = if (isSuccess) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (isSuccess) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val accentColor = if (isSuccess) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MiuixControlShape,
        color = containerColor,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isSuccess) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun MiuixSettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = if (NeriDesign.isClean) cleanSwitchColors() else SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledCheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledCheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    )
}

/**
 * Borderless track with a white thumb, closer to a native toggle. Achromatic covers resolve to a
 * near-white primary, where a white thumb would vanish into the track, so it flips to onPrimary.
 */
@Composable
private fun cleanSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.primary.let { primary ->
        if (primary.luminance() > 0.6f) MaterialTheme.colorScheme.onPrimary else Color.White
    },
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
    uncheckedBorderColor = Color.Transparent,
    disabledCheckedThumbColor = Color.White.copy(alpha = 0.8f),
    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
    disabledUncheckedThumbColor = Color.White.copy(alpha = 0.7f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    disabledUncheckedBorderColor = Color.Transparent
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MiuixSettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    if (NeriDesign.isClean) {
        CleanSettingsSlider(value, onValueChange, modifier, enabled, valueRange, steps, onValueChangeFinished)
        return
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.onPrimary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
            inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
    )
}

private val CleanSliderTrackHeight = 6.dp
private val CleanSliderThumbSize = 24.dp

/**
 * Native-style slider: a round white knob over a soft capsule track. The expressive Material
 * bar thumb reads as a stray divider at either end of the range, so clean pages avoid it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanSettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)?
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsDraggedAsState()
    val knobScale by animateFloatAsState(
        targetValue = if (pressed) 1.12f else 1f,
        animationSpec = NeriMotion.snappy(),
        label = "clean_slider_knob"
    )
    val active = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.28f)
    val inactive = colors.onSurface.copy(alpha = if (colors.surface.luminance() < 0.5f) 0.16f else 0.10f)
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        steps = steps,
        valueRange = valueRange,
        thumb = {
            Box(
                Modifier
                    .size(CleanSliderThumbSize)
                    .graphicsLayer {
                        scaleX = knobScale
                        scaleY = knobScale
                    }
                    .shadow(3.dp, CircleShape, clip = false)
                    .background(if (enabled) Color.White else Color(0xFFD8D8DC), CircleShape)
            )
        },
        track = { state ->
            val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
            val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(CleanSliderTrackHeight)
            ) {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(inactive, cornerRadius = radius)
                if (fraction > 0f) {
                    drawRoundRect(
                        color = active,
                        size = Size(maxOf(size.height, size.width * fraction), size.height),
                        cornerRadius = radius
                    )
                }
                if (state.steps > 0) {
                    val dot = size.height * 0.18f
                    for (index in 1..state.steps) {
                        val x = size.width * index / (state.steps + 1)
                        val onActive = x <= size.width * fraction
                        drawCircle(
                            color = if (onActive) colors.onPrimary.copy(alpha = 0.6f) else colors.onSurfaceVariant.copy(alpha = 0.5f),
                            radius = dot,
                            center = Offset(x, size.height / 2f)
                        )
                    }
                }
            }
        }
    )
}

@Composable
internal fun MiuixSettingsSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (labels.isEmpty()) {
        return
    }
    val context = LocalContext.current
    val safeSelectedIndex = selectedIndex.coerceIn(labels.indices)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(MiuixControlShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .height(48.dp)
            .padding(4.dp)
    ) {
        val tabWidth = maxWidth / labels.size.toFloat()
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * safeSelectedIndex.toFloat(),
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            label = "miuix_segmented_tab_indicator"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .fillMaxHeight()
                .fillMaxWidth(1f / labels.size)
                .clip(MiuixInnerShape)
                .background(MaterialTheme.colorScheme.primary)
        )

        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == safeSelectedIndex
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    label = "miuix_segmented_tab_content"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(MiuixInnerShape)
                        .clickable {
                            if (!selected) {
                                context.performHapticFeedback()
                                onSelectedIndexChange(index)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        Text(text = label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MiuixSettingsChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val supportingColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MiuixControlShape)
            .clickable(enabled = enabled) {
                context.performHapticFeedback()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, color = contentColor)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = supportingColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        MiuixChoiceIndicator(selected = selected, enabled = enabled)
    }
}

/** Check badge whose fill cross-fades and whose tick springs in, so a new pick never pops. */
@Composable
private fun MiuixChoiceIndicator(selected: Boolean, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        targetValue = when {
            selected && enabled -> colors.primary
            selected -> colors.surfaceVariant
            else -> colors.surfaceContainerHighest
        },
        animationSpec = NeriMotion.smooth(),
        label = "miuix_choice_fill"
    )
    val tick by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (selected) NeriMotion.bouncy() else NeriMotion.snappy(),
        label = "miuix_choice_tick"
    )
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center
    ) {
        if (tick > 0.01f) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        alpha = tick.coerceIn(0f, 1f)
                        scaleX = 0.4f + 0.6f * tick
                        scaleY = 0.4f + 0.6f * tick
                    }
            )
        }
    }
}

@Composable
internal fun MiuixSettingsCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.size(40.dp),
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = MaterialTheme.colorScheme.primary,
            uncheckedColor = MaterialTheme.colorScheme.outline,
            checkmarkColor = MaterialTheme.colorScheme.onPrimary,
            disabledCheckedColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledUncheckedColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
internal fun MiuixSettingsDialogContent(
    verticalSpacing: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content
    )
}

@Composable
internal fun MiuixSettingsDialogSpacer() {
    Spacer(Modifier.height(8.dp))
}
