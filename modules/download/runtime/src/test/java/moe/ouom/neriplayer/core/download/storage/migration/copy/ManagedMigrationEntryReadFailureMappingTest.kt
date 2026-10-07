package moe.ouom.neriplayer.core.download.storage.migration.copy

import android.content.Context
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import moe.ouom.neriplayer.data.model.download.storage.StorageLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedMigrationEntryReadFailureMappingTest {
    private val context = mock(Context::class.java)
    private val entry = ManagedDownloadStorage.StoredEntry(
        name = "song.flac",
        reference = "/music/NeriPlayer/song.flac",
        mediaUri = "file:///music/NeriPlayer/song.flac",
        localFilePath = "/music/NeriPlayer/song.flac",
        sizeBytes = 32L,
        lastModifiedMs = 1L
    )

    @Test
    fun `found reads return the block result or rethrow its failure`() = runTest {
        assertEquals(7, readOrThrow(StorageLookupResult.Found(Result.success(7))))

        val failure = failureOf(
            StorageLookupResult.Found(Result.failure(IllegalStateException("decode failed")))
        )

        assertTrue(failure is IllegalStateException)
        assertEquals("decode failed", failure?.message)
    }

    @Test
    fun `missing permission and provider failures become retryable migration errors`() = runTest {
        val providerError = IOException("provider offline")
        listOf(
            StorageLookupResult.Missing to "copy cover: missing song.flac",
            StorageLookupResult.PermissionLost to "copy cover: storage permission lost for song.flac",
            StorageLookupResult.ProviderFailure(providerError) to "copy cover: provider failure for song.flac"
        ).forEach { (result, message) ->
            val error = failureOf(result) as ManagedDownloadMigrationException
            assertEquals(message, error.message)
            assertTrue(message, error.retryable)
        }
        assertSame(
            providerError,
            failureOf(StorageLookupResult.ProviderFailure(providerError))?.cause
        )
    }

    @Test
    fun `out of scope and unsupported reads become permanent migration errors`() = runTest {
        listOf(
            StorageLookupResult.OutOfScope to "copy cover: out-of-scope reference for song.flac",
            StorageLookupResult.Unsupported("read") to "copy cover: unsupported read for song.flac"
        ).forEach { (result, message) ->
            val error = failureOf(result) as ManagedDownloadMigrationException
            assertEquals(message, error.message)
            assertFalse(message, error.retryable)
        }
    }

    private suspend fun readOrThrow(result: StorageLookupResult<Result<Any?>>): Any? =
        FixedResultReader(result).readOrThrow<Any?>(context, entry, "copy cover") {
            error("the fixed reader never opens a stream")
        }

    private suspend fun failureOf(result: StorageLookupResult<Result<Any?>>): Throwable? =
        runCatching { readOrThrow(result) }.exceptionOrNull()

    private class FixedResultReader(
        private val result: StorageLookupResult<Result<Any?>>
    ) : ManagedMigrationEntryReader {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> read(
            context: Context,
            entry: ManagedDownloadStorage.StoredEntry,
            block: suspend (InputStream) -> T
        ): StorageLookupResult<Result<T>> = result as StorageLookupResult<Result<T>>
    }
}
