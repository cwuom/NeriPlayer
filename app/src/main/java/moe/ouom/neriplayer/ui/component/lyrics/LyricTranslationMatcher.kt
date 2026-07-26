package moe.ouom.neriplayer.ui.component.lyrics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TranslationAlignmentToleranceMs = 450L
private const val TranslationClosestMatchToleranceMs = 2_000L

/**
 * 按时间戳把翻译行配对到原文行
 *
 * 两个关键点:
 * 1. 多行共享同一时间戳时 (网易云常见: 作词/作曲/出品/营销/OP 等元数据行与正文行同处一个时间戳)
 * 翻译要向下对齐到组内靠后的正文行, 而不是被最靠前的元数据行"窃取", 否则整体翻译会错位一行
 * 2. 制作信息/元数据行本身不是翻译, 先过滤掉, 避免没有真正翻译的歌把 credits 当成翻译显示
 */
internal fun matchTranslationsToLineIndices(
    lines: List<LyricEntry>,
    translations: List<LyricEntry>,
    toleranceMs: Long = TranslationAlignmentToleranceMs
): Map<Int, LyricEntry> {
    if (lines.isEmpty() || translations.isEmpty()) {
        return emptyMap()
    }

    // 过滤制作信息行: 真正没有翻译时不产生任何配对
    val effectiveTranslations = translations.filterNot { isLyricCreditMetadataLine(it.text) }
    if (effectiveTranslations.isEmpty()) {
        return emptyMap()
    }

    val matchesByIndex = linkedMapOf<Int, LyricEntry>()
    var translationIndex = 0
    var lineIndex = 0

    while (lineIndex < lines.size && translationIndex < effectiveTranslations.size) {
        // 找到与当前行起始时间相同的一组行; 组内最后一行的时间覆盖到下一时间戳, 最能代表这个时间区间
        val groupStartMs = lines[lineIndex].startTimeMs
        var groupEndExclusive = lineIndex
        while (groupEndExclusive < lines.size && lines[groupEndExclusive].startTimeMs == groupStartMs) {
            groupEndExclusive++
        }
        val groupSize = groupEndExclusive - lineIndex
        val representativeLine = lines[groupEndExclusive - 1]
        val nextLine = lines.getOrNull(groupEndExclusive)

        // 收集应归属这一组的翻译, 最多 groupSize 条, 并保持时间顺序
        val groupTranslations = ArrayList<LyricEntry>(groupSize)
        while (
            translationIndex < effectiveTranslations.size &&
            groupTranslations.size < groupSize
        ) {
            val decision = decideTranslationForLine(
                line = representativeLine,
                nextLine = nextLine,
                translation = effectiveTranslations[translationIndex],
                toleranceMs = toleranceMs
            )
            when (decision) {
                TranslationLineDecision.SKIP_STALE -> translationIndex++
                TranslationLineDecision.MATCH -> {
                    groupTranslations.add(effectiveTranslations[translationIndex])
                    translationIndex++
                }
                TranslationLineDecision.STOP -> break
            }
        }

        // 向下对齐: 翻译分配给组内靠后的行, 靠前的元数据行不参与, 避免整体错位一行
        val matchedCount = groupTranslations.size
        for (offset in 0 until matchedCount) {
            matchesByIndex[groupEndExclusive - matchedCount + offset] = groupTranslations[offset]
        }

        lineIndex = groupEndExclusive
    }

    return matchesByIndex
}

private enum class TranslationLineDecision {
    /** 翻译远早于当前行且无重叠, 跳过这条翻译继续比较 */
    SKIP_STALE,

    /** 翻译归属当前行 */
    MATCH,

    /** 翻译应留给后续行 */
    STOP
}

private fun decideTranslationForLine(
    line: LyricEntry,
    nextLine: LyricEntry?,
    translation: LyricEntry,
    toleranceMs: Long
): TranslationLineDecision {
    val currentDistanceMs = calculateStartDistanceToLineMs(
        timestampMs = translation.startTimeMs,
        lineStartMs = line.startTimeMs,
        lineEndMs = line.endTimeMs
    )
    val nextDistanceMs = nextLine?.let { next ->
        calculateStartDistanceToLineMs(
            timestampMs = translation.startTimeMs,
            lineStartMs = next.startTimeMs,
            lineEndMs = next.endTimeMs
        )
    } ?: Long.MAX_VALUE
    val currentOverlapMs = calculateIntervalOverlapMs(
        firstStartMs = line.startTimeMs,
        firstEndMs = normalizeExclusiveEndTime(line.startTimeMs, line.endTimeMs),
        secondStartMs = translation.startTimeMs,
        secondEndMs = normalizeExclusiveEndTime(translation.startTimeMs, translation.endTimeMs)
    )
    val nextOverlapMs = nextLine?.let { next ->
        calculateIntervalOverlapMs(
            firstStartMs = next.startTimeMs,
            firstEndMs = normalizeExclusiveEndTime(next.startTimeMs, next.endTimeMs),
            secondStartMs = translation.startTimeMs,
            secondEndMs = normalizeExclusiveEndTime(translation.startTimeMs, translation.endTimeMs)
        )
    } ?: 0L

    // 翻译远早于当前行且没有重叠时才丢弃
    val shouldSkipStaleTranslation = translation.startTimeMs < line.startTimeMs &&
        currentDistanceMs > TranslationClosestMatchToleranceMs &&
        currentOverlapMs <= 0L
    if (shouldSkipStaleTranslation) {
        return TranslationLineDecision.SKIP_STALE
    }

    // 先看真实播放区间, 避免翻译提前一两秒时被误判为上一句
    val shouldMatchByOverlap = currentOverlapMs > 0L &&
        currentOverlapMs >= nextOverlapMs

    // 严格匹配: 在容差内且离当前行最近
    // 宽松匹配: 翻译在当前行之前但在宽松容差内, 且离当前行比下一行近
    val shouldMatchCurrentLine =
        shouldMatchByOverlap ||
        (currentDistanceMs <= toleranceMs && currentDistanceMs <= nextDistanceMs) ||
        (currentDistanceMs <= TranslationClosestMatchToleranceMs && currentDistanceMs <= nextDistanceMs)

    return if (shouldMatchCurrentLine) {
        TranslationLineDecision.MATCH
    } else {
        TranslationLineDecision.STOP
    }
}

