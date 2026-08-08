package moe.ouom.neriplayer.widget

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import kotlin.math.roundToInt

internal const val PLAYBACK_WIDGET_DEFAULT_FULL_WIDTH_DP = 250
internal const val PLAYBACK_WIDGET_DEFAULT_COMPACT_WIDTH_DP = 110
internal const val PLAYBACK_WIDGET_DEFAULT_HEIGHT_DP = 110
internal const val PLAYBACK_WIDGET_FULL_CARD_MIN_HEIGHT_DP = 170
internal const val PLAYBACK_WIDGET_FULL_CARD_MAX_HEIGHT_DP = 180
private const val PLAYBACK_WIDGET_FULL_CARD_ASPECT_RATIO = 2.05f

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
): PlaybackWidgetSize {
    val defaultWidth = if (hasProgress) {
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
        minDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0,
        maxDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0,
        defaultDp = PLAYBACK_WIDGET_DEFAULT_HEIGHT_DP,
    )
    return PlaybackWidgetSize(
        widthDp = width.coerceIn(1, 1_000),
        heightDp = height.coerceIn(1, 1_000),
    )
}

internal fun playbackWidgetSizeVariantsFromOptions(
    options: Bundle?,
    hasProgress: Boolean,
): List<PlaybackWidgetSize> {
    val fallback = playbackWidgetSizeFromOptions(options, hasProgress)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return listOf(fallback)
    }
    @Suppress("DEPRECATION")
    val hostSizes = options?.getParcelableArrayList<SizeF>(
        AppWidgetManager.OPTION_APPWIDGET_SIZES,
    ).orEmpty()
    return hostSizes.mapNotNull { size ->
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        PlaybackWidgetSize(width, height).takeIf { width > 0 && height > 0 }
    }.distinct().take(8).ifEmpty { listOf(fallback) }
}

internal fun playbackWidgetLayoutSpec(
    size: PlaybackWidgetSize,
    hasProgress: Boolean,
): PlaybackWidgetLayoutSpec {
    if (hasProgress) {
        return PlaybackWidgetLayoutSpec(
            cardHeightDp = fullPlaybackWidgetCardHeightDp(size),
            horizontalPaddingDp = 14,
            topPaddingDp = 20,
            bottomPaddingDp = 8,
            albumSizeDp = 80,
            albumGapDp = 16,
            controlsHeightDp = 48,
            controlSizeDp = 30,
            primaryControlSizeDp = 48,
            controlGapDp = 0,
            usesFullWidthControls = true,
            progressRowHeightDp = 14,
            progressLabelWidthDp = 36,
            progressMarginDp = 8,
            compactInfoTopPaddingDp = 0,
            compactInfoBottomPaddingDp = 0,
            compactControlBottomMarginDp = 0,
            statusTextSizeSp = 11f,
            titleTextSizeSp = 18f,
            subtitleTextSizeSp = 13f,
        )
    }

    val width = size.widthDp.coerceAtLeast(PLAYBACK_WIDGET_DEFAULT_COMPACT_WIDTH_DP)
    val height = size.heightDp.coerceAtLeast(PLAYBACK_WIDGET_DEFAULT_HEIGHT_DP)
    val titleSize = scaledFloat(height * 0.02f + 13f, min = 16f, max = 20f)
    val secondarySize = scaledFloat(height * 0.012f + 9.5f, min = 11f, max = 13f)
    val compactControlHeight = scaledInt(height * 0.32f, min = 36, max = 52)
    val compactHorizontalMargin = (
        scaledInt(minOf(width, height) * 0.09f, 10, 20) - 9
    ).coerceAtLeast(0)
    val compactControlGap = scaledInt(minOf(width, height) * 0.03f, 4, 8)
    val compactControlWidth = width - compactHorizontalMargin * 2
    val maximumCompactControlSize = (
        compactControlWidth / 3f - compactControlGap
    ).toInt()
    val compactControlSize = minOf(
        scaledInt(compactControlHeight * 0.80f, min = 28, max = 42),
        maximumCompactControlSize,
    ).coerceAtLeast(24)
    return PlaybackWidgetLayoutSpec(
        cardHeightDp = 0,
        horizontalPaddingDp = compactHorizontalMargin + 9,
        topPaddingDp = 0,
        bottomPaddingDp = 0,
        albumSizeDp = 0,
        albumGapDp = 0,
        controlsHeightDp = compactControlHeight,
        controlSizeDp = compactControlSize,
        primaryControlSizeDp = (compactControlSize + 4).coerceAtMost(44),
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
    return (size.widthDp / PLAYBACK_WIDGET_FULL_CARD_ASPECT_RATIO)
        .roundToInt()
        .coerceIn(
            PLAYBACK_WIDGET_FULL_CARD_MIN_HEIGHT_DP,
            PLAYBACK_WIDGET_FULL_CARD_MAX_HEIGHT_DP,
        )
}

internal fun shouldUseExpandedFullPlaybackWidgetLayout(
    size: PlaybackWidgetSize,
    sdkInt: Int,
): Boolean {
    val minimumHeightDp = if (sdkInt >= Build.VERSION_CODES.S) {
        PLAYBACK_WIDGET_FULL_CARD_MIN_HEIGHT_DP
    } else {
        PLAYBACK_WIDGET_FULL_CARD_MAX_HEIGHT_DP
    }
    return size.heightDp >= minimumHeightDp
}

internal fun resolvePlaybackWidgetDimension(
    minDp: Int,
    maxDp: Int,
    defaultDp: Int,
): Int {
    return maxOf(minDp, maxDp).takeIf { it > 0 } ?: defaultDp
}

private fun scaledInt(value: Float, min: Int, max: Int): Int {
    return value.roundToInt().coerceIn(min, max)
}

private fun scaledFloat(value: Float, min: Float, max: Float): Float {
    return value.coerceIn(min, max)
}
