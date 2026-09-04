package moe.ouom.neriplayer.core.download.storage.metadata

import android.content.Context
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedDownloadCoverAssetStoreTest {
    @Test
    fun `cover asset hash is stable sha256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ManagedDownloadCoverAssetStore.sha256("abc".toByteArray())
        )
    }

    @Test
    fun `ordinary materialization reuses source without content addressed fallback`() {
        assertNull(
            ManagedDownloadCoverAssetStore.selectTargetFileName(
                sourceDisplayName = "Artist - Song-12345678.jpg",
                preferredFileName = null
            )
        )
    }

    @Test
    fun `preferred readable name is reused or selected without hash fallback`() {
        val readableName = "Artist - Song-12345678.jpg"

        assertNull(
            ManagedDownloadCoverAssetStore.selectTargetFileName(
                sourceDisplayName = readableName,
                preferredFileName = readableName
            )
        )
        assertEquals(
            readableName,
            ManagedDownloadCoverAssetStore.selectTargetFileName(
                sourceDisplayName = "legacy.jpg",
                preferredFileName = readableName
            )
        )
    }

    @Test
    fun `legacy materialization uses readable name and short hash`() {
        val hash = ManagedDownloadCoverAssetStore.sha256("abc".toByteArray())

        assertEquals(
            "legacy-${hash.take(8)}.jpg",
            ManagedDownloadCoverAssetStore.buildLegacyReadableFileName(
                sourceDisplayName = "legacy.jpg",
                assetHash = hash,
                extension = "jpg"
            )
        )
        assertEquals(
            "cover-${hash.take(8)}.jpg",
            ManagedDownloadCoverAssetStore.buildLegacyReadableFileName(
                sourceDisplayName = "$hash.jpg",
                assetHash = hash,
                extension = "jpg"
            )
        )
    }

    @Test
    fun `inspection fingerprints a local cover without changing its reference`() = runBlocking {
        val file = Files.createTempFile("neriplayer-cover-inspect", ".jpg").toFile()
        try {
            file.writeBytes("cover-bytes".toByteArray())

            val inspected = ManagedDownloadCoverAssetStore.inspect(
                context = mock(Context::class.java),
                reference = file.toURI().toString()
            )

            assertEquals(file.toURI().toString(), inspected?.reference)
            assertEquals(file.name, inspected?.fileName)
            assertEquals(
                ManagedDownloadCoverAssetStore.sha256("cover-bytes".toByteArray()),
                inspected?.assetHash
            )
        } finally {
            file.delete()
        }
    }

}
