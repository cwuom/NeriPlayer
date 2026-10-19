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

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 50
    }
}

/** 空实现：未接线或数据源不可用时使用，保证心动模式永远能安全降级 */
object EmptyHeartbeatCandidateProvider : HeartbeatCandidateProvider {
    override suspend fun favorites(): List<SongItem> = emptyList()
    override suspend fun recentHistory(limit: Int): List<SongItem> = emptyList()
}
