package moe.ouom.neriplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class LocalMetadataCompatTest {

    @Test
    fun `buildSystemPlaylistCandidateNames keeps canonical names and one-step legacy mojibake`() {
        val canonicalName = "我喜欢的音乐"
        val candidates = buildSystemPlaylistCandidateNames(
            canonicalChineseName = canonicalName,
            canonicalEnglishName = "My Favorite Music",
            localizedName = canonicalName
        )
        val gbk = Charset.forName("GBK")
        val legacyMojibake = String(canonicalName.toByteArray(Charsets.UTF_8), gbk)
        val doubleMojibake = String(legacyMojibake.toByteArray(Charsets.UTF_8), gbk)

        assertTrue(candidates.contains(canonicalName))
        assertTrue(candidates.contains("My Favorite Music"))
        assertTrue(candidates.contains(legacyMojibake))
        assertFalse(candidates.contains(doubleMojibake))
    }

    @Test
    fun `parseWaveMetadata reads list info metadata encoded in gbk`() {
        val file = createWaveTempFile(
            buildWaveFile(
                "LIST" to buildListInfoChunk(
                    mapOf(
                        "INAM" to "晴天".toByteArray(Charset.forName("GBK")),
                        "IART" to "周杰伦".toByteArray(Charset.forName("GBK")),
                        "IPRD" to "晴天".toByteArray(Charset.forName("GBK"))
                    )
                )
            )
        )

        val metadata = LocalMediaSupport.parseWaveMetadata(file)

        assertEquals("晴天", metadata?.title)
        assertEquals("周杰伦", metadata?.artist)
        assertEquals("晴天", metadata?.album)
    }

    @Test
    fun `parseWaveMetadata reads id3v22 text frames inside wave id3 chunk`() {
        val file = createWaveTempFile(
            buildWaveFile(
                "ID3 " to buildId3v22Tag(
                    mapOf(
                        "TT2" to "Sunny Day",
                        "TP1" to "Jay Chou",
                        "TAL" to "Qing Tian"
                    )
                )
            )
        )

        val metadata = LocalMediaSupport.parseWaveMetadata(file)

        assertEquals("Sunny Day", metadata?.title)
        assertEquals("Jay Chou", metadata?.artist)
        assertEquals("Qing Tian", metadata?.album)
    }

    private fun createWaveTempFile(content: ByteArray): File {
        return File.createTempFile("local-metadata-", ".wav").apply {
            writeBytes(content)
            deleteOnExit()
        }
    }

    private fun buildWaveFile(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeAscii("RIFF")
        output.writeLittleEndianInt(0)
        output.writeAscii("WAVE")
        chunks.forEach { (chunkId, chunkData) ->
            output.writeAscii(chunkId)
            output.writeLittleEndianInt(chunkData.size)
            output.write(chunkData)
            if (chunkData.size % 2 != 0) {
                output.write(0)
            }
        }
        val bytes = output.toByteArray()
        writeLittleEndianInt(bytes, 4, bytes.size - 8)
        return bytes
    }

    private fun buildListInfoChunk(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeAscii("INFO")
        entries.forEach { (entryId, rawValue) ->
            val value = rawValue + byteArrayOf(0)
            output.writeAscii(entryId)
            output.writeLittleEndianInt(value.size)
            output.write(value)
            if (value.size % 2 != 0) {
                output.write(0)
            }
        }
        return output.toByteArray()
    }

    private fun buildId3v22Tag(frames: Map<String, String>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (frameId, value) ->
            val frameValue = byteArrayOf(0) + value.toByteArray(StandardCharsets.ISO_8859_1)
            body.writeAscii(frameId)
            body.writeBigEndianInt24(frameValue.size)
            body.write(frameValue)
        }

        val header = ByteArrayOutputStream()
        header.writeAscii("ID3")
        header.write(byteArrayOf(2, 0, 0))
        header.writeSynchsafeInt(body.size())
        header.write(body.toByteArray())
        return header.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte()
        ))
    }

    private fun ByteArrayOutputStream.writeBigEndianInt24(value: Int) {
        write(byteArrayOf(
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        ))
    }

    private fun ByteArrayOutputStream.writeSynchsafeInt(value: Int) {
        write(byteArrayOf(
            ((value ushr 21) and 0x7F).toByte(),
            ((value ushr 14) and 0x7F).toByte(),
            ((value ushr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte()
        ))
    }

    private fun writeLittleEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
