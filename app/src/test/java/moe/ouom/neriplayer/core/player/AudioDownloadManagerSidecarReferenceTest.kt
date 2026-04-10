package moe.ouom.neriplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDownloadManagerSidecarReferenceTest {

    @Test
    fun `mergeDownloadedSidecarReferences keeps earlier files when later stage adds new refs`() {
        val existing = AudioDownloadManager.DownloadedSidecarReferences(
            lyricReference = "content://lyrics/song.lrc"
        )
        val incoming = AudioDownloadManager.DownloadedSidecarReferences(
            coverReference = "content://covers/song.jpg"
        )

        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(existing, incoming)

        assertEquals("content://covers/song.jpg", merged.coverReference)
        assertEquals("content://lyrics/song.lrc", merged.lyricReference)
        assertEquals(null, merged.translatedLyricReference)
    }

    @Test
    fun `mergeDownloadedSidecarReferences ignores empty updates and keeps translated lyric`() {
        val existing = AudioDownloadManager.DownloadedSidecarReferences(
            lyricReference = "content://lyrics/song.lrc",
            translatedLyricReference = "content://lyrics/song_trans.lrc"
        )

        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(
            existing,
            AudioDownloadManager.DownloadedSidecarReferences()
        )

        assertEquals(existing, merged)
    }
}
