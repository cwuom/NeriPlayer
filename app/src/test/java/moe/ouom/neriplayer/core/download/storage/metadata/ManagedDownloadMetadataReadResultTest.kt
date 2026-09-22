package moe.ouom.neriplayer.core.download.storage.metadata

import java.io.IOException
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import org.junit.Assert.*
import org.junit.Test

class ManagedDownloadMetadataReadResultTest {
    @Test fun `read status keeps missing malformed and provider faults distinct`() {
        assertTrue(classifyManagedMetadataRead(StorageLookupResult.Found("{\"stableKey\":\"a\"}")) is ManagedMetadataReadResult.Found)
        assertEquals(ManagedMetadataReadResult.Malformed, classifyManagedMetadataRead(StorageLookupResult.Found("{bad")))
        assertEquals(ManagedMetadataReadResult.Missing, classifyManagedMetadataRead(StorageLookupResult.Missing))
        assertTrue(classifyManagedMetadataRead(StorageLookupResult.ProviderFailure(IOException("offline"))) is ManagedMetadataReadResult.Unavailable)
        assertTrue(classifyManagedMetadataRead(StorageLookupResult.PermissionLost) is ManagedMetadataReadResult.Unavailable)
        assertTrue(classifyManagedMetadataRead(StorageLookupResult.Unsupported("read")) is ManagedMetadataReadResult.Unavailable)
        assertTrue(classifyManagedMetadataRead(StorageLookupResult.OutOfScope) is ManagedMetadataReadResult.Unavailable)
        assertThrows(CancellationException::class.java) {
            classifyManagedMetadataRead(StorageLookupResult.ProviderFailure(CancellationException("cancelled")))
        }
    }

    @Test fun `only unavailable results stop publication`() {
        requireAvailableMetadata(mapOf("a" to ManagedMetadataReadResult.Malformed, "b" to ManagedMetadataReadResult.Missing))
        val error = assertThrows(ManagedMetadataReadUnavailableException::class.java) {
            requireAvailableMetadata(mapOf("a" to ManagedMetadataReadResult.Unavailable(IOException("offline"))))
        }
        assertEquals(setOf("a"), error.references)
    }
}
