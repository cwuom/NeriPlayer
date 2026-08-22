package moe.ouom.neriplayer.core.download.storage.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadRestorableMetadataTest {
    @Test
    fun `restorable metadata keeps source baseline overrides assets and times`() {
        val original = ManagedDownloadRestorableMetadata(
            sourceStableKey = "youtube:video-1",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                title = "Original title",
                artist = "Original artist",
                album = "Original album",
                coverReference = "content://root/Covers/base.jpg",
                originalLyric = "original",
                translatedLyric = "translation",
                romanizedLyric = "romanized"
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(
                title = "Edited title",
                artist = "Edited artist",
                coverReference = "content://root/Covers/edited.jpg",
                originalLyric = "edited lyric"
            ),
            baselineCoverAssetHash = "base-hash",
            currentCoverAssetHash = "edited-hash",
            createdAtMs = 10L,
            updatedAtMs = 20L
        )

        val restored = ManagedDownloadRestorableMetadata.fromJson(original.toJson())

        assertEquals(original, restored)
        assertEquals("Original title", restored?.baseline?.title)
        assertEquals("Edited title", restored?.overrides?.title)
        assertEquals("base-hash", restored?.baselineCoverAssetHash)
        assertEquals(20L, restored?.updatedAtMs)
    }

    @Test
    fun `missing optional fields remain null`() {
        val restored = ManagedDownloadRestorableMetadata.fromJson(
            ManagedDownloadRestorableMetadata(
                sourceStableKey = "stable",
                baseline = ManagedDownloadRestorableMetadata.Baseline(),
                overrides = ManagedDownloadRestorableMetadata.Overrides()
            ).toJson()
        )

        assertEquals("stable", restored?.sourceStableKey)
        assertNull(restored?.baseline?.title)
        assertNull(restored?.overrides?.coverReference)
    }
}
