package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 心动模式本地降级评分器。
 *
 * 设计原则（任务书 §3.3 + §1.3）：
 * - 只对**本地降级候选**打分，官方接口返回的结果**绝不经过这里**，避免二次排序/二次过滤；
 * - 分数完全由层级优先级与候选自带权重决定，不引入随机数，
 *   保证同一候选池在同样上下文下得到同样的结果（可单测、可复现）；
 * - 已出现在当前队列里的歌曲会被直接排除，避免心动模式把队列变成少数几首歌的循环。
 */
object HeartbeatScorer {

    /** 层级基础分：层级每降一级，基础分按此比例衰减 */
    const val TIER_BASE_SCORE = 1000.0

    /**
     * 每次累计播放带来的额外衰减步长（任务书 §3.4「重复惩罚」）。
     *
     * 语义：播过 1 次的曲乘 0.85，2 次乘 0.7225……最低衰减到 [REPEAT_PENALTY_FLOOR]。
     * 之所以用「系数连乘」而不是「线性扣分」，是因为前者不会把候选压成负数，
     * 层级优先语义也不会被破坏。
     */
    const val REPEAT_PENALTY_STEP = 0.85

    /** 重复惩罚的下限，防止老歌被压到彻底选不出来 */
    const val REPEAT_PENALTY_FLOOR = 0.25

    /** 与当前歌曲同歌手的加分 */
    const val SAME_ARTIST_BONUS = 120.0

    /** 与当前歌曲同专辑的加分（低于同歌手，因为同专辑容易听腻） */
    const val SAME_ALBUM_BONUS = 60.0

    /** 歌曲时长低于此值时降权，避免心动模式频繁塞入极短音频 */
    const val SHORT_TRACK_THRESHOLD_MS = 60_000L

    /** 短音频扣分系数 */
    const val SHORT_TRACK_PENALTY = 0.8

    /**
     * 计算单条候选的分数。分数越高越优先。
     *
     * @param candidate 待评分候选
     * @param currentSong 当前正在播放的歌曲，可为 null（此时不给同歌手/同专辑加分）
     */
    fun score(candidate: HeartbeatCandidate, currentSong: SongItem?): Double {
        val tier = candidate.tier.coerceIn(
            HeartbeatCandidateTier.FAVORITE,
            HeartbeatCandidateTier.RANDOM
        )
        // 层级越高（数值越小）基础分越高
        val tierBase = TIER_BASE_SCORE * (HeartbeatCandidateTier.RANDOM - tier + 1)
        var result = tierBase * candidate.weight.coerceAtLeast(0.0)

        if (currentSong != null) {
            if (sameArtist(candidate.song, currentSong)) result += SAME_ARTIST_BONUS
            if (sameAlbum(candidate.song, currentSong)) result += SAME_ALBUM_BONUS
        }
        result *= repeatPenalty(candidate.effectiveRepeatCount)
        if (candidate.song.durationMs in 1 until SHORT_TRACK_THRESHOLD_MS) {
            result *= SHORT_TRACK_PENALTY
        }
        return result
    }

    /**
     * 重复播放惩罚系数（任务书 §3.4）。
     *
     * 0 次返回 1.0（无惩罚）；n 次返回 `REPEAT_PENALTY_STEP^n`，下限 [REPEAT_PENALTY_FLOOR]。
     * 与旧的「播过就乘 0.35」相比，这里对「播过 1 次」更宽容（0.85 而非 0.35），
     * 因为刚播过一次并不等于用户不喜欢——真正要压的是循环重播的那几首。
     */
    fun repeatPenalty(repeatCount: Int): Double {
        if (repeatCount <= 0) return 1.0
        var penalty = 1.0
        repeat(repeatCount.coerceAtMost(MAX_REPEAT_PENALTY_STEPS)) {
            penalty *= REPEAT_PENALTY_STEP
        }
        return penalty.coerceAtLeast(REPEAT_PENALTY_FLOOR)
    }

    /** 连乘步数上限，避免高播放次数时做无意义的循环 */
    private const val MAX_REPEAT_PENALTY_STEPS = 32

    /**
     * 从候选池中挑出分数最高的一首。
     *
     * 排除规则：
     * - 与 [currentSong] 同一首歌（不能把自己当作下一首）；
     * - 已经存在于 [currentQueue] 中的歌曲（保持队列多样性）；
     * - 出现在 [excludedIdentities] 中的歌曲（本次会话内播放失败过的曲目，任务书 §八）。
     *
     * 分数相同时按候选池原始顺序取靠前者，保证结果稳定。
     */
    fun pickBest(
        pool: HeartbeatCandidatePool,
        currentSong: SongItem?,
        currentQueue: List<SongItem> = emptyList(),
        excludedIdentities: Set<String> = emptySet(),
        sameIdentity: (SongItem, SongItem?) -> Boolean = ::defaultSameIdentity,
    ): SongItem? {
        if (pool.isEmpty) return null
        val queueKeys = currentQueue.mapTo(HashSet()) { identityKey(it) }
        var best: HeartbeatCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in pool.flattenByPriority()) {
            if (sameIdentity(candidate.song, currentSong)) continue
            val key = identityKey(candidate.song)
            if (key in queueKeys) continue
            if (key in excludedIdentities) continue
            val score = score(candidate, currentSong)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best?.song
    }

    /**
     * 两条歌曲是否同歌手或同专辑。
     *
     * 供候选池派生层级 3 使用：层级 3 的定义依赖当前歌曲，无法在缓存期判定。
     */
    fun sameArtistOrAlbum(song: SongItem, other: SongItem): Boolean =
        sameArtist(song, other) || sameAlbum(song, other)

    private fun sameArtist(song: SongItem, other: SongItem): Boolean {
        val left = artistKey(song)
        return left.isNotEmpty() && left == artistKey(other)
    }

    private fun sameAlbum(song: SongItem, other: SongItem): Boolean {
        if (song.albumId > 0L && song.albumId == other.albumId) return true
        val left = song.album.trim()
        return left.isNotEmpty() && left == other.album.trim()
    }

    private fun artistKey(song: SongItem): String {
        val fromSummary = song.neteaseArtists
            ?.mapNotNull { it.name?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.sorted()
            ?.joinToString("/")
            .orEmpty()
        return if (fromSummary.isNotEmpty()) fromSummary else song.artist.trim().lowercase()
    }

    /**
     * 歌曲身份键。
     *
     * 优先使用 [SongItem.sourceStableKey]（本地歌曲/多来源场景下更准确），
     * 回退到「平台:歌曲 id」，再回退到「名称|歌手」。
     */
    fun identityKey(song: SongItem): String {
        song.sourceStableKey?.takeIf { it.isNotBlank() }?.let { return it }
        if (song.id > 0L) {
            val platform = song.matchedLyricSource?.name.orEmpty()
            return "$platform:${song.id}"
        }
        return "${song.name.trim().lowercase()}|${song.artist.trim().lowercase()}"
    }

    /** 默认身份判定：身份键相等即视为同一首歌 */
    fun defaultSameIdentity(song: SongItem, other: SongItem?): Boolean {
        if (other == null) return false
        return identityKey(song) == identityKey(other)
    }
}
