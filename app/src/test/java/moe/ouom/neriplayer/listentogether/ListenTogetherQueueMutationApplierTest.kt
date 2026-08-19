package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.control.applyListenTogetherQueueMutation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueMutation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueOperation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueReference
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ListenTogetherQueueMutationApplierTest {

    @Test
    fun `empty queue insertion selects the requested newly inserted track`() {
        val result = applyListenTogetherQueueMutation(
            roomQueue = emptyList(),
            roomCurrentIndex = -1,
            mutation = mutation(
                operations = listOf(
                    insert("a"),
                    insert("b"),
                    insert("c")
                )
            ),
            targetCurrentStableKey = "channel:c"
        )

        assertEquals(listOf("channel:a", "channel:b", "channel:c"), result.stableKeys())
        assertEquals(2, result.currentIndex)
        assertFalse(result.currentRemoved)
    }

    @Test
    fun `independent moves replay in arrival order`() {
        val first = applyListenTogetherQueueMutation(
            roomQueue = listOf(track("a"), track("b"), track("c"), track("d")),
            roomCurrentIndex = 0,
            mutation = mutation(
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "move",
                        target = reference("b"),
                        placement = "append"
                    )
                )
            )
        )
        val second = applyListenTogetherQueueMutation(
            roomQueue = first.queue,
            roomCurrentIndex = first.currentIndex,
            mutation = mutation(
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "move",
                        target = reference("c"),
                        placement = "prepend"
                    )
                )
            )
        )

        assertEquals(listOf("channel:c", "channel:a", "channel:d", "channel:b"), second.stableKeys())
        assertEquals(1, second.currentIndex)
    }

    @Test
    fun `remove many leaves newer remote tracks untouched`() {
        val result = applyListenTogetherQueueMutation(
            roomQueue = listOf(track("a"), track("b"), track("remote")),
            roomCurrentIndex = 0,
            mutation = mutation(
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "remove_many",
                        order = listOf(reference("a"), reference("b"))
                    )
                )
            )
        )

        assertEquals(listOf("channel:remote"), result.stableKeys())
        assertEquals(0, result.currentIndex)
        assertEquals(true, result.currentRemoved)
    }

    @Test
    fun `duplicate stable keys remove only the requested occurrence`() {
        val firstDuplicate = track("dup", audioId = "first")
        val secondDuplicate = track("dup", audioId = "second")
        val result = applyListenTogetherQueueMutation(
            roomQueue = listOf(firstDuplicate, track("middle"), secondDuplicate),
            roomCurrentIndex = 0,
            mutation = mutation(
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "remove",
                        target = reference("dup", occurrence = 1)
                    )
                )
            )
        )

        assertEquals(listOf(firstDuplicate, track("middle")), result.queue)
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `target current keeps a retained previous current track selected`() {
        val previousCurrent = track("a")
        val targetCurrent = track("c")

        val result = applyListenTogetherQueueMutation(
            roomQueue = listOf(previousCurrent, track("b"), targetCurrent),
            roomCurrentIndex = 0,
            mutation = mutation(
                operations = emptyList(),
                targetCurrent = reference("c")
            ),
            targetCurrentStableKey = targetCurrent.stableKey
        )

        assertEquals(0, result.currentIndex)
        assertEquals(previousCurrent, result.queue[result.currentIndex])
        assertEquals(2, result.targetCurrentIndex)
        assertFalse(result.currentRemoved)
    }

    @Test
    fun `queue cannot grow beyond the shared protocol limit`() {
        val roomQueue = (0 until 2_000).map { index -> track("$index") }
        val result = applyListenTogetherQueueMutation(
            roomQueue = roomQueue,
            roomCurrentIndex = 1_999,
            mutation = mutation(operations = listOf(insert("overflow")))
        )

        assertEquals(2_000, result.queue.size)
        assertEquals("channel:1999", result.queue.last().stableKey)
        assertEquals(1_999, result.currentIndex)
    }

    @Test
    fun `oversized mutation is rejected without partial replay`() {
        val initial = listOf(track("a"), track("b"))
        val result = applyListenTogetherQueueMutation(
            roomQueue = initial,
            roomCurrentIndex = 0,
            mutation = mutation(
                operations = List(65) {
                    ListenTogetherQueueOperation(
                        type = "move",
                        target = reference("a"),
                        placement = "append"
                    )
                }
            )
        )

        assertEquals(initial, result.queue)
        assertEquals(0, result.currentIndex)
        assertFalse(result.currentRemoved)
    }

    private fun mutation(
        operations: List<ListenTogetherQueueOperation>,
        targetCurrent: ListenTogetherQueueReference? = null
    ) = ListenTogetherQueueMutation(
        baseRoomVersion = 7L,
        operations = operations,
        targetCurrent = targetCurrent
    )

    private fun insert(stableKey: String) = ListenTogetherQueueOperation(
        type = "insert",
        placement = "append",
        track = track(stableKey)
    )

    private fun reference(
        stableKey: String,
        occurrence: Int = 0
    ) = ListenTogetherQueueReference(
        stableKey = "channel:$stableKey",
        occurrence = occurrence
    )

    private fun track(
        stableKey: String,
        audioId: String = "audio-$stableKey"
    ) = ListenTogetherTrack(
        stableKey = "channel:$stableKey",
        channelId = "channel",
        audioId = audioId,
        name = stableKey,
        artist = "artist"
    )

    private fun moe.ouom.neriplayer.listentogether.control.ListenTogetherQueueMutationResult
        .stableKeys(): List<String> = queue.map { it.stableKey }
}
