package moe.ouom.neriplayer.data.sync.policy

import moe.ouom.neriplayer.data.model.sync.SyncBiliVideoSkipInterval
import moe.ouom.neriplayer.data.model.sync.SyncBiliVideoSkipRule
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipInterval
import moe.ouom.neriplayer.data.model.bilibili.skip.MAX_BILI_VIDEO_SKIP_RULES
import moe.ouom.neriplayer.data.platform.bili.skip.policy.normalizeBiliVideoSkipIntervals

object SyncBiliVideoSkipMergePolicy {
    fun sanitize(rules: Iterable<SyncBiliVideoSkipRule>): List<SyncBiliVideoSkipRule> {
        val normalizedByTarget = linkedMapOf<String, SyncBiliVideoSkipRule>()
        rules.forEach { rule ->
            val normalized = normalizeRule(rule) ?: return@forEach
            val target = normalized.targetOrNull() ?: return@forEach
            val key = target.stableKey()
            normalizedByTarget[key] = normalizedByTarget[key]
                ?.let { existing -> mergeRule(existing, normalized) }
                ?: normalized
        }
        return normalizedByTarget.values
            .sortedWith(compareBy<SyncBiliVideoSkipRule> { it.bvid }.thenBy { it.cid })
            .take(MAX_BILI_VIDEO_SKIP_RULES)
    }

    fun merge(
        local: Iterable<SyncBiliVideoSkipRule>,
        remote: Iterable<SyncBiliVideoSkipRule>
    ): List<SyncBiliVideoSkipRule> = sanitize(local + remote)

    fun same(
        left: Iterable<SyncBiliVideoSkipRule>,
        right: Iterable<SyncBiliVideoSkipRule>
    ): Boolean {
        val normalizedLeft = sanitize(left)
        val normalizedRight = sanitize(right)
        if (normalizedLeft.size != normalizedRight.size) return false
        return normalizedLeft.zip(normalizedRight).all { (first, second) ->
            first.bvid == second.bvid &&
                first.cid == second.cid &&
                first.isDeleted == second.isDeleted &&
                first.intervals == second.intervals
        }
    }

    private fun normalizeRule(rule: SyncBiliVideoSkipRule): SyncBiliVideoSkipRule? {
        val target = rule.targetOrNull() ?: return null
        val normalizedIntervals = normalizeBiliVideoSkipIntervals(
            rule.intervals.map { interval ->
                BiliVideoSkipInterval(startMs = interval.startMs, endMs = interval.endMs)
            }
        ).map { interval ->
            SyncBiliVideoSkipInterval(startMs = interval.startMs, endMs = interval.endMs)
        }
        if (!rule.isDeleted && normalizedIntervals.isEmpty()) return null
        return rule.copy(
            bvid = target.bvid,
            cid = target.cid,
            intervals = if (rule.isDeleted) emptyList() else normalizedIntervals,
            modifiedAt = rule.modifiedAt.coerceAtLeast(0L)
        )
    }

    private fun mergeRule(
        left: SyncBiliVideoSkipRule,
        right: SyncBiliVideoSkipRule
    ): SyncBiliVideoSkipRule {
        if (left.modifiedAt > right.modifiedAt) return left
        if (right.modifiedAt > left.modifiedAt) return right
        if (left.isDeleted && right.isDeleted) return left
        if (left.isDeleted) return right
        if (right.isDeleted) return left
        return left.copy(
            intervals = normalizeBiliVideoSkipIntervals(
                left.intervals.map { BiliVideoSkipInterval(it.startMs, it.endMs) } +
                    right.intervals.map { BiliVideoSkipInterval(it.startMs, it.endMs) }
            ).map { interval ->
                SyncBiliVideoSkipInterval(interval.startMs, interval.endMs)
            }
        )
    }
}
