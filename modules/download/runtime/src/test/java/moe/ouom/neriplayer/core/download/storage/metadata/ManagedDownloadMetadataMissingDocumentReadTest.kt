package moe.ouom.neriplayer.core.download.storage.metadata

import java.io.FileNotFoundException
import java.io.IOException
import moe.ouom.neriplayer.data.model.download.storage.StorageLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMetadataMissingDocumentReadTest {
    @Test
    fun `provider failures that report a missing document read as missing metadata`() {
        listOf(
            FileNotFoundException("Missing file for content://downloads/document/42"),
            IOException("query failed", IllegalArgumentException("Document not found: 42"))
        ).forEach { error ->
            assertEquals(
                error.toString(),
                ManagedMetadataReadResult.Missing,
                classifyManagedMetadataRead(StorageLookupResult.ProviderFailure(error))
            )
        }
    }

    @Test
    fun `a permission denial stays unavailable even when the provider says not found`() {
        val error = FileNotFoundException("open failed: EACCES (Permission denied), file not found")

        val result = classifyManagedMetadataRead(StorageLookupResult.ProviderFailure(error))

        assertTrue(result is ManagedMetadataReadResult.Unavailable)
        assertSame(error, (result as ManagedMetadataReadResult.Unavailable).error)
    }
}
