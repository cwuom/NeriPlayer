@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveManifestWireGuard

internal object SyncArchiveCodec {
    private val magic = "NPSYNC03".toByteArray(Charsets.US_ASCII)
    private const val DIGEST_BYTES = 32
    private const val HEADER_BYTES = 8 + 4 + DIGEST_BYTES
    private const val HEX = "0123456789abcdef"

    fun isManifest(bytes: ByteArray): Boolean {
        if (bytes.size < magic.size) return false
        return magic.contentEquals(bytes.copyOfRange(0, magic.size))
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "${HEX[(it.toInt() and 255) ushr 4]}${HEX[it.toInt() and 15]}" }

    fun validate(ref: SyncArchiveRef) {
        requireHash(ref.hash, "object")
        requireHash(ref.rawHash, "raw")
        requireRawSize(ref.rawBytes)
        requireWireSize(ref.compressedBytes)
        if (ref.index) require(ref.rawBytes <= SyncArchiveLimits.MAX_INDEX_RAW_BYTES) { "Sync index exceeds raw budget" }
    }

    private val hashPattern = Regex("[0-9a-f]{64}")

    private fun requireHash(hash: String, label: String) {
        require(hashPattern.matches(hash)) { "Invalid sync $label hash" }
    }

    private fun requireRawSize(bytes: Int) {
        require(bytes in 1..SyncArchiveLimits.MAX_RAW_BYTES) { "Sync object exceeds raw budget" }
    }

    private fun requireWireSize(bytes: Int) {
        require(bytes in 1..SyncArchiveLimits.MAX_OBJECT_BYTES) { "Sync object exceeds wire budget" }
    }

    fun compress(bytes: ByteArray): ByteArray {
        requireRawSize(bytes.size)
        return ZstdCompressCtx().use { context ->
            context.setLevel(19).setWindowLog(20).setChecksum(true).compress(bytes)
        }.also { requireWireSize(it.size) }
    }

    fun decompress(bytes: ByteArray, rawBytes: Int): ByteArray {
        requireWireSize(bytes.size)
        requireRawSize(rawBytes)
        val result = ByteArray(rawBytes)
        ZstdInputStreamNoFinalizer(ByteArrayInputStream(bytes)).use { input ->
            input.setLongMax(20)
            DataInputStream(input).readFully(result)
            require(input.read() == -1) { "Sync object contains excess data" }
        }
        return result
    }

    fun decodeObject(ref: SyncArchiveRef, compressed: ByteArray): ByteArray {
        validate(ref)
        require(compressed.size == ref.compressedBytes && digest(compressed) == ref.hash) { "Sync object checksum mismatch" }
        return decompress(compressed, ref.rawBytes).also {
            require(digest(it) == ref.rawHash) { "Sync raw checksum mismatch" }
        }
    }

    fun manifest(manifest: SyncArchiveManifest): ByteArray {
        val raw = ProtoBuf.encodeToByteArray(manifest)
        val compressed = compress(raw)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.write(magic)
            it.writeInt(raw.size)
            it.write(MessageDigest.getInstance("SHA-256").digest(compressed))
            it.write(compressed)
        }
        return output.toByteArray()
    }

    fun readManifest(bytes: ByteArray): SyncArchiveManifest {
        validateEnvelope(bytes)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipBytes(magic.size)
        val rawSize = input.readInt()
        val expected = ByteArray(DIGEST_BYTES).also(input::readFully)
        val compressed = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        require(MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(compressed))) { "Sync manifest checksum mismatch" }
        val raw = decompress(compressed, rawSize)
        SyncArchiveManifestWireGuard.validate(raw, v4 = false)
        return ProtoBuf.decodeFromByteArray<SyncArchiveManifest>(raw)
            .also(SyncArchiveManifestValidation::validate)
    }

    private fun validateEnvelope(bytes: ByteArray) {
        require(bytes.size in HEADER_BYTES..SyncArchiveLimits.MAX_OBJECT_BYTES) { "Invalid sync v3 manifest" }
        require(isManifest(bytes)) { "Invalid sync v3 manifest" }
    }
}
