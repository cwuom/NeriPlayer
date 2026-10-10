package moe.ouom.neriplayer.data.local.audioimport

import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager.LyricSidecarKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAudioImportLyricSidecarNamesTest {
    @Test
    fun `original lyric sidecars use the audio base name`() {
        assertEquals(
            listOf("Night Drive.lrc", "Night Drive.txt", "Night Drive.lrc.txt"),
            LocalAudioImportManager.lyricSidecarNames("Night Drive", LyricSidecarKind.ORIGINAL)
        )
    }

    @Test
    fun `translated lyric sidecars use the trans suffix`() {
        assertEquals(
            listOf("Night Drive_trans.lrc", "Night Drive_trans.txt", "Night Drive_trans.lrc.txt"),
            LocalAudioImportManager.lyricSidecarNames("Night Drive", LyricSidecarKind.TRANSLATED)
        )
    }

    @Test
    fun `romanized lyric sidecars accept every legacy suffix in priority order`() {
        assertEquals(
            listOf(
                "song_roma.lrc", "song_roma.txt", "song_roma.lrc.txt",
                "song_romalrc.lrc", "song_romalrc.txt", "song_romalrc.lrc.txt",
                "song_romanized.lrc", "song_romanized.txt", "song_romanized.lrc.txt"
            ),
            LocalAudioImportManager.lyricSidecarNames("song", LyricSidecarKind.ROMANIZED)
        )
    }
}
