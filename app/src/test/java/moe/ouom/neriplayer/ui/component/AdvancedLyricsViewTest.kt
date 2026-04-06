package moe.ouom.neriplayer.ui.component

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedLyricsViewTest {

    @Test
    fun `buildAdvancedSyncedLyrics converts word timed entries into karaoke line`() {
        val lyrics = listOf(
            LyricEntry(
                text = "难以忘记",
                startTimeMs = 12_580L,
                endTimeMs = 16_050L,
                words = listOf(
                    WordTiming(startTimeMs = 12_580L, endTimeMs = 12_830L, charCount = 1),
                    WordTiming(startTimeMs = 12_830L, endTimeMs = 13_130L, charCount = 1),
                    WordTiming(startTimeMs = 13_130L, endTimeMs = 13_330L, charCount = 2)
                )
            )
        )

        val result = buildAdvancedSyncedLyrics(
            rawLyrics = null,
            rawTranslatedLyrics = null,
            lyrics = lyrics,
            translatedLyrics = emptyList()
        )

        val line = result.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(3, line.syllables.size)
        assertEquals("难", line.syllables[0].content)
        assertEquals("以", line.syllables[1].content)
        assertEquals("忘记", line.syllables[2].content)
    }

    @Test
    fun `buildAdvancedSyncedLyrics attaches translation by overlap`() {
        val lyrics = listOf(
            LyricEntry(
                text = "Starlight",
                startTimeMs = 1_000L,
                endTimeMs = 2_000L
            )
        )
        val translations = listOf(
            LyricEntry(
                text = "星光",
                startTimeMs = 1_100L,
                endTimeMs = 1_900L
            )
        )

        val result = buildAdvancedSyncedLyrics(
            rawLyrics = null,
            rawTranslatedLyrics = null,
            lyrics = lyrics,
            translatedLyrics = translations
        )

        val line = result.lines.single()
        assertTrue(line is SyncedLine)
        assertEquals("星光", (line as SyncedLine).translation)
    }

    @Test
    fun `buildAdvancedSyncedLyrics keeps parsed word timings when raw lyric is plain lrc`() {
        val lyrics = listOf(
            LyricEntry(
                text = "难以忘记",
                startTimeMs = 12_580L,
                endTimeMs = 16_050L,
                words = listOf(
                    WordTiming(startTimeMs = 12_580L, endTimeMs = 12_830L, charCount = 1),
                    WordTiming(startTimeMs = 12_830L, endTimeMs = 13_130L, charCount = 1),
                    WordTiming(startTimeMs = 13_130L, endTimeMs = 13_330L, charCount = 2)
                )
            )
        )

        val result = buildAdvancedSyncedLyrics(
            rawLyrics = "[00:12.58]难以忘记",
            rawTranslatedLyrics = null,
            lyrics = lyrics,
            translatedLyrics = emptyList()
        )

        val line = result.lines.single() as KaraokeLine.MainKaraokeLine
        assertEquals(3, line.syllables.size)
    }

    @Test
    fun `flattenWordTimedEntries removes word timings for plain lyric rendering`() {
        val flattened = listOf(
            LyricEntry(
                text = "难以忘记",
                startTimeMs = 12_580L,
                endTimeMs = 16_050L,
                words = listOf(
                    WordTiming(startTimeMs = 12_580L, endTimeMs = 12_830L, charCount = 1)
                )
            )
        ).flattenWordTimedEntries()

        assertFalse(flattened.single().words != null)
        assertEquals("难以忘记", flattened.single().text)
    }

    @Test
    fun `resolvePreferredLyricContent prefers yrc over matched lrc`() {
        val preferred = resolvePreferredLyricContent(
            matchedLyric = "[00:12.58]难以忘记",
            preferredNeteaseLyric = "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记"
        )

        assertEquals(
            "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记",
            preferred
        )
    }

    @Test
    fun `toEditableLyricsText preserves word timed entries as yrc`() {
        val serialized = listOf(
            LyricEntry(
                text = "难以忘记",
                startTimeMs = 12_580L,
                endTimeMs = 16_050L,
                words = listOf(
                    WordTiming(startTimeMs = 12_580L, endTimeMs = 12_830L, charCount = 1),
                    WordTiming(startTimeMs = 12_830L, endTimeMs = 13_130L, charCount = 1),
                    WordTiming(startTimeMs = 13_130L, endTimeMs = 13_330L, charCount = 2)
                )
            )
        ).toEditableLyricsText()

        assertEquals(
            "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记",
            serialized
        )
    }

    @Test
    fun `shouldSnapInterpolatedPlaybackPosition only snaps on larger desync`() {
        assertTrue(
            shouldSnapInterpolatedPlaybackPosition(
                externalPositionMs = 3_000L,
                renderedPositionMs = 2_700L,
                isPlaying = true
            )
        )
        assertTrue(
            shouldSnapInterpolatedPlaybackPosition(
                externalPositionMs = 3_000L,
                renderedPositionMs = 3_000L,
                isPlaying = false
            )
        )
    }

    @Test
    fun `resolveInterpolatedPlaybackPosition keeps tiny backward drift from causing visible jump`() {
        val predicted = resolveInterpolatedPlaybackPosition(
            anchorPositionMs = 1_000L,
            anchorRealtimeNanos = 1_000_000_000L,
            frameRealtimeNanos = 1_078_000_000L,
            playbackSpeed = 1f,
            previousRenderedPositionMs = 1_080L
        )

        assertEquals(1_080L, predicted)
    }

    @Test
    fun `resolveInterpolatedPlaybackPosition keeps continuous interpolation during long frame stalls`() {
        val predicted = resolveInterpolatedPlaybackPosition(
            anchorPositionMs = 5_000L,
            anchorRealtimeNanos = 1_000_000_000L,
            frameRealtimeNanos = 1_600_000_000L,
            playbackSpeed = 1f,
            previousRenderedPositionMs = 5_000L
        )

        assertEquals(5_600L, predicted)
    }
}
