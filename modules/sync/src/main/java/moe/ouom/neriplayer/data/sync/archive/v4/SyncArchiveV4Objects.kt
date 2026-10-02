@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive.v4

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveCodec
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveLimits
import moe.ouom.neriplayer.data.sync.archive.SyncContentChunker

internal class SyncArchiveV4Objects(private val directory: File) {
    fun store(raw: ByteArray, index: Boolean): SyncArchiveV4Ref? {
        val rawHash = SyncArchiveCodec.digest(raw)
        val pointer = File(directory, "$rawHash-$index.v4ref")
        cachedPointer(pointer, rawHash, raw.size, index)?.let { return it }
        val compressed = SyncArchiveV4Format.compress(raw)
        if (compressed.size > SyncArchiveLimits.MAX_OBJECT_BYTES) return null
        val ref = SyncArchiveV4Ref(SyncArchiveCodec.digest(compressed), rawHash, raw.size, compressed.size, index)
        save(ref, compressed)
        return ref
    }

    private fun cachedPointer(pointer: File, hash: String, size: Int, index: Boolean): SyncArchiveV4Ref? = runCatching {
        require(pointer.length() in 1..512) { "Invalid sync v4 cache pointer" }
        val ref = ProtoBuf.decodeFromByteArray<SyncArchiveV4Ref>(pointer.readBytes())
        validatePointer(ref, hash, size, index)
        readRaw(ref)
        ref
    }.getOrNull()

    private fun validatePointer(ref: SyncArchiveV4Ref, hash: String, size: Int, index: Boolean) {
        require(ref.rawHash == hash) { "Invalid sync v4 cache pointer hash" }
        require(ref.rawBytes == size) { "Invalid sync v4 cache pointer size" }
        require(ref.index == index) { "Invalid sync v4 cache pointer kind" }
    }

    fun cachedCompressed(ref: SyncArchiveV4Ref): ByteArray? = runCatching { readCompressed(ref) }.getOrNull()

    fun readCompressed(ref: SyncArchiveV4Ref): ByteArray {
        SyncArchiveV4Format.validateRef(ref)
        val file = File(directory, ref.path)
        require(file.length() == ref.compressedBytes.toLong()) { "Invalid cached sync v4 size" }
        return file.readBytes().also {
            require(SyncArchiveCodec.digest(it) == ref.hash) { "Cached sync v4 checksum mismatch" }
            file.setLastModified(System.currentTimeMillis())
        }
    }

    fun readRaw(ref: SyncArchiveV4Ref): ByteArray = SyncArchiveV4Format.decode(ref, readCompressed(ref))

    fun save(ref: SyncArchiveV4Ref, compressed: ByteArray) {
        SyncArchiveV4Format.decode(ref, compressed)
        writeAtomic(File(directory, ref.path), compressed)
        writeAtomic(File(directory, "${ref.rawHash}-${ref.index}.v4ref"), ProtoBuf.encodeToByteArray(ref))
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val temporary = File.createTempFile("sync-v4-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            if (!temporary.renameTo(target)) throw IOException("Unable to commit sync v4 cache object")
        } finally { temporary.delete() }
    }

}

internal class SyncArchiveV4TreeWriter(
    private val cache: SyncArchiveV4Objects,
    private val checkActive: () -> Unit
) {
    val objects = LinkedHashMap<String, SyncArchiveV4Ref>()

    fun write(parts: List<File>): SyncArchiveV4Stream {
        val leaves = ArrayList<SyncArchiveV4Ref>()
        var bytes = 0L
        for (part in parts) {
            checkActive()
            if (part.length() in 1..SyncArchiveLimits.MAX_RAW_BYTES.toLong()) {
                // 小的语义块保留边界，避免拆开共享字典后降低压缩率
                val raw = part.readBytes()
                append(raw, leaves)
                bytes = Math.addExact(bytes, raw.size.toLong())
            } else {
                SyncContentChunker(SyncArchiveV4Format.MAX_RAW_BYTES) { raw -> append(raw, leaves) }.use { chunker ->
                    part.inputStream().use { it.copyTo(chunker) }
                    bytes = Math.addExact(bytes, chunker.totalBytes)
                }
            }
        }
        return SyncArchiveV4Stream(buildRoot(leaves), bytes, leaves.size.toLong())
    }

    private fun append(raw: ByteArray, leaves: MutableList<SyncArchiveV4Ref>) {
        checkActive()
        val ref = cache.store(raw, false)
        if (ref != null) {
            leaves += ref
            objects[ref.path] = ref
        } else {
            // 不可压缩的数据也必须遵守单个网络对象的预算
            val middle = raw.size / 2
            append(raw.copyOfRange(0, middle), leaves)
            append(raw.copyOfRange(middle, raw.size), leaves)
        }
    }

    private fun buildRoot(leaves: List<SyncArchiveV4Ref>): SyncArchiveV4Ref? {
        var level = leaves
        while (level.size > 1) {
            level = level.chunked(SyncArchiveLimits.INDEX_FANOUT).map { children ->
                checkActive()
                checkNotNull(cache.store(ProtoBuf.encodeToByteArray(SyncArchiveV4Index(children)), true)).also { objects[it.path] = it }
            }
        }
        return level.firstOrNull()
    }
}

