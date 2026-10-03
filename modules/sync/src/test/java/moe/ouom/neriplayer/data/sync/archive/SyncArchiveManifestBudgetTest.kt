package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncArchiveManifestBudgetTest {
    @Test fun v3RejectsDenseEmbeddedHeaderRecordsBeforeDtoConstruction() {
        val header = ByteArrayOutputStream().also { output -> repeat(300_000) { output.write(byteArrayOf(42, 0)) } }.toByteArray()
        val content = envelope(3, original(header))
        assertThrows(IOException::class.java) { SyncArchiveCodec.readManifest(content) }
    }

    @Test fun v4RejectsDenseOriginalHeaderRecordsBeforeDtoConstruction() {
        val header = ByteArrayOutputStream().also { output -> repeat(300_000) { output.write(byteArrayOf(42, 0)) } }.toByteArray()
        val content = envelope(4, current(original(header)))
        assertThrows(IOException::class.java) { SyncArchiveV4Format.readManifest(content) }
    }

    @Test fun unknownHeaderBytesAndNoncanonicalFieldKeysAreNotMistakenForEmbeddedRecords() {
        val header = byteArrayOf(88, 123) + field(100, byteArrayOf(42, 0))
        val raw = byteArrayOf(0x88.toByte(), 0, 3) + original(header).drop(2).toByteArray()
        assertEquals(123L, SyncArchiveCodec.readManifest(envelope(3, raw)).header.playbackStatsClearedAt)
        assertEquals(123L, SyncArchiveV4Format.readManifest(envelope(4, current(raw))).original.header.playbackStatsClearedAt)
    }

    private fun original(header: ByteArray): ByteArray = byteArrayOf(8, 3) + field(2, header) + byteArrayOf(32, 0, 40, 0, 48, 0)

    private fun current(original: ByteArray): ByteArray = byteArrayOf(8, 4, 16, 1) + field(3, original) +
        field(4, byteArrayOf(16, 0, 24, 0)) + field(5, byteArrayOf(16, 0, 24, 0)) +
        field(6, byteArrayOf(16, 0, 24, 0)) + field(7, "0".repeat(64).toByteArray()) + field(8, "0".repeat(64).toByteArray())

    private fun field(tag: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        number(output, tag * 8 + 2)
        number(output, body.size)
        output.write(body)
    }.toByteArray()

    private fun number(output: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (remaining >= 128) {
            output.write((remaining and 127) or 128)
            remaining = remaining ushr 7
        }
        output.write(remaining)
    }

    private fun envelope(protocol: Int, raw: ByteArray): ByteArray {
        val compressed = SyncArchiveCodec.compress(raw)
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.write("NPSYNC0$protocol".toByteArray(Charsets.US_ASCII))
                data.writeInt(raw.size)
                data.write(MessageDigest.getInstance("SHA-256").digest(compressed))
                data.write(compressed)
            }
        }.toByteArray()
    }
}