private val LyricCreditMetadataRegex = Regex("""^\s*([\p{L}·]{1,12})\s*[:：]\s*\S""")

// 网易云等平台常见的制作信息角色, 用于识别"非翻译"的元数据行
private val LyricCreditMetadataRoles = setOf(
    "作词", "作詞", "作曲", "编曲", "編曲", "制作人", "製作人", "制作", "製作",
    "出品", "出品人", "联合出品", "聯合出品", "营销", "營銷", "策划", "策劃",
    "企划", "企劃", "监制", "監製", "统筹", "統籌", "发行", "發行", "混音",
    "母带", "母帶", "录音", "錄音", "和声", "和聲", "和音", "配唱", "演唱",
    "原唱", "词", "詞", "曲", "吉他", "贝斯", "貝斯", "鼓", "键盘", "鍵盤",
    "弦乐", "弦樂", "录音师", "錄音師", "混音师", "混音師", "母带工程师",
    "制作公司", "版权", "版權", "鸣谢", "鳴謝", "特别鸣谢", "特別鳴謝",
    "op", "sp", "lyricist", "composer", "arranger", "producer", "mixing",
    "mastering", "recording", "vocal", "guitar", "bass", "drums", "keyboard",
    "strings"
)

/**
 * 判断歌词行是否为制作信息/元数据行 (如"作词 : 罗言""OP: 唯迹文化")
 *
 * 仅当分隔符前是已知制作角色时才判定为元数据, 避免把含冒号的正常歌词误删
 */
internal fun isLyricCreditMetadataLine(text: String): Boolean {
    val role = LyricCreditMetadataRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.lowercase()
        ?: return false
    return role in LyricCreditMetadataRoles
}

internal fun findBestMatchingTranslation(
    translations: List<LyricEntry>,
    lineStartMs: Long,
    lineEndMs: Long,
    toleranceMs: Long = 1_500L
): LyricEntry? {
    if (translations.isEmpty()) {
        return null
    }

    val normalizedLineEndMs = normalizeExclusiveEndTime(lineStartMs, lineEndMs)
    var bestOverlappingTranslation: LyricEntry? = null
    var bestOverlapMs = 0L
    var bestStartDeltaMs = Long.MAX_VALUE

    translations.forEach { candidate ->
        val candidateEndMs = normalizeExclusiveEndTime(
            candidate.startTimeMs,
            candidate.endTimeMs
        )
        val overlapMs = calculateIntervalOverlapMs(
            firstStartMs = lineStartMs,
            firstEndMs = normalizedLineEndMs,
            secondStartMs = candidate.startTimeMs,
            secondEndMs = candidateEndMs
        )
        if (overlapMs <= 0L) {
            return@forEach
        }

        val startDeltaMs = abs(candidate.startTimeMs - lineStartMs)
        val shouldReplaceBest = overlapMs > bestOverlapMs ||
            (overlapMs == bestOverlapMs && startDeltaMs < bestStartDeltaMs) ||
            (
                overlapMs == bestOverlapMs &&
                    startDeltaMs == bestStartDeltaMs &&
                    candidate.startTimeMs < (bestOverlappingTranslation?.startTimeMs ?: Long.MAX_VALUE)
                )
        if (shouldReplaceBest) {
            bestOverlappingTranslation = candidate
            bestOverlapMs = overlapMs
            bestStartDeltaMs = startDeltaMs
        }
    }

    if (bestOverlappingTranslation != null) {
        return bestOverlappingTranslation
    }

    val nearestTranslation = translations.minWithOrNull(
        compareBy<LyricEntry> { abs(it.startTimeMs - lineStartMs) }
            .thenBy { it.startTimeMs }
    )
    return nearestTranslation?.takeIf {
        abs(it.startTimeMs - lineStartMs) <= toleranceMs
    }
}

private fun calculateIntervalOverlapMs(
    firstStartMs: Long,
    firstEndMs: Long,
    secondStartMs: Long,
    secondEndMs: Long
): Long {
    return min(firstEndMs, secondEndMs) - max(firstStartMs, secondStartMs)
}

private fun calculateStartDistanceToLineMs(
    timestampMs: Long,
    lineStartMs: Long,
    lineEndMs: Long
): Long {
    val normalizedLineEndMs = normalizeExclusiveEndTime(lineStartMs, lineEndMs)
    return when {
        timestampMs < lineStartMs -> lineStartMs - timestampMs
        timestampMs >= normalizedLineEndMs -> timestampMs - normalizedLineEndMs + 1L
        else -> 0L
    }
}

private fun normalizeExclusiveEndTime(startMs: Long, endMs: Long): Long {
    return if (endMs > startMs) endMs else startMs + 1L
}
