package moe.ouom.neriplayer.data.sync.archive.compact

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal object SyncLyricBodyWire {
    const val MAX_GROUP_BYTES = 4 * 1024 * 1024
    const val MAX_BODY_BYTES = 2 * 1024 * 1024
    const val MAX_BODIES = 4096
    const val MAX_PALETTE = 65_536
    const val MAX_NUMBER = 999_999_999_999_999_999L
    private const val MAX_PART_BYTES = 2 * MAX_GROUP_BYTES + 512 * 1024
    private const val IO_BYTES = 16 * 1024

    fun readParts(input: InputStream, expected: Int, checkActive: () -> Unit): List<ByteArray> {
        require(readUnsigned(input) == expected.toLong()) { "Invalid lyric part count" }
        var total = 0L
        return List(expected) {
            val size = readInt(input, MAX_PART_BYTES)
            total += size
            require(total <= 5L * MAX_GROUP_BYTES) { "Lyric encoded group exceeds budget" }
            readExact(input, size, checkActive)
        }
    }

    fun pack(parts: List<ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        writeUnsigned(output, parts.size.toLong())
        for (part in parts) {
            writeUnsigned(output, part.size.toLong())
            output.write(part)
        }
    }.toByteArray()

    fun writePart(output: OutputStream, part: ByteArray, checkActive: () -> Unit) {
        writeUnsigned(output, part.size.toLong())
        var position = 0
        while (position < part.size) {
            checkActive()
            val count = minOf(IO_BYTES, part.size - position)
            output.write(part, position, count)
            position += count
        }
    }

    fun readExact(input: InputStream, size: Int, checkActive: () -> Unit): ByteArray {
        val result = ByteArray(size)
        var position = 0
        while (position < size) {
            checkActive()
            val count = input.read(result, position, minOf(IO_BYTES, size - position))
            require(count > 0) { "Truncated lyric body part" }
            position += count
        }
        return result
    }

    fun readInt(input: InputStream, maximum: Int): Int = readUnsigned(input).also {
        require(it <= maximum.toLong()) { "Lyric integer exceeds budget" }
    }.toInt()

    fun readUnsigned(input: InputStream): Long {
        var result = 0L
        for (index in 0..8) {
            val byte = input.read()
            require(byte >= 0) { "Truncated lyric integer" }
            result = result or ((byte and 127).toLong() shl (index * 7))
            if (byte < 128) {
                require(index == 0 || byte != 0) { "Noncanonical lyric integer" }
                return result
            }
        }
        throw IllegalArgumentException("Lyric integer overflow")
    }

    fun writeUnsigned(output: OutputStream, input: Long) {
        var value = input
        require(value >= 0) { "Negative lyric integer" }
        while (value > 127) {
            output.write((value.toInt() and 127) or 128)
            value = value ushr 7
        }
        output.write(value.toInt())
    }

    fun strictText(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.UTF_8)
        // 无法原样编码回去的替换字符来自损坏字节，应保留原始字节流
        return text.takeIf { it.toByteArray(Charsets.UTF_8).contentEquals(bytes) }
    }
}
