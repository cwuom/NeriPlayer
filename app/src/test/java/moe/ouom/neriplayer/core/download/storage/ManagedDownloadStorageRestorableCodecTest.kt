package moe.ouom.neriplayer.core.download.storage

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageRestorableCodecTest {
    @Test
    fun `downloaded metadata codec persists the restorable baseline`() {
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "youtube:video-1",
            name = "Edited title",
            artist = "Edited artist",
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
        assertEquals("Original title", parsed.originalName)
        assertEquals("Edited title", parsed.customName)
    }

    @Test
    fun `downloaded metadata codec restores offset from nested overrides`() {
        val nested = JSONObject().apply {
            put(
                "restorableMetadata",
                JSONObject().put(
                    "overrides",
                    JSONObject().put("userLyricOffsetMs", -321L)
                )
            )
        }

        val parsedFromMissing = ManagedDownloadStorageJsonCodec
            .downloadedAudioMetadataFromJsonObject(nested)
        assertEquals(-321L, parsedFromMissing.userLyricOffsetMs)

        val parsedFromZero = ManagedDownloadStorageJsonCodec
            .downloadedAudioMetadataFromJsonObject(
                JSONObject(nested.toString()).put("userLyricOffsetMs", 0L)
            )
        assertEquals(-321L, parsedFromZero.userLyricOffsetMs)

        val parsedFromExplicit = ManagedDownloadStorageJsonCodec
            .downloadedAudioMetadataFromJsonObject(
                JSONObject(nested.toString()).put("userLyricOffsetMs", -120L)
            )
        assertEquals(-120L, parsedFromExplicit.userLyricOffsetMs)
    }
}
