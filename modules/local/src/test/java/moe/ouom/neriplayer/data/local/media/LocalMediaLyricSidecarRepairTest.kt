package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.net.Uri
import java.io.File
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaLyricSidecarRepairTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)

    @After
    fun clearCaches() {
        LocalMediaSupport.clearLyricsLookupCache()
    }

    @Test
    fun `blank lyrics do not require creating sidecars`() {
        withSong { song ->
            assertFalse(LocalMediaSupport.needsLyricSidecarRepair(context, song.copy(
                matchedLyric = "", matchedTranslatedLyric = "  \n", matchedRomanizedLyric = ""
            )))
        }
    }

    @Test
    fun `empty matched lyrics keep the original lyrics explicitly cleared`() {
        withSong { song ->
            assertFalse(LocalMediaSupport.needsLyricSidecarRepair(context, song.copy(
                matchedLyric = "", originalLyric = "old original"
            )))
        }
    }

    @Test
    fun `present original lyrics and absent empty variants need no repeated repair`() {
        withSong { song ->
            File(temporaryFolder.root, "song.lrc").writeText("original")

            repeat(2) {
                assertFalse(LocalMediaSupport.needsLyricSidecarRepair(context, song.copy(
                    matchedLyric = "original", matchedTranslatedLyric = "", matchedRomanizedLyric = ""
                )))
            }
        }
    }

    @Test
    fun `missing nonblank variants still require repair`() {
        withSong { song ->
            File(temporaryFolder.root, "song.lrc").writeText("original")

            assertTrue(LocalMediaSupport.needsLyricSidecarRepair(context, song.copy(
                matchedLyric = "original", matchedTranslatedLyric = "translation"
            )))
        }
    }

    private fun withSong(block: (SongItem) -> Unit) {
        val audio = temporaryFolder.newFile("song.flac").apply { writeText("audio") }
        val uri = mock(Uri::class.java).also {
            doReturn("file").`when`(it).scheme
            doReturn(audio.absolutePath).`when`(it).path
            doReturn(audio.toURI().toString()).`when`(it).toString()
        }
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.fromFile(audio) }.thenReturn(uri)
            block(SongItem(
                id = 1L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
                durationMs = 1000L, coverUrl = null, localFilePath = audio.absolutePath
            ))
        }
    }
}
