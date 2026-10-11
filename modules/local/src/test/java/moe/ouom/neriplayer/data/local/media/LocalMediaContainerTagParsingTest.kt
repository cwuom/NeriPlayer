package moe.ouom.neriplayer.data.local.media

import java.io.File
import java.io.RandomAccessFile
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ContainerMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMediaContainerTagParsingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `id3 frames keep the first usable value of each field`() {
        val metadata = LocalMediaSupport.parseId3Metadata(
            id3(
                3,
                frame("TIT2", "First"),
                frame("TIT2", "Second"),
                frame("TPE1", "Artist"),
                frame("TALB", "Album"),
                frame("TPE2", "Album Artist"),
                frame("TCOM", "Composer"),
                frame("TCON", "Genre"),
                frame("TYER", "unknown"),
                frame("TDRC", "1999-02-03"),
                frame("TRCK", "side"),
                frame("TRCK", "3/12"),
                frame("TPOS", "1/2"),
                frame("COMM", "ignored")
            )
        )

        assertEquals(
            ContainerMetadata(
                title = "First",
                artist = "Artist",
                album = "Album",
                albumArtist = "Album Artist",
                composer = "Composer",
                genre = "Genre",
                year = 1999,
                trackNumber = 3,
                discNumber = 1
            ),
            metadata
        )
    }

    @Test
    fun `id3 v22 tags use three character frame ids`() {
        val metadata = LocalMediaSupport.parseId3Metadata(
            id3(
                2,
                frame("TT2", "Title", version = 2),
                frame("TP1", "Artist", version = 2),
                frame("TAL", "Album", version = 2),
                frame("TP2", "Band", version = 2),
                frame("TCM", "Writer", version = 2),
                frame("TCO", "Rock", version = 2),
                frame("TYE", "2004", version = 2),
                frame("TRK", "7", version = 2),
                frame("TPA", "2", version = 2)
            )
        )

        assertEquals(
            ContainerMetadata("Title", "Artist", "Album", "Band", "Writer", "Rock", 2004, 7, 2),
            metadata
        )
    }

    @Test
    fun `id3 v24 frames use synchsafe sizes`() {
        val metadata = LocalMediaSupport.parseId3Metadata(id3(4, frame("TIT2", "Synchsafe", version = 4)))

        assertEquals(ContainerMetadata(title = "Synchsafe"), metadata)
    }

    @Test
    fun `id3 parsing stops at padding, empty frames and frames past the tag`() {
        val padded = id3(3, frame("TIT2", "Before padding"), ByteArray(10), frame("TPE1", "After padding"))
        val emptyFrame = id3(3, frame("TIT2", "Before empty"), header("TPE1", 0), frame("TALB", "After empty"))
        val overlong = id3(3, frame("TIT2", "Before overlong"), header("TPE1", 100) + byteArrayOf(0, 'x'.code.toByte()))

        assertEquals(ContainerMetadata(title = "Before padding"), LocalMediaSupport.parseId3Metadata(padded))
        assertEquals(ContainerMetadata(title = "Before empty"), LocalMediaSupport.parseId3Metadata(emptyFrame))
        assertEquals(ContainerMetadata(title = "Before overlong"), LocalMediaSupport.parseId3Metadata(overlong))
    }

    @Test
    fun `id3 frame sizes that overflow the tag stop parsing instead of failing`() {
        val bytes = id3(3, frame("TIT2", "Before overflow"), header("TPE1", Int.MAX_VALUE) + byteArrayOf(0, 'x'.code.toByte()))

        assertEquals(ContainerMetadata(title = "Before overflow"), LocalMediaSupport.parseId3Metadata(bytes))
    }

    @Test
    fun `bytes without a usable id3 tag have no id3 metadata`() {
        assertNull(LocalMediaSupport.parseId3Metadata(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())))
        assertNull(LocalMediaSupport.parseId3Metadata(ByteArray(32)))
        assertNull(LocalMediaSupport.parseId3Metadata(id3(3, frame("COMM", "comment only"))))
    }

    @Test
    fun `id3 extended headers that are too small or truncated are rejected`() {
        val tooSmall = id3Header(3, flags = 0x40, size = 14) + integer(2) + ByteArray(10)
        val truncated = id3Header(4, flags = 0x40, size = 2) + byteArrayOf(0, 0)

        assertNull(LocalMediaSupport.parseId3Metadata(tooSmall))
        assertNull(LocalMediaSupport.parseId3Metadata(truncated))
    }

    @Test
    fun `wave info chunks keep first values and honour odd chunk padding`() {
        val metadata = LocalMediaSupport.parseWaveInfoMetadata(
            info("INAM", "Song") +
                info("IART", "Art") +
                info("IPRD", "Album") +
                info("IAAR", "Crew") +
                info("IENG", "Engineer") +
                info("IGNR", "Pop") +
                info("ICRD", "Recorded 2011-05") +
                info("ITRK", "4") +
                info("IPRT", "2/3") +
                info("ISFT", "Encoder") +
                info("INAM", "Other")
        )

        assertEquals(
            ContainerMetadata("Song", "Art", "Album", "Crew", "Engineer", "Pop", 2011, 4, 2),
            metadata
        )
    }

    @Test
    fun `wave info chunk sizes are clamped to the available bytes`() {
        val truncated = "INAM".toByteArray(Charsets.US_ASCII) + littleEndian(1_000) + "Title".toByteArray(Charsets.ISO_8859_1)

        assertEquals(ContainerMetadata(title = "Title"), LocalMediaSupport.parseWaveInfoMetadata(truncated))
        assertNull(LocalMediaSupport.parseWaveInfoMetadata(ByteArray(0)))
        assertNull(LocalMediaSupport.parseWaveInfoMetadata(info("ISFT", "Encoder")))
    }

    @Test
    fun `id3v1 trailers provide title, artist, album, year and track`() {
        val file = file("v1.mp3", ByteArray(64) + id3v1(track = 9))

        assertEquals(
            ContainerMetadata(title = "Title", artist = "Artist", album = "Album", year = 1987, trackNumber = 9),
            readId3v1(file)
        )
    }

    @Test
    fun `id3v1 trailers without a track byte keep the track unknown`() {
        assertEquals(null, readId3v1(file("zero.mp3", id3v1(track = 0)))?.trackNumber)
        assertEquals(null, readId3v1(file("comment.mp3", id3v1(track = 5, commentEndsAtTrack = true)))?.trackNumber)
        assertEquals("Title", readId3v1(file("comment.mp3", id3v1(track = 5, commentEndsAtTrack = true)))?.title)
    }

    @Test
    fun `files without an id3v1 trailer have no id3v1 metadata`() {
        assertNull(readId3v1(file("short.mp3", ByteArray(127))))
        assertNull(readId3v1(file("plain.mp3", ByteArray(256))))
        assertNull(readId3v1(file("blank.mp3", "TAG".toByteArray(Charsets.US_ASCII) + ByteArray(125))))
    }

    @Test
    fun `id3v1 fills fields that the id3v2 tag left empty`() {
        val file = file("both.mp3", id3(3, frame("TIT2", "V2 Title")) + ByteArray(32) + id3v1(track = 2))

        assertEquals(
            ContainerMetadata(title = "V2 Title", artist = "Artist", album = "Album", year = 1987, trackNumber = 2),
            LocalMediaSupport.parseContainerMetadata(file)
        )
    }

    @Test
    fun `merging container metadata prefers primary values`() {
        val primary = ContainerMetadata(title = "Primary", year = 2001)
        val fallback = ContainerMetadata(
            title = "Fallback",
            artist = "Artist",
            album = "Album",
            albumArtist = "Band",
            composer = "Writer",
            genre = "Jazz",
            year = 1999,
            trackNumber = 3,
            discNumber = 1
        )

        assertEquals(
            fallback.copy(title = "Primary", year = 2001),
            LocalMediaSupport.mergeContainerMetadata(primary, fallback)
        )
        assertSame(fallback, LocalMediaSupport.mergeContainerMetadata(null, fallback))
        assertSame(primary, LocalMediaSupport.mergeContainerMetadata(primary, null))
        assertNull(LocalMediaSupport.mergeContainerMetadata(null, null))
    }

    private fun readId3v1(file: File) = RandomAccessFile(file, "r").use { LocalMediaSupport.readId3v1FileMetadata(it) }

    private fun file(name: String, bytes: ByteArray): File = File(temporaryFolder.root, name).apply { writeBytes(bytes) }

    private fun id3v1(track: Int, commentEndsAtTrack: Boolean = false): ByteArray {
        val tag = ByteArray(128)
        "TAG".toByteArray(Charsets.US_ASCII).copyInto(tag, 0)
        "Title".toByteArray(Charsets.ISO_8859_1).copyInto(tag, 3)
        "Artist".toByteArray(Charsets.ISO_8859_1).copyInto(tag, 33)
        "Album".toByteArray(Charsets.ISO_8859_1).copyInto(tag, 63)
        "1987".toByteArray(Charsets.US_ASCII).copyInto(tag, 93)
        if (commentEndsAtTrack) tag[125] = 'c'.code.toByte()
        tag[126] = track.toByte()
        return tag
    }

    private fun info(id: String, text: String): ByteArray {
        val payload = text.toByteArray(Charsets.ISO_8859_1)
        val padding = if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return id.toByteArray(Charsets.US_ASCII) + littleEndian(payload.size) + payload + padding
    }

    private fun id3(version: Int, vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        return id3Header(version, flags = 0, size = body.size) + body
    }

    private fun id3Header(version: Int, flags: Int, size: Int): ByteArray =
        byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, flags.toByte()) +
            synchsafe(size)

    private fun frame(name: String, text: String, version: Int = 3): ByteArray {
        val payload = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        return header(name, payload.size, version) + payload
    }

    private fun header(name: String, size: Int, version: Int = 3): ByteArray {
        val id = name.toByteArray(Charsets.ISO_8859_1)
        return when (version) {
            2 -> id + ByteArray(3) { index -> (size ushr (8 * (2 - index))).toByte() }
            4 -> id + synchsafe(size) + byteArrayOf(0, 0)
            else -> id + integer(size) + byteArrayOf(0, 0)
        }
    }

    private fun littleEndian(value: Int) = ByteArray(4) { index -> (value ushr (8 * index)).toByte() }

    private fun integer(value: Int) = ByteArray(4) { index -> (value ushr (8 * (3 - index))).toByte() }

    private fun synchsafe(value: Int) = ByteArray(4) { index -> ((value ushr (7 * (3 - index))) and 0x7f).toByte() }
}
