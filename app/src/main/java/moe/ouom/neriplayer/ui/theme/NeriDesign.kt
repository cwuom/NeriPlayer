package moe.ouom.neriplayer.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle

/**
 * Style-dependent geometry shared by pages, dialogs and chrome. Colors and type live in
 * MaterialTheme so stock Material components follow the active style automatically.
 */
@Immutable
data class NeriDesignTokens(
    val style: UiStyle,
    val cardShape: Shape,
    val groupShape: Shape,
    val controlShape: Shape,
    val chipShape: Shape,
    val coverSmallShape: Shape,
    val coverLargeShape: Shape,
    val dialogShape: Shape,
    val sheetShape: Shape,
    val menuShape: Shape,
    val miniPlayerShape: Shape,
    val pageHorizontalPadding: Dp,
    val sectionSpacing: Dp,
    val hairlineAlpha: Float,
    val pressScaleEnabled: Boolean,
    val liquidGlass: Boolean = false
) {
    val isClean: Boolean get() = style == UiStyle.Clean
}

internal val MaterialDesignTokens = NeriDesignTokens(
    style = UiStyle.Material,
    cardShape = RoundedCornerShape(16.dp),
    groupShape = RoundedCornerShape(24.dp),
    controlShape = RoundedCornerShape(16.dp),
    chipShape = RoundedCornerShape(12.dp),
    coverSmallShape = RoundedCornerShape(10.dp),
    coverLargeShape = RoundedCornerShape(16.dp),
    dialogShape = RoundedCornerShape(28.dp),
    sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    menuShape = RoundedCornerShape(16.dp),
    miniPlayerShape = RoundedCornerShape(24.dp),
    pageHorizontalPadding = 16.dp,
    sectionSpacing = 20.dp,
    hairlineAlpha = 0f,
    pressScaleEnabled = false
)

internal val CleanDesignTokens = NeriDesignTokens(
    style = UiStyle.Clean,
    cardShape = ContinuousRoundedCornerShape(20.dp),
    groupShape = ContinuousRoundedCornerShape(22.dp),
    controlShape = CircleShape,
    chipShape = CircleShape,
    coverSmallShape = ContinuousRoundedCornerShape(10.dp),
    coverLargeShape = ContinuousRoundedCornerShape(18.dp),
    dialogShape = ContinuousRoundedCornerShape(30.dp),
    sheetShape = ContinuousRoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    menuShape = ContinuousRoundedCornerShape(18.dp),
    miniPlayerShape = ContinuousRoundedCornerShape(22.dp),
    pageHorizontalPadding = 20.dp,
    sectionSpacing = 28.dp,
    hairlineAlpha = 0.06f,
    pressScaleEnabled = true
)

internal fun neriDesignTokensFor(style: UiStyle): NeriDesignTokens = when (style) {
    UiStyle.Material -> MaterialDesignTokens
    UiStyle.Clean -> CleanDesignTokens
}

val LocalNeriDesign = staticCompositionLocalOf { MaterialDesignTokens }

/** True while a custom background image is shown; clean chrome must stay translucent over it. */
val LocalHasCustomBackground = staticCompositionLocalOf { false }

object NeriDesign {
    val tokens: NeriDesignTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalNeriDesign.current

    val isClean: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalNeriDesign.current.isClean
}

/**
 * Transition builders run outside composition (NavHost lambdas, seekable transitions), so the
 * active style is mirrored here. Written from the root theme on every style change.
 */
object NeriMotionStyle {
    @Volatile
    var clean: Boolean = false
}

/**
 * Non-linear motion vocabulary. Easing curves follow the emphasized family; springs are
 * critically-near damped so they settle without visible wobble on 60 Hz panels.
 */
object NeriMotion {
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val SmoothInOut: Easing = CubicBezierEasing(0.45f, 0f, 0.15f, 1f)
    val EmphasizedAccelerateSoft: Easing = CubicBezierEasing(0.55f, 0f, 0.3f, 1f)

    const val SmoothDamping = 0.88f
    const val SmoothStiffness = 380f
    const val SnappyDamping = 0.8f
    const val SnappyStiffness = 700f
    const val BouncyDamping = 0.7f
    const val BouncyStiffness = 460f

    fun <T> smooth(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(SmoothDamping, SmoothStiffness, visibilityThreshold)

    fun <T> snappy(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(SnappyDamping, SnappyStiffness, visibilityThreshold)

    fun <T> bouncy(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(BouncyDamping, BouncyStiffness, visibilityThreshold)
}
