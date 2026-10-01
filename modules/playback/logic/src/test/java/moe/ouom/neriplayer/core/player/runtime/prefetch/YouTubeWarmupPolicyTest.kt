package moe.ouom.neriplayer.core.player.runtime.prefetch

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeWarmupPolicyTest {
    @Test
    fun `queue size bounds the warmup window at each threshold`() {
        val windows = mapOf(0 to 0, 1 to 1, 3 to 3, 4 to 4, 8 to 4, 9 to 5, 20 to 5, 21 to 6)
        windows.forEach { (size, expected) ->
            val songs = (1..size).map { song("video$it") }
            val targets = resolveYouTubePrefetchTargets(songs, 0, "high", SongItem::mediaUri)
            assertEquals(expected, targets.prefetchVideoIds.size)
            assertEquals("high", targets.preferredQuality)
            assertEquals(expected > 0, targets.hasWork)
        }
    }

    @Test
    fun `warmup skips unavailable duplicate and blank ids while keeping their order`() {
        val songs = listOf(song(null), song(""), song("one"), song("one"), song(" "), song("two"))
        val targets = resolveYouTubePrefetchTargets(songs, 0, "medium", SongItem::mediaUri)
        assertEquals(listOf("one", "two"), targets.prefetchVideoIds)
        assertEquals("one", targets.currentVideoId)
        assertEquals("two", targets.nextVideoId)
        assertTrue(targets.hasWork)
    }

    @Test
    fun `queue warmup handles negative indices and ends without wrapping`() {
        val songs = listOf(song("one"), song("two"))
        assertEquals(listOf("one", "two"), resolveYouTubePrefetchTargets(songs, -1, "high", SongItem::mediaUri).prefetchVideoIds)
        val exhausted = resolveYouTubePrefetchTargets(songs, 2, "high", SongItem::mediaUri)
        assertFalse(exhausted.hasWork)
        assertNull(exhausted.currentVideoId)
        assertNull(exhausted.nextVideoId)
    }

    @Test
    fun `immediate warmup clamps to current item and never starts its neighbor`() {
        val songs = listOf(song("one"), song("two"))
        val first = resolveYouTubeImmediatePrefetchTargets(songs, -1, "low", SongItem::mediaUri)
        assertEquals(listOf("one"), first.prefetchVideoIds)
        assertNull(first.nextVideoId)
        assertEquals("low", first.preferredQuality)
        assertEquals(listOf("two"), resolveYouTubeImmediatePrefetchTargets(songs, 5, "low", SongItem::mediaUri).prefetchVideoIds)
    }

    @Test
    fun `immediate warmup ignores empty queues and unresolved or blank current ids`() {
        assertFalse(resolveYouTubeImmediatePrefetchTargets(emptyList(), 0, "high", SongItem::mediaUri).hasWork)
        listOf(null, "", " ").forEach { id ->
            assertFalse(resolveYouTubeImmediatePrefetchTargets(listOf(song(id)), 0, "high", SongItem::mediaUri).hasWork)
        }
    }

    private fun song(videoId: String?) = SongItem(
        id = 1L,
        name = "song",
        artist = "artist",
        album = "album",
        albumId = 0L,
        durationMs = 60_000L,
        coverUrl = null,
        mediaUri = videoId
    )
}
