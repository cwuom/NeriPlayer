package moe.ouom.neriplayer.core.download.storage.metadata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadMetadataCodecTest {

    @Test
    fun `prepared reference replacements keep longest prefix semantics`() {
        val referenceMap = linkedMapOf(
            "content://provider/tree/primary" to "content://provider/tree/target-root",
            "content://provider/tree/primary/document/primary:old" to
                "content://provider/tree/target"
        )
        val rawJson = JSONObject()
            .put(
                "stableKey",
                "42|netease|content://provider/tree/primary/document/primary:old/song.mp3"
            )
            .put("mediaUri", "content://provider/tree/primary/document/primary:old")
            .toString()

        val prepared = prepareManagedMetadataReferenceReplacements(referenceMap)
        val rewrittenWithPreparation = ManagedDownloadMetadataCodec
            .rewriteManagedMetadataReferences(rawJson, referenceMap, prepared)
        val rewrittenWithCompatibilityApi = ManagedDownloadMetadataCodec
            .rewriteManagedMetadataReferences(rawJson, referenceMap)

        assertEquals(
            "42|netease|content://provider/tree/target/song.mp3",
            JSONObject(rewrittenWithPreparation).getString("stableKey")
        )
        assertEquals(
            JSONObject(rewrittenWithCompatibilityApi).getString("stableKey"),
            JSONObject(rewrittenWithPreparation).getString("stableKey")
        )
        assertEquals(
            "content://provider/tree/target",
            JSONObject(rewrittenWithPreparation).getString("mediaUri")
        )
    }

    @Test
    fun `prepared replacements filter identity entries and sort deterministically`() {
        val prepared = prepareManagedMetadataReferenceReplacements(
            linkedMapOf(
                "abc" to "target-abc",
                "abcdef" to "target-long",
                "" to "target-empty",
                "same" to "same"
            )
        )

        assertEquals(
            listOf("abcdef", "abc"),
            prepared.map(ManagedMetadataReferenceReplacement::from)
        )
    }

    @Test
    fun `reference rewrite keeps raw json without replacements and follows restorable cover sections`() {
        val raw = "not json"
        assertEquals(raw, ManagedDownloadMetadataCodec.rewriteManagedMetadataReferences(raw, emptyMap()))

        val references = mapOf("/old/cover.jpg" to "/new/cover.jpg")
        val baselineOnly = JSONObject(
            ManagedDownloadMetadataCodec.rewriteManagedMetadataReferences(
                restorableJson(baseline = true, overrides = false),
                references
            )
        ).getJSONObject("restorableMetadata")
        val overridesOnly = JSONObject(
            ManagedDownloadMetadataCodec.rewriteManagedMetadataReferences(
                restorableJson(baseline = false, overrides = true),
                references
            )
        ).getJSONObject("restorableMetadata")

        assertEquals("/new/cover.jpg", baselineOnly.getString("coverReference"))
        assertEquals("/new/cover.jpg", baselineOnly.getJSONObject("baseline").getString("coverReference"))
        assertFalse(baselineOnly.has("overrides"))
        assertEquals("/new/cover.jpg", overridesOnly.getJSONObject("overrides").getString("coverReference"))
        assertFalse(overridesOnly.has("baseline"))
    }

    @Test
    fun `finalized metadata requires an accepted embedding state`() {
        val finalized = ManagedDownloadMetadataCodec.finalizedDownloadedMetadataJson(
            """{"metadataEmbeddingState":"embedded_verified","name":"Song"}"""
        )

        assertEquals(true, JSONObject(requireNotNull(finalized)).getBoolean("downloadFinalized"))
        assertEquals("Song", JSONObject(finalized).getString("name"))
        assertNull(ManagedDownloadMetadataCodec.finalizedDownloadedMetadataJson("""{"metadataEmbeddingState":null}"""))
        assertNull(ManagedDownloadMetadataCodec.finalizedDownloadedMetadataJson("""{"name":"Song"}"""))
        assertNull(ManagedDownloadMetadataCodec.finalizedDownloadedMetadataJson("{broken"))
    }

    private fun restorableJson(baseline: Boolean, overrides: Boolean): String {
        val restorable = JSONObject().put("coverReference", "/old/cover.jpg")
        if (baseline) restorable.put("baseline", JSONObject().put("coverReference", "/old/cover.jpg"))
        if (overrides) restorable.put("overrides", JSONObject().put("coverReference", "/old/cover.jpg"))
        return JSONObject()
            .put("coverPath", "/old/cover.jpg")
            .put("restorableMetadata", restorable)
            .toString()
    }
}
