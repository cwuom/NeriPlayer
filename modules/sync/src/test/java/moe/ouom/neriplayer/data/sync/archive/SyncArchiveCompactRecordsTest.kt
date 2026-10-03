package moe.ouom.neriplayer.data.sync.archive

import moe.ouom.neriplayer.data.sync.archive.compact.SyncArchiveCompactRecords
import moe.ouom.neriplayer.data.sync.archive.compact.SyncCompactWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections
import java.util.concurrent.CancellationException

class SyncArchiveCompactRecordsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `restores exact fields and separate legacy versions in original order`() {
        val song = byteArrayOf(
            8, 7, 18, 1, 65, 82, 13, 91, 48, 48, 58, 48, 49, 46, 48, 48, 48, 93, 120, 10,
            90, 0, -8, 1, 0, -94, 2, 0, -86, 2, 3, -1, 0, 65
        )
        val older = byteArrayOf(8, 7, 82, 3, 111, 108, 100, -8, 1, 1)
        val main = record(15, song) + record(14, byteArrayOf(8, -128, 0, 18, 2, -1, 0))
        val legacy = record(15, older) + record(15, song)
        val workspace = temporaryFolder.newFolder()
        SyncArchiveCompactRecords.pack(main.inputStream(), legacy.inputStream(), workspace) {}.use { packed ->
            val restoredMain = ByteArrayOutputStream()
            val restoredLegacy = ByteArrayOutputStream()
            streams(packed.mainParts).use { mainInput ->
                streams(packed.legacyParts).use { legacyInput ->
                    streams(packed.poolParts).use { poolInput ->
                        SyncArchiveCompactRecords.unpack(
                            mainInput, legacyInput, poolInput, restoredMain, restoredLegacy,
                            main.size.toLong(), legacy.size.toLong()
                        ) {}
                    }
                }
            }
            assertArrayEquals(main, restoredMain.toByteArray())
            assertArrayEquals(legacy, restoredLegacy.toByteArray())
        }
        assertEquals(emptyList<File>(), workspace.listFiles()?.toList())
    }

    @Test
    fun `cancellation releases only the owned staging directory`() {
        val workspace = temporaryFolder.newFolder()
        val unrelated = File(workspace, "existing.bin").apply { writeBytes(byteArrayOf(7)) }
        val source = record(15, byteArrayOf(8, 1, 82, 1, 65))
        assertThrows(CancellationException::class.java) {
            SyncArchiveCompactRecords.pack(source.inputStream(), ByteArrayInputStream(byteArrayOf()), workspace) {
                throw CancellationException("test cancellation")
            }
        }
        assertArrayEquals(byteArrayOf(7), unrelated.readBytes())
        assertEquals(listOf(unrelated), workspace.listFiles()?.toList())
    }

    @Test
    fun `rejects truncated original record before returning a prepared result`() {
        val workspace = temporaryFolder.newFolder()
        val truncated = byteArrayOf(15, 0, 0, 0, 5, 8, 1)
        assertThrows(Exception::class.java) {
            SyncArchiveCompactRecords.pack(truncated.inputStream(), ByteArrayInputStream(byteArrayOf()), workspace) {}
        }
        assertEquals(emptyList<File>(), workspace.listFiles()?.toList())
    }

    @Test
    fun `preserves null empty unknown nested bytes and signed numeric extremes`() {
        val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1, 0, 1, Long.MIN_VALUE)
        val numeric = values.map { value -> record(8, field(8, SyncCompactWire.number(value)) + field(16, SyncCompactWire.number(value))) }
        val token = field(8, SyncCompactWire.number(-1)) + field(26, byteArrayOf(-1, 0, -128))
        val song = field(8, byteArrayOf(1)) + field(218, token) + field(74, byteArrayOf())
        val nullLyric = record(15, song)
        val emptyLyric = record(15, song + field(82, byteArrayOf()))
        val overlongPrefix = record(14, byteArrayOf(-120, 0, 1, 18, -128, 0))
        roundTrip(numeric.reduce(ByteArray::plus) + nullLyric + emptyLyric + overlongPrefix, emptyLyric + nullLyric)
    }

    @Test
    fun `streams records larger than compact block including large lyric fields`() {
        val lyric = ByteArray(SyncCompactWire.BLOCK_BYTES + 17) { (it % 251).toByte() }
        val largeSong = record(15, field(82, lyric))
        val before = record(14, field(8, byteArrayOf(1)))
        val after = record(14, field(8, byteArrayOf(2)))
        roundTrip(before + largeSong + after, largeSong)
    }

    @Test
    fun `keeps group wire fields in literal records`() {
        roundTrip(record(14, byteArrayOf(11, 16, 1, 12)), record(15, byteArrayOf(11, 12)))
    }

    @Test
    fun `empty datasets retain valid typed streams`() {
        roundTrip(byteArrayOf(), byteArrayOf())
    }

    @Test
    fun `rejects future version truncation trailing data and wrong output budget`() {
        val source = record(15, field(8, byteArrayOf(1)) + field(82, "[01:02.003]a".toByteArray()))
        SyncArchiveCompactRecords.pack(source.inputStream(), byteArrayOf().inputStream(), temporaryFolder.newFolder()) {}.use { packed ->
            val main = joined(packed.mainParts)
            val legacy = joined(packed.legacyParts)
            val pool = joined(packed.poolParts)
            val futureMain = main.clone().apply { this[7] = '9'.code.toByte() }
            for ((a, b, c) in listOf(Triple(futureMain, legacy, pool), Triple(main.dropLast(1).toByteArray(), legacy, pool),
                Triple(main, legacy.dropLast(1).toByteArray(), pool), Triple(main, legacy, pool.dropLast(1).toByteArray()),
                Triple(main, legacy + byteArrayOf(0), pool), Triple(main, legacy, pool + byteArrayOf(0)))) {
                assertThrows(Exception::class.java) { unpack(a, b, c, source.size.toLong(), 0) }
            }
            assertThrows(IllegalArgumentException::class.java) { unpack(main, legacy, pool, 0, 0) }
            assertThrows(IllegalArgumentException::class.java) { unpack(main, legacy, pool, source.size + 1L, 0) }
            assertThrows(IllegalArgumentException::class.java) { unpack(main, legacy, pool, -1, 0) }
            assertThrows(IllegalArgumentException::class.java) { unpack(main, legacy, pool, 0, -1) }
            assertThrows(IllegalArgumentException::class.java) { unpack(main, legacy, pool, Long.MAX_VALUE, 1) }
            assertThrows(IllegalArgumentException::class.java) { unpack(main, legacy, pool, source.size.toLong(), 1) }
        }
    }

    @Test
    fun `rejects duplicate lyric bodies even with equal payload`() {
        val source = record(15, field(82, "duplicate".toByteArray()))
        SyncArchiveCompactRecords.pack(source.inputStream(), byteArrayOf().inputStream(), temporaryFolder.newFolder()) {}.use { packed ->
            val pool = joined(packed.poolParts)
            assertThrows(IllegalArgumentException::class.java) {
                unpack(joined(packed.mainParts), joined(packed.legacyParts), pool + pool.copyOfRange(8, pool.size), source.size.toLong(), 0)
            }
        }
    }

    @Test
    fun `midstream cancellation closes owned files and keeps other workspace files`() {
        val workspace = temporaryFolder.newFolder()
        val existing = File(workspace, "other.bin").apply { writeBytes(byteArrayOf(5)) }
        val source = (0..20).map { record(15, field(82, "[00:01.001]$it".toByteArray())) }.reduce(ByteArray::plus)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            SyncArchiveCompactRecords.pack(source.inputStream(), source.inputStream(), workspace) {
                if (++checks == 12) throw CancellationException("during record parsing")
            }
        }
        assertEquals(listOf(existing), workspace.listFiles()?.toList())
        assertArrayEquals(byteArrayOf(5), existing.readBytes())
    }

    @Test
    fun `rejects original record lengths exceeding existing archive limit`() {
        val header = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeByte(15); writeInt(SyncCompactWire.MAX_RECORD_BYTES + 1) } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            SyncArchiveCompactRecords.pack(header.inputStream(), byteArrayOf().inputStream(), temporaryFolder.newFolder()) {}
        }
    }

    @Test
    fun `literal streaming rejects early EOF and tolerates intermediate zero reads`() {
        val workspace = temporaryFolder.newFolder()
        val header = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeByte(15); writeInt(SyncCompactWire.BLOCK_BYTES + 1) } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            SyncArchiveCompactRecords.pack((header + byteArrayOf(8, 1)).inputStream(), byteArrayOf().inputStream(), workspace) {}
        }
        val body = ByteArray(SyncCompactWire.BLOCK_BYTES + 1) { 65 }
        val raw = record(15, body)
        val source = raw.inputStream()
        val zeroRead = object : InputStream() {
            var zero = true
            override fun read(): Int = source.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (zero) { zero = false; return 0 }
                return source.read(bytes, offset, length)
            }
        }
        SyncArchiveCompactRecords.pack(zeroRead, byteArrayOf().inputStream(), workspace) {}.use { packed ->
            assertArrayEquals(raw, unpack(joined(packed.mainParts), joined(packed.legacyParts), joined(packed.poolParts), raw.size.toLong(), 0).first)
        }
        assertEquals(emptyList<File>(), workspace.listFiles()?.toList())
    }

    @Test
    fun `external pool sorting deduplicates across runs and restores grouped lookups`() {
        val source = ByteArrayOutputStream()
        repeat(17000) { index -> source.write(record(15, field(82, "body-$index".toByteArray()))) }
        val first = record(15, field(82, "body-0".toByteArray()))
        val last = record(15, field(82, "body-16999".toByteArray()))
        roundTrip(source.toByteArray(), last + first)
    }

    @Test
    fun `many tiny records flush independent local blocks without changing order`() {
        val source = ByteArrayOutputStream()
        repeat(SyncCompactWire.MAX_ROWS + 3) { source.write(record(14, field(8, byteArrayOf(1)))) }
        val raw = source.toByteArray()
        SyncArchiveCompactRecords.pack(raw.inputStream(), byteArrayOf().inputStream(), temporaryFolder.newFolder()) {}.use { packed ->
            assertEquals(2, packed.mainParts.size)
            assertEquals(2, packed.legacyParts.size)
            assertArrayEquals(raw, unpack(joined(packed.mainParts), joined(packed.legacyParts), joined(packed.poolParts), raw.size.toLong(), 0).first)
        }
    }

    @Test
    fun `nested rows and root rows both fit their independent local budgets`() {
        val shards = (0..7).map { field(130, byteArrayOf()) }.reduce(ByteArray::plus)
        val source = ByteArrayOutputStream()
        repeat(SyncCompactWire.MAX_ROWS) { source.write(record(8, shards)) }
        roundTrip(source.toByteArray(), byteArrayOf())
    }

    @Test
    fun `reader cancellation releases only its child of the supplied staging parent`() {
        val workspace = temporaryFolder.newFolder()
        val existing = File(workspace, "unrelated.bin").apply { writeBytes(byteArrayOf(6)) }
        val source = record(15, field(82, "[00:01.001]text".toByteArray()))
        SyncArchiveCompactRecords.pack(source.inputStream(), byteArrayOf().inputStream(), workspace) {}.use { packed ->
            val before = workspace.listFiles()?.toSet()
            var checks = 0
            assertThrows(CancellationException::class.java) {
                SyncArchiveCompactRecords.unpack(joined(packed.mainParts).inputStream(), joined(packed.legacyParts).inputStream(),
                    joined(packed.poolParts).inputStream(), ByteArrayOutputStream(), ByteArrayOutputStream(), source.size.toLong(), 0, workspace) {
                    if (++checks == 3) throw CancellationException("during pool restoration")
                }
            }
            assertEquals(before, workspace.listFiles()?.toSet())
        }
        assertEquals(listOf(existing), workspace.listFiles()?.toList())
    }

    private fun roundTrip(main: ByteArray, legacy: ByteArray) {
        val workspace = temporaryFolder.newFolder()
        SyncArchiveCompactRecords.pack(main.inputStream(), legacy.inputStream(), workspace) {}.use { packed ->
            val restored = unpack(joined(packed.mainParts), joined(packed.legacyParts), joined(packed.poolParts), main.size.toLong(), legacy.size.toLong())
            assertArrayEquals(main, restored.first)
            assertArrayEquals(legacy, restored.second)
        }
        assertEquals(emptyList<File>(), workspace.listFiles()?.toList())
    }

    private fun unpack(main: ByteArray, legacy: ByteArray, pool: ByteArray, mainBytes: Long, legacyBytes: Long): Pair<ByteArray, ByteArray> {
        val a = ByteArrayOutputStream()
        val b = ByteArrayOutputStream()
        val workspace = temporaryFolder.newFolder()
        try {
            SyncArchiveCompactRecords.unpack(main.inputStream(), legacy.inputStream(), pool.inputStream(), a, b, mainBytes, legacyBytes, workspace) {}
        } finally {
            assertEquals(emptyList<File>(), workspace.listFiles()?.toList())
        }
        return a.toByteArray() to b.toByteArray()
    }

    private fun joined(parts: List<File>): ByteArray = streams(parts).use(InputStream::readBytes)

    private fun field(key: Long, body: ByteArray): ByteArray = SyncCompactWire.number(key) +
        (if (key and 7 == 2L) SyncCompactWire.number(body.size.toLong()) else byteArrayOf()) + body

    private fun record(kind: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.writeByte(kind); it.writeInt(body.size); it.write(body) }
    }.toByteArray()

    private fun streams(parts: List<File>): InputStream = SequenceInputStream(
        Collections.enumeration(parts.map(File::inputStream))
    )
}
