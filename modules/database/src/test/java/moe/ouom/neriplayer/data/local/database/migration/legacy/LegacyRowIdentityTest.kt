package moe.ouom.neriplayer.data.local.database.migration.legacy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyRowIdentityTest {
    private val lookup = LegacyCatalogLookup(
        stableKeysByReference = mapOf("content://first" to setOf("first"), "content://second" to setOf("second")),
        stableKeysByNormalizedName = mapOf("song.flac" to setOf("first", "second"), "unique.flac" to setOf("unique"))
    )

    @Test
    fun `exact reference wins over an ambiguous basename`() {
        assertEquals("first", findCatalogStableKeyForSnapshotEntry(lookup, " content://first ", null, "song.flac"))
        assertNull(findCatalogStableKeyForSnapshotEntry(lookup, "content://first", "content://second", "unique.flac"))
        assertNull(findCatalogStableKeyForAudioName(lookup, "song.flac"))
        assertEquals("unique", findCatalogStableKeyForAudioName(lookup, "content://root/UNIQUE.FLAC"))
        assertEquals("unique", findCatalogStableKeyForSnapshotEntry(lookup, null, null, "unique.flac"))
        assertNull(findCatalogStableKeyForSnapshotEntry(lookup, null, null, null))
    }

    @Test
    fun `explicit stable key takes priority over inferred catalog identity`() {
        val cursor = legacyRow("stable_key" to " existing ", "id" to 42L, "file_path" to "/song.flac")
        assertEquals("existing", resolveLegacyStableKey("downloaded_song_catalog", cursor, lookup))
    }

    @Test
    fun `catalog derives local identity from decoded file URI or content reference`() {
        assertEquals("42|__local_files__|/sdcard/a b.flac",
            deriveCatalogStableKey(legacyRow("id" to 42L, "file_path" to "file:///sdcard/a%20b.flac")))
        assertEquals("42|__local_files__|content://audio/42",
            deriveCatalogStableKey(legacyRow("id" to 42L, "file_path" to "https://remote", "media_uri" to "content://audio/42")))
        assertNull(deriveCatalogStableKey(legacyRow("id" to 0L, "file_path" to "/song.flac")))
        assertNull(deriveCatalogStableKey(legacyRow("id" to 42L, "file_path" to "song.flac")))
        assertNull(deriveCatalogStableKey(legacyRow("file_path" to "/song.flac")))
    }

    @Test
    fun `snapshot identity preserves unmatched rows and their original scope`() {
        assertEquals("legacy-snapshot:root:entry", deriveSnapshotEntryStableKey(legacyRow("root_key" to "root", "entry_key" to "entry")))
        assertEquals("legacy-snapshot:root:content://audio", deriveSnapshotEntryStableKey(legacyRow("root_key" to "root", "reference" to "content://audio")))
        assertEquals("legacy-snapshot:root:song.flac", deriveSnapshotEntryStableKey(legacyRow("root_key" to "root", "name" to "song.flac")))
        assertNull(deriveSnapshotEntryStableKey(legacyRow("name" to "song.flac")))
        assertNull(deriveSnapshotEntryStableKey(legacyRow("root_key" to "root")))
        assertEquals("unique", resolveLegacyStableKey("download_snapshot_metadata", legacyRow("audio_name" to "unique.flac"), lookup))
        assertEquals("first", resolveLegacyStableKey("download_snapshot_entry", legacyRow("reference" to "content://first"), lookup))
        assertNull(resolveLegacyStableKey("download_cancelled_key", legacyRow("stable_key" to null), lookup))
        assertEquals("42|__local_files__|/song.flac", resolveLegacyStableKey("downloaded_song_catalog", legacyRow("id" to 42L, "file_path" to "/song.flac"), lookup))
        assertEquals("legacy-snapshot:root:entry", resolveLegacyStableKey("download_snapshot_entry", legacyRow("root_key" to "root", "entry_key" to "entry"), lookup))
        assertNull(resolveLegacyStableKey("download_snapshot_metadata", legacyRow("audio_name" to "unknown.flac"), lookup))
    }

    @Test
    fun `fallback keys are deterministic for every historical table`() {
        val cases = listOf(
            Triple("download_pending_queue", "queued_at_ms", "123"),
            Triple("download_cancelled_key", "cancelled_at_ms", "123"),
            Triple("downloaded_song_catalog", "catalog_key", "catalog"),
            Triple("download_snapshot_entry", "root_key", "root"),
            Triple("download_snapshot_metadata", "audio_name", "song.flac"),
            Triple("managed_download_artifact", "artifact_id", "artifact"),
            Triple("unknown_table", "payload", "unknown")
        )
        for ((table, column, value) in cases) {
            assertEquals("legacy:$table:$column=$value", fallbackLegacyStableKey(table, legacyRow(column to value)))
        }
        assertEquals("legacy:download_snapshot_metadata:row-0", fallbackLegacyStableKey("download_snapshot_metadata", legacyRow("audio_name" to null)))
    }

    @Test
    fun `cursor conversion preserves numeric blob and null values without the pagination marker`() {
        val cursor = legacyRow(LEGACY_ROW_ID_ALIAS to 8L, "integer" to 42L, "float" to 1.25, "blob" to byteArrayOf(0, 127, -1), "text" to " text ", "missing" to null)
        val row = rowToJson(cursor, cursor.columnNames)
        assertFalse(row.has(LEGACY_ROW_ID_ALIAS))
        assertEquals(42L, row.getLong("integer"))
        assertEquals(1.25, row.getDouble("float"), 0.0)
        assertEquals("\u0000\u007f\u00ff", row.getString("blob"))
        assertEquals(" text ", row.getString("text"))
        assertEquals(JSONObject.NULL, row.get("missing"))
        assertEquals("text", cursorString(cursor, "text"))
        assertNull(cursorString(cursor, "missing"))
        assertNull(cursorString(cursor, "absent"))
        assertNull(cursorLong(cursor, "missing"))
        assertNull(cursorLong(cursor, "absent"))
        assertNull(cursorString(legacyRow("blank" to "  "), "blank"))
    }

    @Test
    fun `local reference normalization leaves opaque content IDs and invalid file URIs intact`() {
        assertEquals("content://provider/tree/root/document/root%3Asong", normalizeLegacyLocalReference("content://provider/tree/root/document/root%3Asong"))
        assertEquals("file://invalid path", normalizeLegacyLocalReference("file://invalid path"))
        assertEquals("file://", normalizeLegacyLocalReference("file://"))
        assertNull(normalizeLegacyBasename(" "))
        assertNull(normalizeLegacyBasename("content://"))
        assertNull(findCatalogStableKeyForAudioName(lookup, null))
    }
}
