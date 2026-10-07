package moe.ouom.neriplayer.data.local.media

import android.content.Context
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.io.File

class LocalMediaLyricsMetadataSidecarWriteTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mock(Context::class.java)

    @Test
    fun `lyric only writes keep the existing sidecar fields`() {
        val audio = audioFile()
        val sidecar = sidecarFor(audio)
        sidecar.writeText("""{"customName":"Keep me","matchedLyric":"[00:01.00]old"}""")

        val written = LocalMediaSupport.writeLocalLyricsMetadataReference(
            context = context,
            reference = sidecar.absolutePath,
            file = audio,
            song = song(audio)
        )

        assertTrue(written)
        val json = JSONObject(sidecar.readText())
        assertEquals("Keep me", json.getString("customName"))
        assertEquals("[00:01.00]new", json.getString("matchedLyric"))
        assertFalse(json.has("name"))
        verifyNoInteractions(context)
    }

    @Test
    fun `missing sidecars are created with the full editable metadata`() {
        val audio = audioFile()
        val sidecar = sidecarFor(audio)

        val written = LocalMediaSupport.writeLocalLyricsMetadataReference(
            context = context,
            reference = sidecar.absolutePath,
            file = audio,
            song = song(audio),
            writeFullMetadata = true
        )

        assertTrue(written)
        val json = JSONObject(sidecar.readText())
        assertEquals("Night Drive", json.getString("name"))
        assertEquals("Album", json.getString("album"))
        assertEquals(51L, json.getLong("songId"))
        assertEquals("[00:01.00]new", json.getString("matchedLyric"))
        verifyNoInteractions(context)
    }

    @Test
    fun `existing sidecars that cannot be read are never overwritten`() {
        val audio = audioFile()
        val sidecar = sidecarFor(audio)
        val oversized = ByteArray(MAX_LOCAL_LYRIC_BYTES.toInt() + 1) { 'a'.code.toByte() }
        sidecar.writeBytes(oversized)

        val written = LocalMediaSupport.writeLocalLyricsMetadataReference(
            context = context,
            reference = sidecar.absolutePath,
            file = audio,
            song = song(audio),
            writeFullMetadata = true
        )

        assertFalse(written)
        assertArrayEquals(oversized, sidecar.readBytes())
        verifyNoInteractions(context)
    }

    private fun audioFile(): File {
        val album = File(tempFolder.root, "album").apply { mkdirs() }
        return File(album, "Night Drive.flac").apply { writeBytes(ByteArray(8)) }
    }

    private fun sidecarFor(audio: File): File = File(audio.parentFile, audio.name + ".npmeta.json")

    private fun song(audio: File): SongItem {
        return SongItem(
            id = 51L,
            name = "Night Drive",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            matchedLyric = "[00:01.00]new",
            localFilePath = audio.absolutePath
        )
    }
}
