package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 心动模式的「预取就绪快照」。
 *
 * 为什么需要它：播放推进链路（`trackCompletion` / `next` / 播放失败）是**同步**函数，
 * 而官方接口是 `suspend`。若在同步链路上直接 `runBlocking`，会把 IO 等待搬到主线程，
 * 造成切歌卡顿——这与任务书 §3.5「开启瞬间异步预取」的初衷相反。
 *
 * 因此这里用一个小型状态持有者做桥接：
 * - 心动模式开启 / 每次切歌后，异步调用 [HeartbeatNextTrackSelector.selectNext] 预取；
 * - 同步推进链路只读 [pending]，有预取结果就直接用，没有就沿用原有推进并再补一次预取。
 *
 * 线程模型：只有「心跳协程」写、播放线程读，[pending] 用 @Volatile 保证可见性；
 * 竞争下的最坏结果只是多请求一次接口，不会出现状态错乱。
 */
class HeartbeatPendingNextStore {

    @Volatile
    private var pending: SongItem? = null

    /** 当前预取到的下一首；没有则返回 null。 */
    fun current(): SongItem? = pending

    /** 存入预取结果。 */
    fun publish(song: SongItem?) {
        pending = song
    }

    /** 取走预取结果（一次性语义，避免同一首歌被连续播放两次）。 */
    fun take(): SongItem? {
        val song = pending
        pending = null
        return song
    }

    /** 关闭心动模式 / 切歌时调用，防止过期预取被用在错误的上下文中。 */
    fun clear() {
        pending = null
    }
}
