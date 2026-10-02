package moe.ouom.neriplayer.data.sync.archive

import moe.ouom.neriplayer.data.sync.archive.compact.CompactLiteralRequired
import moe.ouom.neriplayer.data.sync.archive.compact.SyncCompactWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.io.InputStream

class SyncCompactWireTest {
    @Test
    fun `unsigned wire bits survive signed Long extremes`() {
        for (value in listOf(0L, 1L, 127L, 128L, Long.MIN_VALUE, Long.MAX_VALUE, -1L)) {
            assertEquals(value, SyncCompactWire.readNumber(SyncCompactWire.number(value).inputStream()))
            assertEquals(value, SyncCompactWire.unzigzag(SyncCompactWire.zigzag(value)))
        }
    }

    @Test
    fun `compact numbers reject overlong truncated and overflow encodings`() {
        assertThrows(IllegalArgumentException::class.java) { SyncCompactWire.readNumber(byteArrayOf(-128, 0).inputStream()) }
        assertEquals(0, SyncCompactWire.readNumber(byteArrayOf(-128, 0).inputStream(), false))
        assertThrows(EOFException::class.java) { SyncCompactWire.readNumber(byteArrayOf(-128).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { SyncCompactWire.readNumber((ByteArray(9) { -128 } + byteArrayOf(2)).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { SyncCompactWire.readNumber(ByteArray(11) { -128 }.inputStream()) }
    }

    @Test
    fun `size vectors reject negative unsigned casts and declared excess`() {
        assertThrows(IllegalArgumentException::class.java) { SyncCompactWire.count(byteArrayOf(1).inputStream(), 0) }
        assertThrows(IllegalArgumentException::class.java) { SyncCompactWire.count(SyncCompactWire.number(-1).inputStream(), Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { SyncCompactWire.bytes(byteArrayOf().inputStream(), -1) }
        assertThrows(EOFException::class.java) { SyncCompactWire.bytes(byteArrayOf(1).inputStream(), 2) }
        assertArrayEquals(byteArrayOf(), SyncCompactWire.bytes(byteArrayOf().inputStream(), 0))
    }

    @Test
    fun `exact payload reads tolerate a zero byte intermediate read`() {
        val bytes = byteArrayOf(4, 5, 6)
        val input = object : InputStream() {
            private val source = bytes.inputStream()
            private var zero = true
            override fun read(): Int = source.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (zero) { zero = false; return 0 }
                return source.read(target, offset, length)
            }
        }
        assertArrayEquals(bytes, SyncCompactWire.bytes(input, 3))
    }

    @Test
    fun `original fixed width and opaque binary fields keep their bytes`() {
        val eight = byteArrayOf(-1, 0, 13, 10, -128, 1, 2, 3)
        val four = byteArrayOf(-1, 0, -128, 4)
        val raw = byteArrayOf(9) + eight + byteArrayOf(21) + four + byteArrayOf(26, 2, -1, 0)
        val fields = SyncCompactWire.fields(raw)
        assertEquals(listOf(9L, 21L, 26L), fields.map { it.key })
        assertArrayEquals(eight, fields[0].body)
        assertArrayEquals(four, fields[1].body)
        assertFalse(fields.any { it.literal })
    }

    @Test
    fun `original prefixes can be overlong but invalid fields are rejected`() {
        val raw = byteArrayOf(-110, 0, -128, 0)
        val field = SyncCompactWire.fields(raw).single()
        assertEquals(0, field.key)
        assertArrayEquals(raw, field.body)
        assertThrows(IllegalArgumentException::class.java) { SyncCompactWire.fields(byteArrayOf(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { SyncCompactWire.fields(byteArrayOf(18, 100)) }
        assertThrows(CompactLiteralRequired::class.java) { SyncCompactWire.fields(byteArrayOf(11, 12)) }
    }

    @Test
    fun `nested schemas are explicit and unknown leaves remain opaque`() {
        val mappings = listOf(Triple("song", 27, "token"), Triple("kind5", 2, "song"),
            Triple("kind8", 16, "shard"), Triple("kind9", 17, "shard"), Triple("kind10", 7, "token"),
            Triple("kind11", 12, "shard"), Triple("kind11", 18, "token"), Triple("kind12", 6, "shard"),
            Triple("kind13", 7, "shard"), Triple("kind16", 2, "token"))
        for ((context, tag, child) in mappings) assertEquals(child, SyncCompactWire.nested(context, tag))
        assertNull(SyncCompactWire.nested("unknown", 1))
        assertNull(SyncCompactWire.nested("song", 255))
    }
}
