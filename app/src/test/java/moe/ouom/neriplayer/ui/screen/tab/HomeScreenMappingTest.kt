package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.tab.home.buildHomeSongInfo
import moe.ouom.neriplayer.ui.screen.tab.home.homeLocalFilesCoverCandidates
import moe.ouom.neriplayer.ui.screen.tab.home.resolveHomeContinueCardWidthDp
import moe.ouom.neriplayer.ui.screen.tab.home.resolveHomeContinueCardsPerPage
import moe.ouom.neriplayer.ui.screen.tab.home.resolveHomeContinuePagerPage
import moe.ouom.neriplayer.ui.screen.tab.home.selectContinueCoverUrl
import moe.ouom.neriplayer.ui.screen.tab.home.shouldResolveHomeContinueLocalCoverFallback
import moe.ouom.neriplayer.ui.screen.tab.home.shouldShowHomeContinueSection
import moe.ouom.neriplayer.ui.screen.tab.home.shouldValidateHomeContinueCoverReference
import moe.ouom.neriplayer.ui.screen.tab.home.toPlayableSongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenMappingTest {

    @Test
    fun `persisted local continue cover is revalidated against playlist sources`() {
        val playlist = LocalPlaylist(id = 7L, name = "local")

        assertTrue(
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
        assertFalse(
            shouldResolveHomeContinueLocalCoverFallback(
                persistedCoverUrl = "https://example.com/cover.jpg",
                localPlaylist = playlist
            )
        )
        assertTrue(shouldValidateHomeContinueCoverReference("content://old-root/cover.jpg"))
        assertFalse(shouldValidateHomeContinueCoverReference("https://example.com/cover.jpg"))
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
    fun `home cover candidates accept each cover source and ignore blank references`() {
        val base = DownloadedSong(
            id = 1L,
            name = "song",
            artist = "artist",
            album = "album",
            filePath = "/music/song.mp3",
            fileSize = 1L,
            downloadTime = 1L
        )
        val candidates = homeLocalFilesCoverCandidates(
            listOf(
                base.copy(id = 1L, customCoverUrl = "content://custom"),
                base.copy(id = 2L, coverPath = "/music/cover.jpg"),
                base.copy(id = 3L, coverUrl = "https://example.com/cover.jpg"),
                base.copy(id = 4L),
                base.copy(id = 5L, customCoverUrl = "   ")
            )
        )

        assertEquals(listOf(1L, 2L, 3L), candidates.map(SongItem::id))
    }

    @Test
    fun `continue cover prefers resolved cover then usable persisted cover`() {
        assertEquals("content://fresh", selectContinueCoverUrl("content://fresh", "file://old"))
        assertEquals("file://old", selectContinueCoverUrl(" ", "file://old"))
        assertEquals("file://old", selectContinueCoverUrl(null, "file://old"))
        assertEquals(null, selectContinueCoverUrl(null, " "))
        assertEquals(null, selectContinueCoverUrl(null, null))
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
