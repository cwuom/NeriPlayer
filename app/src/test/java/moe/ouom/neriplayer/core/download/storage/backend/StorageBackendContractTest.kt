package moe.ouom.neriplayer.core.download.storage.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageBackendContractTest {
    @Test
    fun `opaque document id is returned byte for byte`() {
        val documentId = "Primary:Music/Track%2F7"

        assertEquals(documentId, requireOpaqueDocumentId(documentId))
    }

    @Test
    fun `missing document id is a provider contract failure`() {
        assertThrows(IllegalStateException::class.java) {
            requireOpaqueDocumentId(null)
        }
    }
}
