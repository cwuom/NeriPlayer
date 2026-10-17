package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import moe.ouom.neriplayer.ui.theme.NeriDesign

/** Which dialog action slot a button is composed in; lets shared buttons adapt their chrome. */
enum class DialogButtonSlot { None, Confirm, Dismiss }

val LocalDialogButtonSlot = staticCompositionLocalOf { DialogButtonSlot.None }

/** Zoom settles from slightly larger, critically damped so it lands crisply without wobble. */
private const val DialogEnterScaleFrom = 1.06f
private const val DialogEnterDamping = 1f
private const val DialogEnterStiffness = 900f
private const val DialogFadeMillis = 140

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CleanAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: @Composable (() -> Unit)?,
    icon: @Composable (() -> Unit)?,
    title: @Composable (() -> Unit)?,
    text: @Composable (() -> Unit)?,
    properties: DialogProperties,
    neutralButton: @Composable (() -> Unit)? = null
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties
    ) {
        val entranceScale = remember { Animatable(DialogEnterScaleFrom) }
        val entranceAlpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            launch { entranceAlpha.animateTo(1f, tween(DialogFadeMillis, easing = LinearEasing)) }
            entranceScale.animateTo(1f, spring(DialogEnterDamping, DialogEnterStiffness))
        }
        val colors = MaterialTheme.colorScheme
        Surface(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 420.dp)
                .graphicsLayer {
                    scaleX = entranceScale.value
                    scaleY = entranceScale.value
                    alpha = entranceAlpha.value
                },
            shape = NeriDesign.tokens.dialogShape,
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp)) {
                val centered = icon != null
                icon?.let { iconContent ->
                    CompositionLocalProvider(LocalContentColor provides colors.primary) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 14.dp)
                        ) { iconContent() }
                    }
                }
                title?.let { titleContent ->
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                            Box(
                                modifier = Modifier
                                    .align(if (centered) Alignment.CenterHorizontally else Alignment.Start)
                                    .padding(bottom = 10.dp)
                            ) { titleContent() }
                        }
                    }
                }
                text?.let { textContent ->
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                            Box(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(bottom = 22.dp)
                                    .align(if (centered) Alignment.CenterHorizontally else Alignment.Start)
                            ) { textContent() }
                        }
                    }
                }
                if (title != null && text == null) {
                    Box(Modifier.padding(bottom = 12.dp))
                }
                CleanDialogButtonRow(
                    dismissButton = dismissButton,
                    confirmButton = confirmButton,
                    neutralButton = neutralButton
                )
            }
        }
    }
}

private val DialogButtonGap = 10.dp
private val DialogButtonStackGap = 8.dp

/**
 * actions share a row when every label fits its equal-width slot, otherwise each action uses
 * the full width with confirm first
 */
@Composable
private fun CleanDialogButtonRow(
    dismissButton: @Composable (() -> Unit)?,
    confirmButton: @Composable () -> Unit,
    neutralButton: @Composable (() -> Unit)?
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            CleanDialogButtonSlot(DialogButtonSlot.Confirm, Modifier, confirmButton)
            if (neutralButton != null) {
                CleanDialogButtonSlot(DialogButtonSlot.Dismiss, Modifier, neutralButton)
            }
            if (dismissButton != null) {
                CleanDialogButtonSlot(DialogButtonSlot.Dismiss, Modifier, dismissButton)
            }
        }
    ) { allMeasurables, constraints ->
        val width = constraints.maxWidth
        // pickers pass an empty confirm lambda; an empty slot must not leave a blank pill behind
        val measurables = allMeasurables.withIndex().filter {
            it.value.maxIntrinsicWidth(Constraints.Infinity) > 0
        }
        if (measurables.isEmpty()) return@Layout layout(width, 0) {}
        val gap = DialogButtonGap.roundToPx()
        val count = measurables.size
        val slotWidth = (width - gap * (count - 1)) / count
        val sideBySide = measurables.all { it.value.maxIntrinsicWidth(Constraints.Infinity) <= slotWidth }
        if (sideBySide) {
            // preserve secondary action order while the original confirm slot trails
            val placeables = measurables.sortedBy { if (it.index == 0) 1 else 0 }.map {
                it.value.measure(Constraints.fixedWidth(slotWidth).copy(minHeight = 0, maxHeight = constraints.maxHeight))
            }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(index * (slotWidth + gap), (height - placeable.height) / 2)
                }
            }
        } else {
            val stackGap = DialogButtonStackGap.roundToPx()
            val placeables = measurables.map {
                it.value.measure(Constraints.fixedWidth(width).copy(minHeight = 0, maxHeight = constraints.maxHeight))
            }
            val height = placeables.sumOf { it.height } + stackGap * (count - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.place(0, y)
                    y += placeable.height + stackGap
                }
            }
        }
    }
}

/**
 * A pill that stretches whatever button the caller put in the slot: a lone TextButton fills
 * the pill (so its ripple matches), a filled button simply covers the tint.
 */
@Composable
private fun CleanDialogButtonSlot(
    slot: DialogButtonSlot,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val background = when (slot) {
        DialogButtonSlot.Confirm -> colors.primary.copy(alpha = 0.14f)
        else -> colors.onSurface.copy(alpha = 0.06f)
    }
    Box(
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true
    ) {
        CompositionLocalProvider(LocalDialogButtonSlot provides slot) {
            if (slot == DialogButtonSlot.Dismiss) {
                // stock TextButtons read primary; cancel actions should stay neutral
                MaterialTheme(colorScheme = colors.copy(primary = colors.onSurface)) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}
