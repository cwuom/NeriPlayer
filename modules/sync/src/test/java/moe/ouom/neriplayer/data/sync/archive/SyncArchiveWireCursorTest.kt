package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayOutputStream
import java.io.IOException
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveWireCursor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncArchiveWireCursorTest {
    @Test fun emptyInputAndAnExhaustedWindowHaveNoFields() {
        assertFalse(SyncArchiveWireCursor(byteArrayOf()).next())
        assertFalse(SyncArchiveWireCursor(byteArrayOf(0), end = 0).next())
        assertFalse(SyncArchiveWireCursor(byteArrayOf(0), start = 1).next())
    }

    @Test fun unknownFieldsOfEverySupportedWireTypeKeepTheirExactBodies() {
        val bodies = listOf(number(300), ByteArray(8) { it.toByte() }, byteArrayOf(0, 7, -1), ByteArray(4) { it.toByte() })
        val wires = listOf(0, 1, 2, 5)
        val input = ByteArrayOutputStream().also { output ->
            for (index in wires.indices) {
                output.write(number(((100L + index) shl 3) or wires[index].toLong()))
                if (wires[index] == 2) output.write(number(bodies[index].size.toLong()))
                output.write(bodies[index])
            }
            output.write(byteArrayOf(8, 1))
        }.toByteArray()
        val cursor = SyncArchiveWireCursor(input)
        for (index in wires.indices) assertNext(cursor, input, 100 + index, wires[index], bodies[index])
        assertNext(cursor, input, 1, 0, byteArrayOf(1))
        assertFalse(cursor.next())
        assertFalse(cursor.next())
    }

    @Test fun emptyLengthDelimitedBodyAtTheBoundaryIsValid() {
        val cursor = SyncArchiveWireCursor(byteArrayOf(10, 0))
        assertTrue(cursor.next())
        assertEquals(2, cursor.bodyStart)
        assertEquals(2, cursor.bodyEnd)
        assertFalse(cursor.next())
    }

    @Test fun noncanonicalKeysLengthsAndValuesRemainReadable() {
        val input = byteArrayOf(-118, 0, -126, 0, 8, 1, -112, 0, -128, 0)
        val cursor = SyncArchiveWireCursor(input)
        assertNext(cursor, input, 1, 2, byteArrayOf(8, 1))
        assertNext(cursor, input, 2, 0, byteArrayOf(-128, 0))
        assertFalse(cursor.next())
    }

    @Test fun maximumFieldNumberAndUnsigned64BitValuesRemainValid() {
        val body = ByteArray(9) { -1 } + byteArrayOf(1)
        val input = number(536_870_911L shl 3) + body
        val cursor = SyncArchiveWireCursor(input)
        assertNext(cursor, input, 536_870_911, 0, body)
        assertFalse(cursor.next())
    }

    @Test fun tenBytePaddedKeyAndLengthAreStillAccepted() {
        val key = byteArrayOf(-118) + ByteArray(8) { -128 } + byteArrayOf(0)
        val length = ByteArray(9) { -128 } + byteArrayOf(0)
        val input = key + length
        val cursor = SyncArchiveWireCursor(input)
        assertNext(cursor, input, 1, 2, byteArrayOf())
        assertEquals(input.size, cursor.bodyEnd)
        assertFalse(cursor.next())
    }

    @Test fun aNestedWindowNeverConsumesTheSurroundingFields() {
        val input = byteArrayOf(0, 8, 1, 10, 0, 7)
        val cursor = SyncArchiveWireCursor(input, end = 5, start = 1)
        assertNext(cursor, input, 1, 0, byteArrayOf(1))
        assertNext(cursor, input, 1, 2, byteArrayOf())
        assertFalse(cursor.next())
    }

    @Test fun zeroAndOutOfRangeFieldNumbersAreRejected() {
        for (key in listOf(0L, 7L, 536_870_912L shl 3, -1L)) {
            reject(number(key), "Invalid sync protobuf field")
        }
    }

    @Test fun groupsAndUnsupportedWireTypesAreRejected() {
        for (wire in listOf(3, 4, 6, 7)) {
            reject(byteArrayOf((8 + wire).toByte()), "Unsupported sync protobuf wire type")
        }
    }

    @Test fun unterminatedKeysValuesAndLengthsAreRejected() {
        val truncated = byteArrayOf(-128)
        for (input in listOf(truncated, byteArrayOf(8) + truncated, byteArrayOf(10) + truncated)) {
            reject(input, "Truncated sync protobuf number")
        }
        reject(byteArrayOf(8), "Truncated sync protobuf number")
        reject(byteArrayOf(10), "Truncated sync protobuf number")
    }

    @Test fun overflowingKeysValuesAndLengthsAreRejectedAtTheTenthByte() {
        for (last in listOf(2, 127, 128, 255)) {
            val overflowing = ByteArray(9) { -128 } + byteArrayOf(last.toByte())
            for (prefix in listOf(byteArrayOf(), byteArrayOf(8), byteArrayOf(10))) {
                reject(prefix + overflowing, "Overflowing sync protobuf number")
            }
        }
    }

    @Test fun fixedWidthAndLengthDelimitedBodiesMustFitTheWindow() {
        for ((key, length) in listOf(9 to 8, 13 to 4)) {
            reject(byteArrayOf(key.toByte()) + ByteArray(length - 1), "Truncated sync protobuf field")
        }
        reject(byteArrayOf(10, 2, 1), "Truncated sync protobuf field")
        val input = byteArrayOf(10, 2, 1, 2)
        val cursor = SyncArchiveWireCursor(input, end = 3)
        assertEquals("Truncated sync protobuf field", assertThrows(IOException::class.java) { cursor.next() }.message)
    }

    @Test fun unsignedAndLargeLengthsCannotWrapIntoAnInBoundsBody() {
        for (length in listOf(-1L, Long.MIN_VALUE, 1L shl 31, Long.MAX_VALUE)) {
            reject(byteArrayOf(10) + number(length), "Truncated sync protobuf field")
        }
    }

    @Test fun aNumberCannotConsumeBytesOutsideItsNestedWindow() {
        val input = byteArrayOf(8, -128, 0)
        val cursor = SyncArchiveWireCursor(input, end = 2)
        assertEquals("Truncated sync protobuf number", assertThrows(IOException::class.java) { cursor.next() }.message)
    }

    private fun assertNext(cursor: SyncArchiveWireCursor, input: ByteArray, tag: Int, wire: Int, body: ByteArray) {
        assertTrue(cursor.next())
        assertEquals(tag, cursor.tag)
        assertEquals(wire, cursor.wire)
        assertArrayEquals(body, input.copyOfRange(cursor.bodyStart, cursor.bodyEnd))
    }

    private fun reject(input: ByteArray, message: String) {
        assertEquals(message, assertThrows(IOException::class.java) { SyncArchiveWireCursor(input).next() }.message)
    }

    private fun number(value: Long): ByteArray = ByteArrayOutputStream().also { output ->
        var remaining = value
        while (remaining and -128L != 0L) {
            output.write((remaining.toInt() and 127) or 128)
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
    }.toByteArray()
}
