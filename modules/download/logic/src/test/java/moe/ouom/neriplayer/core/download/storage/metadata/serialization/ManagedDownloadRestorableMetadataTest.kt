package moe.ouom.neriplayer.core.download.storage.metadata.serialization

import moe.ouom.neriplayer.data.model.download.ManagedDownloadRestorableMetadata

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadRestorableMetadataTest {
    @Test
    fun `missing sections and legacy source identity remain readable`() {
        assertNull(ManagedDownloadRestorableMetadata.fromJson(null))
        val empty = requireNotNull(ManagedDownloadRestorableMetadata.fromJson(JSONObject()))
        assertNull(empty.sourceStableKey)
        assertEquals(ManagedDownloadRestorableMetadata.Baseline(), empty.baseline)
        assertEquals(ManagedDownloadRestorableMetadata.Overrides(), empty.overrides)
        assertEquals(emptyList<String>(), empty.legacyCoverRecoveryReferences)
        val legacy = JSONObject().put("sourceStableKey", "legacy")
            .put("sourceIdentity", JSONObject().put("stableKey", " "))
        assertEquals("legacy", ManagedDownloadRestorableMetadata.fromJson(legacy)?.sourceStableKey)
    }

    @Test
    fun `cover recovery references are trimmed deduplicated and exclude empty entries`() {
        val json = JSONObject().put("assetRefs", JSONObject().put("legacyCoverRecoveryReferences",
            JSONArray().put(" old ").put("old").put("").put(" ").put(JSONObject.NULL)))
        assertEquals(listOf("old"), ManagedDownloadRestorableMetadata.fromJson(json)?.legacyCoverRecoveryReferences)
    }

    @Test
    fun `explicit nulls and invalid timestamps retain the legacy absent semantics`() {
        val json = JSONObject().put("sourceIdentity", JSONObject().put("stableKey", JSONObject.NULL))
            .put("assetRefs", JSONObject().put("baselineCoverHash", JSONObject.NULL))
            .put("times", JSONObject().put("createdAtMs", 0L).put("updatedAtMs", -1L))
        val metadata = requireNotNull(ManagedDownloadRestorableMetadata.fromJson(json))
        assertNull(metadata.sourceStableKey)
        assertNull(metadata.baselineCoverAssetHash)
        assertNull(metadata.createdAtMs)
        assertNull(metadata.updatedAtMs)
        json.getJSONObject("times").put("createdAtMs", JSONObject.NULL).put("updatedAtMs", 1L)
        val updated = requireNotNull(ManagedDownloadRestorableMetadata.fromJson(json))
        assertNull(updated.createdAtMs)
        assertEquals(1L, updated.updatedAtMs)
    }

    @Test
    fun `primary lyric offset takes precedence while explicit null falls back to legacy offset`() {
        val overrides = JSONObject().put("userLyricOffsetMs", -20L).put("lyricOffsetMs", 30L)
            .put("originalLyric", JSONObject.NULL).put("translatedLyric", " ")
        val json = JSONObject().put("overrides", overrides)
        val current = requireNotNull(ManagedDownloadRestorableMetadata.fromJson(json))
        assertEquals(-20L, current.overrides.userLyricOffsetMs)
        assertNull(current.overrides.originalLyric)
        assertNull(current.overrides.translatedLyric)
        overrides.put("userLyricOffsetMs", JSONObject.NULL)
        assertEquals(30L, ManagedDownloadRestorableMetadata.fromJson(json)?.overrides?.userLyricOffsetMs)
        overrides.put("lyricOffsetMs", JSONObject.NULL)
        assertEquals(0L, ManagedDownloadRestorableMetadata.fromJson(json)?.overrides?.userLyricOffsetMs)
    }

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
            baselineCoverAssetFileName = "base-12345678.jpg",
            currentCoverAssetFileName = "edited-12345678.jpg",
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
        assertEquals("base-12345678.jpg", restored?.baselineCoverAssetFileName)
        assertEquals("edited-12345678.jpg", restored?.currentCoverAssetFileName)
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
