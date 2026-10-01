package moe.ouom.neriplayer.data.local.database.migration.legacy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyPayloadPersistenceTest {
    @Test
    fun `batched writes retain every payload across the SQL parameter boundary`() {
        val fixture = LegacyMigrationDatabase()
        val writes = linkedMapOf<String, String>()
        repeat(97) { writes["key-$it"] = "payload-$it" }

        flushPayloadWrites(fixture.database, writes)

        assertEquals(listOf(48, 48, 1), fixture.inserts)
        assertEquals(97, fixture.payloads.size)
        assertEquals("payload-96", fixture.payloads["key-96"])
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `failed persistence does not acknowledge or remove unwritten payloads`() {
        val fixture = LegacyMigrationDatabase().apply { writeFailure = IllegalStateException("disk failure") }
        val writes = linkedMapOf("key" to "payload")

        assertThrows(IllegalStateException::class.java) { flushPayloadWrites(fixture.database, writes) }

        assertEquals(mapOf("key" to "payload"), writes)
        assertTrue(fixture.payloads.isEmpty())
    }

    @Test
    fun `cache prefers pending writes and reuses modified JSON until eviction`() {
        val fixture = LegacyMigrationDatabase()
        fixture.payloads["key"] = "{\"source\":\"persisted\"}"
        val cache = LegacyPayloadCache(fixture.database)
        assertEquals("pending", cache.getOrLoad("key", mapOf("key" to "{\"source\":\"pending\"}")).getString("source"))
        cache.put("key", JSONObject().put("source", "updated"))
        assertEquals("updated", cache.getOrLoad("key", emptyMap()).getString("source"))
        repeat(97) { cache.getOrLoad("other-$it", emptyMap()) }
        assertEquals("persisted", cache.getOrLoad("key", emptyMap()).getString("source"))
    }

    @Test
    fun `malformed legacy payload cannot prevent other rows being recovered`() {
        val fixture = LegacyMigrationDatabase()
        fixture.payloads["bad"] = "not JSON"
        val cache = LegacyPayloadCache(fixture.database)
        assertEquals(0, cache.getOrLoad("bad", emptyMap()).length())
        assertEquals(0, cache.getOrLoad("missing", emptyMap()).length())
    }

    @Test
    fun `copy combines queue catalog metadata and sidecars under their original identity`() {
        val fixture = LegacyMigrationDatabase(mapOf(
            "download_pending_queue" to listOf(mapOf("stable_key" to "key", "name" to "queued")),
            "download_cancelled_key" to listOf(mapOf("stable_key" to "key", "cancelled_at_ms" to 9L)),
            "downloaded_song_catalog" to listOf(mapOf("stable_key" to "key", "file_path" to "/music/song.flac")),
            "download_snapshot_metadata" to listOf(mapOf("root_key" to "root", "audio_name" to "song.flac")),
            "download_snapshot_entry" to listOf(mapOf("reference" to "/music/song.flac", "name" to "song.flac")),
            "managed_download_artifact" to listOf(mapOf("stable_key" to "key", "artifact_id" to "artifact"))
        ))

        copyV15DownloadPayload(fixture.database)

        assertEquals(setOf("key"), fixture.payloads.keys)
        val payload = JSONObject(fixture.payloads.getValue("key"))
        assertEquals("key", payload.getString("stableKey"))
        assertEquals("queued", payload.getString("name"))
        assertEquals("song.flac", payload.getString("audioFileName"))
        assertEquals(1, payload.getJSONArray("download_snapshot_entries").length())
        assertEquals("artifact", payload.getJSONObject("managed_download_artifact").getString("artifact_id"))
    }

    @Test
    fun `existing payload survives copy while distinct bytes are kept as conflicts`() {
        val fixture = LegacyMigrationDatabase(mapOf(
            "downloaded_song_catalog" to listOf(mapOf("stable_key" to "key", "content_hash" to "new"))
        ))
        fixture.payloads["key"] = "{\"stableKey\":\"key\",\"customName\":\"edited\",\"downloaded_song_catalog\":{\"content_hash\":\"old\"}}"

        copyV15DownloadPayload(fixture.database)

        val payload = JSONObject(fixture.payloads.getValue("key"))
        assertEquals("edited", payload.getString("customName"))
        assertEquals("old", payload.getJSONObject("downloaded_song_catalog").getString("content_hash"))
        assertEquals("new", payload.getJSONArray("legacyConflicts").getJSONObject(0).getJSONObject("duplicate").getString("content_hash"))
    }

    @Test
    fun `catalog lookup ignores unsupported schemas and derives missing stable keys`() {
        assertTrue(buildLegacyCatalogLookup(LegacyMigrationDatabase().database).stableKeysByReference.isEmpty())
        assertTrue(buildLegacyCatalogLookup(LegacyMigrationDatabase(mapOf("downloaded_song_catalog" to listOf(mapOf("unknown" to 1L)))).database)
            .stableKeysByReference.isEmpty())
        val fixture = LegacyMigrationDatabase(mapOf("downloaded_song_catalog" to listOf(
            mapOf("id" to 42L, "file_path" to "/song.flac", "media_uri" to "content://song"),
            mapOf("id" to 0L, "file_path" to "/bad.flac"),
            mapOf("stable_key" to "root", "file_path" to "/"),
            mapOf("stable_key" to "blank", "file_path" to "  ")
        )))
        val lookup = buildLegacyCatalogLookup(fixture.database)
        assertEquals(setOf("42|__local_files__|/song.flac"), lookup.stableKeysByReference["content://song"])
        assertFalse(lookup.stableKeysByNormalizedName.containsKey("bad.flac"))
        assertFalse(lookup.stableKeysByReference.containsKey("  "))
        assertEquals(setOf("root"), lookup.stableKeysByReference["/"])
    }

    @Test
    fun `copy flushes full batches and remains idempotent for verified records`() {
        val rows = (1..97).map { mapOf("stable_key" to "key-$it", "content_hash" to "hash-$it") }
        val fixture = LegacyMigrationDatabase(mapOf("downloaded_song_catalog" to rows))
        copyV15DownloadPayload(fixture.database)
        assertEquals(97, fixture.payloads.size)
        assertEquals(listOf(48, 48, 1), fixture.inserts)
        fixture.inserts.clear()
        copyV15DownloadPayload(fixture.database)
        assertTrue(fixture.inserts.isEmpty())
    }

    @Test
    fun `copy repairs missing null or mismatched payload identity without losing custom metadata`() {
        for (identity in listOf("", ",\"stableKey\":null", ",\"stableKey\":\"different\"")) {
            val fixture = LegacyMigrationDatabase(mapOf("download_cancelled_key" to listOf(mapOf("stable_key" to "key"))))
            fixture.payloads["key"] = "{\"customName\":\"edited\"$identity}"
            copyV15DownloadPayload(fixture.database)
            val payload = JSONObject(fixture.payloads.getValue("key"))
            assertEquals("key", payload.getString("stableKey"))
            assertEquals("edited", payload.getString("customName"))
        }
    }
}
