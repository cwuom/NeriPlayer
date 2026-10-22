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
 * - 同步推进链路只读 [current]，有预取结果就直接用，没有就沿用原有推进并再补一次预取。
 *
 * **锚定**：每次预取都带着「为哪首歌预取的」身份键（[anchor]）。用户可能在预取飞行途中
 * 直接切到另一首歌（拖动队列、点歌单里的歌），此时旧结果与当前歌无关，必须作废。
 * 读写两侧都带上当前歌的身份键做校验，不匹配就当作没有候选。
 *
 * 线程模型：只有「心跳协程」写、播放线程读，字段用 @Volatile 保证可见性；
 * 竞争下的最坏结果只是多请求一次接口，不会出现状态错乱。
 */
class HeartbeatPendingNextStore {

    @Volatile
    private var pending: SongItem? = null

    @Volatile
    private var anchor: String? = null

    /** 当前预取到的、且确实是「为 [songKey] 这首歌」准备的下一首；否则返回 null。 */
    fun current(songKey: String): SongItem? =
        pending.takeIf { anchor != null && anchor == songKey }

    /** 存入预取结果，并记下它属于哪首歌。 */
    fun publish(songKey: String?, song: SongItem?) {
        anchor = songKey
        pending = song
    }

    /**
     * 取走预取结果（一次性语义，避免同一首歌被连续播放两次）。
     *
     * 锚点不匹配时返回 null 并顺手清空，避免过期结果一直占着位置。
     */
    fun take(songKey: String): SongItem? {
        val song = pending
        if (anchor == null || anchor != songKey) {
            clear()
            return null
        }
        pending = null
        anchor = null
        return song
    }

    /** 关闭心动模式 / 音源不支持 / 锚点漂移时调用，防止过期预取被用在错误的上下文中。 */
    fun clear() {
        pending = null
        anchor = null
    }
}