internal class SyncArchiveV4TreeReader(
    private val cache: SyncArchiveV4Objects,
    private val fetch: suspend (String) -> Result<ByteArray>,
    private val verifyRemote: Boolean
) {
    val paths = LinkedHashSet<String>()
    private var visits = 0L
    private var remainingBytes = 0L

    suspend fun read(stream: SyncArchiveV4Stream): List<SyncArchiveV4Ref> {
        visits = 0L
        remainingBytes = stream.rawBytes
        val leaves = ArrayList<SyncArchiveV4Ref>()
        stream.root?.let { walk(it, 0, stream.chunks, leaves) }
        require(leaves.size.toLong() == stream.chunks) { "Sync v4 chunk count mismatch" }
        require(remainingBytes == 0L) { "Sync v4 stream byte count mismatch" }
        return leaves
    }

    private suspend fun walk(ref: SyncArchiveV4Ref, depth: Int, expected: Long, leaves: MutableList<SyncArchiveV4Ref>) {
        currentCoroutineContext().ensureActive()
        require(depth <= SyncArchiveLimits.MAX_TREE_DEPTH) { "Sync v4 index nesting exceeds safe budget" }
        visits++
        require(visits <= Math.multiplyExact(expected, SyncArchiveLimits.MAX_TREE_DEPTH + 1L)) { "Sync v4 index contains excessive references" }
        if (ref.index) {
            visitIndex(ref, readVerifiedObject(ref), depth, expected, leaves)
        } else {
            validateLeaf(ref, expected, leaves.size)
            readVerifiedObject(ref)
            leaves += ref
        }
    }

    private fun validateLeaf(ref: SyncArchiveV4Ref, expected: Long, count: Int) {
        SyncArchiveV4Format.validateRef(ref)
        require(count.toLong() < expected) { "Unexpected sync v4 chunk" }
        require(ref.rawBytes.toLong() <= remainingBytes) { "Sync v4 stream exceeds declared byte budget" }
        remainingBytes -= ref.rawBytes
    }

    private suspend fun readVerifiedObject(ref: SyncArchiveV4Ref): ByteArray {
        SyncArchiveV4Format.validateRef(ref)
        val cached = if (verifyRemote && ref.path !in paths) null else cache.cachedCompressed(ref)
        val compressed = cached ?: fetch(ref.path).getOrThrow().also { cache.save(ref, it) }
        paths += ref.path
        return compressed
    }

    private suspend fun visitIndex(ref: SyncArchiveV4Ref, compressed: ByteArray, depth: Int,
        expected: Long, leaves: MutableList<SyncArchiveV4Ref>) {
        val index = ProtoBuf.decodeFromByteArray<SyncArchiveV4Index>(SyncArchiveV4Format.decode(ref, compressed))
        require(index.children.size in 1..SyncArchiveLimits.INDEX_FANOUT) { "Invalid sync v4 index fanout" }
        index.children.forEach { walk(it, depth + 1, expected, leaves) }
    }
}

internal class SyncArchiveV4InputStream(private val refs: List<SyncArchiveV4Ref>, private val cache: SyncArchiveV4Objects) : InputStream() {
    private var next = 0
    private var current = ByteArrayInputStream(ByteArray(0))

    private fun advance(): Boolean {
        while (current.available() == 0) {
            if (next == refs.size) return false
            current = ByteArrayInputStream(cache.readRaw(refs[next++]))
        }
        return true
    }

    override fun read(): Int = if (advance()) current.read() else -1

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || length > bytes.size - offset) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        return if (advance()) current.read(bytes, offset, length) else -1
    }
}
