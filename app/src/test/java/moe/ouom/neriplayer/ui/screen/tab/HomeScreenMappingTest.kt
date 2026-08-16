package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeItem
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenMappingTest {

    @Test
    fun `persisted local continue cover skips playlist fallback`() {
        val playlist = LocalPlaylist(id = 7L, name = "local")

        assertFalse(
            shouldResolveHomeContinueLocalCoverFallback(
                persistedCoverUrl = "file:///covers/saved.jpg",
                localPlaylist = playlist
            )
        )
        assertTrue(
            shouldResolveHomeContinueLocalCoverFallback(
                persistedCoverUrl = null,
                localPlaylist = playlist
            )
        )
    }

    @Test
    fun `continue pager restores the saved page and clamps removed pages`() {
        assertEquals(1, resolveHomeContinuePagerPage(savedPage = 1, pageCount = 2))
        assertEquals(0, resolveHomeContinuePagerPage(savedPage = 1, pageCount = 1))
        assertEquals(0, resolveHomeContinuePagerPage(savedPage = -1, pageCount = 2))
    }

    @Test
    fun `home local files cover candidates stay bounded to recent covered downloads`() {
        val downloads = (0 until 80).map { index ->
            DownloadedSong(
                id = index.toLong(),
                name = "song-$index",
                artist = "artist",
                album = "album",
                filePath = "/music/song-$index.mp3",
                fileSize = 1L,
                downloadTime = 80L - index,
                coverUrl = if (index < 32) {
                    "https://example.com/cover-$index.jpg"
                } else {
                    null
                }
            )
        }

        val candidates = homeLocalFilesCoverCandidates(downloads)

        assertEquals(24, candidates.size)
        assertEquals("https://example.com/cover-0.jpg", candidates.first().coverUrl)
        assertEquals("https://example.com/cover-23.jpg", candidates.last().coverUrl)
    }

    @Test
    fun continueSectionStaysMountedWhileUsageRepositoryLoads() {
        assertTrue(
            shouldShowHomeContinueSection(
                showContinueCard = true,
                usageLoaded = false,
                hasUsage = false
            )
        )
    }

    @Test
    fun continueSectionHidesOnlyAfterAnEmptyUsageResultIsLoaded() {
        assertFalse(
            shouldShowHomeContinueSection(
                showContinueCard = true,
                usageLoaded = true,
                hasUsage = false
            )
        )
    }

    @Test
    fun continueSectionRespectsDisabledCardSettingWhileUsageLoads() {
        assertFalse(
            shouldShowHomeContinueSection(
                showContinueCard = false,
                usageLoaded = false,
                hasUsage = true
            )
        )
    }

    @Test
    fun toPlayableSongItem_keepsHomeItemDuration() {
        val song = YouTubeMusicHomeItem(
            title = "爱你",
            subtitle = "歌曲 • 陈芳语 • 爱你 • 3:27",
            coverUrl = "https://example.com/cover.jpg",
            videoId = "video-aini",
            durationText = "3:27",
            durationMs = 207_000L
        ).toPlayableSongItem(sectionTitle = "猜你喜欢")

        assertNotNull(song)
        assertEquals(207_000L, song?.durationMs)
        assertEquals("陈芳语", song?.artist)
        assertEquals("爱你", song?.album)
    }

    @Test
    fun continueCardsFitRegularPhonePageWithoutPeekingNextCard() {
        val containerWidthDp = 360f
        val cardsPerPage = resolveHomeContinueCardsPerPage(containerWidthDp)
        val cardWidthDp = resolveHomeContinueCardWidthDp(containerWidthDp, cardsPerPage)
        val occupiedWidthDp = cardWidthDp * cardsPerPage + 12f * (cardsPerPage - 1) + 16f

        assertEquals(3, cardsPerPage)
        assertEquals(106.67f, cardWidthDp, 0.01f)
        assertTrue(occupiedWidthDp <= containerWidthDp)
    }

    @Test
    fun continueCardsFillThreeSlotsWhenPhoneContentCanFitThem() {
        val containerWidthDp = 320f
        val cardsPerPage = resolveHomeContinueCardsPerPage(containerWidthDp)
        val cardWidthDp = resolveHomeContinueCardWidthDp(containerWidthDp, cardsPerPage)
        val occupiedWidthDp = cardWidthDp * cardsPerPage + 12f * (cardsPerPage - 1) + 16f

        assertEquals(3, cardsPerPage)
        assertEquals(93.33f, cardWidthDp, 0.01f)
        assertTrue(occupiedWidthDp <= containerWidthDp)
    }

    @Test
    fun continueCardsShrinkInsteadOfOverflowingTinyPages() {
        val containerWidthDp = 240f
        val cardsPerPage = resolveHomeContinueCardsPerPage(containerWidthDp)
        val cardWidthDp = resolveHomeContinueCardWidthDp(containerWidthDp, cardsPerPage)
        val occupiedWidthDp = cardWidthDp * cardsPerPage + 12f * (cardsPerPage - 1) + 16f

        assertEquals(2, cardsPerPage)
        assertEquals(106f, cardWidthDp, 0.01f)
        assertTrue(occupiedWidthDp <= containerWidthDp)
    }

    @Test
    fun continueCardsUseMoreSlotsOnWidePages() {
        assertEquals(4, resolveHomeContinueCardsPerPage(600f))
        assertEquals(6, resolveHomeContinueCardsPerPage(840f))
    }

    @Test
    fun buildHomeSongInfoMatchesPlaylistCopyFormat() {
        val song = SongItem(
            id = 1L,
            name = "海屿你",
            artist = "马也_Crabbbit",
            album = "海屿你",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null
        )

        assertEquals("海屿你-马也_Crabbbit", buildHomeSongInfo(song))
    }
}
