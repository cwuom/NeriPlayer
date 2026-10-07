package moe.ouom.neriplayer.network.http.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawHeaderTextParserTest {

    @Test
    fun `unusable header lines are skipped and later names win`() {
        val parsed = parseRawHeaderText(
            listOf(
                "User-Agent: Neri/1.0",
                "",
                "   ",
                ":authority: music.163.com",
                "missing delimiter",
                "X-Empty:   ",
                "  Cookie :  a=b; c=d  ",
                "Referer: https://music.163.com/#/song?id=1",
                "user-agent: Override"
            ).joinToString("\r\n")
        )

        assertEquals(
            listOf(
                "user-agent" to "Override",
                "cookie" to "a=b; c=d",
                "referer" to "https://music.163.com/#/song?id=1"
            ),
            parsed.toList()
        )
    }

    @Test
    fun `blank header text parses to an empty map`() {
        assertTrue(parseRawHeaderText(" \n\n ").isEmpty())
    }
}
