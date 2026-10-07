package moe.ouom.neriplayer.platform.search.api.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQMusicBase64LyricTest {

    @Test
    fun `base64 lyrics decode as utf8 even when wrapped across lines`() {
        assertEquals("[00:02.00]你好", decodeQQMusicBase64Lyric("WzAwOjAy\nLjAwXeS9 oOWlvQ=="))
    }

    @Test
    fun `base64 lyrics must be well formed and contain timestamps`() {
        assertNull(decodeQQMusicBase64Lyric(" \n "))
        assertNull(decodeQQMusicBase64Lyric("abc"))
        assertNull(decodeQQMusicBase64Lyric("ab-d"))
        assertNull(decodeQQMusicBase64Lyric("a=bc"))
        assertNull(decodeQQMusicBase64Lyric("cGxhaW4gd29yZHMgb25seQ=="))
    }

    @Test
    fun `lyric payloads unescape html entities in plain or base64 form`() {
        assertNull(decodeQQMusicLyricPayload(null))
        assertNull(decodeQQMusicLyricPayload("   "))
        assertEquals(
            "[00:01.00]Tom & Jerry's \"song\"",
            decodeQQMusicLyricPayload(" [00:01.00]Tom &amp; Jerry&#39;s &quot;song&quot; ")
        )
        assertEquals("[00:01.00]Rock & Roll", decodeQQMusicLyricPayload("WzAwOjAxLjAwXVJvY2sgJmFtcDsgUm9sbA=="))
    }
}
