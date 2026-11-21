package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion

/** Which dialog action slot a button is composed in; lets shared buttons adapt their chrome. */
enum class DialogButtonSlot { None, Confirm, Dismiss }

val LocalDialogButtonSlot = staticCompositionLocalOf { DialogButtonSlot.None }

/** Zoom settles from slightly larger, critically damped so it lands crisply without wobble. */
private const val DialogEnterScaleFrom = 1.06f
private const val DialogEnterDamping = 1f
private const val DialogEnterStiffness = 900f
private const val DialogFadeMillis = 140

/* enhanced: iOS alert style, a larger drop that settles with a hint of give, and a real exit */
private const val EnhancedDialogEnterScaleFrom = 1.12f
private const val EnhancedDialogEnterDamping = 0.84f
private const val EnhancedDialogEnterStiffness = 520f
private const val EnhancedDialogFadeMillis = 160
private const val EnhancedDialogExitMillis = 150
// FlClash 式磨砂：只模糊卡片自身区域；着色偏实，背后封面再花哨文字也清楚可读
private val EnhancedDialogBlurRadius = 32.dp
private const val EnhancedDialogTintLight = 0.8f
private const val EnhancedDialogTintDark = 0.84f

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
    val enhanced = NeriDesign.tokens.enhancedEffects
    val motion = rememberCleanDialogMotion(enhanced, onDismissRequest)
    // 在宿主窗口的组合里截图，弹窗窗口自己还没有内容
    val frostedSnapshot = rememberFrostedSnapshot(enabled = enhanced)
    BasicAlertDialog(
        onDismissRequest = motion::requestDismiss,
        modifier = modifier,
        properties = properties,
        content = localizedWindowContent {
            val colors = MaterialTheme.colorScheme
            val dark = colors.surface.luminance() < 0.5f
            ApplyEnhancedOverlayScrim(dark)
            ApplyEnhancedOverlayWindowExit()
            LaunchedEffect(motion) { motion.enter() }
            val dialogShape = NeriDesign.tokens.dialogShape
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 420.dp)
                    .graphicsLayer {
                        scaleX = motion.scale.value
                        scaleY = motion.scale.value
                        alpha = motion.alpha.value
                    },
                shape = dialogShape,
                color = if (enhanced) Color.Transparent else colors.surfaceContainerHigh,
                contentColor = colors.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box {
                    if (enhanced) {
                        FrostedCardBackground(
                            snapshot = frostedSnapshot,
                            shape = dialogShape,
                            solidColor = colors.surfaceContainerHigh,
                            frostTint = colors.surfaceContainerHigh.copy(
                                alpha = if (dark) EnhancedDialogTintDark else EnhancedDialogTintLight
                            ),
                            blurRadius = EnhancedDialogBlurRadius
                        )
                    }
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
    )
}

/**
 * Entrance and exit motion of the clean dialog. Exit only runs for back and outside taps, the
 * dismissals the dialog itself initiates; buttons hide it through caller state immediately
 */
private class CleanDialogMotion(
    private val enhanced: Boolean,
    private val scope: CoroutineScope,
    private val onDismissRequest: State<() -> Unit>
) {
    val scale = Animatable(if (enhanced) EnhancedDialogEnterScaleFrom else DialogEnterScaleFrom)
    val alpha = Animatable(0f)
    private var exitJob: Job? = null

    suspend fun enter() = coroutineScope {
        val fadeMillis = if (enhanced) EnhancedDialogFadeMillis else DialogFadeMillis
        launch { alpha.animateTo(1f, tween(fadeMillis, easing = LinearEasing)) }
        scale.animateTo(
            1f,
            if (enhanced) {
                spring(EnhancedDialogEnterDamping, EnhancedDialogEnterStiffness)
            } else {
                spring(DialogEnterDamping, DialogEnterStiffness)
            }
        )
    }

    fun requestDismiss() {
        if (!enhanced) {
            onDismissRequest.value()
            return
        }
        if (exitJob?.isActive == true) return
        exitJob = scope.launch {
            // 与 FlClash 一致，退场只淡出、保持缩放不动
            alpha.animateTo(0f, tween(EnhancedDialogExitMillis, easing = NeriMotion.EmphasizedAccelerate))
            onDismissRequest.value()
            // a caller may veto the dismissal; if the dialog is still here two frames later, bring it back
            withFrameNanos { }
            withFrameNanos { }
            alpha.animateTo(1f, tween(EnhancedDialogFadeMillis))
        }
    }
}

@Composable
private fun rememberCleanDialogMotion(enhanced: Boolean, onDismissRequest: () -> Unit): CleanDialogMotion {
    val scope = rememberCoroutineScope()
    val latestDismiss = rememberUpdatedState(onDismissRequest)
    return remember(enhanced) { CleanDialogMotion(enhanced, scope, latestDismiss) }
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
