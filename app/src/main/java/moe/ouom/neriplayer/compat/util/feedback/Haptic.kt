package moe.ouom.neriplayer.util

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback as performHapticFeedbackImpl

enum class HapticFeedbackEffect(
    val predefinedEffect: Int,
    val fallbackDurationMs: Long,
    val fallbackAmplitude: Int
) {
    Tick(2, 8L, 32),
    Click(0, 12L, 72),
    Confirm(1, 20L, 96),
    Heavy(5, 24L, 160)
}

val hapticFeedbackEnabled: Boolean
    get() = moe.ouom.neriplayer.ui.haptic.hapticFeedbackEnabled

fun syncHapticFeedbackSetting(enabled: Boolean) {
    moe.ouom.neriplayer.ui.haptic.syncHapticFeedbackSetting(enabled)
}

fun Context.performHapticFeedback(effect: HapticFeedbackEffect = HapticFeedbackEffect.Click) {
    this.performHapticFeedbackImpl(effect.toUiEffect())
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
    moe.ouom.neriplayer.ui.haptic.HapticIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        hapticEffect = hapticEffect.toUiEffect(),
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
    moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        interactionSource = interactionSource,
        hapticEffect = hapticEffect.toUiEffect(),
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
    moe.ouom.neriplayer.ui.haptic.HapticButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        hapticEffect = hapticEffect.toUiEffect(),
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
    moe.ouom.neriplayer.ui.haptic.HapticTextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        hapticEffect = hapticEffect.toUiEffect(),
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
    moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        hapticEffect = hapticEffect.toUiEffect(),
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
    moe.ouom.neriplayer.ui.haptic.HapticFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
        elevation = elevation,
        hapticEffect = hapticEffect.toUiEffect(),
        content = content
    )
}

private fun HapticFeedbackEffect.toUiEffect(): moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect {
    return when (this) {
        HapticFeedbackEffect.Tick -> moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect.Tick
        HapticFeedbackEffect.Click -> moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect.Click
        HapticFeedbackEffect.Confirm -> moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect.Confirm
        HapticFeedbackEffect.Heavy -> moe.ouom.neriplayer.ui.haptic.HapticFeedbackEffect.Heavy
    }
}
