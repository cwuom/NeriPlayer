@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive.v4

import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveCodec
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveLimits
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveManifest
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveManifestValidation
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveManifestWireGuard

@Serializable
internal data class SyncArchiveV4Ref(
    @ProtoNumber(1) val hash: String,
    @ProtoNumber(2) val rawHash: String,
    @ProtoNumber(3) val rawBytes: Int,
    @ProtoNumber(4) val compressedBytes: Int,
    @ProtoNumber(5) val index: Boolean = false
) {
    val path: String get() = "neriplayer-sync-v4-$hash.zst"
}

@Serializable
internal data class SyncArchiveV4Index(@ProtoNumber(1) val children: List<SyncArchiveV4Ref>)

@Serializable
internal data class SyncArchiveV4Stream(
    @ProtoNumber(1) val root: SyncArchiveV4Ref?,
    @ProtoNumber(2) val rawBytes: Long,
    @ProtoNumber(3) val chunks: Long
)

@Serializable
internal data class SyncArchiveV4Manifest(
    @ProtoNumber(1) val protocol: Int,
    @ProtoNumber(2) val schema: Int,
    @ProtoNumber(3) val original: SyncArchiveManifest,
    @ProtoNumber(4) val main: SyncArchiveV4Stream,
    @ProtoNumber(5) val legacy: SyncArchiveV4Stream,
    @ProtoNumber(6) val pool: SyncArchiveV4Stream,
    @ProtoNumber(7) val mainRawHash: String,
    @ProtoNumber(8) val legacyRawHash: String,
    @ProtoNumber(9) val publicationId: String? = null
)

internal object SyncArchiveV4Format {
    const val PROTOCOL = 4
    const val SCHEMA = 1
    const val MAX_RAW_BYTES = SyncArchiveLimits.MAX_COMPACT_RAW_BYTES
    private const val WINDOW_LOG = 22
    private const val HEADER_BYTES = 44
    private val magic = "NPSYNC04".toByteArray(Charsets.US_ASCII)
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun isManifest(bytes: ByteArray): Boolean = bytes.size >= magic.size &&
        magic.contentEquals(bytes.copyOfRange(0, magic.size))

    fun manifest(value: SyncArchiveV4Manifest): ByteArray {
        validateManifest(value)
        val raw = ProtoBuf.encodeToByteArray(value)
        val compressed = SyncArchiveCodec.compress(raw)
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(magic)
                output.writeInt(raw.size)
                output.write(MessageDigest.getInstance("SHA-256").digest(compressed))
                output.write(compressed)
            }
        }.toByteArray()
    }

    fun readManifest(bytes: ByteArray): SyncArchiveV4Manifest {
        require(bytes.size in HEADER_BYTES..SyncArchiveLimits.MAX_OBJECT_BYTES && isManifest(bytes)) { "Invalid sync v4 manifest" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipBytes(magic.size)
        val rawSize = input.readInt()
        val hash = ByteArray(32).also(input::readFully)
        val compressed = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        require(MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(compressed))) { "Sync manifest checksum mismatch" }
        val raw = SyncArchiveCodec.decompress(compressed, rawSize)
        SyncArchiveManifestWireGuard.validate(raw, v4 = true)
        return ProtoBuf.decodeFromByteArray<SyncArchiveV4Manifest>(raw)
            .also(::validateManifest)
    }

    private fun validateManifest(value: SyncArchiveV4Manifest) {
        require(value.protocol == PROTOCOL && value.schema == SCHEMA) { "Unsupported sync archive version" }
        validateHash(value.mainRawHash)
        validateHash(value.legacyRawHash)
        SyncArchiveManifestValidation.validate(value.original)
        value.original.root?.let(SyncArchiveCodec::validate)
        validateCompactBudget(value)
    }

    private fun validateCompactBudget(value: SyncArchiveV4Manifest) {
        val originalBytes = Math.addExact(value.original.rawDataBytes, value.original.legacyLyrics?.rawDataBytes ?: 0L)
        val budget = Math.addExact(Math.multiplyExact(originalBytes, 8L), 8L * MAX_RAW_BYTES)
        var total = 0L
        for (stream in listOf(value.main, value.legacy, value.pool)) {
            validateStream(stream)
            total = Math.addExact(total, stream.rawBytes)
        }
        require(total <= budget) { "Sync compact streams exceed decoded budget" }
    }

    private fun validateStream(stream: SyncArchiveV4Stream) {
        validateStreamTotals(stream)
        if (stream.root == null) {
            require(stream.rawBytes == 0L) { "Invalid empty sync v4 stream" }
        } else {
            require(stream.chunks > 0L) { "Invalid sync v4 stream root" }
            validateRef(stream.root)
        }
    }

    private fun validateStreamTotals(stream: SyncArchiveV4Stream) {
        require(stream.rawBytes >= 0L) { "Invalid sync v4 stream byte count" }
        require(stream.chunks in 0L..stream.rawBytes) { "Invalid sync v4 stream chunk count" }
    }

    fun validateRef(ref: SyncArchiveV4Ref) {
        validateHash(ref.hash)
        validateHash(ref.rawHash)
        val rawBudget = if (ref.index) SyncArchiveLimits.MAX_INDEX_RAW_BYTES else MAX_RAW_BYTES
        require(ref.rawBytes in 1..rawBudget) { "Sync v4 object exceeds raw budget" }
        require(ref.compressedBytes in 1..SyncArchiveLimits.MAX_OBJECT_BYTES) { "Sync v4 object exceeds wire budget" }
    }

    private fun validateHash(hash: String) {
        require(hashPattern.matches(hash)) { "Invalid sync v4 hash" }
    }

    fun compress(raw: ByteArray): ByteArray {
        require(raw.size in 1..MAX_RAW_BYTES) { "Sync v4 object exceeds raw budget" }
        return ZstdCompressCtx().use { it.setLevel(19).setWindowLog(WINDOW_LOG).setChecksum(true).compress(raw) }
    }

    fun decode(ref: SyncArchiveV4Ref, compressed: ByteArray): ByteArray {
        validateRef(ref)
        require(compressed.size == ref.compressedBytes && SyncArchiveCodec.digest(compressed) == ref.hash) { "Sync v4 object checksum mismatch" }
        val result = ByteArray(ref.rawBytes)
        ZstdInputStreamNoFinalizer(ByteArrayInputStream(compressed)).use { input ->
            input.setLongMax(WINDOW_LOG)
            DataInputStream(input).readFully(result)
            require(input.read() == -1) { "Sync v4 object contains excess data" }
        }
        require(SyncArchiveCodec.digest(result) == ref.rawHash) { "Sync v4 raw checksum mismatch" }
        return result
    }
}
