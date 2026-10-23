package moe.ouom.neriplayer.core.player.service.lyrics.coloros

import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorOsLyricPayloadTest {
    @Test
    fun `complete lanes carry typed identity real translation and absolute word timing`() {
        val lines = listOf(
            LyricEntry("第一句", 10_000L, 12_800L, listOf(WordTiming(10_000L, 10_700L, 2), WordTiming(10_700L, 12_800L, 1))),
            LyricEntry("second", 14_500L, 17_000L),
        )
        val json = JSONObject(payload(lines, listOf(LyricEntry("First line", 10_050L, 12_800L)), 500L)!!)
        assertEquals("[00:09.500]第一句\n[00:14.000]second\n", json.getString("lyric"))
        assertEquals("[00:09.500]<00:09.500>第一<00:10.200>句<00:12.300>\n", json.getString("rawLyric"))
        assertEquals("[00:09.500]First line\n", json.getString("translationLyric"))
        assertEquals("song", json.get("songId"))
        assertEquals("song", json.get("trackKey"))
        assertEquals("Song", json.get("songName"))
        assertEquals("Artist", json.get("artist"))
        assertEquals("moe.test", json.get("provider"))
        assertEquals(0, json.get("lyricType"))
        assertEquals(false, json.get("noLyric"))
        assertEquals(4L, json.getLong("sessionGeneration"))
    }

    @Test
    fun `negative timestamps clamp and absent words or translations stay absent`() {
        val json = JSONObject(payload(listOf(LyricEntry("line", 100L, 300L)), offsetMs = 500L)!!)
        assertEquals("[00:00.000]line\n", json.getString("lyric"))
        assertFalse(json.has("rawLyric"))
        assertFalse(json.has("translationLyric"))
        val delayed = JSONObject(payload(listOf(LyricEntry("line", 100L, 300L)), offsetMs = -500L)!!)
        assertEquals("[00:00.600]line\n", delayed.getString("lyric"))
    }

    @Test
    fun `invalid UTF16 boundaries and decreasing word intervals do not invent karaoke`() {
        val invalid = LyricEntry("😀a", 1000L, 3000L, listOf(WordTiming(1000L, 2000L, 1), WordTiming(2000L, 3000L, 2)))
        assertFalse(JSONObject(payload(listOf(invalid))!!).has("rawLyric"))
        val valid = invalid.copy(words = listOf(WordTiming(1000L, 2000L, 2), WordTiming(2000L, 3000L, 1)))
        assertEquals("[00:01.000]<00:01.000>😀<00:02.000>a<00:03.000>\n", JSONObject(payload(listOf(valid))!!).getString("rawLyric"))
        val backwards = valid.copy(words = listOf(WordTiming(2000L, 2500L, 2), WordTiming(1000L, 3000L, 1)))
        assertFalse(JSONObject(payload(listOf(backwards))!!).has("rawLyric"))
    }

    @Test
    fun `empty oversized and foreign track payloads are omitted`() {
        assertNull(payload(emptyList()))
        assertNull(payload(listOf(LyricEntry(" ", 0L, 1000L))))
        assertNull(payload(listOf(LyricEntry("a".repeat(300_000), 0L, 1000L))))
        val timeline = ColorOsLyricTimeline(ColorOsLyricRequest("other", 1L, 1L), listOf(LyricEntry("line", 0L, 1000L)), emptyList(), 0L)
        assertNull(colorOsLyricPayload(timeline, "song", "Song", "Artist", "moe.test"))
    }

    @Test
    fun `translation lane removes blank and credit lines and never copies original text`() {
        val lines = listOf(
            LyricEntry("作词：someone", 0L, 500L),
            LyricEntry(" ", 500L, 1000L),
            LyricEntry("first", 1000L, 2000L, translation = "第一句"),
            LyricEntry("second", 3000L, 4000L),
        )
        val json = JSONObject(payload(lines, listOf(LyricEntry("second", 3000L, 4000L)))!!)
        assertEquals("[00:01.000]first\n[00:03.000]second\n", json.getString("lyric"))
        assertEquals("[00:01.000]第一句\n", json.getString("translationLyric"))
    }

    private fun payload(lines: List<LyricEntry>, translations: List<LyricEntry> = emptyList(), offsetMs: Long = 0L): String? =
        colorOsLyricPayload(ColorOsLyricTimeline(ColorOsLyricRequest("song", 4L, 8L), lines, translations, offsetMs), "song", "Song", "Artist", "moe.test")
}
