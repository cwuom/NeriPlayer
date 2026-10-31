package moe.ouom.neriplayer.lanltw.media

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.lan.LanLocalMedia
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanFileSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun unknownLengthCopyHasCorrectHashAndClosesInput() = runBlocking {
        var closed = false
        val directory = temporary.newFolder()
        val result = LanFileSnapshot.create(directory) {
            object : ByteArrayInputStream("abc".toByteArray()) {
                override fun close() { closed = true; super.close() }
            }
        }
        assertTrue(closed)
        assertEquals(3L, result.byteLength)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
        assertEquals("abc", result.file.readText())
        assertEquals(listOf(result.file), directory.listFiles()!!.toList())
    }
    @Test fun emptyBrokenAndCancelledSourcesLeaveNoFiles() = runBlocking {
        for (failure in listOf<Throwable?>(null, java.io.IOException("read failed"), CancellationException("cancelled"))) {
            val directory = temporary.newFolder()
            try {
                LanFileSnapshot.create(directory) {
                    object : InputStream() {
                        override fun read(): Int { if (failure != null) throw failure; return -1 }
                    }
                }
                fail("Source must fail")
            } catch (actual: Exception) {
                if (failure != null) { assertEquals(failure.javaClass, actual.javaClass); assertEquals(failure.message, actual.message) }
                else assertEquals("AUDIO_FORMAT", actual.message)
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }
    @Test fun oversizedUnknownLengthStreamStopsAtBudget() = runBlocking {
        val directory = temporary.newFolder()
        var emitted = 0L
        try {
            LanFileSnapshot.create(directory) {
                object : InputStream() {
                    override fun read(): Int = 0
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int { emitted += length; return length }
                }
            }
            fail("Over budget")
        } catch (actual: IllegalStateException) { assertEquals("MEMORY_LIMIT", actual.message) }
        assertTrue(emitted <= LanLocalMedia.MAX_BYTES + 32768)
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun acceptsExactlyTwoHundredMiB() = runBlocking {
        val directory = temporary.newFolder()
        var remaining = LanLocalMedia.MAX_BYTES
        val snapshot = LanFileSnapshot.create(directory) {
            object : InputStream() {
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (remaining == 0L) return -1
                    val count = minOf(remaining, length.toLong()).toInt()
                    remaining -= count
                    return count
                }
            }
        }
        assertEquals(200L * 1024 * 1024, snapshot.file.length())
        assertEquals(snapshot.file.length(), snapshot.byteLength)
    }
    @Test fun originalFileChangesDoNotChangeSnapshot() = runBlocking {
        val source = temporary.newFile().apply { writeText("before") }
        val snapshot = LanFileSnapshot.create(temporary.newFolder(), source::inputStream)
        source.writeText("after")
        assertEquals("before", snapshot.file.readText())
    }
}
