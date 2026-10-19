package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.netease.playmode.NeteasePlaymodeIntelligenceRepository

/**
 * 心动模式「下一首」的**官方接口**实现。
 *
 * 分层约束（任务书 §3.2）：
 * - 本类在 `playback/runtime`，负责把 `logic` 层的 [HeartbeatNextTrackSelector] 契约
 *   适配到 `platform` 层的 [NeteasePlaymodeIntelligenceRepository]；
 * - `playback/logic` 不认识 [NeteasePlaymodeIntelligenceRepository]，只认识上面的接口，
 *   因此由本类在边界处做转换，域依赖方向保持为 `runtime → platform`，不会反向。
 *
 * 合规红线（任务书 §1.3 / §3.3）：
 * - 官方返回的歌曲**原样透传**，本类绝不做二次排序、二次过滤、插入本地评分；
 * - `NeteasePlaymodeIntelligenceRepository` 已保证任何失败都返回 null（不抛异常），
 *   这里再兜一层 catch，防止实现被替换后违约把播放链路打断。
 */
class OfficialHeartbeatSource(
    private val repository: NeteasePlaymodeIntelligenceRepository,
) : HeartbeatNextTrackSelector {

    override suspend fun selectNext(
        currentSong: SongItem,
        currentQueue: List<SongItem>,
    ): SongItem? {
        if (!repository.isLoggedIn()) return null
        return try {
            repository.fetchNextSong(currentSong.id)
        } catch (error: Throwable) {
            // 取消必须继续向上传播，否则旧请求会在切歌后污染新歌状态
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
    }
}
