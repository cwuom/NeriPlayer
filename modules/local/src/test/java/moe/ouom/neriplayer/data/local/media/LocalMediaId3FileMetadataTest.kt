package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets.ISO_8859_1

class LocalMediaId3FileMetadataTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `id3v1 trailers provide the container metadata`() {
        val file = temporaryFolder.newFile("song.mp3").apply {
            writeBytes(ByteArray(64) + id3v1Tag(title = "Night Drive", artist = "Neri Band", album = "Demo Tape", year = "2024", track = 7))
        }

        assertEquals(
            LocalMediaSupport.ContainerMetadata(
                title = "Night Drive",
                artist = "Neri Band",
                album = "Demo Tape",
                year = 2024,
                trackNumber = 7
            ),
            LocalMediaSupport.parseId3FileMetadataImpl(file)
        )
    }

    @Test
    fun `untagged files have no id3 metadata`() {
        val file = temporaryFolder.newFile("raw.mp3").apply { writeBytes(ByteArray(256)) }

        assertNull(LocalMediaSupport.parseId3FileMetadataImpl(file))
    }

    @Test
    fun `missing files and directories have no id3 metadata`() {
        assertNull(LocalMediaSupport.parseId3FileMetadataImpl(File(temporaryFolder.root, "missing.mp3")))
        assertNull(LocalMediaSupport.parseId3FileMetadataImpl(temporaryFolder.newFolder("album.mp3")))
    }

    private fun id3v1Tag(title: String, artist: String, album: String, year: String, track: Int): ByteArray {
        val tag = ByteArray(128)
        "TAG".toByteArray(ISO_8859_1).copyInto(tag, destinationOffset = 0)
        title.toByteArray(ISO_8859_1).copyInto(tag, destinationOffset = 3)
        artist.toByteArray(ISO_8859_1).copyInto(tag, destinationOffset = 33)
        album.toByteArray(ISO_8859_1).copyInto(tag, destinationOffset = 63)
        year.toByteArray(ISO_8859_1).copyInto(tag, destinationOffset = 93)
        tag[126] = track.toByte()
        return tag
    }
}
