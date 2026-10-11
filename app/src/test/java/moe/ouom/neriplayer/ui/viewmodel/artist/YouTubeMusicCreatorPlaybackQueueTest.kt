package moe.ouom.neriplayer.ui.viewmodel.artist

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemsPage
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicCreatorPlaybackQueueTest {

    @Test
    fun `top songs playback queue includes every continuation page`() = runBlocking {
        val visibleSongs = (1..5).map(::song)
        val selectedSong = visibleSongs[3]
        val section = YouTubeMusicCreatorSection(
            title = "TOP SONGS",
            items = visibleSongs,
            moreEndpoint = YouTubeMusicCreatorBrowseEndpoint("UCcreator", "topSongs")
        )

        val queue = loadYouTubeMusicCreatorPlaybackQueue(
            section = section,
            selectedItem = selectedSong,
            fetchFirstPage = { _, _ ->
                YouTubeMusicCreatorItemsPage(
                    title = "TOP SONGS",
                    items = visibleSongs,
                    continuation = "page-2"
                )
            },
            fetchContinuation = { continuation ->
                when (continuation) {
                    "page-2" -> YouTubeMusicCreatorItemsPage(
                        title = "TOP SONGS",
                        items = listOf(song(6), song(7)),
                        continuation = "page-3"
                    )
                    "page-3" -> YouTubeMusicCreatorItemsPage(
                        title = "TOP SONGS",
                        items = listOf(song(8))
                    )
                    else -> error("Unexpected continuation: $continuation")
                }
            }
        )

        assertNotNull(queue)
        requireNotNull(queue)
        assertEquals((1..8).map { "video-$it" }, queue.songs.map { it.audioId })
        assertEquals(3, queue.startIndex)
    }

    @Test
    fun `creator playback queue stops a repeated continuation`() = runBlocking {
        val firstSong = song(1)
        val requestedContinuations = mutableListOf<String>()
        val section = YouTubeMusicCreatorSection(
            title = "TOP SONGS",
            items = listOf(firstSong),
            moreEndpoint = YouTubeMusicCreatorBrowseEndpoint("UCcreator", "topSongs")
        )

        val queue = loadYouTubeMusicCreatorPlaybackQueue(
            section = section,
            selectedItem = firstSong,
            fetchFirstPage = { _, _ ->
                YouTubeMusicCreatorItemsPage(
                    title = "TOP SONGS",
                    items = listOf(firstSong),
                    continuation = "page-2"
                )
            },
            fetchContinuation = { continuation ->
                requestedContinuations += continuation
                when (continuation) {
                    "page-2" -> YouTubeMusicCreatorItemsPage(
                        title = "TOP SONGS",
                        items = listOf(song(2)),
                        continuation = "page-3"
                    )
                    "page-3" -> YouTubeMusicCreatorItemsPage(
                        title = "TOP SONGS",
                        items = listOf(song(3)),
                        continuation = "page-2"
                    )
                    else -> error("Unexpected continuation: $continuation")
                }
            }
        )

        assertNotNull(queue)
        requireNotNull(queue)
        assertEquals(listOf("page-2", "page-3"), requestedContinuations)
        assertEquals(listOf("video-1", "video-2", "video-3"), queue.songs.map { it.audioId })
        assertEquals(0, queue.startIndex)
    }

    @Test
    fun `creator item keys prefer the most specific trimmed id`() {
        val item = song(1).copy(videoId = " video ", browseId = "browse", playlistId = "playlist")

        assertEquals("video", youtubeMusicCreatorItemKey(item))
        assertEquals("browse", youtubeMusicCreatorItemKey(item.copy(videoId = " ")))
        assertEquals("playlist", youtubeMusicCreatorItemKey(item.copy(videoId = "", browseId = " ")))
        assertEquals(
            "Song|Song 1|Creator",
            youtubeMusicCreatorItemKey(item.copy(videoId = "", browseId = "", playlistId = "", title = " Song 1 "))
        )
    }

    @Test
    fun `creator playback queue without more endpoint uses visible items only`() = runBlocking {
        val songs = listOf(song(1), song(2))

        val queue = loadYouTubeMusicCreatorPlaybackQueue(
            section = YouTubeMusicCreatorSection(title = "SONGS", items = songs),
            selectedItem = song(9),
            fetchFirstPage = { _, _ -> error("first page should not load") },
            fetchContinuation = { error("continuation should not load") }
        )
        val unplayable = loadYouTubeMusicCreatorPlaybackQueue(
            section = YouTubeMusicCreatorSection(title = "ALBUMS", items = listOf(song(3).copy(videoId = ""))),
            selectedItem = song(3),
            fetchFirstPage = { _, _ -> error("first page should not load") },
            fetchContinuation = { error("continuation should not load") }
        )

        assertEquals(listOf("video-1", "video-2"), queue?.songs?.map { it.audioId })
        assertEquals(0, queue?.startIndex)
        assertNull(unplayable)
    }

    @Test
    fun `creator playback queue stops at blank continuations and the page limit`() = runBlocking {
        val section = YouTubeMusicCreatorSection(
            title = "TOP SONGS",
            items = emptyList(),
            moreEndpoint = YouTubeMusicCreatorBrowseEndpoint("UCcreator", "topSongs")
        )
        val requested = mutableListOf<String>()

        val blankContinuation = loadYouTubeMusicCreatorPlaybackQueue(
            section = section,
            selectedItem = song(1),
            fetchFirstPage = { _, title ->
                YouTubeMusicCreatorItemsPage(title = title, items = listOf(song(1)), continuation = "  ")
            },
            fetchContinuation = { error("blank continuation should not load") }
        )
        val limited = loadYouTubeMusicCreatorPlaybackQueue(
            section = section,
            selectedItem = song(2),
            fetchFirstPage = { _, title ->
                YouTubeMusicCreatorItemsPage(title = title, items = listOf(song(1)), continuation = " page-2 ")
            },
            fetchContinuation = { continuation ->
                requested += continuation
                YouTubeMusicCreatorItemsPage(title = "TOP SONGS", items = listOf(song(2)), continuation = "page-3")
            },
            pageLimit = 2
        )

        assertEquals(listOf("video-1"), blankContinuation?.songs?.map { it.audioId })
        assertEquals(listOf("page-2"), requested)
        assertEquals(listOf("video-1", "video-2"), limited?.songs?.map { it.audioId })
        assertEquals(1, limited?.startIndex)
    }

    private fun song(index: Int): YouTubeMusicCreatorItem {
        return YouTubeMusicCreatorItem(
            type = YouTubeMusicCreatorItemType.Song,
            title = "Song $index",
            subtitle = "Creator",
            coverUrl = "",
            videoId = "video-$index",
            artist = "Creator",
            durationMs = 180_000L
        )
    }
}
