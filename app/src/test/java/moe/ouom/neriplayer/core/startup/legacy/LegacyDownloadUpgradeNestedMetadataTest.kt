package moe.ouom.neriplayer.core.startup.legacy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyDownloadUpgradeNestedMetadataTest {

    @Test
    fun `metadata json stored as an object overrides the payload and keeps unknown fields`() {
        val merged = merge(
            JSONObject()
                .put("name", "Payload name")
                .put("metadataJson", JSONObject().put("name", "Object name").put("customTag", "object"))
        )

        assertEquals("Object name", merged.getString("name"))
        assertEquals("object", merged.getString("customTag"))
    }

    @Test
    fun `metadata json stored as a string is parsed like a nested object`() {
        val merged = merge(
            JSONObject()
                .put("name", "Payload name")
                .put("metadataJson", """{"name":"String name","customTag":"string"}""")
        )

        assertEquals("String name", merged.getString("name"))
        assertEquals("string", merged.getString("customTag"))
    }

    @Test
    fun `unreadable or blank metadata json falls back to known payload fields only`() {
        listOf("{not json", "   ").forEach { metadataJson ->
            val merged = merge(
                JSONObject()
                    .put("name", "Payload name")
                    .put("customTag", "payload")
                    .put("metadataJson", metadataJson)
            )

            assertEquals("Payload name", merged.getString("name"))
            assertFalse(merged.has("customTag"))
            assertFalse(merged.has("metadataJson"))
        }
    }

    @Test
    fun `existing restorable metadata is carried over without null fields`() {
        val merged = merge(
            JSONObject().put(
                "metadata",
                JSONObject(
                    """
                        {
                            "stableKey": "netease:42",
                            "restorableMetadata": {
                                "sourceIdentity": {"origin": "netease"},
                                "overrides": {"title": "Custom title"},
                                "note": null
                            }
                        }
                    """
                )
            )
        )

        val restorable = merged.getJSONObject("restorableMetadata")
        assertEquals("netease", restorable.getJSONObject("sourceIdentity").getString("origin"))
        assertEquals("netease:42", restorable.getJSONObject("sourceIdentity").getString("stableKey"))
        assertEquals("Custom title", restorable.getJSONObject("overrides").getString("title"))
        assertFalse(restorable.has("note"))
    }

    private fun merge(payload: JSONObject): JSONObject =
        LegacyDownloadUpgradeMetadataMerger.merge(payload, existing = null, audioFileName = "song.flac")
}
