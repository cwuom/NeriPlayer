package moe.ouom.neriplayer.core.download.storage.backend

import android.net.Uri
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

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
                    backend.delete(
                        TrustedManagedRef(StorageReference.FileRef("nested/song.mp3"))
                    )
                )
                assertEquals(
                    StorageMutationResult.Missing,
                    backend.delete(
                        TrustedManagedRef(StorageReference.FileRef("nested/song.mp3"))
                    )
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
                    StorageLookupResult.OutOfScope,
                    backend.stat(StorageReference.FileRef("../outside"))
                )
                assertEquals(
                    StorageConfidence.OutOfScope,
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
                assertFalse(
                    root.listFiles().orEmpty().any { file ->
                        file.name.startsWith(".npdl_tmp_") &&
                            file.name.endsWith(".pending")
                    }
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `file backend surfaces unconfirmed temporary cleanup after writer failure`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val result = FileStorageBackend(root).writeRecoverable(
                    StorageTarget.FileTarget("song.mp3")
                ) { output ->
                    output.write("audio".toByteArray())
                    output.close()
                    val temporary = root.listFiles().orEmpty().single { file ->
                        file.name.startsWith(".npdl_tmp_") &&
                            file.name.endsWith(".pending")
                    }
                    assertTrue(temporary.delete())
                    assertTrue(temporary.mkdirs())
                    temporary.resolve("held").writeText("keep")
                    throw IllegalStateException("writer failed")
                }

                assertTrue(result is StorageWriteResult.ProviderFailure)
                val error = (result as StorageWriteResult.ProviderFailure).error
                assertTrue(error.message?.contains("临时写入文件未能确认删除") == true)
                assertTrue(error.cause?.message?.contains("不是普通文件") == true)
                assertTrue(error.suppressed.any { it.message == "writer failed" })
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `file backends serialize the same target across instances and clean temporary files`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val firstBackend = FileStorageBackend(root)
                val secondBackend = FileStorageBackend(root.canonicalFile)
                val firstWriterEntered = CompletableDeferred<Unit>()
                val releaseFirstWriter = CompletableDeferred<Unit>()
                val secondWriterStarted = CompletableDeferred<Unit>()
                val secondWriterEntered = CompletableDeferred<Unit>()

                val first = async(Dispatchers.Default) {
                    firstBackend.writeRecoverable(StorageTarget.FileTarget("song.mp3")) { output ->
                        output.write("first".toByteArray())
                        firstWriterEntered.complete(Unit)
                        releaseFirstWriter.await()
                    }
                }
                withTimeout(5_000L) { firstWriterEntered.await() }

                val second = async(Dispatchers.Default) {
                    secondWriterStarted.complete(Unit)
                    secondBackend.writeRecoverable(StorageTarget.FileTarget("song.mp3")) { output ->
                        secondWriterEntered.complete(Unit)
                        output.write("second".toByteArray())
                    }
                }
                withTimeout(5_000L) { secondWriterStarted.await() }
                delay(100L)
                assertFalse(secondWriterEntered.isCompleted)

                releaseFirstWriter.complete(Unit)
                val firstResult = withTimeout(5_000L) { first.await() }
                val secondResult = withTimeout(5_000L) { second.await() }
                assertTrue(firstResult is StorageWriteResult.Written)
                assertTrue(secondResult is StorageWriteResult.Written)
                assertTrue(secondWriterEntered.isCompleted)

                val content = secondBackend.read(StorageReference.FileRef("song.mp3")) { input ->
                    input.readBytes().decodeToString()
                }
                assertEquals("second", (content as StorageLookupResult.Found).value)
                assertFalse(
                    root.listFiles().orEmpty().any { file ->
                        file.name.startsWith(".npdl_tmp_") &&
                            file.name.endsWith(".pending")
                    }
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `file delete waits for an in-flight write of the same target`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val backend = FileStorageBackend(root)
                val writerEntered = CompletableDeferred<Unit>()
                val releaseWriter = CompletableDeferred<Unit>()
                val writer = async(Dispatchers.Default) {
                    backend.writeRecoverable(StorageTarget.FileTarget("song.mp3")) { output ->
                        output.write("audio".toByteArray())
                        writerEntered.complete(Unit)
                        releaseWriter.await()
                    }
                }
                withTimeout(5_000L) { writerEntered.await() }

                val deleteStarted = CompletableDeferred<Unit>()
                val delete = async(Dispatchers.Default) {
                    deleteStarted.complete(Unit)
                    backend.delete(
                        TrustedManagedRef(StorageReference.FileRef("song.mp3"))
                    )
                }
                withTimeout(5_000L) { deleteStarted.await() }
                delay(100L)
                assertFalse(delete.isCompleted)

                releaseWriter.complete(Unit)
                assertTrue(withTimeout(5_000L) { writer.await() } is StorageWriteResult.Written)
                assertEquals(
                    StorageMutationResult.Deleted,
                    withTimeout(5_000L) { delete.await() }
                )
                assertFalse(root.resolve("song.mp3").exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `file rename waits for an in-flight write of the source target`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val backend = FileStorageBackend(root)
                val writerEntered = CompletableDeferred<Unit>()
                val releaseWriter = CompletableDeferred<Unit>()
                val writer = async(Dispatchers.Default) {
                    backend.writeRecoverable(StorageTarget.FileTarget("song.mp3")) { output ->
                        output.write("audio".toByteArray())
                        writerEntered.complete(Unit)
                        releaseWriter.await()
                    }
                }
                withTimeout(5_000L) { writerEntered.await() }

                val renameStarted = CompletableDeferred<Unit>()
                val rename = async(Dispatchers.Default) {
                    renameStarted.complete(Unit)
                    backend.rename(
                        TrustedManagedRef(StorageReference.FileRef("song.mp3")),
                        "renamed.mp3"
                    )
                }
                withTimeout(5_000L) { renameStarted.await() }
                delay(100L)
                assertFalse(rename.isCompleted)

                releaseWriter.complete(Unit)
                assertTrue(withTimeout(5_000L) { writer.await() } is StorageWriteResult.Written)
                assertTrue(
                    withTimeout(5_000L) { rename.await() } is StorageRenameResult.Renamed
                )
                assertTrue(root.resolve("renamed.mp3").isFile)
                assertFalse(root.resolve("song.mp3").exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `many concurrent file targets complete without pending file buildup`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val targetCount = 96
                val results = (0 until targetCount).map { index ->
                    async(Dispatchers.Default) {
                        FileStorageBackend(root).writeRecoverable(
                            StorageTarget.FileTarget("batch/song-$index.mp3")
                        ) { output ->
                            output.write("audio-$index".toByteArray())
                        }
                    }
                }.awaitAll()

                assertTrue(results.all { result -> result is StorageWriteResult.Written })
                assertEquals(targetCount, root.resolve("batch").listFiles().orEmpty().size)
                assertFalse(
                    root.walkTopDown().any { file ->
                        file.isFile && file.name.endsWith(".pending")
                    }
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `file backend writes a long target through a short temporary name`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val targetName = "x".repeat(226) + ".mp3"
                val result = FileStorageBackend(root).writeRecoverable(
                    StorageTarget.FileTarget(targetName)
                ) { output ->
                    output.write("audio".toByteArray())
                }

                assertTrue(result is StorageWriteResult.Written)
                assertTrue(root.resolve(targetName).isFile)
                assertFalse(
                    root.listFiles().orEmpty().any { file ->
                        file.name.startsWith(".npdl_tmp_") && file.name.endsWith(".pending")
                    }
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `file backend does not advertise capabilities for SAF references`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-storage-backend").toFile()
            try {
                val capabilities = FileStorageBackend(root).capabilities(
                    StorageReference.SafRef(mock(Uri::class.java))
                )

                assertFalse(capabilities.canRead)
                assertFalse(capabilities.canWrite)
                assertFalse(capabilities.canDelete)
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
