package moe.ouom.neriplayer.ui.component

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

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
    fun `buildAdvancedSyncedLyrics keeps raw translated lrc aligned on shared boundary`() {
        val lyrics = listOf(
            LyricEntry(
                text = "We've got all weekend",
                startTimeMs = 18_090L,
                endTimeMs = 22_620L
            ),
            LyricEntry(
                text = "Tear it up, tear it down",
                startTimeMs = 22_620L,
                endTimeMs = 24_630L
            )
        )

        val result = buildAdvancedSyncedLyrics(
            rawLyrics = null,
            rawTranslatedLyrics = """
                [00:18.09]我们有一整个周末
                [00:22.62]撕碎它
            """.trimIndent(),
            lyrics = lyrics,
            translatedLyrics = emptyList()
        )

        val firstLine = result.lines[0] as SyncedLine
        val secondLine = result.lines[1] as SyncedLine
        assertEquals("我们有一整个周末", firstLine.translation)
        assertEquals("撕碎它", secondLine.translation)
    }

    @Test
    fun `buildAdvancedSyncedLyrics keeps sparse translation bound to its own line`() {
        val lyrics = listOf(
            LyricEntry(
                text = "My Baby",
                startTimeMs = 29_440L,
                endTimeMs = 30_180L
            ),
            LyricEntry(
                text = "Let It Go",
                startTimeMs = 30_180L,
                endTimeMs = 30_860L
            ),
            LyricEntry(
                text = "我们去过的每个角落像寄托",
                startTimeMs = 30_860L,
                endTimeMs = 32_780L
            ),
            LyricEntry(
                text = "那我们也笑过",
                startTimeMs = 32_780L,
                endTimeMs = 33_950L
            ),
            LyricEntry(
                text = "那逝去的生活的每个片段叫我如何删减",
                startTimeMs = 33_950L,
                endTimeMs = 37_080L
            )
        )

        val result = buildAdvancedSyncedLyrics(
            rawLyrics = null,
            rawTranslatedLyrics = """
                [00:29.44]我的宝贝
                [00:30.18]放手吧
            """.trimIndent(),
            lyrics = lyrics,
            translatedLyrics = emptyList()
        )

        val firstLine = result.lines[0] as SyncedLine
        val secondLine = result.lines[1] as SyncedLine
        val thirdLine = result.lines[2] as SyncedLine
        val fourthLine = result.lines[3] as SyncedLine
        val fifthLine = result.lines[4] as SyncedLine
        assertEquals("我的宝贝", firstLine.translation)
        assertEquals("放手吧", secondLine.translation)
        assertEquals(null, thirdLine.translation)
        assertEquals(null, fourthLine.translation)
        assertEquals(null, fifthLine.translation)
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
    fun `resolvePreferredLyricContent keeps explicit cleared lyric over fallback`() {
        val preferred = resolvePreferredLyricContent(
            matchedLyric = "",
            preferredNeteaseLyric = "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记"
        )

        assertEquals("", preferred)
    }

    @Test
    fun `resolveLyricsEditorInitialText keeps explicit cleared lyric over displayed fallback`() {
        val resolved = resolveLyricsEditorInitialText(
            matchedLyric = "",
            preferredNeteaseLyric = "[12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记",
            displayedLyricsText = "[00:12.58]旧歌词",
            displayedHasWordTimedEntries = true,
            fallbackLyricsText = "[00:12.58]远端旧歌词"
        )

        assertEquals("", resolved)
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

    @Test
    fun `resolvePlayedLyricViewportOffset supports roughly thirty percent played space`() {
        val offset = resolvePlayedLyricViewportOffset(
            viewportHeight = 1_000.dp,
            keepAliveZone = 108.dp,
            minimumOffset = 48.dp,
            playedLyricViewportFraction = 0.30f,
            focusedLineVisualCompensation = 18.dp,
            topFadeLength = 112.dp
        )

        assertEquals(210.dp, offset)
    }

    @Test
    fun `resolvePlayedLyricViewportOffset keeps focused line below top fade on short viewports`() {
        val offset = resolvePlayedLyricViewportOffset(
            viewportHeight = 500.dp,
            keepAliveZone = 108.dp,
            minimumOffset = 48.dp,
            playedLyricViewportFraction = 0.30f,
            focusedLineVisualCompensation = 18.dp,
            topFadeLength = 112.dp
        )

        assertEquals(60.dp, offset)
    }
}
