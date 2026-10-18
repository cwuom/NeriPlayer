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

/** Style-aware replacement for `RoundedCornerShape(radius)` on pages that support both styles. */
@Composable
@ReadOnlyComposable
fun neriRoundedShape(radius: Dp): CornerBasedShape =
    if (LocalNeriDesign.current.isClean) {
        ContinuousRoundedCornerShape(cleanCornerRadiusFor(radius))
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
        ContinuousRoundedCornerShape(
            topStart = cleanCornerRadiusFor(topStart),
            topEnd = cleanCornerRadiusFor(topEnd),
            bottomEnd = cleanCornerRadiusFor(bottomEnd),
            bottomStart = cleanCornerRadiusFor(bottomStart)
        )
    } else {
        RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
    }

/** Search inputs: borderless filled capsule in the clean style, stock outline in Material. */
@Composable
fun neriSearchFieldColors(): TextFieldColors = if (LocalNeriDesign.current.isClean) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.onSurface.copy(alpha = 0.06f),
        unfocusedContainerColor = colors.onSurface.copy(alpha = 0.06f),
        focusedBorderColor = colors.primary.copy(alpha = 0.5f),
        unfocusedBorderColor = Color.Transparent
    )
} else {
    OutlinedTextFieldDefaults.colors()
}

@Composable
@ReadOnlyComposable
fun neriSearchFieldShape(materialShape: Shape): Shape =
    if (LocalNeriDesign.current.isClean) LocalNeriDesign.current.groupShape else materialShape
