package moe.ouom.neriplayer.data.local.database.migration.legacy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPayloadMergeTest {
    @Test
    fun `first row supplies missing aliases without replacing existing metadata`() {
        val payload = JSONObject().put("customName", "edited").put("audioFileName", JSONObject.NULL)
        val row = JSONObject().put("custom_name", "original").put("audio_name", "song.flac")

        assertTrue(mergeLegacyRow(payload, "download_snapshot_metadata", "key", row))

        assertEquals("edited", payload.getString("customName"))
        assertEquals("song.flac", payload.getString("audioFileName"))
        assertEquals("song.flac", payload.getString("audioName"))
        assertEquals("original", payload.getJSONObject("download_snapshot_metadata").getString("custom_name"))
    }

    @Test
    fun `matching verified hashes retain the original row without a conflict`() {
        val payload = JSONObject()
        val original = JSONObject().put("content_hash", "hash").put("file_path", "/old.flac")
        mergeLegacyRow(payload, "downloaded_song_catalog", "key", original)

        assertFalse(mergeLegacyRow(payload, "downloaded_song_catalog", "key",
            JSONObject().put("contentHash", " hash ").put("file_path", "/copy.flac")))

        assertEquals("/old.flac", payload.getJSONObject("downloaded_song_catalog").getString("file_path"))
        assertFalse(payload.has("legacyConflicts"))
    }

    @Test
    fun `different verified bytes preserve both records and their references`() {
        val payload = JSONObject()
        mergeLegacyRow(payload, "downloaded_song_catalog", "key",
            JSONObject().put("content_hash", "first").put("media_uri", "content://old"))
        assertTrue(mergeLegacyRow(payload, "downloaded_song_catalog", "key",
            JSONObject().put("content_hash", "second").put("file_path", "/new.flac")))

        val conflict = payload.getJSONArray("legacyConflicts").getJSONObject(0)
        assertEquals("SAME_STABLE_KEY_DIFFERENT_BYTES", conflict.getString("reason"))
        assertEquals("content://old", conflict.getString("firstReference"))
        assertEquals("/new.flac", conflict.getString("duplicateReference"))
        assertEquals("first", conflict.getJSONObject("previous").getString("content_hash"))
        assertEquals("second", conflict.getJSONObject("duplicate").getString("content_hash"))
        assertEquals("first", payload.getJSONObject("downloaded_song_catalog").getString("content_hash"))
    }

    @Test
    fun `matching paths do not prove matching bytes when either hash is missing`() {
        for (hashes in listOf(null to null, "first" to null, null to "second")) {
            val payload = JSONObject()
            val first = JSONObject().put("file_path", "/same.flac").put("content_hash", hashes.first)
            val second = JSONObject().put("file_path", "/same.flac").put("content_hash", hashes.second)
            mergeLegacyRow(payload, "downloaded_song_catalog", "key", first)

            assertTrue(mergeLegacyRow(payload, "downloaded_song_catalog", "key", second))
            assertEquals("SAME_STABLE_KEY_BYTES_UNVERIFIED",
                payload.getJSONArray("legacyConflicts").getJSONObject(0).getString("reason"))
        }
    }

    @Test
    fun `snapshot entry replay is idempotent and distinct entries survive`() {
        val payload = JSONObject()
        val audio = JSONObject().put("entry_key", "audio").put("reference", "content://audio")
        val cover = JSONObject().put("entry_key", "cover").put("reference", "content://cover")

        assertTrue(mergeLegacyRow(payload, "download_snapshot_entry", "key", audio))
        assertTrue(mergeLegacyRow(payload, "download_snapshot_entry", "key", cover))
        assertFalse(mergeLegacyRow(payload, "download_snapshot_entry", "key", JSONObject(cover.toString())))
        assertEquals(2, payload.getJSONArray("download_snapshot_entries").length())
        assertEquals("audio", payload.getJSONArray("download_snapshot_entries").getJSONObject(0).getString("entry_key"))
    }

    @Test
    fun `later conflicts append rather than replace earlier evidence`() {
        val payload = JSONObject()
        for (hash in listOf("first", "second", "third")) {
            mergeLegacyRow(payload, "downloaded_song_catalog", "key", JSONObject().put("content_hash", hash))
        }
        assertEquals(2, payload.getJSONArray("legacyConflicts").length())
        assertEquals("hash:third", payload.getJSONArray("legacyConflicts").getJSONObject(1).getString("duplicateFingerprint"))
    }

    @Test
    fun `aliases fill null values but preserve non-null values including audio file name`() {
        val payload = JSONObject().put("audioFileName", "edited.flac").put("durationMs", JSONObject.NULL)
        addCamelCaseAliases(payload, JSONObject().put("audio_name", "old.flac").put("duration_ms", 123).put("_", "ignored"))
        addCamelCaseAliases(payload, JSONObject().put("duration_ms", 456))
        assertEquals("edited.flac", payload.getString("audioFileName"))
        assertEquals(123, payload.getInt("durationMs"))
        assertFalse(payload.has(""))
        assertEquals("durationMs", snakeToCamel("duration_ms"))
        assertEquals("Name", snakeToCamel("_name"))
        assertEquals("plain", snakeToCamel("plain"))
    }

    @Test
    fun `reference lookup skips JSON null and blank values in priority order`() {
        assertEquals("/local.flac", legacyReference(JSONObject()
            .put("media_uri", JSONObject.NULL).put("mediaUri", " ").put("file_path", " /local.flac ")))
        assertEquals("content://first", legacyReference(JSONObject()
            .put("media_uri", "content://first").put("audio_reference", "content://second")))
        assertNull(legacyReference(JSONObject()))
        assertNull(legacyByteFingerprint(JSONObject().put("content_hash", " ")))
    }
}
