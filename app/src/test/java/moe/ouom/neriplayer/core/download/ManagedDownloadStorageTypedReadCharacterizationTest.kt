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
            .substringAfter("internal suspend fun <T> readStoredEntryForMigration")
            .substringBefore("private fun restoreStoredEntryLastModified")

        assertFalse(
            "migration reads must not bypass StorageBackend with ContentResolver",
            reader.contains("contentResolver.openInputStream")
        )
        assertTrue(
            "migration reads must classify the typed backend result",
            reader.contains("StorageLookupResult")
        )
        assertFalse(
            "migration reads must not stage the whole file in cache",
            reader.contains("File.createTempFile") || reader.contains("runBlocking")
        )
    }

    @Test
    fun `SAF promote fallback copies through typed backend`() {
        val source = readSource()
        val promote = source
            .substringAfter("private suspend fun promotePendingAudio")
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
    fun `SAF promotion keeps suspend calls off runBlocking bridges`() {
        val source = readSource()
        val promotion = source
            .substringAfter("private suspend fun promotePendingAudio")
            .substringBefore("private fun discardNewTreePromotionTarget")

        assertFalse(
            "suspend SAF promotion must not block an IO worker through runBlocking",
            promotion.contains("runBlocking")
        )
        assertTrue(
            "SAF promotion must use the typed suspend read operation",
            promotion.contains("backend.read(StorageReference.SafRef(pending.uri))")
        )
    }

    @Test
    fun `managed SAF rename is delegated to the typed backend`() {
        val source = readSource()
        val rename = source
            .substringAfter("private fun renameTreeDocument")
            .substringBefore("private fun resolvePendingTreeDocument")

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
    fun `typed migration reader streams without creating a cache file`() {
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
            val lookup: StorageLookupResult<Result<ByteArray>> = runBlocking {
                ManagedDownloadStorage.readStoredEntryForMigration(context, entry) { input ->
                    assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
                    input.readBytes()
                }
            }
            val result = lookup as StorageLookupResult.Found<Result<ByteArray>>
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.value.getOrThrow())
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
