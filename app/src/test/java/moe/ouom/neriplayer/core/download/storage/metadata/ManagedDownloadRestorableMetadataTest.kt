package moe.ouom.neriplayer.core.download.storage.metadata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                userLyricOffsetMs = -321L,
                originalLyric = "edited lyric"
            ),
            baselineCoverAssetHash = "base-hash",
            currentCoverAssetHash = "edited-hash",
            createdAtMs = 10L,
            updatedAtMs = 20L
        )

        val json = original.toJson()
        val restored = ManagedDownloadRestorableMetadata.fromJson(json)

        assertEquals(
            "content://root/Covers/base.jpg",
            json.getJSONObject("baseline").getString("coverReference")
        )
        assertEquals(
            "content://root/Covers/edited.jpg",
            json.getJSONObject("overrides").getString("coverReference")
        )
        assertEquals("content://root/Covers/base.jpg", restored?.baseline?.coverReference)
        assertEquals("Original title", restored?.baseline?.title)
        assertEquals("Edited title", restored?.overrides?.title)
        assertEquals(-321L, restored?.overrides?.userLyricOffsetMs)
        assertEquals("base-hash", restored?.baselineCoverAssetHash)
        assertEquals(20L, restored?.updatedAtMs)
    }

    @Test
    fun `legacy baseline cover reference remains readable during upgrade`() {
        val restored = ManagedDownloadRestorableMetadata.fromJson(
            JSONObject(
                """
                {
                  "baseline": {"coverReference": "content://legacy/cover.jpg"}
                }
                """.trimIndent()
            )
        )

        assertEquals("content://legacy/cover.jpg", restored?.baseline?.coverReference)
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

    @Test
    fun `baseline empty lyrics survive round trip as explicit absence`() {
        val restored = ManagedDownloadRestorableMetadata.fromJson(
            ManagedDownloadRestorableMetadata(
                sourceStableKey = "stable",
                baseline = ManagedDownloadRestorableMetadata.Baseline(
                    originalLyric = "",
                    translatedLyric = "",
                    romanizedLyric = ""
                ),
                overrides = ManagedDownloadRestorableMetadata.Overrides()
            ).toJson()
        )

        assertEquals("", restored?.baseline?.originalLyric)
        assertEquals("", restored?.baseline?.translatedLyric)
        assertEquals("", restored?.baseline?.romanizedLyric)
    }
}
