package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.ui.component.parseNeteaseLyricsAuto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerLyricsProviderTest {

    @Test
    fun `resolveLocalLyricOverrideState keeps blank local override as cleared`() {
        assertEquals(LocalLyricOverrideState.ABSENT, resolveLocalLyricOverrideState(null))
        assertEquals(LocalLyricOverrideState.CLEARED, resolveLocalLyricOverrideState(""))
        assertEquals(LocalLyricOverrideState.CLEARED, resolveLocalLyricOverrideState("   "))
        assertEquals(LocalLyricOverrideState.PRESENT, resolveLocalLyricOverrideState("[00:00.00]歌词"))
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
}
