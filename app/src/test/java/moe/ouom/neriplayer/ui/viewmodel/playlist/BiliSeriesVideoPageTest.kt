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

    @Test
    fun `resolved archive uploader replaces the legacy collection title fallback`() {
        val videos = listOf(
            video(id = 1L, bvid = "BV1").copy(uploader = "合集", uploaderMid = 0L),
            video(id = 2L, bvid = "BV2").copy(uploader = "合集", uploaderMid = 0L)
        )

        val resolved = applyBiliArchiveUploader(
            videos = videos,
            uploader = "UP 主",
            uploaderMid = 123456L
        )

        assertEquals(listOf("UP 主", "UP 主"), resolved.map(BiliVideoItem::uploader))
        assertEquals(listOf(123456L, 123456L), resolved.map(BiliVideoItem::uploaderMid))
        assertEquals(listOf("BV1", "BV2"), resolved.map(BiliVideoItem::bvid))
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
