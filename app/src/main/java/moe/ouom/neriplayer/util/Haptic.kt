package moe.ouom.neriplayer.util

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.util/Haptic
 * Created: 2025/8/13
 */
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource

private const val VIBRATION_EFFECT_CLICK = 0
private const val VIBRATION_EFFECT_DOUBLE_CLICK = 1
private const val VIBRATION_EFFECT_TICK = 2
private const val VIBRATION_EFFECT_HEAVY_CLICK = 5

enum class HapticFeedbackEffect(
    val predefinedEffect: Int,
    val fallbackDurationMs: Long,
    val fallbackAmplitude: Int
) {
    Tick(VIBRATION_EFFECT_TICK, 8L, 32),
    Click(VIBRATION_EFFECT_CLICK, 12L, 72),
    Confirm(VIBRATION_EFFECT_DOUBLE_CLICK, 20L, 96),
    Heavy(VIBRATION_EFFECT_HEAVY_CLICK, 24L, 160)
}

@Volatile
var hapticFeedbackEnabled: Boolean = true
    private set

fun syncHapticFeedbackSetting(enabled: Boolean) {
    hapticFeedbackEnabled = enabled
}

fun Context.performHapticFeedback(effect: HapticFeedbackEffect = HapticFeedbackEffect.Click) {
    if (!hapticFeedbackEnabled) return

    val vibrator = getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return

    runCatching {
        vibrator.vibrate(createVibrationEffect(vibrator, effect))
    }
}

private fun createVibrationEffect(
    vibrator: Vibrator,
    effect: HapticFeedbackEffect
): VibrationEffect {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        vibrator.supportsPredefinedEffect(effect.predefinedEffect)
    ) {
        return VibrationEffect.createPredefined(effect.predefinedEffect)
    }
    return VibrationEffect.createOneShot(effect.fallbackDurationMs, effect.fallbackAmplitude)
}

private fun Vibrator.supportsPredefinedEffect(effect: Int): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
    val support = areEffectsSupported(effect).firstOrNull()
    return support == Vibrator.VIBRATION_EFFECT_SUPPORT_YES ||
        support == Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN
}

@Composable
fun HapticIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    hapticEffect: HapticFeedbackEffect = HapticFeedbackEffect.Click,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            context.performHapticFeedback(hapticEffect)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun HapticFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    hapticEffect: HapticFeedbackEffect = HapticFeedbackEffect.Confirm,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    FilledIconButton(
        onClick = {
            context.performHapticFeedback(hapticEffect)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun HapticButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    hapticEffect: HapticFeedbackEffect = HapticFeedbackEffect.Confirm,
    content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    Button(
        onClick = {
            context.performHapticFeedback(hapticEffect)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun HapticTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    hapticEffect: HapticFeedbackEffect = HapticFeedbackEffect.Click,
    content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            context.performHapticFeedback(hapticEffect)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun HapticOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    hapticEffect: HapticFeedbackEffect = HapticFeedbackEffect.Click,
    content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            context.performHapticFeedback(hapticEffect)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun HapticFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.shape,
    containerColor: Color = FloatingActionButtonDefaults.containerColor,
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    hapticEffect: HapticFeedbackEffect = HapticFeedbackEffect.Confirm,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    FloatingActionButton(
        onClick = {
            context.performHapticFeedback(hapticEffect)
            onClick()
        },
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
        elevation = elevation,
        content = content
    )
}
