package moe.ouom.neriplayer.core.player.service.lyrics

import java.text.BreakIterator

/**
 * 状态栏胶囊上限约 96dp，Android 17 实测默认字号下文字区能完整放下 5 个汉字宽；
 * 放不下时系统不会截断而是只显示图标，所以必须按显示宽度预先截好。
 */
internal const val LIVE_UPDATE_CHIP_MAX_WIDTH_EM = 5f
private const val LIVE_UPDATE_CHIP_WIDTH_EPSILON_EM = 0.01f
private const val LIVE_UPDATE_CHIP_ELLIPSIS = "…"

private val liveUpdateWhitespace = Regex("\\s+")

/** 以 em 为单位的文字宽度；生产环境用 TextPaint 实测，测试用固定字宽 */
internal fun interface LiveUpdateTextMeasurer {
    fun widthEm(text: String): Float
}

/** 压缩换行与连续空白；空白文本返回 null */
internal fun normalizeLiveUpdateText(text: String?): String? =
    text?.replace(liveUpdateWhitespace, " ")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 按显示宽度截断胶囊文字：同样 7 个字符，汉字约是拉丁字母的两倍宽。
 * 按字素截断以免拆开 emoji 与组合字符；一个字素加省略号都放不下时返回 null，交给系统计时器。
 */
internal fun fitLiveUpdateChipText(
    text: String?,
    maxWidthEm: Float,
    measurer: LiveUpdateTextMeasurer,
): String? {
    val normalized = normalizeLiveUpdateText(text) ?: return null
    val limit = maxWidthEm + LIVE_UPDATE_CHIP_WIDTH_EPSILON_EM
    if (measurer.widthEm(normalized) <= limit) return normalized
    val boundaries = BreakIterator.getCharacterInstance().apply { setText(normalized) }
    var fitted: String? = null
    var end = boundaries.next()
    while (end != BreakIterator.DONE && end < normalized.length) {
        val candidate = normalized.substring(0, end).trimEnd() + LIVE_UPDATE_CHIP_ELLIPSIS
        if (measurer.widthEm(candidate) > limit) break
        fitted = candidate
        end = boundaries.next()
    }
    return fitted
}
