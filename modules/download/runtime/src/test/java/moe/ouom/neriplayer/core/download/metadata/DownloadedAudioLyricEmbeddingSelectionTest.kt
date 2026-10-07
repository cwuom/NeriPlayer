package moe.ouom.neriplayer.core.download.metadata

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager.DownloadedSidecarReferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedAudioLyricEmbeddingSelectionTest {
    private val writer = DownloadedAudioTagWriter

    @Test
    fun `fresh lyric content wins over restored and fallback lyrics`() {
        assertEquals(
            "[00:01.00]fresh",
            writer.selectEmbeddedLyricContent("[00:01.00]fresh", "[00:01.00]restored", "[00:01.00]stale")
        )
        assertEquals(
            "[00:01.00]restored",
            writer.selectEmbeddedLyricContent(" ", "[00:01.00]restored", "[00:01.00]stale")
        )
        assertEquals(
            "[00:01.00]restored",
            writer.selectEmbeddedLyricContent(null, "[00:01.00]restored", null)
        )
    }

    @Test
    fun `blank or missing lyric content falls back to the song lyric`() {
        assertEquals("[00:01.00]stale", writer.selectEmbeddedLyricContent(null, "\n", "[00:01.00]stale"))
        assertEquals("[00:01.00]stale", writer.selectEmbeddedLyricContent("", null, "[00:01.00]stale"))
        assertNull(writer.selectEmbeddedLyricContent(null, null, null))
    }

    @Test
    fun `word lyric conversion keeps timed and tag lines but drops structured payload lines`() {
        val lyric = listOf(
            "[ar:Artist]",
            "[1000,500](1000,500,0)Hello",
            "{\"t\":1000,\"c\":[{\"tx\":\"meta\"}]}",
            "[{\"tx\":\"credit\"}]",
            "[\"a\",\"b\"]",
            "credits \"tx\" inline",
            "x \"t\": 1",
            "[00:05.00]Timed",
            "plain text line"
        ).joinToString("\n")

        assertEquals(
            listOf("[ar:Artist]", "[00:01.00]Hello", "[00:05.00]Timed", "plain text line").joinToString("\n"),
            writer.normalizeLyricForEmbedding(lyric, enabled = true)
        )
    }

    @Test
    fun `missing or blank lyrics are embedded untouched`() {
        assertNull(writer.normalizeLyricForEmbedding(null, enabled = true))
        assertEquals("  ", writer.normalizeLyricForEmbedding("  ", enabled = true))
        assertNull(writer.normalizeLyricForEmbedding(null, enabled = false))
    }

    @Test
    fun `existing pictures are loaded only for a real cover in a container with picture roles`() {
        val cover = DownloadedSidecarReferences(coverReference = "content://covers/song.jpg")

        assertFalse(writer.shouldLoadEmbeddedPictures(DownloadedSidecarReferences(coverReference = null), "flac"))
        assertFalse(writer.shouldLoadEmbeddedPictures(DownloadedSidecarReferences(coverReference = ""), "flac"))
        assertTrue(writer.shouldLoadEmbeddedPictures(cover, ""))
        assertTrue(writer.shouldLoadEmbeddedPictures(cover, "  "))
        assertTrue(writer.shouldLoadEmbeddedPictures(cover, "mp3"))
        assertFalse(writer.shouldLoadEmbeddedPictures(cover, " MP4 "))
    }
}
