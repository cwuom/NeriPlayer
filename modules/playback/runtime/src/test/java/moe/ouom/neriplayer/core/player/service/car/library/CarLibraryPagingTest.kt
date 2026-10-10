package moe.ouom.neriplayer.core.player.service.car.library

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLibraryPagingTest {
    @Test
    fun `up to one hundred songs are immediately playable`() {
        listOf(0, 1, 99, 100).forEach { count ->
            val library = library(count)
            val children = library.children(CarMediaIds.QUEUE)
            assertEquals(count, children.size)
            assertTrue(children.all { it.isPlayable })
        }
    }

    @Test
    fun `one hundred and one songs produce explicit pages with an absolute playback index`() {
        val library = library(101)
        val pages = library.children(CarMediaIds.QUEUE)

        assertEquals(listOf("1 - 100", "101 - 101"), pages.map { it.title })
        assertTrue(pages.all { it.isBrowsable })
        assertEquals(100, library.children(pages.first().mediaId).size)
        val last = library.children(pages.last().mediaId).single()
        assertEquals(100, library.resolveId(last.mediaId)?.startIndex)
        assertEquals(101, library.resolveId(last.mediaId)?.songs?.size)
        assertEquals(pages.last(), library.getItem(pages.last().mediaId))
        assertNull(library.resolveId(pages.last().mediaId))
    }

    @Test
    fun `very large directories nest pages so every browser result has at most one hundred items`() {
        val library = library(10_001)
        val firstLevel = library.children(CarMediaIds.QUEUE)
        assertEquals(listOf("1 - 10000", "10001 - 10001"), firstLevel.map { it.title })
        val secondLevel = library.children(firstLevel.first().mediaId)
        assertEquals(100, secondLevel.size)
        assertTrue(secondLevel.all { it.isBrowsable })
        val songs = library.children(secondLevel.last().mediaId)
        assertEquals(100, songs.size)
        assertEquals(9_999, library.resolveId(songs.last().mediaId)?.startIndex)
        assertEquals(10_001, library.resolveId(songs.last().mediaId)?.songs?.size)
        val tail = library.children(firstLevel.last().mediaId).single()
        assertEquals(10_000, library.resolveId(tail.mediaId)?.startIndex)
    }

    @Test
    fun `playlist lists use explicit pagination while selected playlist keeps its full queue`() {
        val playlists = (0L..100L).map { id -> LocalPlaylist(id, "Playlist $id", mutableListOf(song(id))) }
        val library = CarMediaLibrary(CarLibrarySnapshot(playlists = playlists), identity = { it.id.toString() })
        val pages = library.children(CarMediaIds.PLAYLISTS)
        val lastPlaylist = library.children(pages.last().mediaId).single()

        assertEquals(2, pages.size)
        assertEquals(CarMediaIds.playlist(100), lastPlaylist.mediaId)
        assertTrue(lastPlaylist.isBrowsable)
        val selection = library.resolveId(library.children(lastPlaylist.mediaId).single().mediaId)
        assertEquals(100L, selection?.localPlaylistId)
        assertEquals(0, selection?.startIndex)
    }

    @Test
    fun `search pages and voice search share the full matching result queue`() {
        val library = library(101)
        val pages = library.search("Song")
        val tail = library.children(pages.last().mediaId).single()

        assertEquals(2, pages.size)
        assertTrue(pages.all { it.isBrowsable })
        assertEquals(100, library.resolveId(tail.mediaId)?.startIndex)
        assertEquals(101, library.resolveId(tail.mediaId)?.songs?.size)
        assertEquals(101, library.resolveSearch("Song")?.songs?.size)
        assertEquals(0, library.resolveSearch("Song")?.startIndex)
    }

    @Test
    fun `invalid page ranges never throw or expose unrelated nodes`() {
        val library = library(101)
        val invalid = listOf(
            CarMediaIds.page(CarMediaIds.QUEUE, -1, 100),
            CarMediaIds.page(CarMediaIds.QUEUE, 100, 100),
            CarMediaIds.page(CarMediaIds.QUEUE, 100, 99),
            CarMediaIds.page(CarMediaIds.QUEUE, 0, 102),
            CarMediaIds.page(CarMediaIds.QUEUE, 0, Int.MAX_VALUE),
            CarMediaIds.page(CarMediaIds.playlist(404), 0, 1)
        )
        invalid.forEach { id ->
            assertTrue(library.children(id).isEmpty())
            assertNull(library.getItem(id))
            assertNull(library.resolveId(id))
        }
    }

    @Test
    fun `paging span handles integer limit without overflow or more than one hundred nodes`() {
        val pages = CarLibraryPaging.pages(CarMediaIds.QUEUE, 0, Int.MAX_VALUE)

        assertTrue(pages.size <= 100)
        val last = CarMediaIds.parse(pages.last().mediaId) as CarMediaRoute.Page
        assertEquals(Int.MAX_VALUE, last.end)
        assertFalse(CarLibraryPaging.isValidRange(0, Int.MAX_VALUE, 100))
    }

    private fun library(count: Int) = CarMediaLibrary(
        CarLibrarySnapshot(queue = List(count) { song(it.toLong()) }), identity = { it.id.toString() }
    )

    private fun song(id: Long) = SongItem(id, "Song $id", "Artist", "Album", 0, 1_000, null)
}
