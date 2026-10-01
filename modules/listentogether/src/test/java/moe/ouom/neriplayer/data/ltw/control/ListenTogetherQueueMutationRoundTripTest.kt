package moe.ouom.neriplayer.data.ltw.control

import moe.ouom.neriplayer.data.model.ltw.message.queue.ListenTogetherQueueMutation
import moe.ouom.neriplayer.data.model.ltw.message.queue.ListenTogetherQueueOperation
import moe.ouom.neriplayer.data.model.ltw.message.queue.ListenTogetherQueueReference
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherQueueMutationRoundTripTest {
    @Test
    fun `reordering retains every identity and selected duplicate occurrence`() {
        val base = listOf(track("a"), track("b"), track("c"), track("d"))
        for (target in permutations(base)) {
            val state = ListenTogetherRoomState(roomId = "room", version = 7L, queue = base, currentIndex = 1)
            val plan = buildListenTogetherQueueMutationPlan(state, target, target.indexOf(base[1]))
            assertFalse(plan.requiresSnapshotFallback)
            val result = applyListenTogetherQueueMutation(base, 1, plan.mutation)
            assertEquals(target, result.queue)
            assertEquals(base[1], result.queue[result.currentIndex])
        }
        val duplicate = listOf(track("dup"), track("middle"), track("dup"))
        val target = listOf(duplicate[0], duplicate[2], duplicate[1])
        val plan = buildListenTogetherQueueMutationPlan(ListenTogetherRoomState(roomId = "room", version = 1L, queue = duplicate, currentIndex = 2), target, 1)
        assertEquals(1, applyListenTogetherQueueMutation(duplicate, 2, plan.mutation).currentIndex)
    }

    @Test
    fun `consecutive inserts before retained anchor preserve requested playback order`() {
        val base = listOf(track("a"), track("b"))
        val target = listOf(base[0], track("x"), track("y"), base[1])
        val state = ListenTogetherRoomState(roomId = "room", version = 7L, queue = base, currentIndex = 1)
        val plan = buildListenTogetherQueueMutationPlan(state, target, 3)
        val result = applyListenTogetherQueueMutation(base, 1, plan.mutation)
        assertEquals(target, result.queue)
        assertEquals(3, result.currentIndex)
    }

    @Test
    fun `invalid and unknown mutation operations cannot add blank identities`() {
        val base = listOf(track("a"), track("b"))
        val operations = listOf(
            ListenTogetherQueueOperation(type = "insert"),
            ListenTogetherQueueOperation(type = "insert", track = track("")),
            ListenTogetherQueueOperation(type = "reorder", order = emptyList()),
            ListenTogetherQueueOperation(type = "reorder", order = listOf(reference("missing"))),
            ListenTogetherQueueOperation(type = "unknown")
        )
        assertEquals(base, applyListenTogetherQueueMutation(base, 0, mutation(operations)).queue)
    }

    @Test
    fun `compact reorder uses retained slots and preserves newer unrelated entries`() {
        val base = listOf(track("a"), track("remote"), track("b"))
        val operations = listOf(ListenTogetherQueueOperation(type = "reorder", order = listOf(reference("b"), reference("a"))))
        val result = applyListenTogetherQueueMutation(base, 0, mutation(operations))
        assertEquals(listOf(base[2], base[1], base[0]), result.queue)
        assertEquals(2, result.currentIndex)
    }

    @Test
    fun `invalid identities and excessive inserts request full snapshot fallback`() {
        val state = ListenTogetherRoomState(roomId = "room", version = 7L, queue = listOf(track("a")), currentIndex = 0)
        assertTrue(buildListenTogetherQueueMutationPlan(state, listOf(track("")), 0).requiresSnapshotFallback)
        assertTrue(buildListenTogetherQueueMutationPlan(state.copy(queue = listOf(track(""))), state.queue, 0).requiresSnapshotFallback)
        assertTrue(buildListenTogetherQueueMutationPlan(state, state.queue + (0 until 65).map { track("insert-$it") }, 0).requiresSnapshotFallback)
    }

    private fun <T> permutations(values: List<T>): List<List<T>> {
        if (values.isEmpty()) return listOf(emptyList())
        return values.indices.flatMap { index ->
            permutations(values.filterIndexed { candidate, _ -> candidate != index }).map { listOf(values[index]) + it }
        }
    }

    private fun mutation(operations: List<ListenTogetherQueueOperation>) = ListenTogetherQueueMutation(baseRoomVersion = 7L, operations = operations)
    private fun reference(key: String) = ListenTogetherQueueReference(stableKey = key, occurrence = 0)
    private fun track(key: String) = ListenTogetherTrack(stableKey = key, channelId = "netease", audioId = key, name = key, artist = "artist")
}
