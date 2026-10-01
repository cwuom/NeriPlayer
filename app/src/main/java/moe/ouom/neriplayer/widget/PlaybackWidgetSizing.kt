package moe.ouom.neriplayer.widget

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import kotlin.math.roundToInt

internal const val PLAYBACK_WIDGET_DEFAULT_FULL_WIDTH_DP = 250
internal const val PLAYBACK_WIDGET_DEFAULT_COMPACT_WIDTH_DP = 110
internal const val PLAYBACK_WIDGET_DEFAULT_HEIGHT_DP = 110
internal const val PLAYBACK_WIDGET_DEFAULT_STRIP_HEIGHT_DP = 56
internal const val PLAYBACK_WIDGET_FULL_CARD_MIN_HEIGHT_DP = 180

internal data class PlaybackWidgetSize(
    val widthDp: Int,
    val heightDp: Int,
)

internal data class PlaybackWidgetLayoutSpec(
    val cardHeightDp: Int,
    val horizontalPaddingDp: Int,
    val topPaddingDp: Int,
    val bottomPaddingDp: Int,
    val albumSizeDp: Int,
    val albumGapDp: Int,
    val controlsHeightDp: Int,
    val controlSizeDp: Int,
    val primaryControlSizeDp: Int,
    val controlGapDp: Int,
    val usesFullWidthControls: Boolean,
    val progressRowHeightDp: Int,
    val progressLabelWidthDp: Int,
    val progressMarginDp: Int,
    val compactInfoTopPaddingDp: Int,
    val compactInfoBottomPaddingDp: Int,
    val compactControlBottomMarginDp: Int,
    val statusTextSizeSp: Float,
    val titleTextSizeSp: Float,
    val subtitleTextSizeSp: Float,
)

internal fun playbackWidgetSizeFromOptions(
    options: Bundle?,
    hasProgress: Boolean,
    isStrip: Boolean = false,
): PlaybackWidgetSize {
    val defaultWidth = if (hasProgress || isStrip) {
        PLAYBACK_WIDGET_DEFAULT_FULL_WIDTH_DP
    } else {
        PLAYBACK_WIDGET_DEFAULT_COMPACT_WIDTH_DP
    }
    val width = resolvePlaybackWidgetDimension(
        minDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0,
        maxDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0,
        defaultDp = defaultWidth,
    )
    val height = resolvePlaybackWidgetDimension(
        minDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0,
        maxDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0,
        defaultDp = if (isStrip) PLAYBACK_WIDGET_DEFAULT_STRIP_HEIGHT_DP else PLAYBACK_WIDGET_DEFAULT_HEIGHT_DP,
    )
    return PlaybackWidgetSize(
        widthDp = width.coerceIn(1, 1_000),
        heightDp = height.coerceIn(1, 1_000),
    )
}

internal fun playbackWidgetSizeVariantsFromOptions(
    options: Bundle?,
    hasProgress: Boolean,
    isStrip: Boolean = false,
): List<PlaybackWidgetSize> {
    val fallback = playbackWidgetSizeFromOptions(options, hasProgress, isStrip)
    val landscape = PlaybackWidgetSize(
        widthDp = resolvePlaybackWidgetDimension(
            options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0,
            options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0,
            fallback.widthDp,
        ).coerceIn(1, 1_000),
        heightDp = resolvePlaybackWidgetDimension(
            options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0,
            options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0,
            fallback.heightDp,
        ).coerceIn(1, 1_000),
    )
    val boundsSizes = listOf(fallback, landscape).distinct()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return boundsSizes
    @Suppress("DEPRECATION")
    val hostSizes = options?.getParcelableArrayList<SizeF>(
        AppWidgetManager.OPTION_APPWIDGET_SIZES,
    ).orEmpty()
    return hostSizes.mapNotNull { size ->
        if (!size.width.isFinite() || !size.height.isFinite()) return@mapNotNull null
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        PlaybackWidgetSize(width.coerceAtMost(1_000), height.coerceAtMost(1_000))
            .takeIf { width > 0 && height > 0 }
    }.distinct().take(8).ifEmpty { boundsSizes }
}

