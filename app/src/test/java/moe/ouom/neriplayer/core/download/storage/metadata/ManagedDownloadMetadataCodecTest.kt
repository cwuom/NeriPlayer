package moe.ouom.neriplayer.core.download.storage.metadata

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
