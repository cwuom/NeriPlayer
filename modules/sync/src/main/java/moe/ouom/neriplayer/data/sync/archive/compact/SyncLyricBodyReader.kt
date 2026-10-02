package moe.ouom.neriplayer.data.sync.archive.compact

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.MAX_BODIES
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.MAX_BODY_BYTES
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.MAX_GROUP_BYTES
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.MAX_NUMBER
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.MAX_PALETTE
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.readExact
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.readInt
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.readParts
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.readUnsigned
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricBodyWire.strictText

internal object SyncLyricBodyReader {
    fun read(input: InputStream, checkActive: () -> Unit): List<ByteArray> {
        checkActive()
        val frame = readFrame(input, checkActive)
        val parts = frame.parts
        val text = if (frame.mode == 0) parts[2] else restoreUnicode(parts, checkActive)
        require(text.size <= MAX_GROUP_BYTES) { "Lyric literal group exceeds budget" }
        return restoreBodies(parts[0], parts[1], text, checkActive)
    }

    private class BodyFrame(val mode: Int, val parts: List<ByteArray>)

    private fun readFrame(input: InputStream, checkActive: () -> Unit): BodyFrame {
        val mode = input.read()
        require(mode == 0 || mode == 1) { "Invalid lyric body mode" }
        val parts = readParts(input, if (mode == 0) 3 else 5, checkActive)
        require(input.read() == -1) { "Trailing lyric body content" }
        return BodyFrame(mode, parts)
    }

    private fun restoreUnicode(parts: List<ByteArray>, checkActive: () -> Unit): ByteArray {
        val palette = readPalette(parts[2], checkActive)
        val characters = readCharacterCount(parts[3], parts[4].size)
        val output = ByteArrayOutputStream()
        for (index in 0 until characters) {
            if (index % 4096 == 0) checkActive()
            val offset = index * 2
            val value = (parts[4][offset].toInt() and 255) or ((parts[4][offset + 1].toInt() and 255) shl 8)
            require(value < palette.size) { "Invalid lyric palette index" }
            val bytes = palette[value]
            require(output.size() + bytes.size <= MAX_GROUP_BYTES) { "Lyric literals exceed byte budget" }
            output.write(bytes)
        }
        return output.toByteArray()
    }

    private fun readCharacterCount(bytes: ByteArray, contentBytes: Int): Int {
        val lengths = ByteArrayInputStream(bytes)
        require(readUnsigned(lengths) == 1L) { "Invalid lyric literal count" }
        val characters = readInt(lengths, MAX_GROUP_BYTES)
        require(lengths.read() == -1) { "Trailing lyric character lengths" }
        require(contentBytes == characters * 2) { "Invalid lyric character count" }
        return characters
    }

    private fun readPalette(bytes: ByteArray, checkActive: () -> Unit): List<ByteArray> {
        val input = ByteArrayInputStream(bytes)
        val count = readInt(input, MAX_PALETTE)
        require(count > 0) { "Empty lyric palette" }
        val seen = HashSet<Int>()
        val result = List(count) {
            val raw = readExact(input, readInt(input, 4), checkActive)
            val codePoint = paletteCodePoint(raw)
            require(seen.add(codePoint)) { "Duplicate lyric palette entry" }
            raw
        }
        require(input.read() == -1) { "Trailing lyric palette content" }
        return result
    }

    private fun paletteCodePoint(raw: ByteArray): Int {
        val text = requireNotNull(strictText(raw)) { "Invalid lyric palette UTF8" }
        require(text.codePointCount(0, text.length) == 1) { "Invalid lyric palette entry" }
        return text.codePointAt(0)
    }

