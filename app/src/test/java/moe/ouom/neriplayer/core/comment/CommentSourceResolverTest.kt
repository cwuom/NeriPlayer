package moe.ouom.neriplayer.core.comment

import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 评论来源解析的单元测试。
 *
 * 核心规则 (§3/§49.2): 评论平台只由「逻辑音源」决定, 与最终播放地址无关。
 */
class CommentSourceResolverTest {

    private companion object {
        const val BILI_AUDIO_URL =
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/5678/5678-1-30280.m4s"
    }

    private fun song(
        id: Long = 1L,
        album: String = "",
        channelId: String? = null,
        audioId: String? = null,
        subAudioId: String? = null,
        mediaUri: String? = null,
        streamUrl: String? = null
    ) = SongItem(
        id = id,
        name = "song",
        artist = "artist",
        album = album,
        albumId = 0L,
        durationMs = 0L,
        coverUrl = null,
        mediaUri = mediaUri,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        streamUrl = streamUrl
    )

    @Test
    fun `netease song resolves to netease comments`() {
        val source = resolveCommentSource(
            song(
                id = 33894312L,
                album = "Netease",
                channelId = "netease",
                audioId = "33894312"
            )
        )

        assertEquals(CommentPlatform.NETEASE, source?.platform)
        assertEquals(33894312L, source?.resourceId)
        assertNull(source?.secondaryId)
    }

    @Test
    fun `netease song whose audio fell back to bilibili still resolves to netease comments`() {
        // 任务书 §49.2 的规范用例: 网易云歌曲音频回退到 Bilibili,
        // 播放地址是 Bilibili 的, 但评论必须仍然是网易云评论。
        val source = resolveCommentSource(
            song(
                id = 33894312L,
                album = "Netease",
                channelId = "netease",
                audioId = "33894312",
                mediaUri = BILI_AUDIO_URL,
                streamUrl = BILI_AUDIO_URL
            )
        )

        assertEquals(CommentPlatform.NETEASE, source?.platform)
        assertEquals(33894312L, source?.resourceId)
    }

    @Test
    fun `bilibili song resolves to bilibili comments with aid and bvid`() {
        val source = resolveCommentSource(
            song(
                id = 12345L,
                album = "Bilibili|987654|BV1xx411c7mD",
                channelId = "bilibili",
                audioId = "12345",
                subAudioId = "987654"
            )
        )

        assertEquals(CommentPlatform.BILIBILI, source?.platform)
        assertEquals(12345L, source?.resourceId)
        assertEquals("BV1xx411c7mD", source?.secondaryId)
    }

    @Test
    fun `bilibili album without bvid yields null secondary id`() {
        val source = resolveCommentSource(
            song(
                id = 12345L,
                album = "Bilibili",
                channelId = "bilibili",
                audioId = "12345"
            )
        )

        assertEquals(CommentPlatform.BILIBILI, source?.platform)
        assertNull(source?.secondaryId)
    }

    @Test
    fun `explicit channel id wins over the album source tag`() {
        // album 标记是 Bilibili, 但 channelId 明确写着 netease -> 以 channelId 为准
        val source = resolveCommentSource(
            song(
                id = 7L,
                album = "Bilibili|1|BV1xx411c7mD",
                channelId = "netease",
                audioId = "7"
            )
        )

        assertEquals(CommentPlatform.NETEASE, source?.platform)
        assertEquals(7L, source?.resourceId)
    }

    @Test
    fun `album source tag is used when channel id is missing`() {
        val bili = resolveCommentSource(
            song(id = 9L, album = "Bilibili|42|BV1yy411c7mE", audioId = "9")
        )
        assertEquals(CommentPlatform.BILIBILI, bili?.platform)
        assertEquals(9L, bili?.resourceId)
        assertEquals("BV1yy411c7mE", bili?.secondaryId)

        val netease = resolveCommentSource(song(id = 10L, album = "Netease", audioId = "10"))
        assertEquals(CommentPlatform.NETEASE, netease?.platform)
        assertEquals(10L, netease?.resourceId)
    }

    @Test
    fun `unknown channel id still falls back to the album source tag`() {
        val source = resolveCommentSource(
            song(id = 11L, album = "Bilibili|55|BV1zz411c7mF", channelId = "some-legacy-id", audioId = "11")
        )

        assertEquals(CommentPlatform.BILIBILI, source?.platform)
        assertEquals(11L, source?.resourceId)
    }

    @Test
    fun `channel id is matched case insensitively and trimmed`() {
        val source = resolveCommentSource(
            song(id = 12L, album = "", channelId = "  BiliBili ", audioId = "12")
        )

        assertEquals(CommentPlatform.BILIBILI, source?.platform)
        assertEquals(12L, source?.resourceId)
    }

    @Test
    fun `unsupported source returns null`() {
        assertNull(resolveCommentSource(song(id = 13L, album = "youtube_music", channelId = "youtube_music")))
        assertNull(resolveCommentSource(song(id = 14L, album = "", channelId = "local")))
        assertNull(resolveCommentSource(song(id = 15L, album = "本地音乐", channelId = null)))
        assertNull(resolveCommentSource(null))
    }

    @Test
    fun `blank or invalid audio id falls back to the song id`() {
        val blank = resolveCommentSource(
            song(id = 55L, album = "Netease", channelId = "netease", audioId = "   ")
        )
        assertEquals(55L, blank?.resourceId)

        val invalid = resolveCommentSource(
            song(id = 56L, album = "Netease", channelId = "netease", audioId = "not-a-number")
        )
        assertEquals(56L, invalid?.resourceId)

        val nonPositive = resolveCommentSource(
            song(id = 57L, album = "Netease", channelId = "netease", audioId = "-3")
        )
        assertEquals(57L, nonPositive?.resourceId)
    }

    @Test
    fun `non positive ids return null`() {
        assertNull(
            resolveCommentSource(song(id = 0L, album = "Netease", channelId = "netease", audioId = "0"))
        )
        assertNull(
            resolveCommentSource(song(id = -1L, album = "Netease", channelId = "netease", audioId = null))
        )
    }

    @Test
    fun `source key is stable and platform scoped`() {
        val netease = resolveCommentSource(song(id = 1L, album = "Netease", channelId = "netease", audioId = "123"))
        val bili = resolveCommentSource(song(id = 2L, album = "Bilibili", channelId = "bilibili", audioId = "123"))

        assertEquals("NETEASE:123", netease?.key)
        assertEquals("BILIBILI:123", bili?.key)
    }
}
