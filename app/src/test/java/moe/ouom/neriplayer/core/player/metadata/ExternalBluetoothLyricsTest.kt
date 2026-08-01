package moe.ouom.neriplayer.core.player.metadata

import android.media.AudioDeviceInfo
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBluetoothLyricsTest {
    @Test
    fun `findExternalBluetoothLyricLine applies offset and trims blank lines`() {
        val lyrics = listOf(
            LyricEntry(" intro ", startTimeMs = 1_000L, endTimeMs = 1_500L),
            LyricEntry("verse", startTimeMs = 2_000L, endTimeMs = 3_000L),
            LyricEntry("   ", startTimeMs = 4_000L, endTimeMs = 5_000L)
        )

        assertNull(findExternalBluetoothLyricLine(lyrics, 500L))
        assertEquals("intro", findExternalBluetoothLyricLine(lyrics, 1_000L))
        assertEquals("verse", findExternalBluetoothLyricLine(lyrics, 1_500L, 600L))
        assertNull(findExternalBluetoothLyricLine(lyrics, 4_500L))
    }

    @Test
    fun `findExternalBluetoothLyricLine clears an expired final line`() {
        val lyrics = listOf(
            LyricEntry("last line", startTimeMs = 1_000L, endTimeMs = 2_000L)
        )

        assertEquals(
            "last line",
            findExternalBluetoothLyricLine(
                lyrics = lyrics,
                positionMs = 2_000L + EXTERNAL_BLUETOOTH_LYRIC_STALE_GRACE_MS
            )
        )
        assertNull(
            findExternalBluetoothLyricLine(
                lyrics = lyrics,
                positionMs = 2_001L + EXTERNAL_BLUETOOTH_LYRIC_STALE_GRACE_MS
            )
        )
    }

    @Test
    fun `findFloatingTranslatedLyricLine only shows translation for current lyric line`() {
        val lyrics = listOf(
            LyricEntry("first", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry("second", startTimeMs = 2_000L, endTimeMs = 3_000L),
            LyricEntry("third", startTimeMs = 3_000L, endTimeMs = 4_000L)
        )
        val translations = listOf(
            LyricEntry("第一句", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry("第三句", startTimeMs = 3_000L, endTimeMs = 4_000L)
        )

        assertEquals(
            "第一句",
            findFloatingTranslatedLyricLine(lyrics, translations, positionMs = 1_200L)
        )
        assertNull(findFloatingTranslatedLyricLine(lyrics, translations, positionMs = 2_200L))
        assertEquals(
            "第三句",
            findFloatingTranslatedLyricLine(lyrics, translations, positionMs = 3_200L)
        )
    }

    @Test
    fun `findFloatingTranslatedLyricLine trims blanks and ignores blank current lyric`() {
        val lyrics = listOf(
            LyricEntry("   ", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry("line", startTimeMs = 2_000L, endTimeMs = 3_000L)
        )
        val translations = listOf(
            LyricEntry("不应显示", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry("  译文  ", startTimeMs = 2_000L, endTimeMs = 3_000L)
        )

        assertNull(findFloatingTranslatedLyricLine(lyrics, translations, positionMs = 1_200L))
        assertEquals("译文", findFloatingTranslatedLyricLine(lyrics, translations, positionMs = 2_200L))
    }

    @Test
    fun `findFloatingTranslatedLyricLine applies lyric offset before choosing current line`() {
        val lyrics = listOf(
            LyricEntry("first", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry("second", startTimeMs = 2_000L, endTimeMs = 3_000L)
        )
        val translations = listOf(
            LyricEntry("第一句", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry("第二句", startTimeMs = 2_000L, endTimeMs = 3_000L)
        )

        assertEquals(
            "第一句",
            findFloatingTranslatedLyricLine(
                lyrics = lyrics,
                translations = translations,
                positionMs = 1_500L
            )
        )
        assertEquals(
            "第二句",
            findFloatingTranslatedLyricLine(
                lyrics = lyrics,
                translations = translations,
                positionMs = 1_500L,
                lyricOffsetMs = 600L
            )
        )
    }

    @Test
    fun `findFloatingTranslatedLyricLine keeps early overlapping translation aligned`() {
        val lyrics = listOf(
            LyricEntry("The road gets cold", startTimeMs = 68_020L, endTimeMs = 70_480L),
            LyricEntry("There's no spring in the middle this year", startTimeMs = 70_510L, endTimeMs = 75_250L)
        )
        val translations = listOf(
            LyricEntry("踽踽独行 长路孤冷", startTimeMs = 66_440L, endTimeMs = 70_600L),
            LyricEntry("已然初夏 春光还迟迟未来", startTimeMs = 70_600L, endTimeMs = 75_390L)
        )

        assertEquals(
            "踽踽独行 长路孤冷",
            findFloatingTranslatedLyricLine(lyrics, translations, positionMs = 68_500L)
        )
        assertEquals(
            "已然初夏 春光还迟迟未来",
            findFloatingTranslatedLyricLine(lyrics, translations, positionMs = 71_000L)
        )
    }

    @Test
    fun `resolveExternalBluetoothLyricPayload keeps original and translation independent`() {
        val both = resolveExternalBluetoothLyricPayload(
            lyricEnabled = true,
            translationEnabled = true,
            lyricLine = "  original\nline\u0000 ",
            translationLine = "  translated\tline  "
        )

        assertEquals("original line", both.lyric)
        assertEquals("translated line", both.translation)
        assertEquals(
            ExternalBluetoothLyricPayload(lyric = "original line"),
            resolveExternalBluetoothLyricPayload(
                lyricEnabled = true,
                translationEnabled = false,
                lyricLine = "original line",
                translationLine = "translated line"
            )
        )
        assertEquals(
            ExternalBluetoothLyricPayload(translation = "translated line"),
            resolveExternalBluetoothLyricPayload(
                lyricEnabled = false,
                translationEnabled = true,
                lyricLine = "original line",
                translationLine = "translated line"
            )
        )
    }

    @Test
    fun `resolveExternalBluetoothLyricPayload bounds utf8 metadata without splitting emoji`() {
        val payload = resolveExternalBluetoothLyricPayload(
            lyricEnabled = true,
            translationEnabled = false,
            lyricLine = "歌词🎵".repeat(100),
            translationLine = null
        )

        val lyric = payload.lyric.orEmpty()
        assertTrue(lyric.endsWith("…"))
        assertTrue(
            lyric.toByteArray(Charsets.UTF_8).size <=
                EXTERNAL_BLUETOOTH_METADATA_MAX_UTF8_BYTES
        )
        assertFalse(lyric.contains('\uFFFD'))
    }

    @Test
    fun `resolveExternalBluetoothLyricPayload removes duplicate translation only when original is sent`() {
        assertEquals(
            ExternalBluetoothLyricPayload(lyric = "same line"),
            resolveExternalBluetoothLyricPayload(
                lyricEnabled = true,
                translationEnabled = true,
                lyricLine = "same line",
                translationLine = "same line"
            )
        )
        assertEquals(
            ExternalBluetoothLyricPayload(translation = "same line"),
            resolveExternalBluetoothLyricPayload(
                lyricEnabled = false,
                translationEnabled = true,
                lyricLine = "same line",
                translationLine = "same line"
            )
        )
    }

    @Test
    fun `shouldUseExternalBluetoothLyrics requires bluetooth device and lyric payload`() {
        assertTrue(
            shouldUseExternalBluetoothLyrics(
                audioDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                payload = ExternalBluetoothLyricPayload(lyric = "current line")
            )
        )
        assertFalse(
            shouldUseExternalBluetoothLyrics(
                audioDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                payload = ExternalBluetoothLyricPayload()
            )
        )
        assertFalse(
            shouldUseExternalBluetoothLyrics(
                audioDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                payload = ExternalBluetoothLyricPayload(translation = "translated line")
            )
        )
        assertFalse(
            shouldUseExternalBluetoothLyrics(
                audioDeviceType = null,
                payload = ExternalBluetoothLyricPayload(lyric = "current line")
            )
        )
    }

    @Test
    fun `resolveExternalBluetoothMetadataText submits original and translation in separate fields`() {
        val metadata = resolveExternalBluetoothMetadataText(
            normalTitle = "Song",
            normalArtist = "Artist",
            payload = ExternalBluetoothLyricPayload(
                lyric = "current line",
                translation = "当前翻译"
            ),
            useBluetoothLyrics = true
        )

        assertEquals("current line", metadata.title)
        assertEquals("当前翻译", metadata.artist)
        assertEquals("Song - Artist", metadata.album)
        assertEquals("current line", metadata.displayTitle)
        assertEquals("当前翻译", metadata.displaySubtitle)
        assertEquals("Song - Artist", metadata.displayDescription)
    }

    @Test
    fun `resolveExternalBluetoothMetadataText falls back to song info for a single lyric field`() {
        val metadata = resolveExternalBluetoothMetadataText(
            normalTitle = "Song",
            normalArtist = "Artist",
            payload = ExternalBluetoothLyricPayload(translation = "当前翻译"),
            useBluetoothLyrics = true
        )

        assertEquals("当前翻译", metadata.title)
        assertEquals("Song - Artist", metadata.artist)
        assertNull(metadata.album)
        assertEquals("当前翻译", metadata.displayTitle)
        assertEquals("Song - Artist", metadata.displaySubtitle)
        assertNull(metadata.displayDescription)
    }

    @Test
    fun `resolveExternalBluetoothMetadataText preserves normal metadata without a payload`() {
        val metadata = resolveExternalBluetoothMetadataText(
            normalTitle = "Song",
            normalArtist = "Artist",
            payload = ExternalBluetoothLyricPayload(),
            useBluetoothLyrics = false
        )

        assertEquals("Song", metadata.title)
        assertEquals("Artist", metadata.artist)
        assertNull(metadata.album)
        assertEquals("Song", metadata.displayTitle)
        assertEquals("Artist", metadata.displaySubtitle)
        assertNull(metadata.displayDescription)
    }
}
