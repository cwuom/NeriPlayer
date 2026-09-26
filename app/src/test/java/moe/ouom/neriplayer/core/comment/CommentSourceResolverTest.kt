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

    @Test
    fun `legacy identity retains cid even when bvid is absent`() {
        val source = resolveCommentSource(
            song(id = 1700010003L, album = "Bilibili|279787", subAudioId = "279787")
        )
        assertEquals(1700010003L, source?.resourceId)
        assertEquals(279787L, source?.subResourceId)
        assertEquals(false, source?.hasExplicitResourceId)
        assertNull(source?.secondaryId)
    }

    @Test
    fun `identity hints ignore presentation changes but retain title when needed`() {
        val canonical = song(id = 7L, album = "Bilibili|8|BV1test", audioId = "7")
        assertEquals(resolveCommentSource(canonical), resolveCommentSource(canonical.copy(name = "edited")))
        val legacy = song(id = 1700010003L, album = "Bilibili")
        assertEquals("song", resolveCommentSource(legacy)?.resourceTitle)
    }

    private companion object {
        const val BILI_AUDIO_URL =
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/5678/5678-1-30280.m4s"
    }

    /**
     * 构造测试用 SongItem：只填充专辑标签、channelId、audioId 等评论解析相关字段，便于逐项覆盖。
     */
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

    /**
     * 网易云音源：channelId=netease 时解析为网易云评论，资源 id 取 audioId 且 secondaryId 为 null。
     */
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

    /**
     * 网易云歌曲音频回退到 Bilibili 播放地址时，评论仍解析为网易云（平台只由逻辑音源决定）。
     */
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

    /**
     * Bilibili 音源：资源 id 取音频 id，secondaryId 从专辑标签解析出 BV 号。
     */
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

    /**
     * Bilibili 专辑标签不带 BV 号时 secondaryId 为 null，平台与资源 id 仍正常解析。
     */
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

    /**
     * 专辑标签写 Bilibili 而 channelId 明确为 netease 时，以 channelId 为准解析为网易云。
     */
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

    /**
     * 缺少 channelId 时退回专辑标签：分别解析出 Bilibili（含 BV 号）与网易云来源。
     */
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

    /**
     * channelId 为无法识别的历史值时忽略它，继续按专辑标签解析为 Bilibili。
     */
    @Test
    fun `unknown channel id still falls back to the album source tag`() {
        val source = resolveCommentSource(
            song(id = 11L, album = "Bilibili|55|BV1zz411c7mF", channelId = "some-legacy-id", audioId = "11")
        )

        assertEquals(CommentPlatform.BILIBILI, source?.platform)
        assertEquals(11L, source?.resourceId)
    }

    /**
     * channelId 匹配忽略大小写并去除首尾空格（"  BiliBili " 仍识别为 Bilibili）。
     */
    @Test
    fun `channel id is matched case insensitively and trimmed`() {
        val source = resolveCommentSource(
            song(id = 12L, album = "", channelId = "  BiliBili ", audioId = "12")
        )

        assertEquals(CommentPlatform.BILIBILI, source?.platform)
        assertEquals(12L, source?.resourceId)
    }

    /**
     * 不支持的平台（youtube_music / local / 本地音乐）与 null 音源一律返回 null。
     */
    @Test
    fun `unsupported source returns null`() {
        assertNull(resolveCommentSource(song(id = 13L, album = "youtube_music", channelId = "youtube_music")))
        assertNull(resolveCommentSource(song(id = 14L, album = "", channelId = "local")))
        assertNull(resolveCommentSource(song(id = 15L, album = "本地音乐", channelId = null)))
        assertNull(resolveCommentSource(null))
    }

    /**
     * audioId 为空白、非数字或非正数时，资源 id 统一退回歌曲自增 id。
     */
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

    /**
     * 歌曲 id 为 0 或负数时无法定位评论资源，返回 null。
     */
    @Test
    fun `non positive ids return null`() {
        assertNull(
            resolveCommentSource(song(id = 0L, album = "Netease", channelId = "netease", audioId = "0"))
        )
        assertNull(
            resolveCommentSource(song(id = -1L, album = "Netease", channelId = "netease", audioId = null))
        )
    }

    /**
     * 来源 key 形如「平台:资源id」，同一资源 id 在不同平台下互不冲突。
     */
    @Test
    fun `source key is stable and platform scoped`() {
        val netease = resolveCommentSource(song(id = 1L, album = "Netease", channelId = "netease", audioId = "123"))
        val bili = resolveCommentSource(song(id = 2L, album = "Bilibili", channelId = "bilibili", audioId = "123"))

        assertEquals("NETEASE:123", netease?.key)
        assertEquals("BILIBILI:123", bili?.key)
    }
}
