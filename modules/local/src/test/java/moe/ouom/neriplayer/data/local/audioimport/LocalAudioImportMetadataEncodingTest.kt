package moe.ouom.neriplayer.data.local.audioimport

import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class LocalAudioImportMetadataEncodingTest {
    @Before
    fun bindMediaHost() {
        val downloads = mock(LocalMediaDownloadAccess::class.java)
        doReturn(emptyList<String>()).`when`(downloads).candidateFileNameTemplates(null)
        LocalMediaHostAccess.bind(
            downloads = downloads,
            covers = mock(LocalMediaCoverAccess::class.java),
            crashLogs = CrashLogCleanup { false }
        )
    }

    @Test
    fun `quick MediaStore songs recover title artist and album before detailed inspection`() {
        val song = quickSong(
            title = "é˜¿å°”æ³• - vae",
            artist = "ÐíáÔ",
            album = "寻宝游戏".toByteArray(UTF_8).toString(ISO_8859_1)
        )

        assertEquals("阿尔法 - vae", song.name)
        assertEquals("许嵩", song.artist)
        assertEquals("寻宝游戏", song.album)
        assertEquals("阿尔法 - vae", song.originalName)
        assertEquals("许嵩", song.originalArtist)
    }

    @Test
    fun `quick songs preserve valid Japanese Korean and European metadata`() {
        listOf("夜に駆ける", "소녀시대", "Björk", "Mötley Crüe", "ÄÖÜß", "éèêë").forEach { value ->
            val song = quickSong(title = value, artist = value, album = value)

            assertEquals(value, song.name)
            assertEquals(value, song.artist)
            assertEquals(value, song.album)
        }
    }

    private fun quickSong(title: String, artist: String, album: String) =
        LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "content://media/external/audio/media/1",
                displayName = "track.mp3",
                title = title,
                artist = artist,
                album = album,
                durationMs = 1000L
            ),
            unknownArtistLabel = "Unknown Artist"
        )
}
