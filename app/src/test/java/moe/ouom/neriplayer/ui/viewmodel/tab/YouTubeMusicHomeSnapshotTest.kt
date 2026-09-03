package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicHomeSnapshotTest {

    @Test
    fun loadHomeSnapshot_fetchesShelvesOnceAndDerivesBothSections() = runTest {
        val shelves = listOf(
            YouTubeMusicHomeShelf(
                title = "为你推荐",
                items = listOf(
                    homePlaylistItem(
                        title = "Playlist A",
                        browseId = "VLPL-home-a",
                        subtitle = "42 songs"
                    ),
                    YouTubeMusicHomeItem(
                        title = "Song A",
                        subtitle = "Artist A",
                        coverUrl = "https://example.com/song.jpg",
                        videoId = "song-a"
                    )
                )
            )
        )
        var fetches = 0

        val snapshot = loadYouTubeMusicHomeSnapshot(
            playlistLimit = 24,
            loadShelves = {
                fetches += 1
                shelves
            }
        )

        assertEquals(1, fetches)
        assertSame(shelves, snapshot.shelves)
        assertEquals(1, snapshot.playlists.size)
        assertEquals("VLPL-home-a", snapshot.playlists.single().browseId)
        assertEquals("PL-home-a", snapshot.playlists.single().playlistId)
        assertEquals("Playlist A", snapshot.playlists.single().title)
        assertEquals("42 songs", snapshot.playlists.single().subtitle)
        assertEquals(42, snapshot.playlists.single().trackCount)
    }

    @Test
    fun buildHomeSnapshot_appliesPlaylistLimitWithoutChangingShelves() {
        val shelves = listOf(
            YouTubeMusicHomeShelf(
                title = "为你推荐",
                items = (0 until 4).map { index ->
                    homePlaylistItem(
                        title = "Playlist $index",
                        browseId = "VLPL-home-$index",
                        subtitle = "${index + 1} songs"
                    )
                }
            )
        )

        val snapshot = buildYouTubeMusicHomeSnapshot(shelves, playlistLimit = 2)

        assertSame(shelves, snapshot.shelves)
        assertEquals(listOf("PL-home-0", "PL-home-1"), snapshot.playlists.map { it.playlistId })
        assertEquals(4, snapshot.shelves.single().items.size)
    }

    @Test
    fun homeLoadRejectsStaleGenerationAuthOrDisabledHome() {
        assertTrue(
            shouldAcceptYouTubeMusicHomeLoadResult(
                requestGeneration = 2L,
                activeGeneration = 2L,
                requestAuthFingerprint = "account-a",
                activeAuthFingerprint = "account-a",
                internationalizationEnabled = true
            )
        )
        assertFalse(
            shouldAcceptYouTubeMusicHomeLoadResult(
                requestGeneration = 1L,
                activeGeneration = 2L,
                requestAuthFingerprint = "account-a",
                activeAuthFingerprint = "account-a",
                internationalizationEnabled = true
            )
        )
        assertFalse(
            shouldAcceptYouTubeMusicHomeLoadResult(
                requestGeneration = 2L,
                activeGeneration = 2L,
                requestAuthFingerprint = "account-a",
                activeAuthFingerprint = "account-b",
                internationalizationEnabled = true
            )
        )
        assertFalse(
            shouldAcceptYouTubeMusicHomeLoadResult(
                requestGeneration = 2L,
                activeGeneration = 2L,
                requestAuthFingerprint = "account-a",
                activeAuthFingerprint = "account-a",
                internationalizationEnabled = false
            )
        )
    }

    @Test
    fun pendingHomeRefreshDoesNotRestartAfterAuthenticationChanges() {
        assertTrue(
            shouldScheduleYouTubeMusicHomeRefresh(
                refreshPending = true,
                offlineMode = false,
                requestGeneration = 2L,
                activeGeneration = 2L,
                requestAuthFingerprint = "account-a",
                activeAuthFingerprint = "account-a",
                internationalizationEnabled = true
            )
        )
        assertFalse(
            shouldScheduleYouTubeMusicHomeRefresh(
                refreshPending = true,
                offlineMode = false,
                requestGeneration = 2L,
                activeGeneration = 2L,
                requestAuthFingerprint = "account-a",
                activeAuthFingerprint = "",
                internationalizationEnabled = true
            )
        )
        assertFalse(
            shouldScheduleYouTubeMusicHomeRefresh(
                refreshPending = true,
                offlineMode = true,
                requestGeneration = 2L,
                activeGeneration = 2L,
                requestAuthFingerprint = "account-a",
                activeAuthFingerprint = "account-a",
                internationalizationEnabled = true
            )
        )
    }

    private fun homePlaylistItem(
        title: String,
        browseId: String,
        subtitle: String
    ): YouTubeMusicHomeItem {
        return YouTubeMusicHomeItem(
            title = title,
            subtitle = subtitle,
            coverUrl = "https://example.com/$title.jpg",
            browseId = browseId,
            pageType = "MUSIC_PAGE_TYPE_PLAYLIST"
        )
    }
}
