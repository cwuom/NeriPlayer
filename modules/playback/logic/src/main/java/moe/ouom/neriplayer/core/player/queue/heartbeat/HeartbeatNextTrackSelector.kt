package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 心动模式「下一首」决策接口。
 *
 * 分层约束（见心动模式任务书 §3.2）：
 * - 本接口位于 `playback/logic`，是播放域唯一认识的抽象；
 * - 官方接口实现在 `playback/runtime`（内部委托 `platform` 的网易云客户端），
 *   本地降级实现同样在 `playback/runtime`，由 `app` 的 DI 容器负责接线；
 * - `playback/logic` 绝不反向依赖 `platform` / `local`，因此实现必须从外部注入。
 *
 * 契约：
 * - 返回 `null` 表示「本次无法给出心动候选」，调用方必须回退到原有播放推进策略，
 *   **不得**把它当作播放失败处理；
 * - 返回的歌曲由调用方负责插入队列并播放，本接口不产生任何副作用；
 * - 实现必须自行处理网络/IO 异常，任何异常都应转换为 `null` 而不是向上抛出。
 */
interface HeartbeatNextTrackSelector {
    /**
     * 为 [currentSong] 挑选下一首心动歌曲。
     *
     * @param currentSong 当前正在播放的歌曲，调用方保证非空
     * @param currentQueue 当前播放队列快照，实现可用于排除已在队列中的歌曲
     * @return 下一首歌曲；无可用候选时返回 `null`
     */
    suspend fun selectNext(currentSong: SongItem, currentQueue: List<SongItem>): SongItem?
}
