package moe.ouom.neriplayer.data.ltw.playback

import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTogetherRoomTrackPolicyTest {
    @Test
    fun `queue index keeps duplicate occurrence and safely resolves missing or invalid targets`() {
        val queue = listOf(track("a"), track("b"), track("a"))
        assertEquals(-1, resolveListenTogetherQueueIndex(emptyList(), 1, "a"))
        assertEquals(2, resolveListenTogetherQueueIndex(queue, 2, "a"))
        assertEquals(1, resolveListenTogetherQueueIndex(queue, 0, "b"))
        assertEquals(1, resolveListenTogetherQueueIndex(queue, 1, "missing"))
        assertEquals(0, resolveListenTogetherQueueIndex(queue, -1, "missing"))
        assertEquals(0, resolveListenTogetherQueueIndex(queue, 99, null))
        assertEquals(2, resolveListenTogetherQueueIndex(queue, 2, " "))
    }

    @Test
    fun `request identity prefers explicit target then indexed queue then legacy track`() {
        val event = ListenTogetherEvent(type = "SEEK", currentIndex = 0, queue = listOf(track("queue")), track = track("legacy"))
        assertEquals("target", event.copy(requestTrackStableKey = "target").requestedStableKey())
        assertEquals("queue", event.requestedStableKey())
        assertEquals("legacy", event.copy(currentIndex = 10).requestedStableKey())
        assertEquals("legacy", event.copy(queue = null).requestedStableKey())
        assertNull(event.copy(queue = null, track = null, currentIndex = null).requestedStableKey())
    }

    @Test
    fun `merge updates only the matching current occurrence without touching other entries`() {
        val original = track("a")
        val queue = listOf(original, track("b"))
        assertSame(queue, queue.mergeCurrentTrack(0, null))
        assertSame(queue, queue.mergeCurrentTrack(-1, original))
        assertSame(queue, queue.mergeCurrentTrack(0, track("b")))
        assertSame(queue, queue.mergeCurrentTrack(0, original))
        val updated = original.copy(name = "updated")
        assertEquals(listOf(updated, queue[1]), queue.mergeCurrentTrack(0, updated))
        assertEquals("a", queue.first().name)
    }

    @Test
    fun `bounded queue defaults to beginning when current identity is missing`() {
        val short = listOf(track("a"))
        assertSame(short, short.boundedAroundStableKey(null))
        val long = (0 until 2_500).map { track("$it") }
        assertEquals(long.take(2_000), long.boundedAroundStableKey(null))
        assertEquals(long.take(2_000), long.boundedAroundStableKey("missing"))
    }

    private fun track(key: String) = ListenTogetherTrack(stableKey = key, channelId = "netease", audioId = key, name = key, artist = "artist")
}
