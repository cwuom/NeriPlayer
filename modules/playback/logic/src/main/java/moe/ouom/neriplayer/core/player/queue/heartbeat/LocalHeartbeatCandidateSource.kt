package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 心动模式的本地降级候选源。
 *
 * 层级优先级严格遵循任务书 §3.3：
 * ```
 * 0 官方接口（由 NeteasePlaymodeIntelligenceSource 负责，不在本类内）
 * 1 收藏
 * 2 历史
 * 3 同歌手 / 同专辑
 * 4 随机兜底
 * ```
 *
 * 本类只做「候选池构建 + 评分挑选」，不发起任何网络请求，
 * 数据来源通过 [HeartbeatCandidateProvider] 注入，避免 `playback/logic` 反向依赖 `local`。
 *
 * **合规约束（任务书 §1.3）**：本类的产出是「本地推荐」，
 * 上层 UI 文案不得表述为官方推荐算法。
 */
class LocalHeartbeatCandidateSource(
    private val provider: HeartbeatCandidateProvider = EmptyHeartbeatCandidateProvider,
    private val cache: HeartbeatCandidateCache = HeartbeatCandidateCache(),
) : HeartbeatNextTrackSelector {

    override suspend fun selectNext(currentSong: SongItem, currentQueue: List<SongItem>): SongItem? {
        val pool = cache.getOrBuild { buildPool() }
        if (pool.isEmpty) return null
        return HeartbeatScorer.pickBest(
            pool = pool,
            currentSong = currentSong,
            currentQueue = currentQueue,
        )
    }

    /** 候选池因收藏/历史变化而失效时调用 */
    fun invalidate() = cache.invalidate()

    /**
     * 构建候选池。
     *
     * 任何单一来源失败都不影响其它来源——用 `runCatching` 兜住，
     * 因为「收藏读不出来」不应该让整个心动模式失效。
     */
    private suspend fun buildPool(): HeartbeatCandidatePool {
        val favorites = runCatching { provider.favorites() }.getOrDefault(emptyList())
        val history = runCatching {
            provider.recentHistory(HeartbeatCandidateProvider.DEFAULT_HISTORY_LIMIT)
        }.getOrDefault(emptyList())

        val historyKeys = history.mapTo(HashSet()) { HeartbeatScorer.identityKey(it) }
        val tiers = LinkedHashMap<Int, List<HeartbeatCandidate>>()

        if (favorites.isNotEmpty()) {
            tiers[HeartbeatCandidateTier.FAVORITE] = favorites.map { song ->
                HeartbeatCandidate(
                    song = song,
                    tier = HeartbeatCandidateTier.FAVORITE,
                    // 收藏本身已是强信号，权重统一给 1.0，由评分器按层级拉开差距
                    weight = 1.0,
                    recentlyPlayed = HeartbeatScorer.identityKey(song) in historyKeys,
                )
            }
        }

        if (history.isNotEmpty()) {
            tiers[HeartbeatCandidateTier.HISTORY] = history.mapIndexed { index, song ->
                HeartbeatCandidate(
                    song = song,
                    tier = HeartbeatCandidateTier.HISTORY,
                    // 越靠前（越近）的历史权重越高，线性衰减到 0.5 为止
                    weight = (1.0 - index * HISTORY_WEIGHT_STEP).coerceAtLeast(HISTORY_MIN_WEIGHT),
                    recentlyPlayed = true,
                )
            }
        }

        // 层级 3（同歌手/同专辑）与层级 4（随机兜底）在本地数据源里没有额外数据可挖，
        // 由第 1、2 层天然覆盖。此处显式留空，避免编造不存在的数据来源。

        return HeartbeatCandidatePool(tiers)
    }

    private companion object {
        /** 历史权重随位置线性衰减的步长 */
        const val HISTORY_WEIGHT_STEP = 0.01

        /** 历史权重下限，防止第 50 条历史权重过低 */
        const val HISTORY_MIN_WEIGHT = 0.5
    }
}
