package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenMappingTest {

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
