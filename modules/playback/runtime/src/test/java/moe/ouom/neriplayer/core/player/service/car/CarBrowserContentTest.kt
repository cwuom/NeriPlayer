package moe.ouom.neriplayer.core.player.service.car

import moe.ouom.neriplayer.core.player.service.car.library.CarLibrarySnapshot
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaIds
import moe.ouom.neriplayer.core.player.service.car.library.CarMediaLibrary
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarBrowserContentTest {
    @Test
    fun `ordinary root keeps four browsable categories when no smaller positive limit is requested`() {
        val library = library()
        for (limit in listOf(null, 0, -1, 4, 100)) {
            for (flags in listOf(null, 1, 3)) {
                val items = carBrowserChildren(library, CarMediaIds.ROOT, CarMediaIds.ROOT, limit, flags)
                assertEquals(library.children(CarMediaIds.ROOT), items)
                assertEquals(4, items.size)
                assertTrue(items.all { it.isBrowsable && !it.isPlayable })
            }
        }
    }

    @Test
    fun `small root limits group all categories without limiting the catalogue children`() {
        val library = library()
        for (limit in 1..3) {
            val root = carBrowserChildren(library, CarMediaIds.ROOT, CarMediaIds.ROOT, limit, 1)
            assertEquals(listOf(CarMediaIds.CATALOGUE), root.map { it.mediaId })
            assertTrue(root.single().isBrowsable)
            assertEquals(library.children(CarMediaIds.ROOT),
                carBrowserChildren(library, CarMediaIds.CATALOGUE, CarMediaIds.ROOT, limit, 1))
        }
    }

    @Test
    fun `recent and offline roots honor explicit positive child limits`() {
        val songs = (1L..6L).map(::song)
        val library = library(CarLibrarySnapshot(history = songs, offlineSongs = songs))
        for (root in listOf(CarMediaIds.HISTORY, CarMediaIds.OFFLINE)) {
            assertEquals(songs.take(2), carBrowserChildren(library, root, root, 2, null).map { it.song })
            assertEquals(songs, carBrowserChildren(library, root, root, 100, 3).map { it.song })
            for (limit in listOf(null, 0, -1)) {
                assertEquals(library.children(root), carBrowserChildren(library, root, root, limit, null))
            }
        }
    }

    @Test
    fun `playable only ordinary root merges queue recent and offline with a default of four`() {
        val library = library(CarLibrarySnapshot(
            queue = listOf(song(1)), history = listOf(song(2), song(3)),
            offlineSongs = listOf(song(4), song(5)),
        ))
        for (limit in listOf(null, 0, -1, 4)) {
            val items = carBrowserChildren(library, CarMediaIds.ROOT, CarMediaIds.ROOT, limit, 2)
            assertEquals((1L..4L).toList(), items.map { it.song?.id })
            assertTrue(items.all { it.isPlayable && !it.isBrowsable })
            items.forEach { assertTrue(library.resolveId(it.mediaId) != null) }
        }
        assertEquals(listOf(1L), carBrowserChildren(library, CarMediaIds.ROOT, CarMediaIds.ROOT, 1, 2)
            .map { it.song?.id })
        assertEquals((1L..5L).toList(), carBrowserChildren(library, CarMediaIds.ROOT, CarMediaIds.ROOT, 8, 2)
            .map { it.song?.id })
    }

    @Test
    fun `playable only root falls through empty directories without exposing categories`() {
        val library = library(CarLibrarySnapshot(offlineSongs = listOf(song(1))))
        val items = carBrowserChildren(library, CarMediaIds.ROOT, CarMediaIds.ROOT, null, 2)
        assertEquals(listOf(song(1)), items.map { it.song })
        assertTrue(items.single().isPlayable)
        assertTrue(carBrowserChildren(library(), CarMediaIds.ROOT, CarMediaIds.ROOT, null, 2).isEmpty())
    }

    @Test
    fun `playable only special roots use the positive limit or default four`() {
        val songs = (1L..6L).map(::song)
        val library = library(CarLibrarySnapshot(history = songs, offlineSongs = songs))
        for (root in listOf(CarMediaIds.HISTORY, CarMediaIds.OFFLINE)) {
            assertEquals(songs.take(4), carBrowserChildren(library, root, root, null, 2).map { it.song })
            assertEquals(songs.take(3), carBrowserChildren(library, root, root, 3, 2).map { it.song })
        }
    }

    @Test
    fun `playable only roots descend multiple explicit page levels in directory order`() {
        val songs = (1L..10_001L).map(::song)
        val library = library(CarLibrarySnapshot(queue = songs, history = songs, offlineSongs = songs))
        val firstPage = library.children(CarMediaIds.QUEUE).first()
        assertTrue(firstPage.isBrowsable)
        assertTrue(library.children(firstPage.mediaId).first().isBrowsable)
        for (root in listOf(CarMediaIds.ROOT, CarMediaIds.HISTORY, CarMediaIds.OFFLINE)) {
            val items = carBrowserChildren(library, root, root, 3, 2)
            assertEquals(songs.take(3), items.map { it.song })
            assertTrue(items.all { it.isPlayable && !it.isBrowsable })
        }
    }

    @Test
    fun `oversized playable root limit stays within one hundred items`() {
        val songs = (1L..101L).map(::song)
        val library = library(CarLibrarySnapshot(queue = songs, history = songs, offlineSongs = songs))
        for (root in listOf(CarMediaIds.ROOT, CarMediaIds.HISTORY, CarMediaIds.OFFLINE)) {
            val items = carBrowserChildren(library, root, root, Int.MAX_VALUE, 2)
            assertEquals(songs.take(100), items.map { it.song })
            assertTrue(items.all { it.isPlayable && !it.isBrowsable })
        }
    }

    @Test
    fun `non root directories and pages ignore both root hints`() {
        val library = library(CarLibrarySnapshot(queue = (1L..101L).map(::song)))
        val pages = library.children(CarMediaIds.QUEUE)
        assertEquals(pages, carBrowserChildren(library, CarMediaIds.QUEUE, CarMediaIds.ROOT, 1, 2))
        val pageId = pages.first().mediaId
        assertEquals(library.children(pageId), carBrowserChildren(library, pageId, CarMediaIds.ROOT, 1, 1))
    }

    private fun library(snapshot: CarLibrarySnapshot = CarLibrarySnapshot()) =
        CarMediaLibrary(snapshot, identity = { it.id.toString() })

    private fun song(id: Long) = SongItem(id, "Song $id", "Artist", "Album", 0L, 1_000L, null)
}
