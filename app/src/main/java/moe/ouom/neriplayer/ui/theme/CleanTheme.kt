package moe.ouom.neriplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Clean (preview) style palette. Neutral, slightly cool greys carry every surface so the
 * cover-driven or user accent only lands on interactive roles. Dark mode stays off pure
 * black and off pure white to lower glare during long listening sessions.
 */

private data class CleanNeutrals(
    val background: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val containerLowest: Color,
    val containerLow: Color,
    val container: Color,
    val containerHigh: Color,
    val containerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color
)

private val CleanLightNeutrals = CleanNeutrals(
    background = Color(0xFFF4F4F7),
    surfaceDim = Color(0xFFE3E3E8),
    surfaceBright = Color(0xFFFFFFFF),
    containerLowest = Color(0xFFFFFFFF),
    containerLow = Color(0xFFFFFFFF),
    container = Color(0xFFF9F9FB),
    containerHigh = Color(0xFFFFFFFF),
    containerHighest = Color(0xFFEAEAEF),
    onSurface = Color(0xFF1D1D1F),
    onSurfaceVariant = Color(0xFF5E5E64),
    outline = Color(0xFF85858C),
    outlineVariant = Color(0xFFE1E1E7),
    inverseSurface = Color(0xFF2C2C30),
    inverseOnSurface = Color(0xFFF2F2F6)
)

private val CleanDarkNeutrals = CleanNeutrals(
    background = Color(0xFF0E0E10),
    surfaceDim = Color(0xFF0A0A0C),
    surfaceBright = Color(0xFF2B2B30),
    containerLowest = Color(0xFF09090B),
    containerLow = Color(0xFF17171A),
    container = Color(0xFF1B1B1E),
    containerHigh = Color(0xFF212125),
    containerHighest = Color(0xFF2A2A2F),
    onSurface = Color(0xFFF2F2F5),
    onSurfaceVariant = Color(0xFFADADB5),
    outline = Color(0xFF8A8A93),
    outlineVariant = Color(0xFF2E2E34),
    inverseSurface = Color(0xFFE6E6EB),
    inverseOnSurface = Color(0xFF1C1C1F)
)

private const val CleanNeutralTintLight = 0.01f
private const val CleanNeutralTintDark = 0.016f

internal fun cleanColorScheme(accent: ColorScheme, isDark: Boolean): ColorScheme {
    val neutrals = if (isDark) CleanDarkNeutrals else CleanLightNeutrals
    val tintAmount = if (isDark) CleanNeutralTintDark else CleanNeutralTintLight
    val tint = accent.primary
    fun neutral(color: Color) = lerp(color, tint, tintAmount)

    val containerLow = neutral(neutrals.containerLow)
    val selectionContainer = lerp(containerLow, accent.primary, if (isDark) 0.20f else 0.12f)
    // selected labels stay readable ink; the container tint alone marks selection
    val onSelectionContainer = lerp(neutrals.onSurface, accent.primary, 0.12f)
    return accent.copy(
        secondaryContainer = selectionContainer,
        onSecondaryContainer = onSelectionContainer,
        background = neutral(neutrals.background),
        onBackground = neutrals.onSurface,
        surface = neutral(neutrals.background),
        onSurface = neutrals.onSurface,
        surfaceVariant = neutral(neutrals.containerHighest),
        onSurfaceVariant = neutrals.onSurfaceVariant,
        surfaceTint = Color.Transparent,
        inverseSurface = neutrals.inverseSurface,
        inverseOnSurface = neutrals.inverseOnSurface,
        outline = neutrals.outline,
        outlineVariant = neutrals.outlineVariant,
        scrim = Color.Black,
        surfaceBright = neutral(neutrals.surfaceBright),
        surfaceDim = neutral(neutrals.surfaceDim),
        surfaceContainerLowest = neutral(neutrals.containerLowest),
        surfaceContainerLow = containerLow,
        surfaceContainer = neutral(neutrals.container),
        surfaceContainerHigh = neutral(neutrals.containerHigh),
        surfaceContainerHighest = neutral(neutrals.containerHighest)
    )
}

/** Tinted accent fill used behind confirm actions, e.g. dialog primary buttons. */
internal fun ColorScheme.cleanTintedAccent(alpha: Float = 0.14f): Color =
    primary.copy(alpha = alpha).compositeOver(surfaceContainerHigh)

private val Base = Typography()

private fun TextStyle.clean(
    size: Float,
    lineHeight: Float,
    weight: FontWeight,
    tracking: Float
): TextStyle = copy(
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = tracking.em
)

/*
 * Tighter tracking and a clearer weight ladder: titles carry hierarchy through weight
 * instead of size jumps, body text keeps generous line height for long lyric lists.
 */
internal val CleanTypography = Typography(
    displayLarge = Base.displayLarge.clean(52f, 58f, FontWeight.Bold, -0.022f),
    displayMedium = Base.displayMedium.clean(42f, 48f, FontWeight.Bold, -0.02f),
    displaySmall = Base.displaySmall.clean(34f, 40f, FontWeight.Bold, -0.018f),
    headlineLarge = Base.headlineLarge.clean(30f, 36f, FontWeight.Bold, -0.016f),
    headlineMedium = Base.headlineMedium.clean(26f, 32f, FontWeight.Bold, -0.014f),
    headlineSmall = Base.headlineSmall.clean(22f, 28f, FontWeight.SemiBold, -0.012f),
    titleLarge = Base.titleLarge.clean(20f, 26f, FontWeight.SemiBold, -0.01f),
    titleMedium = Base.titleMedium.clean(16.5f, 22f, FontWeight.SemiBold, -0.006f),
    titleSmall = Base.titleSmall.clean(14.5f, 20f, FontWeight.Medium, -0.004f),
    bodyLarge = Base.bodyLarge.clean(16f, 23f, FontWeight.Normal, -0.006f),
    bodyMedium = Base.bodyMedium.clean(14f, 20f, FontWeight.Normal, -0.002f),
    bodySmall = Base.bodySmall.clean(12.5f, 17f, FontWeight.Normal, 0f),
    labelLarge = Base.labelLarge.clean(14.5f, 20f, FontWeight.SemiBold, -0.004f),
    labelMedium = Base.labelMedium.clean(12.5f, 16f, FontWeight.Medium, 0f),
    labelSmall = Base.labelSmall.clean(11f, 14f, FontWeight.Medium, 0.01f)
)

internal val CleanShapes = Shapes(
    extraSmall = ContinuousRoundedCornerShape(14.dp),
    small = ContinuousRoundedCornerShape(16.dp),
    medium = ContinuousRoundedCornerShape(18.dp),
    large = ContinuousRoundedCornerShape(24.dp),
    extraLarge = ContinuousRoundedCornerShape(30.dp)
)
