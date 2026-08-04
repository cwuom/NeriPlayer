package moe.ouom.neriplayer.ui.viewmodel.artist

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItemsPage
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
