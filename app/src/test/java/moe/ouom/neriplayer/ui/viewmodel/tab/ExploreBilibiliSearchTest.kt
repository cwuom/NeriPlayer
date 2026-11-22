package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.bilibili.search.SearchUploaderPage
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoPage
import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderProfile
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ExploreBilibiliSearchTest {
    private val client = mock(BiliClient::class.java)

    @Test
    fun `songs use music search while videos preserve all-site ordering and whole-video playback IDs`() = runTest {
        val items = listOf(video(10L, "First"), video(20L, "Needle"))
        val page = SearchVideoPage(page = 2, pageSize = 2, numResults = 6, numPages = 3, items = items)
        `when`(client.searchMusicVideos("api keyword", 2)).thenReturn(page)
        `when`(client.searchVideos("api keyword", 2)).thenReturn(page.copy(items = items + video(30L, "Other")))

        val songs = client.fetchExplorePage("api keyword", "needle", 2, BilibiliExploreSearchType.SONG)
        val videos = client.fetchExplorePage("api keyword", "needle", 2, BilibiliExploreSearchType.VIDEO)

        assertEquals(listOf(20L, 10L), songs.songs.map { it.id })
        assertEquals(listOf(10L, 20L, 30L), videos.songs.map { it.id })
        assertEquals(2, songs.page)
        assertTrue(videos.hasMore)
        val first = videos.songs.first()
        assertEquals("bilibili", first.channelId)
        assertEquals("10", first.audioId)
        assertTrue(first.album.endsWith(items.first().bvid))
        assertNull(first.subAudioId)
        assertEquals(30_000L, first.durationMs)
    }

    @Test
    fun `creator pages keep distinct mids across same-name profiles and out of playback`() = runTest {
        val first = UploaderProfile(81L, "Same", "https://example.com/81.jpg", "", "")
        val second = first.copy(mid = 82L, faceUrl = "https://example.com/82.jpg")
        `when`(client.searchUploaders("name", 1)).thenReturn(SearchUploaderPage(1, 2, listOf(first, first)))
        `when`(client.searchUploaders("name", 2)).thenReturn(SearchUploaderPage(2, 2, listOf(first, second)))

        val firstPage = client.fetchExplorePage("name", "name", 1, BilibiliExploreSearchType.CREATOR)
        val lastPage = client.fetchExplorePage("name", "name", 2, BilibiliExploreSearchType.CREATOR)
        val merged = mergeExploreSearchResults(firstPage.items, lastPage.items)

        assertTrue(firstPage.hasMore)
        assertEquals(1, firstPage.items.size)
        assertFalse(lastPage.hasMore)
        assertEquals(2, lastPage.page)
        assertTrue(searchSongItems(merged).isEmpty())
        assertEquals(
            listOf(BiliUploaderSummary(81L, "Same", first.faceUrl), BiliUploaderSummary(82L, "Same", second.faceUrl)),
            merged.map { (it as ExploreSearchResult.BilibiliCreator).creator }
        )
    }

    private fun video(id: Long, title: String) = SearchVideoItem(
        aid = id,
        bvid = "BV1xx411c7mD",
        titleHtml = title,
        titlePlain = title,
        author = "Uploader",
        mid = 81L,
        coverUrl = "",
        durationSec = 30,
        play = null,
        pubdate = null
    )
}
