package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 本地降级候选的来源层级。
 *
 * 数值即降级优先级：数值越小越优先（见心动模式任务书 §3.3）。
 * 官方接口（优先级 0）不在此枚举中，因为它是独立的一层，由协调器先行尝试。
 */
object HeartbeatCandidateTier {
    /** 1：用户收藏（红心/喜欢的歌曲） */
    const val FAVORITE = 1

    /** 2：最近播放历史 */
    const val HISTORY = 2

    /** 3：同歌手 / 同专辑 */
    const val SAME_ARTIST_OR_ALBUM = 3

    /** 4：随机兜底 */
    const val RANDOM = 4

    /** 候选池为空时的哨兵值 */
    const val NONE = Int.MAX_VALUE

    fun isKnown(tier: Int): Boolean = tier in FAVORITE..RANDOM
}

/**
 * 一条本地降级候选。
 *
 * [weight] 是该候选在其来源层级内的相对权重（越大越优先），
 * 由候选池构建方给出，评分器只做归一化与衰减，不重新发明业务权重。
 */
data class HeartbeatCandidate(
    val song: SongItem,
    val tier: Int,
    val weight: Double = 1.0,
    /** 是否刚刚播放过，用于避免立刻重复 */
    val recentlyPlayed: Boolean = false,
)

/**
 * 本地候选池：按层级分组的一次性快照。
 *
 * 构造后不可变，便于在协程间安全传递与缓存。
 */
data class HeartbeatCandidatePool(
    val tiers: Map<Int, List<HeartbeatCandidate>> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = tiers.values.all { it.isEmpty() }

    fun candidatesOf(tier: Int): List<HeartbeatCandidate> = tiers[tier].orEmpty()

    /** 按优先级从高到低展开全部候选，供评分器统一处理 */
    fun flattenByPriority(): List<HeartbeatCandidate> =
        tiers.keys.sorted().flatMap { candidatesOf(it) }

    companion object {
        val EMPTY = HeartbeatCandidatePool()
    }
}
