package moe.ouom.neriplayer.core.player.lyrics.floating

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FloatingLyricsContentSourceTest {
    private val source = FloatingLyricsContentSource()
    private val lyrics = listOf(
        LyricEntry("first", 1000L, 2000L),
        LyricEntry("   ", 2000L, 3000L),
        LyricEntry("second", 3000L, 4000L),
        LyricEntry("third", 4000L, 5000L)
    )

    @Test
    fun `window applies offsets skips blank previews and reuses unchanged content`() {
        val translations = mapOf(0 to LyricEntry("第一句", 1000L, 2000L))
        source.update("song", lyrics, translations, 500L, 600L)
        val content = source.content.value
        assertEquals(FloatingLyricsContent("song", "first", "第一句", "second", "third"), content)
        repeat(1000) { source.update("song", lyrics, translations, 1100L + it, 0L) }
        // Seek back into the first sentence, then verify progress ticks retain the same snapshot instance.
        source.update("song", lyrics, translations, 1100L, 0L)
        val active = source.content.value
        repeat(800) { source.update("song", lyrics, translations, 1100L + it, 0L) }
        assertSame(active, source.content.value)
        assertEquals(content, active)
    }

    @Test
    fun `seeks gaps and final sentence never retain old previews`() {
        source.update("song", lyrics, emptyMap(), 500L, 0L)
        assertNull(source.content.value.lyric)
        source.update("song", lyrics, emptyMap(), 3100L, 0L)
        assertEquals("second", source.content.value.lyric)
        assertEquals("third", source.content.value.nextLyric)
        assertNull(source.content.value.secondNextLyric)
        source.update("song", lyrics, emptyMap(), 2100L, 0L)
        assertNull(source.content.value.lyric)
        assertNull(source.content.value.nextLyric)
        source.update("song", lyrics, emptyMap(), 4000L, 0L)
        assertEquals("third", source.content.value.lyric)
        assertNull(source.content.value.nextLyric)
        source.update("song", lyrics, emptyMap(), 6501L, 0L)
        assertNull(source.content.value.lyric)
    }

    @Test
    fun `repeated text still advances preview and late translations update atomically`() {
        val repeated = listOf(
            LyricEntry("same", 1000L, 2000L), LyricEntry("same", 2000L, 3000L),
            LyricEntry("last", 3000L, 4000L)
        )
        source.update("song", repeated, emptyMap(), 1000L, 0L)
        assertEquals("same", source.content.value.nextLyric)
        source.update("song", repeated, emptyMap(), 2000L, 0L)
        assertEquals("last", source.content.value.nextLyric)
        source.update("song", repeated, mapOf(1 to LyricEntry("译文", 2000L, 3000L)), 2000L, 0L)
        assertEquals(FloatingLyricsContent("song", "same", "译文", "last"), source.content.value)
    }

    @Test
    fun `switching timeline or song clears all previous sentence data`() {
        source.update("old", lyrics, emptyMap(), 1000L, 0L)
        source.clear()
        assertEquals(FloatingLyricsContent(), source.content.value)
        source.clear()
        source.update("new", emptyList(), emptyMap(), 1000L, 0L)
        assertEquals(FloatingLyricsContent(songKey = "new"), source.content.value)
        source.update("new", lyrics, emptyMap(), 1000L, 0L)
        assertEquals("new", source.content.value.songKey)
    }

    @Test
    fun `offset arithmetic saturates at both time limits`() {
        source.update("song", lyrics, emptyMap(), Long.MAX_VALUE, 1000L)
        assertNull(source.content.value.lyric)
        source.update("song", lyrics, emptyMap(), Long.MIN_VALUE, -1000L)
        assertNull(source.content.value.lyric)
    }
}
