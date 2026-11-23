package moe.ouom.neriplayer.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke

/**
 * Corner radius used by the clean style for a radius designed against Material.
 * Small radii grow the most (8dp covers read as sharp squares next to continuous corners),
 * large radii converge so sheets and dialogs do not balloon.
 */
internal fun cleanCornerRadiusFor(materialRadius: Dp): Dp = when {
    materialRadius <= 0.dp -> 0.dp
    materialRadius <= 4.dp -> materialRadius + 2.dp
    materialRadius <= 8.dp -> materialRadius + 4.dp
    materialRadius <= 16.dp -> materialRadius + 4.dp
    materialRadius <= 24.dp -> materialRadius + 2.dp
    else -> materialRadius
}

/**
 * Style-aware replacement for `RoundedCornerShape(radius)` on pages that support both styles.
 * Clean style scales up small radii while keeping analytic [RoundedCornerShape] outlines so
 * scrolling lists stay on the GPU clip fast path instead of triggering per-frame software path
 * mask uploads under Skia/OpenGL.
 */
@Composable
@ReadOnlyComposable
fun neriRoundedShape(radius: Dp): CornerBasedShape =
    if (LocalNeriDesign.current.isClean) {
        RoundedCornerShape(cleanCornerRadiusFor(radius))
    } else {
        RoundedCornerShape(radius)
    }

@Composable
@ReadOnlyComposable
fun neriRoundedShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp
): CornerBasedShape =
    if (LocalNeriDesign.current.isClean) {
        RoundedCornerShape(
            topStart = cleanCornerRadiusFor(topStart),
            topEnd = cleanCornerRadiusFor(topEnd),
            bottomEnd = cleanCornerRadiusFor(bottomEnd),
            bottomStart = cleanCornerRadiusFor(bottomStart)
        )
    } else {
        RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
    }

/**
 * Search inputs. Clean: ink-tinted capsule. Material: the M3 search-bar look, a tonal filled
 * pill that only shows an accent outline while focused; both soften over a custom background
 */
@Composable
fun neriSearchFieldColors(): TextFieldColors {
    val colors = MaterialTheme.colorScheme
    val overImage = LocalHasCustomBackground.current
    return if (LocalNeriDesign.current.isClean) {
        OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.onSurface.copy(alpha = 0.06f),
            unfocusedContainerColor = colors.onSurface.copy(alpha = 0.06f),
            focusedBorderColor = colors.primary.copy(alpha = 0.5f),
            unfocusedBorderColor = Color.Transparent
        )
    } else {
        val container = if (overImage) {
            // 底下已有 NeriSearchFieldGlass 的玻璃/半透明底, 这里只补一层轻色调
            colors.surfaceContainerHigh.copy(alpha = 0.42f)
        } else {
            colors.surfaceContainerHigh
        }
        OutlinedTextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container.copy(alpha = container.alpha * 0.6f),
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent
        )
    }
}

private val MaterialSearchFieldShape = RoundedCornerShape(28.dp)

/** Material search fields are full pills regardless of the legacy per-page radius */
@Composable
@ReadOnlyComposable
fun neriSearchFieldShape(): Shape =
    if (LocalNeriDesign.current.isClean) LocalNeriDesign.current.groupShape else MaterialSearchFieldShape

private val MaterialInputFieldShape = RoundedCornerShape(16.dp)

/** Free-form inputs that may wrap to several lines keep a moderate radius instead of a pill */
@Composable
@ReadOnlyComposable
fun neriInputFieldShape(): Shape =
    if (LocalNeriDesign.current.isClean) LocalNeriDesign.current.groupShape else MaterialInputFieldShape

/**
 * 描边按钮的发丝线. M3 默认 outline 是不透明中灰, 压在背景图, 封面色和玻璃上发灰发脏,
 * 改用半透明前景色让线条和底色融在一起
 */
@Composable
fun neriOutlinedButtonBorder(enabled: Boolean = true): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.24f else 0.1f))
