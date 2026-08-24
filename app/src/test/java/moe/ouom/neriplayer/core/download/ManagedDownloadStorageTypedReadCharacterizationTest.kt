package moe.ouom.neriplayer.core.download

import android.content.ContentResolver
import android.content.Context
import java.io.FileNotFoundException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.storage.backend.SafStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedDownloadStorageTypedReadCharacterizationTest {

    @Test
    fun `migration entry reader uses typed backend instead of raw resolver stream`() {
        val source = readSource()
        val reader = source
            .substringAfter("private fun openStoredEntryInputStream")
            .substringBefore("private fun restoreStoredEntryLastModified")

        assertFalse(
            "migration reads must not bypass StorageBackend with ContentResolver",
            reader.contains("contentResolver.openInputStream")
        )
        assertTrue(
            "migration reads must classify the typed backend result",
            reader.contains("StorageLookupResult")
        )
    }

    @Test
    fun `SAF promote fallback copies through typed backend`() {
        val source = readSource()
        val promote = source
            .substringAfter("private fun promotePendingAudioBlocking")
            .substringBefore("private fun writeSeedMetadataAfterAudioCommit")

        assertFalse(
            "SAF promote fallback must not open pending bytes through raw resolver",
            promote.contains("contentResolver.openInputStream")
        )
        assertTrue(
            "SAF promote fallback must use the backend read operation",
            promote.contains("backend.read")
        )
        val safPromote = promote.substringAfter("is RootHandle.TreeRoot")
        assertFalse(
            "SAF promote existence checks must use typed stat, not DocumentFile.isFile",
            safPromote.contains("DocumentFile.fromSingleUri") || safPromote.contains("it.isFile")
        )
    }

    @Test
    fun `managed SAF rename is delegated to the typed backend`() {
        val source = readSource()
        val rename = source
            .substringAfter("private fun renameTreeDocument")
            .substringBefore("private fun createRootFile")

        assertFalse(
            "managed storage must not call DocumentsContract.renameDocument directly",
            rename.contains("DocumentsContract.renameDocument")
        )
        assertTrue(
            "managed storage rename must consume the backend returned reference",
            rename.contains("backend.rename")
        )
    }

    @Test
    fun `typed migration reader removes its temporary stream file on close`() {
        val sourceDirectory = Files.createTempDirectory("neriplayer-typed-reader").toFile()
        val cacheDirectory = File(sourceDirectory, "cache").apply { mkdirs() }
        val sourceFile = File(sourceDirectory, "song.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val context = mock(Context::class.java)
        `when`(context.cacheDir).thenReturn(cacheDirectory)
        val entry = ManagedDownloadStorage.StoredEntry(
            name = sourceFile.name,
            reference = sourceFile.absolutePath,
            mediaUri = sourceFile.toURI().toString(),
            localFilePath = sourceFile.absolutePath,
            sizeBytes = sourceFile.length(),
            lastModifiedMs = sourceFile.lastModified()
        )
        try {
            val stream = invokeReader(context, entry)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), stream.readBytes())
            stream.close()
            assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            sourceDirectory.deleteRecursively()
        }
    }

    @Test
    fun `typed migration reader preserves arbitrary SAF provider failures`() {
        val cacheDirectory = Files.createTempDirectory("neriplayer-typed-reader-cache").toFile()
        val error = FileNotFoundException("provider temporarily unavailable")
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.cacheDir).thenReturn(cacheDirectory)
        `when`(context.contentResolver).thenReturn(resolver)
        doAnswer { throw error }.`when`(resolver).query(any(), any(), any(), any(), any())
        try {
            val result = runBlocking {
                SafStorageBackend(context).read(
                    StorageReference.SafRef(mock(android.net.Uri::class.java))
                ) { it.readBytes() }
            }
            assertTrue(result is StorageLookupResult.ProviderFailure)
            assertEquals(error, (result as StorageLookupResult.ProviderFailure).error)
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    private fun invokeReader(
        context: Context,
        entry: ManagedDownloadStorage.StoredEntry
    ): java.io.InputStream {
        val method = ManagedDownloadStorage::class.java
            .getDeclaredMethod(
                "openStoredEntryInputStream",
                Context::class.java,
                ManagedDownloadStorage.StoredEntry::class.java
            )
            .apply { isAccessible = true }
        return method.invoke(ManagedDownloadStorage, context, entry)
            as? java.io.InputStream
            ?: throw AssertionError("typed reader returned no stream")
    }

    private fun readSource(): String {
        val relativePath =
            "src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        return sequenceOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath")
        ).firstOrNull(File::isFile)?.readText()
            ?: throw IllegalStateException("source file not found: $relativePath")
    }
}
