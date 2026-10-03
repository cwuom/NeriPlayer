package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncRetainedLegacySourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyTheCompleteSourceAssociatedWithTheSuccessfulReadCanBeReused() {
        val directory = temporary.newFolder()
        val retained = SyncRetainedLegacySource(directory)
        val raw = byteArrayOf(1, 2, 3)
        val input = temporary.newFile().also { it.writeBytes(raw) }
        val cache = SyncArchiveCache(temporary.newFolder())
        val source = SyncLegacyLyricSource(cache.store(raw, false), 1, 3, 1)
        assertNull(retained.resolve(null))
        assertNull(retained.resolve(source))
        retained.retain(source, input)
        val file = requireNotNull(retained.resolve(source))
        assertArrayEquals(raw, file.readBytes())
        assertEquals(SyncArchiveCodec.digest(raw), retained.checksum)
        assertNull(retained.resolve(source.copy(recordCount = 2)))
        file.writeBytes(raw.copyOf(2))
        assertNull(retained.resolve(source))
        file.delete()
        assertNull(retained.resolve(source))
        retained.retain(source, input)
        retained.invalidate()
        assertNull(retained.resolve(source))
        assertNull(retained.checksum)
        retained.retain(source, input)
        retained.retain(null, null)
        assertFalse(file.exists())
        assertNull(retained.resolve(source))
    }

    @Test fun rejectedInputsAndFailedCommitsCannotLeaveAnApprovedSourceOrTemporaryFiles() {
        val directory = temporary.newFolder()
        val retained = SyncRetainedLegacySource(directory)
        val input = temporary.newFile().also { it.writeBytes(byteArrayOf(1)) }
        val source = SyncLegacyLyricSource(SyncArchiveCache(temporary.newFolder()).store(byteArrayOf(1), false), 1, 1, 1)
        assertThrows(IllegalArgumentException::class.java) { retained.retain(source, null) }
        assertThrows(IllegalArgumentException::class.java) { retained.retain(source.copy(rawDataBytes = 2), input) }
        val destination = File(directory, "legacy-source.raw")
        assertTrue(destination.mkdir())
        File(destination, "occupied").writeBytes(byteArrayOf(1))
        assertThrows(IllegalStateException::class.java) { retained.retain(source, input) }
        assertNull(retained.checksum)
        assertNull(retained.resolve(source))
        assertEquals(listOf("legacy-source.raw"), directory.listFiles().orEmpty().map { it.name })
    }
}
