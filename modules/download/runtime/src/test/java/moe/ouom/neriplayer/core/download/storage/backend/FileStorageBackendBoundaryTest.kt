package moe.ouom.neriplayer.core.download.storage.backend

import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.download.storage.StorageConfidence
import moe.ouom.neriplayer.data.model.download.storage.StorageLookupResult
import moe.ouom.neriplayer.data.model.download.storage.StorageMutationResult
import moe.ouom.neriplayer.data.model.download.storage.StorageReference
import moe.ouom.neriplayer.data.model.download.storage.StorageRenameResult
import moe.ouom.neriplayer.data.model.download.storage.StorageTarget
import moe.ouom.neriplayer.data.model.download.storage.StorageWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class FileStorageBackendBoundaryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val safReference = StorageReference.SafRef(mock(Uri::class.java))

    @Test
    fun `non file references are rejected by every file operation`() = runTest {
        val backend = FileStorageBackend(tempFolder.root)

        assertTrue(backend.list(safReference).confidence is StorageConfidence.ProviderFailure)
        assertTrue(backend.read(safReference) { it.read() } is StorageLookupResult.Unsupported)
        assertTrue(
            backend.writeRecoverable(StorageTarget.SafTarget(safReference, "song.mp3", "audio/mpeg")) {}
                is StorageWriteResult.Unsupported
        )
        assertTrue(backend.delete(TrustedManagedRef(safReference)) is StorageMutationResult.Unsupported)
        assertTrue(backend.rename(TrustedManagedRef(safReference), "song.mp3") is StorageRenameResult.Unsupported)
    }

    @Test
    fun `references escaping the root stay out of scope for reads and mutations`() = runTest {
        val backend = FileStorageBackend(tempFolder.newFolder("root"))
        val outside = StorageReference.FileRef("../outside.mp3")

        assertEquals(StorageLookupResult.OutOfScope, backend.read(outside) { it.read() })
        assertEquals(
            StorageWriteResult.OutOfScope,
            backend.writeRecoverable(StorageTarget.FileTarget("../outside.mp3")) { it.write(1) }
        )
        assertEquals(StorageMutationResult.OutOfScope, backend.delete(TrustedManagedRef(outside)))
        assertEquals(StorageRenameResult.OutOfScope, backend.rename(TrustedManagedRef(outside), "inside.mp3"))
        assertFalse(File(tempFolder.root, "outside.mp3").exists())
    }

    @Test
    fun `listing separates plain files from directories and drops unknown timestamps`() = runTest {
        val root = tempFolder.root
        val song = File(root, "song.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(song.setLastModified(0L))
        File(root, "Covers").mkdirs()
        val backend = FileStorageBackend(root)

        assertEquals(
            StorageConfidence.Complete,
            backend.list(StorageReference.FileRef("song.mp3")).confidence
        )
        assertTrue(backend.list(StorageReference.FileRef("song.mp3")).entries.isEmpty())

        val entries = backend.list(StorageReference.FileRef("")).entries.associateBy { it.displayName }
        val songStat = entries.getValue("song.mp3")
        assertEquals(StorageReference.FileRef("song.mp3"), songStat.reference)
        assertEquals(3L, songStat.sizeBytes)
        assertNull(songStat.lastModifiedMs)
        assertFalse(songStat.isDirectory)
        val coversStat = entries.getValue("Covers")
        assertNull(coversStat.sizeBytes)
        assertTrue(coversStat.isDirectory)
    }

    @Test
    fun `read failures become provider failures unless the block marks its own failure`() = runTest {
        File(tempFolder.root, "song.mp3").writeText("audio")
        val backend = FileStorageBackend(tempFolder.root)
        val decoderFailure = IOException("decoder")

        val result = backend.read(StorageReference.FileRef("song.mp3")) { throw decoderFailure }
        assertEquals(StorageLookupResult.ProviderFailure(decoderFailure), result)

        val parseFailure = IllegalStateException("parse")
        val thrown = runCatching {
            backend.read(StorageReference.FileRef("song.mp3")) { throw StorageReadBlockFailure(parseFailure) }
        }.exceptionOrNull()
        assertTrue(thrown is StorageReadBlockFailure)
        assertTrue(generateSequence(thrown) { it.cause }.any { it.message == "parse" })
    }

    @Test
    fun `writes reject directory targets and report parents that are plain files`() = runTest {
        val root = tempFolder.root
        File(root, "Covers").mkdirs()
        val blocker = File(root, "blocker").apply { writeText("keep") }
        val backend = FileStorageBackend(root)

        assertTrue(backend.writeRecoverable(StorageTarget.FileTarget("Covers")) {} is StorageWriteResult.Unsupported)
        val blocked = backend.writeRecoverable(StorageTarget.FileTarget("blocker/child.mp3")) { it.write(1) }

        assertTrue(blocked is StorageWriteResult.ProviderFailure)
        assertEquals("keep", blocker.readText())
    }

    @Test
    fun `cancelled writes remove their temporary file before rethrowing`() = runTest {
        val backend = FileStorageBackend(tempFolder.root)

        val thrown = runCatching {
            backend.writeRecoverable(StorageTarget.FileTarget("song.mp3")) { output ->
                output.write(1)
                throw CancellationException("stop")
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(tempFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `writer failure after its temporary file disappeared reports only the writer error`() = runTest {
        val root = tempFolder.root
        val writerFailure = IllegalStateException("writer failed")

        val result = FileStorageBackend(root).writeRecoverable(StorageTarget.FileTarget("song.mp3")) { output ->
            output.close()
            assertTrue(root.listFiles().orEmpty().single { it.name.startsWith(".npdl_tmp_") }.delete())
            throw writerFailure
        }

        assertEquals(StorageWriteResult.ProviderFailure(writerFailure), result)
        assertTrue(writerFailure.suppressed.isEmpty())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `delete distinguishes missing files from deleted ones`() = runTest {
        val song = File(tempFolder.root, "song.mp3").apply { writeText("audio") }
        val backend = FileStorageBackend(tempFolder.root)

        assertEquals(
            StorageMutationResult.Missing,
            backend.delete(TrustedManagedRef(StorageReference.FileRef("missing.mp3")))
        )
        assertEquals(
            StorageMutationResult.Deleted,
            backend.delete(TrustedManagedRef(StorageReference.FileRef("song.mp3")))
        )
        assertFalse(song.exists())
    }

    @Test
    fun `rename validates names and never overwrites another file`() = runTest {
        val root = tempFolder.root
        val first = File(root, "a.mp3").apply { writeText("first") }
        val second = File(root, "b.mp3").apply { writeText("second") }
        val backend = FileStorageBackend(root)
        val reference = TrustedManagedRef(StorageReference.FileRef("a.mp3"))

        listOf(" ", ".", "..").forEach { name ->
            assertTrue(name, backend.rename(reference, name) is StorageRenameResult.Unsupported)
        }
        assertEquals(
            StorageRenameResult.Missing,
            backend.rename(TrustedManagedRef(StorageReference.FileRef("missing.mp3")), "c.mp3")
        )
        assertTrue(backend.rename(reference, "b.mp3") is StorageRenameResult.ProviderFailure)
        assertEquals("second", second.readText())
        assertTrue(backend.rename(reference, "nested/c.mp3") is StorageRenameResult.ProviderFailure)
        assertTrue(first.exists())

        val renamed = backend.rename(reference, "a.mp3")
        assertEquals(StorageReference.FileRef("a.mp3"), (renamed as StorageRenameResult.Renamed).stat.reference)
        val moved = backend.rename(reference, "c.mp3") as StorageRenameResult.Renamed
        assertEquals("c.mp3", moved.stat.displayName)
        assertFalse(first.exists())
        assertEquals("first", File(root, "c.mp3").readText())
    }
}
