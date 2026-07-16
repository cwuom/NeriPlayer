package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.util.network.isTransientHttp2StreamReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlayerLyricsProviderTest {

    @Test
    fun `resolveLocalLyricOverrideState keeps blank local override as cleared`() {
        assertEquals(LocalLyricOverrideState.ABSENT, resolveLocalLyricOverrideState(null))
        assertEquals(LocalLyricOverrideState.CLEARED, resolveLocalLyricOverrideState(""))
        assertEquals(LocalLyricOverrideState.CLEARED, resolveLocalLyricOverrideState("   "))
        assertEquals(LocalLyricOverrideState.PRESENT, resolveLocalLyricOverrideState("[00:00.00]歌词"))
    }

    @Test
    fun `isTransientHttp2StreamReset detects cancel and refused stream failures`() {
        assertTrue(IOException("stream was reset: CANCEL").isTransientHttp2StreamReset())

        val wrapped = IOException("lyric request failed").apply {
            addSuppressed(IOException("stream was reset: REFUSED_STREAM"))
        }
        assertTrue(wrapped.isTransientHttp2StreamReset())
    }

    @Test
    fun `isTransientHttp2StreamReset keeps normal network failures visible`() {
        assertFalse(IOException("timeout").isTransientHttp2StreamReset())
        assertFalse(IOException("stream was reset: INTERNAL_ERROR").isTransientHttp2StreamReset())
    }

    @Test
    fun `extractPreferredNeteaseLyricContent prefers yrc over lrc`() {
        val payload = """
            {
              "code": 200,
              "yrc": {
                "lyric": "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记"
              },
              "lrc": {
                "lyric": "[00:12.58]难以忘记"
              }
            }
        """.trimIndent()

        val preferred = extractPreferredNeteaseLyricContent(payload)
        val parsed = parseNeteaseLyricsAuto(preferred)

        assertEquals("[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记", preferred)
        assertEquals("难以忘记", parsed.single().text)
        assertNotNull(parsed.single().words)
        assertEquals(3, parsed.single().words!!.size)
    }

    @Test
    fun `extractPreferredNeteaseLyricContent falls back to lrc when yrc missing`() {
        val payload = """
            {
              "code": 200,
              "lrc": {
                "lyric": "[00:12.58]难以忘记"
              }
            }
        """.trimIndent()

        val preferred = extractPreferredNeteaseLyricContent(payload)
        val parsed = parseNeteaseLyricsAuto(preferred)

        assertEquals("[00:12.58]难以忘记", preferred)
        assertEquals("难以忘记", parsed.single().text)
        assertNull(parsed.single().words)
    }

    @Test
    fun `parseNeteaseLyricsAuto parses ttml word timings`() {
        val rawLyrics = """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <div>
                        <p begin="00:01.000" end="00:02.000">
                            <span begin="00:01.000" end="00:01.500">Hello</span>
                            <span begin="00:01.500" end="00:02.000">World</span>
                        </p>
                    </div>
                </body>
            </tt>
        """.trimIndent()

        val parsed = parseNeteaseLyricsAuto(rawLyrics)

        assertEquals("HelloWorld", parsed.single().text)
        assertNotNull(parsed.single().words)
        assertEquals(2, parsed.single().words!!.size)
    }

    @Test
    fun `extractRomanizedNeteaseLyricContent reads romalrc`() {
        val payload = """
            {
              "code": 200,
              "romalrc": {
                "lyric": "[00:23.88]1/3 6 5 no ki ma gu re de"
              }
            }
        """.trimIndent()

        val romanized = extractRomanizedNeteaseLyricContent(payload)
        val parsed = parseNeteaseLyricsAuto(romanized)

        assertEquals("[00:23.88]1/3 6 5 no ki ma gu re de", romanized)
        assertEquals("1/3 6 5 no ki ma gu re de", parsed.single().text)
    }

    @Test
    fun `buildNeteaseLyricsCacheEntry parses original translated and romanized lyrics from one payload`() {
        val payload = """
            {
              "code": 200,
              "yrc": {
                "lyric": "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记"
              },
              "tlyric": {
                "lyric": "[00:12.58]hard to forget"
              },
              "romalrc": {
                "lyric": "[00:12.58]na n yi wang ji"
              }
            }
        """.trimIndent()

        val entry = PlayerLyricsProvider.buildNeteaseLyricsCacheEntry(payload)

        assertEquals(
            "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记",
            entry.preferredLyricText
        )
        assertEquals("难以忘记", entry.preferredLyricEntries.single().text)
        assertEquals("hard to forget", entry.translatedLyricEntries.single().text)
        assertEquals("[00:12.58]na n yi wang ji", entry.romanizedLyricText)
        assertEquals("na n yi wang ji", entry.romanizedLyricEntries.single().text)
    }
}