internal fun playbackWidgetLayoutSpec(
    size: PlaybackWidgetSize,
    hasProgress: Boolean,
): PlaybackWidgetLayoutSpec {
    if (hasProgress) {
        val expanded = shouldUseExpandedFullPlaybackWidgetLayout(size)
        val topPaddingDp = if (expanded) 12 else 8
        val bottomPaddingDp = if (expanded) 8 else 4
        val controlsHeightDp = 44
        val progressRowHeightDp = if (expanded) 20 else 14
        val headerGapDp = if (expanded) 12 else 4
        val progressGapDp = if (expanded) 8 else 4
        val availableArtworkHeightDp = size.heightDp - topPaddingDp - bottomPaddingDp -
            controlsHeightDp - progressRowHeightDp - headerGapDp - progressGapDp
        val preferredArtworkSizeDp = minOf(size.widthDp * 0.25f, size.heightDp * 0.40f)
            .roundToInt().coerceAtMost(80)
        return PlaybackWidgetLayoutSpec(
            cardHeightDp = fullPlaybackWidgetCardHeightDp(size),
            horizontalPaddingDp = if (expanded) 16 else 14,
            topPaddingDp = topPaddingDp,
            bottomPaddingDp = bottomPaddingDp,
            albumSizeDp = minOf(availableArtworkHeightDp, preferredArtworkSizeDp).coerceAtLeast(28),
            albumGapDp = 16,
            controlsHeightDp = controlsHeightDp,
            controlSizeDp = 24,
            primaryControlSizeDp = 36,
            controlGapDp = 0,
            usesFullWidthControls = true,
            progressRowHeightDp = progressRowHeightDp,
            progressLabelWidthDp = 36,
            progressMarginDp = 8,
            compactInfoTopPaddingDp = 0,
            compactInfoBottomPaddingDp = 0,
            compactControlBottomMarginDp = 0,
            statusTextSizeSp = 11f,
            titleTextSizeSp = if (expanded) 18f else 16f,
            subtitleTextSizeSp = 12f,
        )
    }

    val width = size.widthDp.coerceAtLeast(PLAYBACK_WIDGET_DEFAULT_COMPACT_WIDTH_DP)
    val height = size.heightDp.coerceAtLeast(PLAYBACK_WIDGET_DEFAULT_HEIGHT_DP)
    val titleSize = scaledFloat(height * 0.02f + 13f, min = 16f, max = 20f)
    val secondarySize = scaledFloat(height * 0.012f + 9.5f, min = 11f, max = 13f)
    val compactHorizontalMargin = (
        scaledInt(minOf(width, height) * 0.09f, 10, 20) - 9
    ).coerceAtLeast(0)
    val compactControlGap = scaledInt(minOf(width, height) * 0.03f, 4, 8)
    return PlaybackWidgetLayoutSpec(
        cardHeightDp = 0,
        horizontalPaddingDp = if (width < 150) 6 else compactHorizontalMargin + 9,
        topPaddingDp = 8,
        bottomPaddingDp = 6,
        albumSizeDp = 0,
        albumGapDp = 0,
        controlsHeightDp = 44,
        controlSizeDp = 28,
        primaryControlSizeDp = 36,
        controlGapDp = compactControlGap,
        usesFullWidthControls = false,
        progressRowHeightDp = 0,
        progressLabelWidthDp = 0,
        progressMarginDp = 0,
        compactInfoTopPaddingDp = scaledInt(height * 0.08f, 8, 16),
        compactInfoBottomPaddingDp = scaledInt(height * 0.05f, 5, 12),
        compactControlBottomMarginDp = scaledInt(height * 0.035f, 4, 10),
        statusTextSizeSp = secondarySize,
        titleTextSizeSp = titleSize,
        subtitleTextSizeSp = secondarySize,
    )
}

internal fun fullPlaybackWidgetCardHeightDp(size: PlaybackWidgetSize): Int {
    return size.heightDp.coerceAtLeast(1)
}

internal fun shouldUseExpandedFullPlaybackWidgetLayout(
    size: PlaybackWidgetSize,
): Boolean {
    return size.heightDp >= PLAYBACK_WIDGET_FULL_CARD_MIN_HEIGHT_DP && size.widthDp >= 240
}

internal fun resolvePlaybackWidgetDimension(
    minDp: Int,
    maxDp: Int,
    defaultDp: Int,
): Int {
    return minDp.takeIf { it > 0 } ?: maxDp.takeIf { it > 0 } ?: defaultDp
}

private fun scaledInt(value: Float, min: Int, max: Int): Int {
    return value.roundToInt().coerceIn(min, max)
}

private fun scaledFloat(value: Float, min: Float, max: Float): Float {
    return value.coerceIn(min, max)
}
