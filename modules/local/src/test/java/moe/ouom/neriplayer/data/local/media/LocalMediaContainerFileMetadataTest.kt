package moe.ouom.neriplayer.data.local.media

import java.io.File
import java.io.RandomAccessFile
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ContainerMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMediaContainerFileMetadataTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `id3v2 tags are read from the start of the file`() {
        val file = file("song.mp3", id3v23(frame("TIT2", "Title"), frame("TPE1", "Artist")) + ByteArray(32))

        assertEquals(
            ContainerMetadata(title = "Title", artist = "Artist"),
            RandomAccessFile(file, "r").use { LocalMediaSupport.readId3v2FileMetadata(it) }
        )
    }

    @Test
    fun `files without a usable id3v2 header have no id3v2 metadata`() {
        listOf(
            file("short.mp3", byteArrayOf(1, 2, 3)),
            file("plain.mp3", ByteArray(64)),
            file("empty-tag.mp3", header(tagSize = 0) + ByteArray(16)),
            file("header-only.mp3", header(tagSize = 20))
        ).forEach { file ->
            assertNull(file.name, RandomAccessFile(file, "r").use { LocalMediaSupport.readId3v2FileMetadata(it) })
        }
    }

    @Test
    fun `mpeg and unknown extensions are parsed as id3 tagged files`() {
        val tag = id3v23(frame("TIT2", "Tagged"), frame("TALB", "Album"))
        val expected = ContainerMetadata(title = "Tagged", album = "Album")

        listOf("song.MP3", "song.aac", "song.flac").forEach { name ->
            assertEquals(name, expected, LocalMediaSupport.parseContainerMetadata(file(name, tag)))
        }
    }

    @Test
    fun `wave files are parsed as riff containers instead of raw id3`() {
        val tag = id3v23(frame("TIT2", "Tagged"))

        assertNull(LocalMediaSupport.parseContainerMetadata(file("song.wav", tag)))
        assertNull(LocalMediaSupport.parseContainerMetadata(file("song.WAVE", tag)))
    }

    @Test
    fun `missing files and directories have no container metadata`() {
        assertNull(LocalMediaSupport.parseContainerMetadata(File(temporaryFolder.root, "missing.mp3")))
        assertNull(LocalMediaSupport.parseContainerMetadata(temporaryFolder.newFolder("album.mp3")))
    }

    private fun file(name: String, bytes: ByteArray): File = File(temporaryFolder.root, name).apply { writeBytes(bytes) }

    private fun id3v23(vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        return header(tagSize = body.size) + body
    }

    private fun header(tagSize: Int): ByteArray =
        byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) + synchsafe(tagSize)

    private fun frame(name: String, text: String): ByteArray {
        val payload = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        return name.toByteArray(Charsets.ISO_8859_1) + integer(payload.size) + byteArrayOf(0, 0) + payload
    }

    private fun integer(value: Int) = ByteArray(4) { index -> (value ushr (8 * (3 - index))).toByte() }

    private fun synchsafe(value: Int) = ByteArray(4) { index -> ((value ushr (7 * (3 - index))) and 0x7f).toByte() }
}
