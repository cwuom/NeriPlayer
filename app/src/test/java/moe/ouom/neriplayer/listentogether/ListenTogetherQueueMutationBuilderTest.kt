package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.control.buildListenTogetherQueueMutationPlan
import moe.ouom.neriplayer.listentogether.control.ListenTogetherEventFactory
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherPlaybackState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.data.model.SongItem

class ListenTogetherQueueMutationBuilderTest {

    @Test
    fun `queue mutation carries base version and keeps moved current track`() {
        val baseQueue = listOf(track("a"), track("b"), track("c"))
        val plan = buildListenTogetherQueueMutationPlan(
            baseState = roomState(baseQueue, currentIndex = 1),
            targetQueue = listOf(baseQueue[1], baseQueue[0], baseQueue[2]),
            targetCurrentIndex = 0
        )

        assertFalse(plan.requiresSnapshotFallback)
        assertEquals(7L, plan.mutation.baseRoomVersion)
        assertEquals("netease:b", plan.mutation.targetCurrent?.stableKey)
        assertTrue(plan.mutation.operations.any { it.type == "move" })
    }

    @Test
    fun `trailing inserts are emitted in playback order`() {
        val baseQueue = listOf(track("a"), track("b"))
        val plan = buildListenTogetherQueueMutationPlan(
            baseState = roomState(baseQueue, currentIndex = 0),
            targetQueue = baseQueue + listOf(track("x"), track("y")),
            targetCurrentIndex = 0
        )

        assertEquals(
            listOf("netease:x", "netease:y"),
            plan.mutation.operations
                .filter { it.type == "insert" }
                .mapNotNull { it.track?.stableKey }
        )
    }

    @Test
    fun `large reorder uses a compact order operation`() {
        val baseQueue = (0 until 100).map { track(it.toString()) }
        val plan = buildListenTogetherQueueMutationPlan(
            baseState = roomState(baseQueue, currentIndex = 0),
            targetQueue = baseQueue.reversed(),
            targetCurrentIndex = 99
        )

        assertFalse(plan.requiresSnapshotFallback)
        assertEquals(1, plan.mutation.operations.count { it.type == "reorder" })
        assertEquals(100, plan.mutation.operations.single { it.type == "reorder" }.order?.size)
    }

    @Test
    fun `large clear uses one compact remove many operation`() {
        val baseQueue = (0 until 100).map { track(it.toString()) }
        val plan = buildListenTogetherQueueMutationPlan(
            baseState = roomState(baseQueue, currentIndex = 0),
            targetQueue = emptyList(),
            targetCurrentIndex = -1
        )

        assertFalse(plan.requiresSnapshotFallback)
        assertEquals(1, plan.mutation.operations.size)
        assertEquals("remove_many", plan.mutation.operations.single().type)
        assertEquals(100, plan.mutation.operations.single().order?.size)
    }

    @Test
    fun `schema two queue event omits stale full snapshot`() {
        val first = songItem("1")
        val second = songItem("2")
        val state = roomState(
            queue = listOf(track("1"), track("2")),
            currentIndex = 0
        )
        val factory = ListenTogetherEventFactory(
            roomStateProvider = { state },
            isControllerProvider = { true },
            eventIdFactory = { "event" },
            clientInstanceIdProvider = { "client" },
            clientSequenceFactory = { 1L },
            localPlaybackStateNameProvider = { "paused" },
            localTransportActiveProvider = { false }
        )

        val event = factory.buildSetQueueEvent(
            queue = listOf(first, second),
            currentIndex = 1,
            positionMs = 0L,
            commandShouldPlay = false
        )

        assertTrue(event?.queue == null)
        assertTrue(event?.queueMutation != null)
        assertEquals(7L, event?.queueMutation?.baseRoomVersion)
    }

    private fun roomState(
        queue: List<ListenTogetherTrack>,
        currentIndex: Int
    ): ListenTogetherRoomState {
        return ListenTogetherRoomState(
            roomId = "ABC234",
            version = 7L,
            schemaVersion = 2,
            queue = queue,
            currentIndex = currentIndex,
            playback = ListenTogetherPlaybackState(state = "paused")
        )
    }

    private fun track(stableKey: String): ListenTogetherTrack {
        return ListenTogetherTrack(
            stableKey = "netease:$stableKey",
            channelId = ListenTogetherChannels.NETEASE,
            audioId = stableKey,
            name = stableKey,
            artist = "artist"
        )
    }

    private fun songItem(audioId: String): SongItem {
        return SongItem(
            id = audioId.toLong(),
            name = audioId,
            artist = "artist",
            album = "",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = ListenTogetherChannels.NETEASE,
            audioId = audioId
        )
    }
}
