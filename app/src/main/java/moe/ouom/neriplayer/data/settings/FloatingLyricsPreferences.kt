package moe.ouom.neriplayer.data.settings

import java.util.Locale

const val FLOATING_LYRICS_ALIGNMENT_LEFT = "left"
const val FLOATING_LYRICS_ALIGNMENT_CENTER = "center"
const val FLOATING_LYRICS_ALIGNMENT_RIGHT = "right"
const val FLOATING_LYRICS_TRANSLATION_STYLE_SCALE = 0.72f

const val MIN_FLOATING_LYRICS_FONT_SIZE_SP = 8f
const val MAX_FLOATING_LYRICS_FONT_SIZE_SP = 32f

const val MIN_FLOATING_LYRICS_OUTLINE_WIDTH_DP = 0f
const val MAX_FLOATING_LYRICS_OUTLINE_WIDTH_DP = 4.0f

const val MIN_FLOATING_LYRICS_ALPHA = 0f
const val MAX_FLOATING_LYRICS_ALPHA = 1f

const val MIN_FLOATING_LYRICS_MAX_WIDTH_DP = 80f
const val MAX_FLOATING_LYRICS_MAX_WIDTH_DP = 420f

private const val DEFAULT_FLOATING_LYRICS_TEXT_COLOR = "FFFFFF"
private const val DEFAULT_FLOATING_LYRICS_OUTLINE_COLOR = "121212"
private const val DEFAULT_FLOATING_LYRICS_FONT_SIZE_SP = 22f
private const val DEFAULT_FLOATING_LYRICS_OUTLINE_WIDTH_DP = 1.6f
private const val DEFAULT_FLOATING_LYRICS_TRANSLATION_ALPHA = 0.72f
private const val DEFAULT_FLOATING_LYRICS_MAX_WIDTH_DP = 280f
private const val DEFAULT_FLOATING_LYRICS_POSITION_X = 0.10f
private const val DEFAULT_FLOATING_LYRICS_POSITION_Y = 0.70f
private const val LEGACY_MIN_FLOATING_LYRICS_TRANSLATION_OUTLINE_WIDTH_DP = 0.3f

private val HEX_COLOR_REGEX = Regex("^[0-9A-F]{6}$")

data class FloatingLyricsPreferences(
    val enabled: Boolean = false,
    val hideInApp: Boolean = false,
    val textColorHex: String = DEFAULT_FLOATING_LYRICS_TEXT_COLOR,
    val outlineColorHex: String = DEFAULT_FLOATING_LYRICS_OUTLINE_COLOR,
    val fontSizeSp: Float = DEFAULT_FLOATING_LYRICS_FONT_SIZE_SP,
    val outlineWidthDp: Float = DEFAULT_FLOATING_LYRICS_OUTLINE_WIDTH_DP,
    val translationOutlineWidthDp: Float = defaultFloatingLyricsTranslationOutlineWidthDp(),
    val translationAlpha: Float = DEFAULT_FLOATING_LYRICS_TRANSLATION_ALPHA,
    val maxWidthDp: Float = DEFAULT_FLOATING_LYRICS_MAX_WIDTH_DP,
    val positionX: Float = DEFAULT_FLOATING_LYRICS_POSITION_X,
    val positionY: Float = DEFAULT_FLOATING_LYRICS_POSITION_Y,
    val alignment: String = FLOATING_LYRICS_ALIGNMENT_CENTER,
    val showTranslation: Boolean = true
) {
    fun normalized(): FloatingLyricsPreferences {
        return copy(
            textColorHex = normalizeFloatingLyricsColorHex(textColorHex),
            outlineColorHex = normalizeFloatingLyricsColorHex(outlineColorHex),
            fontSizeSp = normalizeFloatingLyricsFontSizeSp(fontSizeSp),
            outlineWidthDp = normalizeFloatingLyricsOutlineWidthDp(outlineWidthDp),
            translationOutlineWidthDp = normalizeFloatingLyricsOutlineWidthDp(translationOutlineWidthDp),
            translationAlpha = normalizeFloatingLyricsAlpha(translationAlpha),
            maxWidthDp = normalizeFloatingLyricsMaxWidthDp(maxWidthDp),
            positionX = normalizeFloatingLyricsPosition(positionX),
            positionY = normalizeFloatingLyricsPosition(positionY),
            alignment = normalizeFloatingLyricsAlignment(alignment)
        )
    }
}

