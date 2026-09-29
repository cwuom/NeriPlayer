package moe.ouom.neriplayer.core.download.catalog.assembly

import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedSongLyricsTest {
    @Test
    fun `file matched original and indexed precedence is identical for every lyric variant`() {
        val values = listOf(null, "", " ", "lyric")
        for (file in values) for (matched in values) for (original in values) {
            val lyrics = DownloadedSongLyricContent(file, "indexed", file, "indexed", file, "indexed")
            val metadata = DownloadedAudioMetadata(
                matchedLyric = matched, originalLyric = original,
                matchedTranslatedLyric = matched, originalTranslatedLyric = original,
                matchedRomanizedLyric = matched, originalRomanizedLyric = original
            )
            val result = lyrics.resolve(true, metadata) { null }
            val expected = file ?: matched ?: original ?: "indexed"
            assertEquals("file=$file matched=$matched original=$original", DownloadedSongLyrics(expected, expected, expected), result)
        }
    }

    @Test
    fun `local original lyrics precede index but never fill translation or romanization`() {
        val lyrics = DownloadedSongLyricContent(indexedLyric = "index", indexedTranslatedLyric = "translation", indexedRomanizedLyric = "romanization")
        assertEquals(DownloadedSongLyrics("local", "translation", "romanization"), lyrics.resolve(true, DownloadedAudioMetadata()) { "local" })
    }

    @Test
    fun `disabled loading retains only embedded matched values without evaluating local supplier`() {
        val lyrics = DownloadedSongLyricContent(fileLyric = "file", indexedTranslatedLyric = "index")
        val metadata = DownloadedAudioMetadata(matchedLyric = "", matchedTranslatedLyric = "matched", originalRomanizedLyric = "original")
        assertEquals(DownloadedSongLyrics("", "matched", null), lyrics.resolve(false, metadata) { throw AssertionError("local lyrics read") })
    }
}
