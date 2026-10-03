package moe.ouom.neriplayer.data.sync.archive.budget

import java.io.IOException

internal class SyncArchiveWireCursor(private val bytes: ByteArray, private val end: Int = bytes.size, start: Int = 0) {
    private var position = start
    var tag = 0
        private set
    var wire = 0
        private set
    var bodyStart = 0
        private set
    var bodyEnd = 0
        private set

    fun next(): Boolean {
        if (position == end) return false
        readHeader()
        readBody()
        return true
    }

    private fun readHeader() {
        val key = number()
        val field = key ushr 3
        if (field !in 1L..536_870_911L) throw IOException("Invalid sync protobuf field")
        tag = field.toInt()
        wire = (key and 7L).toInt()
    }

    private fun readBody() {
        if (wire == 0) {
            bodyStart = position
            number()
            bodyEnd = position
            return
        }
        advanceBody(bodyLength())
    }

    private fun bodyLength(): Long = when (wire) {
        1 -> 8L
        2 -> number()
        5 -> 4L
        else -> throw IOException("Unsupported sync protobuf wire type")
    }

    private fun advanceBody(length: Long) {
        if (length < 0L || length > end - position) throw IOException("Truncated sync protobuf field")
        bodyStart = position
        position += length.toInt()
        bodyEnd = position
    }

    private fun number(): Long {
        var value = 0L
        for (index in 0..9) {
            val byte = readNumberByte(index)
            value = value or ((byte and 127).toLong() shl (index * 7))
            if (byte and 128 == 0) return value
        }
        throw IOException("Overflowing sync protobuf number")
    }

    private fun readNumberByte(index: Int): Int {
        if (position == end) throw IOException("Truncated sync protobuf number")
        val byte = bytes[position++].toInt() and 255
        if (index == 9 && byte > 1) throw IOException("Overflowing sync protobuf number")
        return byte
    }
}
