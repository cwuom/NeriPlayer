package moe.ouom.neriplayer.data.local.database.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDownloadUpgradeMetadataMergerTest {
    @Test
    fun payloadBuildsRestorableMetadataForManagedSidecar() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "name": "Song",
                  "artist": "Artist",
                  "album": "Album",
                  "mediaUri": "file:///music/song.flac",
                  "downloadTime": 1234,
                  "originalLyric": "base lyric",
                  "matchedLyric": "edited lyric"
                }
                """.trimIndent()
            ),
            existing = null,
            audioFileName = "song.flac"
        )

        assertEquals("file:song.flac", merged.getString("stableKey"))
        assertEquals("song.flac", merged.getString("audioFileName"))
        assertEquals(1234L, merged.getLong("downloadTimeMs"))
        val restorable = merged.getJSONObject("restorableMetadata")
        assertEquals("file:song.flac", restorable.getJSONObject("sourceIdentity").getString("stableKey"))
        assertEquals("base lyric", restorable.getJSONObject("baseline").getString("originalLyric"))
        assertEquals("edited lyric", restorable.getJSONObject("overrides").getString("originalLyric"))
    }

    @Test
    fun existingMetadataWinsForUserOverridesButPayloadFillsMissingFields() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "name": "Remote title",
                  "artist": "Remote artist",
                  "downloadTime": 1234,
                  "customName": "Remote override"
                }
                """.trimIndent()
            ),
            existing = JSONObject(
                """
                {
                  "stableKey": "file:song.flac",
                  "name": "Local title",
                  "customName": "Local override",
                  "matchedLyric": "local lyric"
                }
                """.trimIndent()
            ),
            audioFileName = "song.flac"
        )

        assertEquals("Local title", merged.getString("name"))
        assertEquals("Local override", merged.getString("customName"))
        assertEquals("local lyric", merged.getString("matchedLyric"))
        assertEquals("Remote artist", merged.getString("artist"))
        assertEquals(1234L, merged.getLong("downloadTimeMs"))
    }

    @Test
    fun nullPayloadValueDoesNotEraseExistingMetadata() {
        val merged = LegacyDownloadUpgradeMetadataMerger.merge(
            payload = JSONObject().apply {
                put("stableKey", "file:song.flac")
                put("customName", JSONObject.NULL)
            },
            existing = JSONObject().apply {
                put("stableKey", "file:song.flac")
                put("customName", "Local override")
            },
            audioFileName = "song.flac"
        )

        assertEquals("Local override", merged.getString("customName"))
        assertTrue(merged.has("restorableMetadata"))
        assertFalse(merged.isNull("stableKey"))
    }
}
