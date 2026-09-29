package moe.ouom.neriplayer.core.download.storage.metadata.codec

import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadedAudioLegacyMetadataTest {
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

        val parsedFromMissing = decode(nested)
        assertEquals(-321L, parsedFromMissing.userLyricOffsetMs)

        val parsedFromZero = decode(
            JSONObject(nested.toString()).put("userLyricOffsetMs", 0L)
        )
        assertEquals(-321L, parsedFromZero.userLyricOffsetMs)

        val parsedFromExplicit = decode(
            JSONObject(nested.toString()).put("userLyricOffsetMs", -120L)
        )
        assertEquals(-120L, parsedFromExplicit.userLyricOffsetMs)
    }

    @Test
    fun `downloaded metadata codec recognizes shipped finalized legacy metadata`() {
        val parsed = decode(
            JSONObject().put("downloadFinalized", true)
        )

        assertEquals(true, parsed.downloadFinalized)
        assertEquals(
            DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED,
            parsed.metadataEmbeddingState
        )
    }

    @Test
    fun `downloaded metadata codec keeps explicit unfinished legacy metadata unverified`() {
        val parsed = decode(
            JSONObject().put("downloadFinalized", false)
        )

        assertEquals(false, parsed.downloadFinalized)
        assertNull(parsed.metadataEmbeddingState)
    }

    @Test
    fun `downloaded metadata codec repairs metadata downgraded by the v15 upgrader`() {
        val parsed = decode(
            JSONObject()
                .put("stableKey", "file:song.flac")
                .put("audioFileName", "song.flac")
                .put("downloadTimeMs", 1234L)
                .put("downloadFinalized", false)
                .put("metadataEmbeddingState", "LEGACY_UNVERIFIED")
                .put("createdAtSource", "LEGACY_V15")
        )

        assertEquals(true, parsed.downloadFinalized)
        assertEquals(
            DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED,
            parsed.metadataEmbeddingState
        )
    }

    private fun decode(root: JSONObject): DownloadedAudioMetadata {
        return ManagedDownloadedAudioMetadataDecoder(root).decode()
    }
}
