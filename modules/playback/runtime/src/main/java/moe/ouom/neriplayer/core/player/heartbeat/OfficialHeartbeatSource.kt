package moe.ouom.neriplayer.core.player.heartbeat

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatNextTrackSelector
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatScorer
import moe.ouom.neriplayer.data.heartbeat.DislikedSongRepository
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
 *
 * 「不喜欢」名单的处理（用户显式授权，见交付方案）：
 * - 这是对「官方结果不得二次过滤」的**唯一例外**，且性质不同：不是客户端对推荐算法
 *   做加权/重排，而是用户已经明确说过「不要这首」。命中时丢弃这一次返回并**重新请求**，
 *   让服务端重新出歌，客户端仍然原样透传新结果；
 * - 重试有上限（[MAX_DISLIKE_RETRY]），仍命中则放行并打日志——宁可偶尔播到一首被标记的歌，
 *   也不能让官方通道因为本地名单而空转；
 * - 名单读取失败按「没有标记」处理：本地数据问题不该影响官方推荐。
 */
class OfficialHeartbeatSource(
    private val repository: NeteasePlaymodeIntelligenceRepository,
    private val dislikedSongRepository: DislikedSongRepository? = null,
) : HeartbeatNextTrackSelector {

    override suspend fun selectNext(
        currentSong: SongItem,
        currentQueue: List<SongItem>,
    ): SongItem? {
        if (!repository.isLoggedIn()) return null
        val disliked = readDislikedIdentities()
        var attempt = 0
        while (true) {
            // 第一次走缓存；重试必须绕过缓存，否则拿到的还是刚才那首被标记的歌
            val song = fetchOnce(currentSong, bypassCache = attempt > 0) ?: return null
            if (disliked.isEmpty() || HeartbeatScorer.identityKey(song) !in disliked) {
                return song
            }
            if (attempt >= MAX_DISLIKE_RETRY) {
                // 重试用尽仍然命中：放行，避免官方通道因本地名单而彻底空转
                NPLogger.d(
                    TAG,
                    "官方候选命中「不喜欢」名单且重试已用尽，放行: ${song.name}/${song.id}",
                )
                return song
            }
            attempt++
            NPLogger.d(
                TAG,
                "官方候选命中「不喜欢」名单，重新请求($attempt/$MAX_DISLIKE_RETRY): ${song.name}/${song.id}",
            )
        }
    }

    /** 单次官方请求，失败一律折算成 null（不打断播放链路）。 */
    private suspend fun fetchOnce(currentSong: SongItem, bypassCache: Boolean): SongItem? = try {
        repository.fetchNextSong(currentSong.id, bypassCache)
    } catch (error: Throwable) {
        // 取消必须继续向上传播，否则旧请求会在切歌后污染新歌状态
        if (error is kotlinx.coroutines.CancellationException) throw error
        null
    }

    /** 读一次名单快照，整个选歌过程复用，避免重试时反复读盘。 */
    private suspend fun readDislikedIdentities(): Set<String> {
        val local = dislikedSongRepository ?: return emptySet()
        return runCatching { local.snapshot() }.getOrDefault(emptySet())
    }

    companion object {
        private const val TAG = "OfficialHeartbeat"

        /**
         * 命中「不喜欢」名单时允许重新请求的次数。
         *
         * 取 2 而不是更多：每次重试都是一次真实网络往返，而心动模式是在用户点「下一首」
         * 的同步路径上等结果，重试太多次会让切歌明显变慢。2 次足够避开偶尔撞上的一首，
         * 撞上两首说明名单与官方推荐口味高度重叠，继续重试收益很低。
         */
        internal const val MAX_DISLIKE_RETRY = 2
    }
}
