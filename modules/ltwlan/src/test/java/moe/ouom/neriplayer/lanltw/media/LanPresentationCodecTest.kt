package moe.ouom.neriplayer.lanltw.media

import moe.ouom.neriplayer.data.model.lan.*
import org.junit.Assert.*
import org.junit.Test

class LanPresentationCodecTest {
    @Test fun roundTripRetainsResolvedUserMetadataAndWordTimings() {
        val line = LanSharedLyricLine("custom lyric", 100, 500,
            listOf(LanSharedWordTiming(100, 500, 12)), "translation")
        val value = LanSharedPresentation("Custom title", "Custom artist", "Album", -120,
            listOf(line), listOf(line.copy(text = "translation")), listOf(line.copy(text = "romanized")))
        assertEquals(value, LanPresentationCodec.decode(LanPresentationCodec.encode(value)))
    }
    @Test fun malformedTimingsAndOversizedResourceAreRejected() {
        for (value in listOf(
            LanSharedPresentation("Title", "Artist", "Album", lyrics = listOf(LanSharedLyricLine("bad", 500, 10))),
            LanSharedPresentation("Title", "Artist", "Album", cover = LanFileResource("bad", "a".repeat(64), 1, "image/png")))) {
            try { LanPresentationCodec.decode(LanPresentationCodec.encode(value)); fail("Accepted invalid metadata") }
            catch (_: IllegalStateException) { }
        }
    }
}
