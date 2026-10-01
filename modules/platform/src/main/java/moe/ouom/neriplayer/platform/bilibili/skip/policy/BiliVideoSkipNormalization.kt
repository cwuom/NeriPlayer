package moe.ouom.neriplayer.platform.bilibili.skip.policy

import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipDraft
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipInterval
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipRule
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTarget
import moe.ouom.neriplayer.data.model.bilibili.skip.MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH
import moe.ouom.neriplayer.data.model.bilibili.skip.MAX_BILI_VIDEO_SKIP_INTERVALS
import moe.ouom.neriplayer.data.model.bilibili.skip.MAX_BILI_VIDEO_SKIP_RULES

fun normalizeBiliVideoSkipIntervals(
    intervals: Iterable<BiliVideoSkipInterval>,
    durationMs: Long = 0L
): List<BiliVideoSkipInterval> {
    val maxEndMs = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    val sorted = intervals.asSequence()
        .map { interval ->
            BiliVideoSkipInterval(
                startMs = interval.startMs.coerceAtLeast(0L),
                endMs = interval.endMs.coerceAtMost(maxEndMs)
            )
        }
        .filter { interval -> interval.endMs > interval.startMs }
        .sortedWith(compareBy<BiliVideoSkipInterval> { it.startMs }.thenBy { it.endMs })
        .take(MAX_BILI_VIDEO_SKIP_INTERVALS)
        .toList()
    if (sorted.isEmpty()) return emptyList()

    val normalized = ArrayList<BiliVideoSkipInterval>(sorted.size)
    sorted.forEach { candidate ->
        val previous = normalized.lastOrNull()
        if (previous != null && candidate.startMs <= previous.endMs) {
            normalized[normalized.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, candidate.endMs))
        } else {
            normalized += candidate
        }
    }
    return normalized
}

internal fun normalizeBiliVideoSkipRules(
    rules: Iterable<BiliVideoSkipRule>
): List<BiliVideoSkipRule> {
    val normalizedByTarget = linkedMapOf<String, BiliVideoSkipRule>()
    rules.forEach { rule ->
        val target = rule.target.normalizedOrNull() ?: return@forEach
        val normalized = rule.copy(
            target = target,
            intervals = if (rule.isDeleted) {
                emptyList()
            } else {
                normalizeBiliVideoSkipIntervals(rule.intervals)
            },
            modifiedAt = rule.modifiedAt.coerceAtLeast(0L)
        )
        if (!normalized.isDeleted && normalized.intervals.isEmpty()) return@forEach

        val key = target.stableKey()
        val existing = normalizedByTarget[key]
        normalizedByTarget[key] = when {
            existing == null -> normalized
            existing.modifiedAt > normalized.modifiedAt -> existing
            normalized.modifiedAt > existing.modifiedAt -> normalized
            existing.isDeleted && !normalized.isDeleted -> normalized
            !existing.isDeleted && normalized.isDeleted -> existing
            existing.isDeleted -> existing
            else -> existing.copy(
                intervals = normalizeBiliVideoSkipIntervals(existing.intervals + normalized.intervals)
            )
        }
    }
    return normalizedByTarget.values
        .sortedWith(compareBy<BiliVideoSkipRule> { it.target.bvid }.thenBy { it.target.cid })
        .take(MAX_BILI_VIDEO_SKIP_RULES)
}

internal fun intervalsForBiliVideoSkipCid(
    rules: Iterable<BiliVideoSkipRule>,
    cid: Long
): List<BiliVideoSkipInterval> {
    if (cid <= 0L) return emptyList()
    val matchingRule = rules.singleOrNull { rule -> rule.target.cid == cid }
    return matchingRule
        ?.takeUnless { it.isDeleted }
        ?.intervals
        .orEmpty()
}

internal fun intervalsForBiliVideoSkipPlayback(
    rules: Iterable<BiliVideoSkipRule>,
    target: BiliVideoSkipTarget?,
    fallbackCid: Long?,
    fallbackBvid: String? = null
): List<BiliVideoSkipInterval> {
    val normalizedTarget = target?.normalizedOrNull()
    if (normalizedTarget != null) {
        val exactRule = rules.firstOrNull { rule -> rule.target == normalizedTarget }
        if (exactRule != null) {
            return if (exactRule.isDeleted) emptyList() else exactRule.intervals
        }
        return emptyList()
    }
    val cidIntervals = intervalsForBiliVideoSkipCid(rules = rules, cid = fallbackCid ?: 0L)
    if (cidIntervals.isNotEmpty() || fallbackCid != null) {
        return cidIntervals
    }
    val normalizedBvid = fallbackBvid?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val matchingRule = rules.singleOrNull { rule -> rule.target.bvid == normalizedBvid }
    return matchingRule
        ?.takeUnless { it.isDeleted }
        ?.intervals
        .orEmpty()
}

internal fun normalizeBiliVideoSkipDrafts(
    drafts: Iterable<BiliVideoSkipDraft>
): List<BiliVideoSkipDraft> {
    val normalizedByTarget = linkedMapOf<String, BiliVideoSkipDraft>()
    drafts.forEach { draft ->
        val target = draft.target.normalizedOrNull() ?: return@forEach
        val normalizedStartText = draft.startText.trim().take(MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH)
        val normalizedEndText = draft.endText.trim().take(MAX_BILI_VIDEO_SKIP_DRAFT_TEXT_LENGTH)
        if (normalizedStartText.isEmpty() && normalizedEndText.isEmpty()) return@forEach

        val normalized = draft.copy(
            target = target,
            startText = normalizedStartText,
            endText = normalizedEndText,
            modifiedAt = draft.modifiedAt.coerceAtLeast(0L)
        )
        val key = target.stableKey()
        val existing = normalizedByTarget[key]
        if (existing == null || normalized.modifiedAt >= existing.modifiedAt) {
            normalizedByTarget[key] = normalized
        }
    }
    return normalizedByTarget.values
        .sortedWith(compareBy<BiliVideoSkipDraft> { it.target.bvid }.thenBy { it.target.cid })
        .take(MAX_BILI_VIDEO_SKIP_RULES)
}
