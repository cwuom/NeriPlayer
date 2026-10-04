package moe.ouom.neriplayer.core.player.service.car

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarQueueIdentityTest {
    @Test
    fun `ids follow songs after reorder and reject a removed song`() {
        val songs = listOf(song(1), song(2), song(3))
        val ids = carQueueEntries(songs).associate { it.song.id to it.id }
        val reordered = listOf(songs[2], songs[0])
        assertEquals(ids[3], carQueueItemId(reordered, 0))
        assertEquals(ids[1], carQueueItemId(reordered, 1))
        assertNull(carQueueIndex(reordered, checkNotNull(ids[2])))
        assertEquals(0, carQueueIndex(reordered, checkNotNull(ids[3])))
    }

    @Test
    fun `duplicate songs have distinct queue ids`() {
        val songs = List(3) { song(1) }
        val entries = carQueueEntries(songs)
        assertEquals(3, entries.map { it.id }.toSet().size)
        assertEquals(listOf(0, 1, 2), entries.map { carQueueIndex(songs, it.id) })
    }

    @Test
    fun `immutable queue reference reuses computed identities`() {
        val cache = CarQueueIdentityCache()
        val songs = List(1_000) { song(it.toLong()) }
        val first = cache.entries(songs)
        assertSame(first, cache.entries(songs))
        assertNotSame(first, cache.entries(songs.reversed()))
        val beforeClear = cache.entries(songs)
        cache.clear()
        assertNotSame(beforeClear, cache.entries(songs))
    }

    @Test
    fun `a large queue publishes a bounded window containing the selected song`() {
        val entries = carQueueEntries(List(1_000) { song(it.toLong()) })
        for (selected in listOf(0, 250, 999)) {
            val window = carQueueWindow(entries, selected)
            assertEquals(100, window.size)
            assertTrue(window.any { it.index == selected })
        }
        assertEquals(-1L, carQueueItemId(emptyList(), 0))
        assertNull(carQueueIndex(emptyList(), 0L))
    }

    private fun song(id: Long) = SongItem(id, "Song $id", "Artist", "netease", 0, 1_000L, null)
}
