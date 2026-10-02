package moe.ouom.neriplayer.data.sync.archive

import moe.ouom.neriplayer.data.sync.archive.compact.SyncCompactBlock
import moe.ouom.neriplayer.data.sync.archive.compact.SyncCompactWire
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricPoolFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class SyncCompactBlockValidationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `explicit scalar column vector restores its original record`() {
        val shape = table("kind14", listOf(listOf(8L)), listOf(column(8, 1, 2, 2)))
        assertArrayEquals(record(14, byteArrayOf(8, 1)), decode(shape, byteArrayOf(0, 0, 2)))
    }

    @Test
    fun `rejects duplicate unknown and truncated context tables`() {
        val empty = tableBody("kind14", emptyList(), emptyList())
        val cases = listOf(number(2) + empty + empty,
            table("future-context", emptyList(), emptyList()),
            table("kind14", emptyList(), emptyList()).dropLast(1).toByteArray())
        for (shape in cases) assertThrows(Exception::class.java) { decode(shape, byteArrayOf(0), order = byteArrayOf(0)) }
    }

    @Test
    fun `rejects missing duplicate columns and mismatched value counts`() {
        val missing = table("kind14", listOf(listOf(8L)), emptyList())
        val duplicate = table("kind14", listOf(listOf(8L, 16L)), listOf(column(8, 1, 0, 2), column(8, 1, 0, 2)))
        val wrongCount = table("kind14", listOf(listOf(8L)), listOf(column(8, 2, 2, 2)))
        for (shape in listOf(missing, duplicate, wrongCount)) {
            assertThrows(Exception::class.java) { decode(shape, byteArrayOf(0, 1, 1, 1, 1)) }
        }
    }

    @Test
    fun `rejects invalid tags wire types and numeric column modes`() {
        val bad = listOf(Triple(1L, 0, 2), Triple(11L, 0, 2), Triple(8L, 1, 2),
            Triple(0L, 2, 2), Triple(18L, 2, 2))
        for ((key, mode, size) in bad) {
            val shape = table("kind14", listOf(listOf(key)), listOf(column(key, 1, mode, size)))
            assertThrows(Exception::class.java) { decode(shape, byteArrayOf(0, 0, 2)) }
        }
    }

    @Test
    fun `rejects numeric references beyond shared pool and column suffixes`() {
        val numeric = table("kind14", listOf(listOf(8L)), listOf(column(8, 1, 2, 1)))
        assertThrows(Exception::class.java) { decode(numeric, byteArrayOf(0, 1)) }
        val literal = table("kind14", listOf(listOf(8L)), listOf(column(8, 1, 0, 3)))
        assertThrows(Exception::class.java) { decode(literal, byteArrayOf(0, 1, 1, 9)) }
        assertThrows(Exception::class.java) { decode(numeric, byteArrayOf(0)) }
    }

    @Test
    fun `rejects invalid order and nested references`() {
        val scalar = table("kind14", listOf(listOf(8L)), listOf(column(8, 1, 2, 2)))
        assertThrows(Exception::class.java) { decode(scalar, byteArrayOf(0, 0, 2), order = byteArrayOf(1, 14, 1)) }
        assertThrows(Exception::class.java) { decode(scalar, byteArrayOf(0, 0, 2), order = byteArrayOf(1)) }
        val song = tableBody("song", listOf(listOf(218L)), listOf(column(218, 1, 0, 2)))
        val child = tableBody("token", listOf(emptyList()), emptyList())
        assertThrows(Exception::class.java) { decode(number(2) + song + child, byteArrayOf(0, 1, 1), order = byteArrayOf(1, 15, 0)) }
        assertThrows(Exception::class.java) { decode(number(1) + song, byteArrayOf(0, 1, 0), order = byteArrayOf(1, 15, 0)) }
        val huge = part(SyncCompactWire.number(Int.MAX_VALUE + 1L))
        val tooLarge = table("song", listOf(listOf(218L)), listOf(column(218, 1, 0, huge.size)))
        assertThrows(Exception::class.java) { decode(tooLarge, byteArrayOf(0) + huge, order = byteArrayOf(1, 15, 0)) }
    }

    @Test
    fun `rejects wrong dictionary modes references and trailing bytes`() {
        val shape = table("song", listOf(listOf(82L)), listOf(column(82, 1, 0, 2)))
        for (reference in listOf(0, 1, 4)) {
            assertThrows(Exception::class.java) {
                decode(shape, byteArrayOf(0, 1, reference.toByte()), dictionary = byteArrayOf(1, 0, 1, 65), order = byteArrayOf(1, 15, 0))
            }
        }
        assertThrows(Exception::class.java) { decode(shape, byteArrayOf(0, 1, 2), dictionary = byteArrayOf(1, 2), order = byteArrayOf(1, 15, 0)) }
        assertThrows(Exception::class.java) { decode(shape, byteArrayOf(0, 1, 2), dictionary = byteArrayOf(0, 0), order = byteArrayOf(1, 15, 0)) }
    }

    @Test
    fun `rejects missing lyric hash and mismatched stored length`() {
        val shape = table("song", listOf(listOf(82L)), listOf(column(82, 1, 0, 2)))
        val hash = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(65))
        val dictionary = number(1) + byteArrayOf(1) + hash + number(2)
        assertThrows(Exception::class.java) { decode(shape, byteArrayOf(0, 1, 2), dictionary, byteArrayOf(1, 15, 0)) }
        val workspace = temporaryFolder.newFolder()
        val pool = SyncLyricPoolFiles(workspace) {}
        val files = pool.use { it.add(byteArrayOf(65)); it.encode() }
        val rawPool = ByteArrayOutputStream().also { output -> files.forEach { output.write(it.readBytes()) } }.toByteArray()
        assertThrows(Exception::class.java) { decode(shape, byteArrayOf(0, 1, 2), dictionary, byteArrayOf(1, 15, 0), rawPool) }
    }

    @Test
    fun `rejects row field and dictionary metadata budgets`() {
        val manyRows = number(2) + part("kind14".toByteArray()) + number(SyncCompactWire.MAX_FIELDS) +
            ByteArray(SyncCompactWire.MAX_FIELDS) + number(0) + part("kind15".toByteArray()) + number(SyncCompactWire.MAX_ROWS + 1)
        assertThrows(Exception::class.java) { decode(manyRows, byteArrayOf(0), order = byteArrayOf(0)) }
        val manyFields = number(1) + part("kind14".toByteArray()) + number(2) + number(SyncCompactWire.MAX_FIELDS) +
            ByteArray(SyncCompactWire.MAX_FIELDS) { 8 } + number(1)
        assertThrows(Exception::class.java) { decode(manyFields, byteArrayOf(0), order = byteArrayOf(0)) }
        val dictionary = ByteArrayOutputStream()
        val entries = SyncCompactWire.BLOCK_BYTES / 32 + 1
        dictionary.write(number(entries))
        repeat(entries) { dictionary.write(byteArrayOf(1) + ByteArray(32) + number(0)) }
        assertThrows(Exception::class.java) { decode(table("kind14", emptyList(), emptyList()), byteArrayOf(0), dictionary.toByteArray(), byteArrayOf(0)) }
    }

    @Test
    fun `rejects malformed compact block sections`() {
        val pool = emptyPool()
        for (body in listOf(byteArrayOf(), field(10, byteArrayOf(0)), field(10, byteArrayOf(0)) + field(10, byteArrayOf(0)))) {
            assertThrows(Exception::class.java) {
                withPool(pool) { index -> SyncCompactBlock.decode(body, byteArrayOf(0), index, ByteArrayOutputStream(), ByteArrayOutputStream()) {} }
            }
        }
    }

    private fun decode(shape: ByteArray, values: ByteArray, dictionary: ByteArray = byteArrayOf(0),
                       order: ByteArray = byteArrayOf(1, 14, 0), pool: ByteArray = emptyPool()): ByteArray {
        val result = ByteArrayOutputStream()
        val block = field(10, order) + field(18, shape) + field(26, values) + field(34, dictionary)
        withPool(pool) { index -> SyncCompactBlock.decode(block, byteArrayOf(0), index, result, ByteArrayOutputStream()) {} }
        return result.toByteArray()
    }

    private fun withPool(bytes: ByteArray, action: (SyncLyricPoolFiles.Index) -> Unit) {
        val workspace = temporaryFolder.newFolder()
        SyncLyricPoolFiles.decode(bytes.inputStream(), workspace, SyncCompactWire.MAX_RECORD_BYTES.toLong()) {}.use(action)
    }

    private fun table(context: String, rows: List<List<Long>>, columns: List<ByteArray>): ByteArray =
        number(1) + tableBody(context, rows, columns)

    private fun tableBody(context: String, rows: List<List<Long>>, columns: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            output.write(part(context.toByteArray()))
            output.write(number(rows.size))
            for (row in rows) { output.write(number(row.size)); row.forEach { output.write(SyncCompactWire.number(it)) } }
            output.write(number(columns.size))
            columns.forEach(output::write)
        }.toByteArray()

    private fun column(key: Long, count: Int, mode: Int, size: Int): ByteArray =
        SyncCompactWire.number(key) + number(count) + byteArrayOf(mode.toByte()) + number(size)

    private fun number(value: Int): ByteArray = SyncCompactWire.number(value.toLong())
    private fun part(bytes: ByteArray): ByteArray = number(bytes.size) + bytes
    private fun field(key: Long, bytes: ByteArray): ByteArray = SyncCompactWire.number(key) + part(bytes)
    private fun emptyPool(): ByteArray = "NPBODY01".toByteArray()
    private fun record(kind: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.writeByte(kind); it.writeInt(body.size); it.write(body) }
    }.toByteArray()
}
