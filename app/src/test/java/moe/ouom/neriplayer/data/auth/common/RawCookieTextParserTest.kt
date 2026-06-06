package moe.ouom.neriplayer.data.auth.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RawCookieTextParserTest {
    @Test
    fun `parseRawCookieText parses semicolon separated header`() {
        val parsed = parseRawCookieText("SESSDATA=sess; bili_jct=csrf; DedeUserID=123")

        assertEquals("sess", parsed["SESSDATA"])
        assertEquals("csrf", parsed["bili_jct"])
        assertEquals("123", parsed["DedeUserID"])
    }

    @Test
    fun `parseRawCookieText supports newline separated cookies`() {
        val parsed = parseRawCookieText(
            """
            MUSIC_U=music-cookie
            __csrf=csrf-token
            os=pc
            """.trimIndent()
        )

        assertEquals("music-cookie", parsed["MUSIC_U"])
        assertEquals("csrf-token", parsed["__csrf"])
        assertEquals("pc", parsed["os"])
    }

    @Test
    fun `parseRawCookieText ignores malformed segments and blank values`() {
        val parsed = parseRawCookieText("bad; GOOD=value; EMPTY= ; =oops; PREF=a=b=c")

        assertEquals("value", parsed["GOOD"])
        assertEquals("a=b=c", parsed["PREF"])
        assertFalse(parsed.containsKey("EMPTY"))
        assertFalse(parsed.containsKey(""))
    }
}
