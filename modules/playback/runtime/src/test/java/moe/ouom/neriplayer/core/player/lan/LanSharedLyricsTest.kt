package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class LanSharedLyricsTest {
    @Test fun sharingPreservesWordTimingTranslationAndUntimedLines() {
        for (line in listOf(
            LyricEntry("逐字歌词", 1000, 3000, listOf(WordTiming(1000, 1600, 2), WordTiming(1600, 3000, 2)), "Translation"),
            LyricEntry("Untimed", 0, 0), LyricEntry("", 3200, 3400, emptyList(), ""))) {
            assertEquals(line, restoreLanLyricLine(shareLanLyricLine(line)))
        }
    }
}
