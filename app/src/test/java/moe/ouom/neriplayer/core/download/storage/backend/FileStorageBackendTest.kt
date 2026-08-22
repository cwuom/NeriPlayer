package moe.ouom.neriplayer.core.download.storage.backend

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStorageBackendTest {
    @Test
    fun `file backend writes atomically and reports structured state`() {
        runBlocking {
        val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
        val backend = FileStorageBackend(root)

        val write = backend.writeRecoverable(StorageTarget.FileTarget("nested/song.mp3")) { output ->
            output.write("audio".toByteArray())
        }
        val stat = backend.stat(StorageReference.FileRef("nested/song.mp3"))
        val content = backend.read(StorageReference.FileRef("nested/song.mp3")) { input ->
            input.readBytes().decodeToString()
        }

        assertTrue(write is StorageWriteResult.Written)
        assertTrue(stat is StorageLookupResult.Found)
        assertEquals("audio", (content as StorageLookupResult.Found).value)
        assertEquals(
            StorageMutationResult.Deleted,
            backend.delete(StorageReference.FileRef("nested/song.mp3"))
        )
        assertEquals(
            StorageMutationResult.Missing,
            backend.delete(StorageReference.FileRef("nested/song.mp3"))
        )
        root.deleteRecursively()
        }
    }

    @Test
    fun `file backend rejects path escape`() {
        runBlocking {
        val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
        val backend = FileStorageBackend(root)

        assertEquals(
            StorageLookupResult.PermissionLost,
            backend.stat(StorageReference.FileRef("../outside"))
        )
        root.deleteRecursively()
        }
    }
}
