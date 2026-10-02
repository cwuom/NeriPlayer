package moe.ouom.neriplayer.data.sync.archive

import java.io.OutputStream

internal class SyncContentChunker(private val maximumBytes: Int, private val emit: (ByteArray) -> Unit) : OutputStream() {
    constructor(emit: (ByteArray) -> Unit) : this(SyncArchiveLimits.MAX_RAW_BYTES, emit)

    init { require(maximumBytes in 1..SyncArchiveLimits.MAX_COMPACT_RAW_BYTES) { "Invalid sync chunk budget" } }
    private val buffer = ByteArray(maximumBytes)
    private var size = 0
    private var rolling = 0L
    var totalBytes = 0L
        private set

    override fun write(value: Int) {
        buffer[size++] = value.toByte()
        totalBytes++
        rolling = (rolling shl 1) + GEAR[value and 255]
        if (size == maximumBytes || size >= MIN_BYTES && (rolling and BOUNDARY_MASK) == 0L) flushChunk()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset in 0..bytes.size)
        require(length in 0..bytes.size - offset)
        for (i in offset until offset + length) write(bytes[i].toInt() and 255)
    }

    override fun close() { if (size > 0) flushChunk() }

    private fun flushChunk() {
        emit(buffer.copyOf(size))
        size = 0
        // 保留滚动窗口, 插入或删除后可在后续内容恢复原有边界
    }

    private companion object {
        const val MIN_BYTES = 64 * 1024
        const val BOUNDARY_MASK = (1L shl 18) - 1
        val GEAR = LongArray(256) { index ->
            var value = index.toLong() + -7046029254386353131L
            value = (value xor (value ushr 30)) * -4658895280553007687L
            value = (value xor (value ushr 27)) * -7723592293110705685L
            value xor (value ushr 31)
        }
    }
}
