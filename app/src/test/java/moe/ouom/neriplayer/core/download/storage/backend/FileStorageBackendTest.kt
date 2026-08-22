package moe.ouom.neriplayer.core.download.storage.backend

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStorageBackendTest {
    @Test
    fun `file backend writes atomically and reports structured state`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
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
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `file backend rejects path escape`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val backend = FileStorageBackend(root)

                assertEquals(
                    StorageLookupResult.PermissionLost,
                    backend.stat(StorageReference.FileRef("../outside"))
                )
                assertEquals(
                    StorageConfidence.PermissionLost,
                    backend.list(StorageReference.FileRef("../outside")).confidence
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `file backend maps missing and non-file reads without provider failure`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val backend = FileStorageBackend(root)
                root.resolve("directory").mkdirs()

                assertEquals(
                    StorageLookupResult.Missing,
                    backend.stat(StorageReference.FileRef("missing.mp3"))
                )
                assertEquals(
                    StorageConfidence.Missing,
                    backend.list(StorageReference.FileRef("missing-directory")).confidence
                )
                assertEquals(
                    StorageLookupResult.Missing,
                    backend.read(StorageReference.FileRef("directory")) { it.readBytes() }
                )
                assertFalse(
                    backend.list(StorageReference.FileRef("directory")).entries.any {
                        it.displayName == "missing.mp3"
                    }
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `file backend maps writer failure to provider failure and leaves final absent`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val backend = FileStorageBackend(root)
                val result = backend.writeRecoverable(StorageTarget.FileTarget("song.mp3")) {
                    throw IllegalStateException("writer failed")
                }

                assertTrue(result is StorageWriteResult.ProviderFailure)
                assertEquals(
                    StorageLookupResult.Missing,
                    backend.stat(StorageReference.FileRef("song.mp3"))
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
