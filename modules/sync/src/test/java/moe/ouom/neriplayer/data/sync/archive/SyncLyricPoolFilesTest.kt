package moe.ouom.neriplayer.data.sync.archive

import moe.ouom.neriplayer.data.sync.archive.compact.SyncCompactWire
import moe.ouom.neriplayer.data.sync.archive.compact.SyncLyricPoolFiles
import org.junit.Assert.assertThrows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException

class SyncLyricPoolFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `duplicate body occurrences reject damaged backing payload`() {
        val workspace = temporaryFolder.newFolder()
        SyncLyricPoolFiles(workspace) {}.use { pool ->
            pool.add(byteArrayOf(1, 2))
            val duplicate = pool.add(byteArrayOf(1, 2))
            RandomAccessFile(File(workspace, "lyric-bodies"), "rw").use { it.seek(duplicate.offset); it.writeByte(9) }
            assertThrows(IllegalArgumentException::class.java) { pool.encode() }
        }
    }

    @Test
    fun `duplicate body occurrences reject damaged backing length`() {
        val workspace = temporaryFolder.newFolder()
        SyncLyricPoolFiles(workspace) {}.use { pool ->
            pool.add(byteArrayOf(1, 2))
            pool.add(byteArrayOf(1, 2))
            // 填满输出缓冲区后修改已落盘的第二条索引，模拟暂存文件损坏
            repeat(256) { pool.add("filler-$it".toByteArray()) }
            RandomAccessFile(File(workspace, "lyric-occurrences"), "rw").use { it.seek(84); it.writeInt(0) }
            assertThrows(IllegalArgumentException::class.java) { pool.encode() }
        }
    }

    @Test
    fun `decoded pool enforces the aggregate original byte budget`() {
        val workspace = temporaryFolder.newFolder()
        val parts = SyncLyricPoolFiles(workspace) {}.use { pool -> pool.add(byteArrayOf(65)); pool.encode() }
        val bytes = ByteArrayOutputStream().also { output -> parts.forEach { output.write(it.readBytes()) } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            SyncLyricPoolFiles.decode(bytes.inputStream(), temporaryFolder.newFolder(), 0) {}.close()
        }
    }

    @Test
    fun `pool refuses oversized entries before persisting payload`() {
        SyncLyricPoolFiles(temporaryFolder.newFolder()) {}.use { pool ->
            assertThrows(IllegalArgumentException::class.java) { pool.add(ByteArray(SyncCompactWire.BLOCK_BYTES + 1)) }
        }
    }

    @Test
    fun `constructor IO failure leaves its backing file reusable`() {
        val workspace = temporaryFolder.newFolder()
        val blocker = File(workspace, "lyric-occurrences").apply { mkdir() }
        assertThrows(FileNotFoundException::class.java) { SyncLyricPoolFiles(workspace) {} }
        assertEquals(true, blocker.delete())
        SyncLyricPoolFiles(workspace) {}.use { pool -> pool.add(byteArrayOf(65)); pool.encode() }
    }

    @Test
    fun `merge closes earlier inputs when a later run cannot open`() {
        val workspace = temporaryFolder.newFolder()
        val first = File(workspace, "first.run").apply { writeBytes(ByteArray(44)) }
        val missing = File(workspace, "missing.run")
        val target = File(workspace, "merged.run")
        SyncLyricPoolFiles(workspace) {}.use { pool ->
            val method = pool.javaClass.getDeclaredMethod("merge", List::class.java, File::class.java).apply { isAccessible = true }
            assertThrows(FileNotFoundException::class.java) { invoke { method.invoke(pool, listOf(first, missing), target) } }
            missing.createNewFile()
            invoke { method.invoke(pool, listOf(first, missing), target) }
            assertArrayEquals(first.readBytes(), target.readBytes())
        }
    }

    @Test
    fun `cleanup continues after close failures and preserves the first error`() {
        val first = IOException("first close")
        val second = IOException("second close")
        val closed = ArrayList<Int>()
        val resources = listOf(Closeable { closed += 1; throw first }, Closeable { closed += 2; throw second }, Closeable { closed += 3 })
        val failure = assertThrows(IOException::class.java) { closeAll(resources, null) }
        assertSame(first, failure)
        assertEquals(listOf(1, 2, 3), closed)
        assertEquals(listOf(second), failure.suppressed.toList())
    }

    @Test
    fun `cleanup adds suppressed errors while preserving cancellation`() {
        val primary = CancellationException("primary")
        val secondary = IOException("close")
        var closed = 0
        closeAll(listOf(Closeable { closed++; throw primary }, Closeable { closed++; throw secondary }, Closeable { closed++ }), primary)
        assertEquals(3, closed)
        assertEquals(listOf(secondary), primary.suppressed.toList())
    }

    private fun closeAll(resources: List<Closeable>, primary: Throwable?) {
        val companion = SyncLyricPoolFiles.Companion
        val method = companion.javaClass.getDeclaredMethod("closeAll", List::class.java, Throwable::class.java).apply { isAccessible = true }
        invoke { method.invoke(companion, resources, primary) }
    }

    private fun invoke(action: () -> Any?) {
        try { action() } catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    }
}
