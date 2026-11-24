package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicHomeSnapshotTest {

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
    fun appendKeepsOriginalOrderAndSkipsRepeatedCardsFromTheSameShelf() {
        val a = homePlaylistItem("A", "VLPL-a", "1 song")
        val b = homePlaylistItem("B", "VLPL-b", "2 songs")
        val original = YouTubeMusicHomeShelf("Discover", listOf(a))
        val page = listOf(original.copy(items = listOf(a, b)), YouTubeMusicHomeShelf("Mixes", listOf(a)))
        assertEquals(
            listOf(original, original.copy(items = listOf(b)), page.last()),
            appendYouTubeMusicHomeShelves(listOf(original), page)
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
