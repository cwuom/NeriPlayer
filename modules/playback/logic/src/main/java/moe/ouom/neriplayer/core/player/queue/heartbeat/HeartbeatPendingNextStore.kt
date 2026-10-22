package moe.ouom.neriplayer.core.player.queue.heartbeat

import java.util.concurrent.atomic.AtomicReference
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
 * **锚定**：每次预取都带着「为哪首歌预取的」身份键。用户可能在预取飞行途中直接切到另一首歌
 * （拖动队列、点歌单里的歌），此时旧结果与当前歌无关，必须作废。读写两侧都带当前歌的身份键
 * 做校验，不匹配就当作没有候选。
 *
 * **原子性**：锚点与歌曲必须一起换。若分两个字段写，后台协程写完新锚点、还没写新歌时，
 * 播放线程可能拿「新锚点」读到「旧歌」，把为上一首预取的结果当成当前歌的下一首播出去。
 * 所以两者打包成一个不可变对象，用 [AtomicReference] 一次性替换。
 *
 * 线程模型：心跳协程写、播放线程读；[take] 用 `getAndSet` 保证同一候选只会被消费一次。
 */
class HeartbeatPendingNextStore {

    /** 锚点与候选是不可分割的一份状态，任何时刻都只能整体替换。 */
    private data class Pending(val anchor: String, val song: SongItem)

    private val pending = AtomicReference<Pending?>(null)

    /** 当前预取到的、且确实是「为 [songKey] 这首歌」准备的下一首；否则返回 null。 */
    fun current(songKey: String): SongItem? =
        pending.get()?.takeIf { it.anchor == songKey }?.song

    /** 存入预取结果，并记下它属于哪首歌；任一为 null 表示「没有候选」。 */
    fun publish(songKey: String?, song: SongItem?) {
        pending.set(if (songKey != null && song != null) Pending(songKey, song) else null)
    }

    /**
     * 取走预取结果（一次性语义，避免同一首歌被连续播放两次）。
     *
     * 锚点不匹配时返回 null；无论匹配与否都会把槽位清空，避免过期结果一直占着位置。
     */
    fun take(songKey: String): SongItem? {
        val taken = pending.getAndSet(null) ?: return null
        return taken.song.takeIf { taken.anchor == songKey }
    }

    /** 关闭心动模式 / 音源不支持 / 锚点漂移时调用，防止过期预取被用在错误的上下文中。 */
    fun clear() {
        pending.set(null)
    }
}
