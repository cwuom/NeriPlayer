package moe.ouom.neriplayer.core.download.storage.backend

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.download.storage.StorageLookupResult
import moe.ouom.neriplayer.data.model.download.storage.StorageReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageBackendBoundedReadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val backend by lazy { FileStorageBackend(temporaryFolder.root) }

    @Test
    fun `single byte reads stop exactly at the limit and reject extra bytes`() = runTest {
        file("exact.bin", "abc")
        file("long.bin", "abcd")

        val exact = backend.readBounded(StorageReference.FileRef("exact.bin"), maxBytes = 3L) { input ->
            generateSequence { input.read().takeIf { it >= 0 } }.map(Int::toChar).joinToString("")
        }
        val overflow = backend.readBounded(StorageReference.FileRef("long.bin"), maxBytes = 3L) { input ->
            repeat(4) { input.read() }
        }

        assertEquals(StorageLookupResult.Found("abc"), exact)
        val error = (overflow as StorageLookupResult.ProviderFailure).error as StorageReadLimitExceededException
        assertEquals(4L, error.actualBytes)
        assertEquals(3L, error.maxBytes)
    }

    @Test
    fun `buffered reads are clipped to the remaining budget`() = runTest {
        file("data.bin", "abcdef")
        file("short.bin", "ab")

        val counts = backend.readBounded(StorageReference.FileRef("data.bin"), maxBytes = 6L) { input ->
            val buffer = ByteArray(4)
            listOf(
                input.read(buffer, 0, 0),
                input.read(buffer, 0, 4),
                input.read(buffer, 1, 3),
                input.read(buffer, 0, 4)
            )
        }
        val shortCounts = backend.readBounded(StorageReference.FileRef("short.bin"), maxBytes = 10L) { input ->
            val buffer = ByteArray(4)
            listOf(input.read(buffer), input.read(buffer))
        }
        val overflow = backend.readBounded(StorageReference.FileRef("data.bin"), maxBytes = 3L) { input ->
            val buffer = ByteArray(8)
            listOf(input.read(buffer), input.read(buffer))
        }

        assertEquals(StorageLookupResult.Found(listOf(0, 4, 2, -1)), counts)
        assertEquals(StorageLookupResult.Found(listOf(2, -1)), shortCounts)
        assertEquals(
            4L,
            ((overflow as StorageLookupResult.ProviderFailure).error as StorageReadLimitExceededException).actualBytes
        )
    }

    @Test
    fun `buffered reads reject ranges outside the buffer`() = runTest {
        file("data.bin", "abc")

        for ((offset, length) in listOf(-1 to 1, 0 to -1, 3 to 2)) {
            val result = backend.readBounded(StorageReference.FileRef("data.bin"), maxBytes = 3L) { input ->
                input.read(ByteArray(4), offset, length)
            }
            val error = (result as StorageLookupResult.ProviderFailure).error
            assertEquals("offset=$offset length=$length", "invalid read range", (error as IllegalArgumentException).message)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                backend.readBounded(StorageReference.FileRef("data.bin"), maxBytes = -1L) { it.read() }
            }
        }
    }

    @Test
    fun `trusted references compare storage reference and external identity`() {
        val reference = StorageReference.FileRef("Song.mp3")
        val trusted = TrustedManagedRef(reference)

        assertEquals("Song.mp3", trusted.externalReference)
        assertEquals(TrustedManagedRef(StorageReference.FileRef("Song.mp3")), trusted)
        assertEquals(TrustedManagedRef(StorageReference.FileRef("Song.mp3")).hashCode(), trusted.hashCode())
        assertNotEquals(TrustedManagedRef(reference, externalReference = "/music/Song.mp3"), trusted)
        assertNotEquals(TrustedManagedRef(StorageReference.FileRef("Other.mp3"), externalReference = "Song.mp3"), trusted)
        assertFalse(trusted.equals(reference))
        assertEquals("TrustedManagedRef(reference=$reference, externalReference=Song.mp3)", trusted.toString())
    }

    private fun file(name: String, content: String) {
        File(temporaryFolder.root, name).writeText(content)
    }
}
