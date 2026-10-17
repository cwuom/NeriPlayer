package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.platform.comments.resolveCommentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌曲百科评论预览的准入判断。
 *
 * 百科评论预览只对网易云歌曲开放：其他平台暂时没有歌曲百科数据源，
 * 因此不该在百科页出现评论预览（完整评论弹窗仍由各自的入口提供）。
 */
class SongEncyclopediaCommentPreviewPolicyTest {

    @Test
    fun `netease song resolves to a netease comment source eligible for preview`() {
        val source = resolveCommentSource(neteaseSong(186016L))

        assertEquals(CommentPlatform.NETEASE, source?.platform)
        assertEquals(186016L, source?.resourceId)
        assertTrue(isPreviewEligible(source))
    }

    @Test
    fun `bilibili song resolves to a bilibili source but is not eligible for preview`() {
        val source = resolveCommentSource(biliSong())

        assertEquals(CommentPlatform.BILIBILI, source?.platform)
        // B 站没有歌曲百科，因此百科页不渲染评论预览
        assertTrue(!isPreviewEligible(source))
    }

    @Test
    fun `local song has no comment source and no preview`() {
        val source = resolveCommentSource(localSong())

        assertNull(source)
        assertTrue(!isPreviewEligible(source))
    }

    @Test
    fun `netease song without positive resource id is not eligible`() {
        val source = CommentSource(platform = CommentPlatform.NETEASE, resourceId = 0L)

        assertTrue(!isPreviewEligible(source))
    }

    /** 与 `SongEncyclopediaBody` 中的判断保持一致。 */
    private fun isPreviewEligible(source: CommentSource?): Boolean {
        if (source == null) return false
        return source.platform == CommentPlatform.NETEASE && source.resourceId > 0L
    }

    private fun neteaseSong(id: Long): SongItem = SongItem(
        id = id,
        name = "Song$id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 200_000L,
        coverUrl = null,
        channelId = "netease",
        audioId = id.toString()
    )

    private fun biliSong(): SongItem = SongItem(
        id = 1700010003L,
        name = "Bili Song",
        artist = "UP",
        album = "Bilibili",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        channelId = "bilibili",
        audioId = "1700010003"
    )

    private fun localSong(): SongItem = SongItem(
        id = 42L,
        name = "Local",
        artist = "Local Artist",
        album = "Local Album",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        mediaUri = "content://media/42"
    )
}