fun normalizeFloatingLyricsFontSizeSp(value: Float): Float =
    value.coerceIn(MIN_FLOATING_LYRICS_FONT_SIZE_SP, MAX_FLOATING_LYRICS_FONT_SIZE_SP)

fun normalizeFloatingLyricsOutlineWidthDp(value: Float): Float =
    value.coerceIn(MIN_FLOATING_LYRICS_OUTLINE_WIDTH_DP, MAX_FLOATING_LYRICS_OUTLINE_WIDTH_DP)

fun normalizeFloatingLyricsAlpha(value: Float): Float {
    return if (value.isNaN() || value.isInfinite()) {
        DEFAULT_FLOATING_LYRICS_TRANSLATION_ALPHA
    } else {
        value.coerceIn(MIN_FLOATING_LYRICS_ALPHA, MAX_FLOATING_LYRICS_ALPHA)
    }
}

fun resolveFloatingLyricsTranslationOutlineWidthDp(
    value: Float?,
    outlineWidthDp: Float
): Float {
    return if (value == null || value.isNaN() || value.isInfinite()) {
        defaultFloatingLyricsTranslationOutlineWidthDp(outlineWidthDp)
    } else {
        normalizeFloatingLyricsOutlineWidthDp(value)
    }
}

fun resolveFloatingLyricsTranslationAlpha(value: Float?): Float {
    return if (value == null || value.isNaN() || value.isInfinite()) {
        DEFAULT_FLOATING_LYRICS_TRANSLATION_ALPHA
    } else {
        normalizeFloatingLyricsAlpha(value)
    }
}

fun normalizeFloatingLyricsMaxWidthDp(value: Float): Float =
    value.coerceIn(MIN_FLOATING_LYRICS_MAX_WIDTH_DP, MAX_FLOATING_LYRICS_MAX_WIDTH_DP)

fun normalizeFloatingLyricsPosition(value: Float): Float =
    value.coerceIn(0f, 1f)

fun normalizeFloatingLyricsAlignment(value: String?): String {
    return when (value?.trim()?.lowercase(Locale.ROOT)) {
        FLOATING_LYRICS_ALIGNMENT_LEFT -> FLOATING_LYRICS_ALIGNMENT_LEFT
        FLOATING_LYRICS_ALIGNMENT_RIGHT -> FLOATING_LYRICS_ALIGNMENT_RIGHT
        else -> FLOATING_LYRICS_ALIGNMENT_CENTER
    }
}

fun normalizeFloatingLyricsColorHex(value: String?): String {
    val normalized = value
        ?.trim()
        ?.removePrefix("#")
        ?.uppercase(Locale.ROOT)
        .orEmpty()
    return normalized.takeIf { HEX_COLOR_REGEX.matches(it) }
        ?: DEFAULT_FLOATING_LYRICS_TEXT_COLOR
}

private fun defaultFloatingLyricsTranslationOutlineWidthDp(
    outlineWidthDp: Float = DEFAULT_FLOATING_LYRICS_OUTLINE_WIDTH_DP
): Float {
    val normalizedOutlineWidth = normalizeFloatingLyricsOutlineWidthDp(outlineWidthDp)
    if (normalizedOutlineWidth <= 0f) {
        return 0f
    }
    return normalizeFloatingLyricsOutlineWidthDp(
        (normalizedOutlineWidth * FLOATING_LYRICS_TRANSLATION_STYLE_SCALE)
            .coerceAtLeast(LEGACY_MIN_FLOATING_LYRICS_TRANSLATION_OUTLINE_WIDTH_DP)
    )
}
