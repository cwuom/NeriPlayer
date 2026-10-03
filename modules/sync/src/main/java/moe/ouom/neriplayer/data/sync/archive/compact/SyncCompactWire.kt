package moe.ouom.neriplayer.data.sync.archive.compact

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

internal object SyncCompactWire {
    const val BLOCK_BYTES = 2 * 1024 * 1024
    const val MAX_RECORD_BYTES = 64 * 1024 * 1024
    const val MAX_PART_BYTES = 16 * 1024 * 1024
    const val MAX_ROWS = 32768
    const val MAX_FIELDS = 262144
    const val MAX_DEPTH = 8

    data class Field(val key: Long, val body: ByteArray, val literal: Boolean)
    private val childContexts = mapOf(
        "song" to mapOf(27 to "token"),
        "kind5" to mapOf(2 to "song"),
        "kind8" to mapOf(16 to "shard"),
        "kind9" to mapOf(17 to "shard"),
        "kind10" to mapOf(7 to "token"),
        "kind11" to mapOf(12 to "shard", 18 to "token"),
        "kind12" to mapOf(6 to "shard"),
        "kind13" to mapOf(7 to "shard"),
        "kind16" to mapOf(2 to "token")
    )
    private val lyricTags = setOf(10, 11, 21, 22, 32, 33)

    fun number(value: Long): ByteArray = ByteArrayOutputStream(10).also { writeNumber(it, value) }.toByteArray()

    fun writeNumber(output: OutputStream, value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) {
            output.write((remaining.toInt() and 127) or 128)
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
    }

    fun readNumber(input: InputStream, canonical: Boolean = true): Long {
        var result = 0L
        for (index in 0..9) {
            val byte = numberByte(input, index)
            result = result or ((byte and 127).toLong() shl (index * 7))
            if (byte and 128 == 0) {
                validateEnding(index, byte, canonical)
                return result
            }
        }
        error("Compact number overflow")
    }

    private fun numberByte(input: InputStream, index: Int): Int {
        val byte = input.read()
        if (byte < 0) throw EOFException("Truncated compact number")
        require(index != 9 || byte <= 1) { "Compact number overflow" }
        return byte
    }

    private fun validateEnding(index: Int, byte: Int, canonical: Boolean) {
        require(!canonical || index == 0 || byte != 0) { "Noncanonical compact number" }
    }

    fun count(input: InputStream, maximum: Int): Int {
        return size(input, maximum, true)
    }

    private fun size(input: InputStream, maximum: Int, canonical: Boolean): Int {
        val value = readNumber(input, canonical)
        require(value in 0..maximum.toLong()) { "Compact size exceeds safe budget" }
        return value.toInt()
    }

    fun bytes(input: InputStream, length: Int): ByteArray {
        require(length >= 0) { "Negative compact length" }
        val result = ByteArray(length)
        DataInputStream(input).readFully(result)
        return result
    }

    fun part(input: InputStream, maximum: Int = MAX_PART_BYTES): ByteArray = bytes(input, count(input, maximum))

    fun writePart(output: OutputStream, bytes: ByteArray) {
        writeNumber(output, bytes.size.toLong())
        output.write(bytes)
    }

    fun fields(raw: ByteArray): List<Field> {
        val input = ByteArrayInputStream(raw)
        val result = ArrayList<Field>()
        while (input.available() > 0) {
            if (result.size >= MAX_FIELDS) throw CompactLiteralRequired()
            result += originalField(raw, input)
        }
        return result
    }

    private fun originalField(raw: ByteArray, input: ByteArrayInputStream): Field {
        val start = raw.size - input.available()
        val key = readNumber(input, false)
        require(key ushr 3 in 1..536870911L) { "Invalid original protobuf field" }
        val keyEnd = raw.size - input.available()
        val wire = (key and 7).toInt()
        val length = originalLength(input, wire, raw.size)
        val prefixEnd = if (wire == 0) keyEnd else raw.size - input.available()
        val body = if (wire == 0) raw.copyOfRange(keyEnd, keyEnd + length) else bytes(input, length)
        val canonical = if (wire == 2) number(key) + number(length.toLong()) else number(key)
        if (raw.copyOfRange(start, prefixEnd).contentEquals(canonical)) return Field(key, body, false)
        return Field(0, raw.copyOfRange(start, raw.size - input.available()), true)
    }

    private fun originalLength(input: ByteArrayInputStream, wire: Int, maximum: Int): Int = when (wire) {
        0 -> input.available().let { available -> readNumber(input, false); available - input.available() }
        1 -> 8
        2 -> size(input, maximum, false)
        5 -> 4
        else -> throw CompactLiteralRequired()
    }

    fun root(kind: Int): String = if (kind == 2 || kind == 4 || kind == 15) "song" else "kind$kind"

    fun nested(context: String, tag: Int): String? = childContexts[context]?.get(tag)

    fun isLyric(context: String, tag: Int): Boolean = context == "song" && tag in lyricTags
    fun zigzag(value: Long): Long = (value shl 1) xor (value shr 63)
    fun unzigzag(value: Long): Long = (value ushr 1) xor -(value and 1)

    fun compare(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val order = (left[index].toInt() and 255).compareTo(right[index].toInt() and 255)
            if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }

    fun exhausted(input: InputStream) = require(input.read() == -1) { "Unexpected compact trailing bytes" }
}

internal class CompactLiteralRequired : RuntimeException()
