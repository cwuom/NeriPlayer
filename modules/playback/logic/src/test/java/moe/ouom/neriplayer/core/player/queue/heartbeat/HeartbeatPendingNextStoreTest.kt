package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「预取就绪快照」语义测试（任务书 §5.1 建议补充项）。
 *
 * 三条硬语义：
 * - [HeartbeatPendingNextStore.take] 是一次性的，防止同一首歌被连续播放两次；
 * - [HeartbeatPendingNextStore.publish] 可以存 null（表示没有预取结果）；
 * - [HeartbeatPendingNextStore.clear] 能丢弃过期预取。
 */
class HeartbeatPendingNextStoreTest {

    private val song = song(1, "下一首")

    @Test
    fun `starts empty`() {
        val store = HeartbeatPendingNextStore()

        assertNull(store.current())
        assertNull(store.take())
    }

    @Test
    fun `publish then current exposes the song`() {
        val store = HeartbeatPendingNextStore()

        store.publish(song)

        assertEquals(song, store.current())
    }

    @Test
    fun `take is one shot`() {
        val store = HeartbeatPendingNextStore()
        store.publish(song)

        assertEquals("第一次 take 必须拿到歌", song, store.take())
        assertNull("第二次 take 必须为空", store.take())
        assertNull("take 之后 current 也必须为空", store.current())
    }

    @Test
    fun `current does not consume the pending song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(song)

        assertEquals(song, store.current())
        assertEquals("current 只是窥视，不应消费", song, store.current())
        assertEquals(song, store.take())
    }

    @Test
    fun `publish null clears the previous pending song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(song)

        store.publish(null)

        assertNull(store.current())
        assertNull(store.take())
    }

    @Test
    fun `publish replaces the previous pending song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(song)
        val replacement = song(2, "更新的下一首")

        store.publish(replacement)

        assertEquals(replacement, store.current())
        assertEquals(replacement, store.take())
    }

    @Test
    fun `clear discards a published song`() {
        val store = HeartbeatPendingNextStore()
        store.publish(song)

        store.clear()

        assertNull(store.current())
        assertNull("clear 之后不得还能 take 到", store.take())
    }

    @Test
    fun `clear on an empty store stays empty`() {
        val store = HeartbeatPendingNextStore()

        store.clear()

        assertNull(store.current())
        assertNull(store.take())
    }

    @Test
    fun `store is usable after clear`() {
        val store = HeartbeatPendingNextStore()
        store.publish(song)
        store.clear()

        store.publish(song)

        assertEquals(song, store.current())
    }

    @Test
    fun `take then publish works repeatedly`() {
        val store = HeartbeatPendingNextStore()

        store.publish(song)
        assertEquals(song, store.take())
        store.publish(song)
        assertEquals(song, store.take())

        assertTrue(store.current() == null)
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
