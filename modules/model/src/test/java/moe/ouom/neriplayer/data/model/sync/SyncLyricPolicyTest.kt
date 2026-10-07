package moe.ouom.neriplayer.data.model.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLyricPolicyTest {
    private fun override(
        lyric: String? = null,
        translated: String? = null,
        romanized: String? = null,
        original: String? = null,
        originalTranslated: String? = null,
        originalRomanized: String? = null,
        source: String? = null
    ) = hasLegacyLyricOverride(lyric, translated, romanized, original, originalTranslated, originalRomanized, source)

    @Test
    fun `legacy rows without lyric text carry no override`() {
        assertFalse(override(original = "baseline", source = "CLOUD_MUSIC"))
    }

    @Test
    fun `a recorded match source marks the legacy lyric as an override`() {
        assertTrue(override(lyric = "baseline", original = "baseline", source = "CLOUD_MUSIC"))
    }

    @Test
    fun `without a source only text differing from the original is an override`() {
        assertFalse(override(lyric = "baseline", original = "baseline"))
        assertFalse(override(romanized = "roma", originalRomanized = "roma"))
        assertTrue(override(lyric = "edited", original = "baseline"))
        assertTrue(override(translated = "translation"))
        assertTrue(override(lyric = "baseline", romanized = "roma", original = "baseline", originalRomanized = "old"))
    }

    @Test
    fun `lyric revisions move forward from the clock or the previous revision`() {
        assertEquals(100L, nextLyricSyncRevision(previous = 5L, now = 100L))
        assertEquals(101L, nextLyricSyncRevision(previous = 100L, now = 50L))
        assertEquals(1L, nextLyricSyncRevision(previous = -5L, now = 0L))
        assertThrows(IllegalStateException::class.java) {
            nextLyricSyncRevision(previous = Long.MAX_VALUE, now = 0L)
        }
    }
}
