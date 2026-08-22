package moe.ouom.neriplayer.core.download.storage.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadCoverAssetStoreTest {
    @Test
    fun `cover asset hash is stable sha256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ManagedDownloadCoverAssetStore.sha256("abc".toByteArray())
        )
    }
}
