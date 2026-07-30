package moe.ouom.neriplayer.core.player.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * addToQueueNext 随机索引重映射回归测试(#P3)
 *
 * 复现随机播放"下一首播放"在队列中部插入/移除后的错播, 漏曲, 重复:
 * shuffleBag/shuffleHistory/shuffleFuture 里保存的是队列下标,
 * 队列结构变化时必须做同样的收缩/扩张,否则旧下标会指向错误曲目或越界
 */
class PlayerManagerShuffleQueueRemapTest {

    @Test
    fun `middle insert keeps every remaining shuffle index valid and complete`() {
        // 队列 [A,B,C,D],当前 B(1),随机袋 = [D(3), C(2)];对新曲 E 执行"下一首播放"
        // insertIndex = currentIndex + 1 = 2,E 不在队列 -> existingIndex = -1
        // newPlaylist = [A, B, E, C, D],E 的真实下标 = 2
        val newPlaylist = listOf("A", "B", "E", "C", "D")
        val newSongIndex = newPlaylist.indexOf("E")

        val result = remapShuffleStateForInsertNext(
            state = ShuffleQueueIndexState(
                bag = listOf(3, 2), // D, C
                history = emptyList(),
                future = emptyList(),
            ),
            existingIndex = -1,
            insertIndex = 2,
            newSongIndex = newSongIndex,
        )

        // 旧 bug 会把 D 丢出随机池, 把 C 错位;修复后袋里仍应恰好是 D 和 C
        assertEquals(setOf("D", "C"), result.bag.map { newPlaylist[it] }.toSet())
        // 新曲 E 进入 future,不再留在 bag
        assertEquals(listOf("E"), result.future.map { newPlaylist[it] })
        assertFalse(newSongIndex in result.bag)
        assertAllIndicesWithin(result, newPlaylist.size)
        assertNoDuplicateAcross(result)
    }

    @Test
    fun `moving an existing song to next remaps both removal and insertion`() {
        // 队列 [A,B,C,D,E],当前 A(0),随机袋 = [D(3), B(1), E(4), C(2)]
        // 对已存在的 D 执行"下一首播放":removeAt(3) 后 add(1, D)
        // newPlaylist = [A, D, B, C, E]
        val newPlaylist = listOf("A", "D", "B", "C", "E")
        val newSongIndex = newPlaylist.indexOf("D") // 1

        val result = remapShuffleStateForInsertNext(
            state = ShuffleQueueIndexState(
                bag = listOf(3, 1, 4, 2), // D, B, E, C(相对原队列)
                history = emptyList(),
                future = emptyList(),
            ),
            existingIndex = 3,
            insertIndex = 1,
            newSongIndex = newSongIndex,
        )

        // 关键回归:仅做插入位移会把原下标 4 抬成不存在的 5(越界)
        assertAllIndicesWithin(result, newPlaylist.size)
        assertEquals(setOf("B", "E", "C"), result.bag.map { newPlaylist[it] }.toSet())
        assertEquals(listOf("D"), result.future.map { newPlaylist[it] })
        assertNoDuplicateAcross(result)
    }

    @Test
    fun `history entries shift on removal and stale new-song history is dropped`() {
        // 队列 [A,B,C,D],当前 C(2),history=[A(0)],bag=[B(1), D(3)]
        // 对已存在的 A 执行"下一首播放":removeAt(0) 后 insertIndex=2
        // newPlaylist = [B, C, A, D]
        val newPlaylist = listOf("B", "C", "A", "D")
        val newSongIndex = newPlaylist.indexOf("A") // 2

        val result = remapShuffleStateForInsertNext(
            state = ShuffleQueueIndexState(
                bag = listOf(1, 3), // B, D
                history = listOf(0), // A
                future = emptyList(),
            ),
            existingIndex = 0,
            insertIndex = 2,
            newSongIndex = newSongIndex,
        )

        // A 被移动到 future,history 不应再引用它
        assertFalse(result.history.any { newPlaylist[it] == "A" })
        assertEquals(listOf("A"), result.future.map { newPlaylist[it] })
        assertEquals(setOf("B", "D"), result.bag.map { newPlaylist[it] }.toSet())
        assertAllIndicesWithin(result, newPlaylist.size)
        assertNoDuplicateAcross(result)
    }

    @Test
    fun `insertion shift only affects indices at or after the insert point`() {
        val result = remapShuffleStateForInsertNext(
            state = ShuffleQueueIndexState(
                bag = listOf(0, 1, 2, 3),
                history = emptyList(),
                future = emptyList(),
            ),
            existingIndex = -1,
            insertIndex = 2,
            newSongIndex = 2,
        )

        // 0,1 保持;2,3 后移为 3,4;新曲槽 2 移出 bag 后进入 future
        assertEquals(listOf(0, 1, 3, 4), result.bag)
        assertEquals(listOf(2), result.future)
    }

    @Test
    fun `existing future entries are preserved and new song is not duplicated`() {
        // future 里已有一首待播曲,插入新曲后两者都应存在且不重复
        // 队列 [A,B,C,D],future=[C(2)],bag=[D(3)];对新曲 E 下一首播放,insertIndex=2
        // newPlaylist = [A, B, E, C, D]
        val newPlaylist = listOf("A", "B", "E", "C", "D")
        val newSongIndex = newPlaylist.indexOf("E")

        val result = remapShuffleStateForInsertNext(
            state = ShuffleQueueIndexState(
                bag = listOf(3), // D
                history = emptyList(),
                future = listOf(2), // C
            ),
            existingIndex = -1,
            insertIndex = 2,
            newSongIndex = newSongIndex,
        )

        assertEquals(setOf("C", "E"), result.future.map { newPlaylist[it] }.toSet())
        assertEquals(setOf("D"), result.bag.map { newPlaylist[it] }.toSet())
        assertAllIndicesWithin(result, newPlaylist.size)
        assertNoDuplicateAcross(result)
    }

    @Test
    fun `queue current index follows dragged current item`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 1,
            fromIndex = 1,
            toIndex = 3,
            queueSize = 5
        )

        assertEquals(3, index)
    }

    @Test
    fun `queue current index shifts when earlier item moves after it`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 2,
            fromIndex = 0,
            toIndex = 3,
            queueSize = 5
        )

        assertEquals(1, index)
    }

    @Test
    fun `queue current index shifts when later item moves before it`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 2,
            fromIndex = 4,
            toIndex = 1,
            queueSize = 5
        )

        assertEquals(3, index)
    }

    @Test
    fun `queue current index survives invalid move`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 2,
            fromIndex = -1,
            toIndex = 1,
            queueSize = 5
        )

        assertEquals(2, index)
    }

    @Test
    fun `queue current index returns missing for empty queue`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 0,
            fromIndex = 0,
            toIndex = 0,
            queueSize = 0
        )

        assertEquals(-1, index)
    }

    private fun assertAllIndicesWithin(state: ShuffleQueueIndexState, size: Int) {
        val all = state.bag + state.history + state.future
        assertTrue(
            "shuffle indices out of range: $all for size=$size",
            all.all { it in 0 until size }
        )
    }

    private fun assertNoDuplicateAcross(state: ShuffleQueueIndexState) {
        val live = state.bag + state.future
        assertEquals(
            "bag/future must not contain duplicated indices: $live",
            live.size,
            live.toSet().size
        )
    }
}
