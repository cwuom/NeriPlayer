package moe.ouom.neriplayer.core.download.storage

import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import moe.ouom.neriplayer.data.model.download.ManagedDownloadRestorableMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageRestorableCodecTest {
    @Test
    fun `downloaded metadata codec persists the restorable baseline`() {
        val metadata = DownloadedAudioMetadata(
            stableKey = "youtube:video-1",
            name = "Edited title",
            artist = "Edited artist",
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
            restorableMetadata = ManagedDownloadRestorableMetadata(
                sourceStableKey = "youtube:video-1",
                baseline = ManagedDownloadRestorableMetadata.Baseline(
                    title = "Original title",
                    artist = "Original artist",
                    originalLyric = "original"
                ),
                overrides = ManagedDownloadRestorableMetadata.Overrides(
                    title = "Edited title",
                    artist = "Edited artist"
                ),
                baselineCoverAssetHash = "base-hash",
                currentCoverAssetHash = "edited-hash",
                createdAtMs = 1L,
                updatedAtMs = 2L
            )
        )

        val json = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(metadata)
        val parsed = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataFromJsonObject(json)

        assertTrue(json.optInt("schemaVersion") >= 5)
        assertEquals(metadata.restorableMetadata, parsed.restorableMetadata)
        assertEquals(DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED, parsed.metadataEmbeddingState)
        assertEquals("Original title", parsed.originalName)
        assertEquals("Edited title", parsed.customName)
    }

    @Test
    fun `downloaded metadata codec preserves terminal finalization token`() {
        val metadata = DownloadedAudioMetadata(
            terminalTemporaryWriteCleanupToken = "finalization-token"
        )

        val json = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(metadata)
        val parsed = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataFromJsonObject(json)

        assertEquals("finalization-token", parsed.terminalTemporaryWriteCleanupToken)
    }

    @Test
    fun `legacy encoding retains original baseline edited values and creation fallback`() {
        val original = DownloadedAudioMetadata(
            stableKey = "legacy-key",
            name = "title",
            artist = "artist",
            album = "album",
            coverUrl = "remote-cover",
            matchedLyric = "current-original",
            matchedTranslatedLyric = "current-translated",
            matchedRomanizedLyric = "current-romanized",
            customName = "custom-title",
            customArtist = "custom-artist",
            customCoverUrl = "custom-cover",
            originalName = "original-title",
            originalArtist = "original-artist",
            originalCoverUrl = "original-cover",
            originalLyric = "original-lyric",
            originalTranslatedLyric = "original-translated",
            originalRomanizedLyric = "original-romanized",
            coverPath = "local-cover",
            downloadTimeMs = 123L
        )
        val encoded = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(original)
        val decoded = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataFromJsonObject(encoded)
        val expectedNested = ManagedDownloadRestorableMetadata(
            sourceStableKey = "legacy-key",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                title = "original-title",
                artist = "original-artist",
                album = "album",
                coverReference = "original-cover",
                originalLyric = "original-lyric",
                translatedLyric = "original-translated",
                romanizedLyric = "original-romanized"
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(
                title = "custom-title",
                artist = "custom-artist",
                coverReference = "local-cover",
                originalLyric = "current-original",
                translatedLyric = "current-translated",
                romanizedLyric = "current-romanized"
            ),
            createdAtMs = 123L,
            updatedAtMs = 123L
        )

        assertEquals(6, encoded.getInt("schemaVersion"))
        assertEquals(original.copy(createdAtMs = 123L, restorableMetadata = expectedNested), decoded)
    }
}