    private fun restoreBodies(layoutBytes: ByteArray, numberBytes: ByteArray, literals: ByteArray, checkActive: () -> Unit): List<ByteArray> {
        val layout = ByteArrayInputStream(layoutBytes)
        val columns = NumericColumns(numberBytes, checkActive)
        val count = readInt(layout, MAX_BODIES)
        val text = LiteralReader(literals)
        var total = 0L
        val bodies = List(count) {
            val body = restoreBody(layout, columns, text, checkActive)
            total += body.size
            require(total <= MAX_GROUP_BYTES) { "Lyric restored group exceeds byte budget" }
            body
        }
        require(layout.read() == -1) { "Trailing lyric layout content" }
        text.requireFinished()
        columns.requireFinished()
        return bodies
    }

    private fun restoreBody(layout: InputStream, columns: NumericColumns, text: LiteralReader, checkActive: () -> Unit): ByteArray {
        checkActive()
        val output = ByteArrayOutputStream()
        val previous = LongArray(8)
        val tokens = readInt(layout, MAX_BODY_BYTES)
        repeat(tokens) { index ->
            if (index % 256 == 0) checkActive()
            text.append(layout, output)
            val format = TimestampFormat.read(layout)
            val pieces = columns.readPieces(layout, format, previous)
            val token = format.render(pieces)
            require(output.size() + token.size <= MAX_BODY_BYTES) { "Lyric restored body exceeds byte budget" }
            output.write(token)
        }
        text.append(layout, output)
        return output.toByteArray()
    }

    private class LiteralReader(private val bytes: ByteArray) {
        private var position = 0

        fun append(layout: InputStream, output: ByteArrayOutputStream) {
            val count = readInt(layout, MAX_BODY_BYTES)
            require(count <= bytes.size - position) { "Truncated lyric literal content" }
            require(output.size() + count <= MAX_BODY_BYTES) { "Lyric literal body exceeds byte budget" }
            output.write(bytes, position, count)
            position += count
        }

        fun requireFinished() {
            require(position == bytes.size) { "Trailing lyric literal content" }
        }
    }

    private class NumericColumns(bytes: ByteArray, checkActive: () -> Unit) {
        private val columns: List<ByteArrayInputStream>

        init {
            val input = ByteArrayInputStream(bytes)
            columns = readParts(input, 8, checkActive).map(::ByteArrayInputStream)
            require(input.read() == -1) { "Trailing lyric numeric columns" }
        }

        fun readPieces(layout: InputStream, format: TimestampFormat, previous: LongArray): List<String> = List(format.pieces) { index ->
            val width = readInt(layout, 18)
            require(width > 0) { "Invalid lyric numeric width" }
            val slot = format.offset + index
            val number = restoreNumber(slot, index == 0, previous)
            val decimal = number.toString()
            require(decimal.length <= width) { "Lyric numeric width overflow" }
            decimal.padStart(width, '0')
        }

        private fun restoreNumber(slot: Int, delta: Boolean, previous: LongArray): Long {
            val encoded = readUnsigned(columns[slot])
            val number = if (delta) previous[slot] + ((encoded ushr 1) xor -(encoded and 1)) else encoded
            require(number in 0..MAX_NUMBER) { "Invalid lyric numeric value" }
            previous[slot] = number
            return number
        }

        fun requireFinished() {
            for (column in columns) require(column.read() == -1) { "Trailing lyric numeric content" }
        }
    }

    private class TimestampFormat(val offset: Int, val pieces: Int, private val open: Char, private val close: Char, private val separators: String) {
        fun render(values: List<String>): ByteArray = buildString {
            append(open)
            append(values[0])
            for (index in 1 until pieces) {
                append(separators[index - 1])
                append(values[index])
            }
            append(close)
        }.toByteArray(Charsets.US_ASCII)

        companion object {
            fun read(layout: InputStream): TimestampFormat = when (layout.read()) {
                0 -> {
                    val separator = layout.read()
                    require(separator == 0 || separator == 1) { "Invalid lyric timestamp separator" }
                    TimestampFormat(0, 3, '[', ']', if (separator == 1) ":." else "::")
                }
                1 -> TimestampFormat(3, 3, '(', ')', ",,")
                2 -> TimestampFormat(6, 2, '[', ']', ",")
                else -> throw IllegalArgumentException("Invalid lyric timestamp kind")
            }
        }
    }
}
