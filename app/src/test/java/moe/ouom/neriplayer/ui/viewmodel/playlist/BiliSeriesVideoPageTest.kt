package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliPagedVideoPageTest {
    @Test
    fun `merges only the next archive page and keeps server total`() {
        val merged = mergeBiliPagedVideoPage(
            existingVideos = listOf(video(id = 1L, bvid = "BV1")),
            incomingVideos = listOf(
                video(id = 1L, bvid = "BV1"),
                video(id = 2L, bvid = "BV2")
            ),
            totalCount = 80,
            hasMore = true
        )

        assertEquals(listOf("BV1", "BV2"), merged.videos.map(BiliVideoItem::bvid))
        assertEquals(80, merged.totalCount)
        assertTrue(merged.hasMore)
    }

    @Test
    fun `stops paging when a further page has no items`() {
        val merged = mergeBiliPagedVideoPage(
            existingVideos = listOf(video(id = 1L, bvid = "BV1")),
            incomingVideos = emptyList(),
            totalCount = 1,
            hasMore = true
        )

        assertFalse(merged.hasMore)
    }

    @Test
    fun `retains a collection total when its first page is partial`() {
        val merged = mergeBiliPagedVideoPage(
            existingVideos = emptyList(),
            incomingVideos = listOf(video(id = 1L, bvid = "BV1")),
            totalCount = 63,
            hasMore = true
        )

        assertEquals(63, merged.totalCount)
        assertTrue(merged.hasMore)
    }

    private fun video(id: Long, bvid: String) = BiliVideoItem(
        id = id,
        bvid = bvid,
        title = "video $id",
        uploader = "uploader",
        coverUrl = "",
        durationSec = 0
    )
}
