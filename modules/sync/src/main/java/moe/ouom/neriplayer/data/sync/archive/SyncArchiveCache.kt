@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Format
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Ref

internal class SyncArchiveCache(private val directory: File) {
    init { require(directory.isDirectory || directory.mkdirs()) { "Unable to create sync cache" } }

    fun store(raw: ByteArray, index: Boolean): SyncArchiveRef {
        val rawHash = SyncArchiveCodec.digest(raw)
        val pointer = File(directory, "$rawHash-$index.ref")
        val cached = findCached(pointer, rawHash, index, raw.size)
        if (cached != null) return cached
        val compressed = SyncArchiveCodec.compress(raw)
        val ref = SyncArchiveRef(SyncArchiveCodec.digest(compressed), rawHash, raw.size, compressed.size, index)
        save(ref, compressed)
        return ref
    }

    private fun findCached(pointer: File, rawHash: String, index: Boolean, rawBytes: Int): SyncArchiveRef? = runCatching {
        val ref = readPointer(pointer)
        require(ref.rawHash == rawHash)
        require(ref.index == index)
        require(ref.rawBytes == rawBytes)
        SyncArchiveCodec.decodeObject(ref, readCompressed(ref))
        ref
    }.getOrNull()

    private fun readPointer(pointer: File): SyncArchiveRef {
        require(pointer.length() in 1..512) { "Invalid sync cache pointer size" }
        return ProtoBuf.decodeFromByteArray(pointer.readBytes())
    }

    fun cachedCompressed(ref: SyncArchiveRef): ByteArray? = runCatching {
        readCompressed(ref)
    }.getOrNull()

    fun save(ref: SyncArchiveRef, compressed: ByteArray) {
        SyncArchiveCodec.decodeObject(ref, compressed)
        writeAtomic(File(directory, ref.path), compressed)
        File(directory, ref.path).setLastModified(System.currentTimeMillis())
        // 下载块也建立已校验的原始内容索引，首次本地发布即可复用
        writeAtomic(File(directory, "${ref.rawHash}-${ref.index}.ref"), ProtoBuf.encodeToByteArray(ref))
    }

    fun readCompressed(ref: SyncArchiveRef): ByteArray {
        SyncArchiveCodec.validate(ref)
        val file = File(directory, ref.path)
        require(file.length() == ref.compressedBytes.toLong()) { "Invalid cached sync object size" }
        return file.readBytes().also {
            require(SyncArchiveCodec.digest(it) == ref.hash) { "Cached sync object checksum mismatch" }
            file.setLastModified(System.currentTimeMillis())
        }
    }

    fun readRaw(ref: SyncArchiveRef): ByteArray = SyncArchiveCodec.decodeObject(ref, readCompressed(ref))

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val temp = File.createTempFile("sync-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { stream -> stream.write(bytes); stream.fd.sync() }
            if (!temp.renameTo(target)) throw IOException("Unable to commit sync cache object")
        } finally { temp.delete() }
    }

    fun trim(protectedPaths: Set<String>) {
        val objects = directory.listFiles { file -> file.name.endsWith(".zst") }.orEmpty()
            .sortedBy { it.lastModified() }
        var bytes = objects.sumOf(File::length)
        for (file in objects) {
            if (bytes <= SyncArchiveLimits.CACHE_BYTES) break
            if (file.name in protectedPaths) continue
            val size = file.length()
            if (file.delete()) bytes -= size
        }
        directory.listFiles { file -> file.name.endsWith(".ref") }.orEmpty().forEach { pointer ->
            val ref = runCatching { readPointer(pointer) }.getOrNull()
            if (ref == null || !File(directory, ref.path).exists()) pointer.delete()
        }
        directory.listFiles { file -> file.name.endsWith(".v4ref") }.orEmpty().forEach { pointer ->
            val ref = runCatching {
                require(pointer.length() in 1..512) { "Invalid sync v4 cache pointer size" }
                ProtoBuf.decodeFromByteArray<SyncArchiveV4Ref>(pointer.readBytes()).also(SyncArchiveV4Format::validateRef)
            }.getOrNull()
            if (ref == null || !File(directory, ref.path).isFile) pointer.delete()
        }
    }
}
