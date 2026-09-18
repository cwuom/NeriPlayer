package moe.ouom.neriplayer.core.download.storage.metadata

import android.content.Context
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class ManagedDownloadCoverAssetStoreTest {
    @Test
    fun `source cover exactly at 16 MiB is accepted while the next byte is rejected`() = runBlocking {
        val exact = Files.createTempFile("neriplayer-cover-exact", ".bin").toFile()
        val oversized = Files.createTempFile("neriplayer-cover-oversized", ".bin").toFile()
        try {
            exact.outputStream().use { output ->
                output.write(ByteArray(MAX_SOURCE_COVER_BYTES.toInt()))
            }
            oversized.outputStream().use { output ->
                output.write(ByteArray(MAX_SOURCE_COVER_BYTES.toInt() + 1))
            }
            val context = mock(Context::class.java)
            assertTrue(
                ManagedDownloadCoverAssetStore.inspect(
                    context = context,
                    reference = exact.toURI().toString()
                ) != null
            )
            val failure = runCatching {
                ManagedDownloadCoverAssetStore.inspect(
                    context = context,
                    reference = oversized.toURI().toString()
                )
            }.exceptionOrNull()
            assertTrue(failure is CoverSourceTooLargeException)
        } finally {
            exact.delete()
            oversized.delete()
        }
    }

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
    fun `materialized cover preserves the source encoding instead of the jpg default`() {
        assertEquals(
            ManagedDownloadCoverAssetStore.CoverEncoding("png", "image/png"),
            ManagedDownloadCoverAssetStore.resolveCoverEncoding(
                sourceDisplayName = "cover.png",
                detectedMimeType = "image/png",
                fallbackExtension = "jpg",
                fallbackMimeType = "image/jpeg"
            )
        )
        assertEquals(
            "Artist-Song.webp",
            ManagedDownloadCoverAssetStore.replaceCoverFileExtension(
                fileName = "Artist-Song.jpg",
                extension = "webp"
            )
        )
    }

    @Test
    fun `legacy readable cover filename stays within the managed filename limit`() {
        val name = ManagedDownloadCoverAssetStore.buildLegacyReadableFileName(
            sourceDisplayName = "封面".repeat(100) + ".png",
            assetHash = "a".repeat(64),
            extension = "png"
        )

        assertTrue(name.endsWith(".png"))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 128)
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
