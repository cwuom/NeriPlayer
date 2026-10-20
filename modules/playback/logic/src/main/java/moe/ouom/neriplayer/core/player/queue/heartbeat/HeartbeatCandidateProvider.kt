package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 本地降级候选池的数据来源抽象。
 *
 * 为什么需要这层接口：候选池的真实数据来自 `modules/local`
 * （`FavoritePlaylistRepository` 与 `PlayHistoryRepository`），
 * 而 `playback/logic` 不允许依赖 `local`（域依赖门禁）。
 * 因此由 `app` 的 DI 容器在 `playback/runtime` 侧实现本接口并注入。
 *
 * 实现约定：
 * - 所有方法都必须是**廉价且非阻塞**的快照读取，不允许在这里发起网络请求；
 * - 返回空列表表示该来源暂无数据，属于正常情况，不是错误。
 */
interface HeartbeatCandidateProvider {

    /** 用户收藏/红心歌曲（层级 1） */
    suspend fun favorites(): List<SongItem>

    /** 最近播放历史，按时间倒序（层级 2） */
    suspend fun recentHistory(limit: Int = DEFAULT_HISTORY_LIMIT): List<SongItem>

    /**
     * 全库抽样曲目，供层级 3（同歌手/同专辑）与层级 4（随机兜底）派生。
     *
     * 这些层级必须相对「当前播放歌曲」才能判定，所以这里只提供原料，不做判定。
     * 返回空列表表示无额外数据，此时层级 3/4 自然为空，属于正常降级。
     */
    suspend fun librarySamples(limit: Int = DEFAULT_LIBRARY_SAMPLE_LIMIT): List<SongItem> = emptyList()

    /**
     * 各曲的累计播放次数，key 为 [HeartbeatScorer.identityKey]。
     *
     * 数据来自 `PlaybackStatsRepository`（`TrackStat.playCount`）。该读取是 suspend 的，
     * 且需要统计库已初始化，因此这里给出默认空实现：未接线时评分完全退回旧行为，
     * 不会因为统计不可用而影响心动模式可用性。
     */
    suspend fun recentPlayCounts(limit: Int = DEFAULT_HISTORY_LIMIT): Map<String, Int> = emptyMap()

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 50

        /** 层级 3/4 的原料抽样上限：全库可能上万首，不宜全量进内存 */
        const val DEFAULT_LIBRARY_SAMPLE_LIMIT = 200
    }
}

/** 空实现：未接线或数据源不可用时使用，保证心动模式永远能安全降级 */
object EmptyHeartbeatCandidateProvider : HeartbeatCandidateProvider {
    override suspend fun favorites(): List<SongItem> = emptyList()
    override suspend fun recentHistory(limit: Int): List<SongItem> = emptyList()
}
