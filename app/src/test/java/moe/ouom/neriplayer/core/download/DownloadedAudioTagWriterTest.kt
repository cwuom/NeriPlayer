package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedAudioTagWriterTest {

    @Test
    fun `embedded album name strips netease source prefix`() {
        assertEquals(
            "十一月的萧邦",
            DownloadedAudioTagWriter.normalizeEmbeddedAlbumName("Netease十一月的萧邦")
        )
    }

    @Test
    fun `embedded album name removes source only markers`() {
        assertNull(DownloadedAudioTagWriter.normalizeEmbeddedAlbumName("Netease"))
        assertNull(DownloadedAudioTagWriter.normalizeEmbeddedAlbumName("Bilibili"))
        assertNull(DownloadedAudioTagWriter.normalizeEmbeddedAlbumName("Bilibili|12345"))
    }

    @Test
    fun `embedded album name keeps regular album`() {
        assertEquals(
            "The Book",
            DownloadedAudioTagWriter.normalizeEmbeddedAlbumName(" The Book ")
        )
    }

    @Test
    fun `standardized lyric embedding converts netease word lyric to lrc`() {
        val rawLyric = """
            [12580,3470](12580,250,0)难(12830,300,0)以(13130,200,0)忘记
            [16050,1200]<16050,300,0>你<16350,300,0>好
        """.trimIndent()

        val converted = DownloadedAudioTagWriter.normalizeLyricForEmbedding(
            lyric = rawLyric,
            enabled = true
        )

        assertEquals(
            """
                [00:12.58]难以忘记
                [00:16.05]你好
            """.trimIndent(),
            converted
        )
    }

    @Test
    fun `standardized lyric embedding keeps normal lrc and metadata lines`() {
        val lrc = """
            [ar:Artist]
            [00:12.58]already synced
            plain line
        """.trimIndent()

        assertEquals(
            lrc,
            DownloadedAudioTagWriter.normalizeLyricForEmbedding(
                lyric = lrc,
                enabled = true
            )
        )
    }

    @Test
    fun `standardized lyric embedding preserves raw lyric when disabled`() {
        val wordLyric = "[12580,3470](12580,250,0)难(12830,300,0)忘"

        assertEquals(
            wordLyric,
            DownloadedAudioTagWriter.normalizeLyricForEmbedding(
                lyric = wordLyric,
                enabled = false
            )
        )
    }
}
