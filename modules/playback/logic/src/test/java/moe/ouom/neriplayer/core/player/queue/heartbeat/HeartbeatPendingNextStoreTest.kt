package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「预取就绪快照」语义测试（任务书 §5.1 建议补充项）。
 *
 * 四条硬语义：
 * - [HeartbeatPendingNextStore.take] 是一次性的，防止同一首歌被连续播放两次；
 * - [HeartbeatPendingNextStore.publish] 可以存 null（表示没有预取结果）；
 * - [HeartbeatPendingNextStore.clear] 能丢弃过期预取；
 * - 预取结果带锚点，锚点与当前歌不一致时视为过期。
 */
class HeartbeatPendingNextStoreTest {

    private val anchor = "netease:1"
    private val song = song(1, "下一首")

    @Test
    fun `starts empty`() {
        val store = HeartbeatPendingNextStore()

        assertNull(store.current(anchor))
        assertNull(store.take(anchor))
    }

    @Test
    fun `publish then current exposes the song`() {
        val store = HeartbeatPendingNextStore()

        store.publish(anchor, song)

        assertEquals(song, store.current(anchor))
    }

    @Test
    fun `take is one shot`() {
        val store = HeartbeatPendingNextStore()
        store.publish(anchor, song)

        assertEquals("第一次 take 必须拿到歌", song, store.take(anchor))
        assertNull("第二次 take 必须为空", store.take(anchor))
        assertNull("take 之后 current 也必须为空", store.current(anchor))
    }

    @Test
    fun `current does not consume the pending song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(anchor, song)

        assertEquals(song, store.current(anchor))
        assertEquals("current 只是窥视，不应消费", song, store.current(anchor))
        assertEquals(song, store.take(anchor))
    }

    @Test
    fun `publish null clears the previous pending song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(anchor, song)

        store.publish(anchor, null)

        assertNull(store.current(anchor))
        assertNull(store.take(anchor))
    }

    @Test
    fun `publish replaces the previous pending song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(anchor, song)
        val replacement = song(2, "更新的下一首")

        store.publish(anchor, replacement)

        assertEquals(replacement, store.current(anchor))
        assertEquals(replacement, store.take(anchor))
    }

    @Test
    fun `clear discards a published song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(anchor, song)

        store.clear()

        assertNull(store.current(anchor))
        assertNull("clear 之后不得还能 take 到", store.take(anchor))
    }

    @Test
    fun `clear on an empty store stays empty`() {
        val store = HeartbeatPendingNextStore()

        store.clear()

        assertNull(store.current(anchor))
        assertNull(store.take(anchor))
    }

    @Test
    fun `store is usable after clear`() {
        val store = HeartbeatPendingNextStore()
        store.publish(anchor, song)
        store.clear()

        store.publish(anchor, song)

        assertEquals(song, store.current(anchor))
    }

    @Test
    fun `take then publish works repeatedly`() {
        val store = HeartbeatPendingNextStore()

        store.publish(anchor, song)
        assertEquals(song, store.take(anchor))
        store.publish(anchor, song)
        assertEquals(song, store.take(anchor))

        assertTrue(store.current(anchor) == null)
    }

    @Test
    fun `pending song is invisible under a different anchor`() {
        val store = HeartbeatPendingNextStore()
        store.publish(anchor, song)

        assertNull("锚点不一致时不得返回预取结果", store.current("netease:2"))
        assertNull("锚点不一致时 take 必须为空", store.take("netease:2"))
    }

    @Test
    fun `taking under a mismatched anchor drops the stale pending song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(anchor, song)

        assertNull(store.take("netease:2"))
        assertNull("过期预取应被清掉，不能留给后续锚点", store.current(anchor))
    }

    @Test
    fun `publishing with a null anchor clears the previous pending song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(anchor, song)

        store.publish(null, song(3, "无锚点"))

        assertNull(store.current(anchor))
        assertNull(store.take(anchor))
    }

    private fun song(id: Long, name: String) = SongItem(
        id = id,
        name = name,
        artist = "歌手$id",
        album = "专辑$id",
        albumId = id,
        durationMs = 180_000L,
        coverUrl = null,
        sourceStableKey = "netease:$id",
    )
}
