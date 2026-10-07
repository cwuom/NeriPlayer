package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BiliArchiveUploaderFallbackTest {

    @Test
    fun `blank uploader leaves the archive videos untouched`() {
        val videos = listOf(video(id = 1L, uploaderMid = 7L))

        assertSame(videos, applyBiliArchiveUploader(videos, uploader = "   ", uploaderMid = 99L))
    }

    @Test
    fun `unknown uploader mid keeps each video mid while the name is trimmed`() {
        val videos = listOf(
            video(id = 1L, uploaderMid = 7L),
            video(id = 2L, uploaderMid = 0L)
        )

        val resolved = applyBiliArchiveUploader(videos, uploader = "  UP 主  ", uploaderMid = 0L)

        assertEquals(listOf("UP 主", "UP 主"), resolved.map(BiliVideoItem::uploader))
        assertEquals(listOf(7L, 0L), resolved.map(BiliVideoItem::uploaderMid))
        assertEquals(listOf("BV1", "BV2"), resolved.map(BiliVideoItem::bvid))
    }

    private fun video(id: Long, uploaderMid: Long) = BiliVideoItem(
        id = id,
        bvid = "BV$id",
        title = "video $id",
        uploader = "合集",
        uploaderMid = uploaderMid,
        coverUrl = "",
        durationSec = 0
    )
}
