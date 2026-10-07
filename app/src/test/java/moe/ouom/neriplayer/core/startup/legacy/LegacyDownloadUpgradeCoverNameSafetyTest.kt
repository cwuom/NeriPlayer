package moe.ouom.neriplayer.core.startup.legacy

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDownloadUpgradeCoverNameSafetyTest {
    private val managedCover = ManagedDownloadStorage.StoredEntry(
        name = "Song.jpg",
        reference = "content://managed/Covers/document-1",
        mediaUri = "content://media/document-1",
        localFilePath = "/managed/Covers/Song.jpg",
        sizeBytes = 64L,
        lastModifiedMs = 1L
    )
    private val covers = mapOf(managedCover.name to managedCover)

    @Test
    fun `unsafe persisted cover names fall back to the reference file name`() {
        listOf("   ", ".", "..", "nested/Song.jpg", "nested\\Song.jpg").forEach { unsafeName ->
            assertEquals(
                unsafeName,
                managedCover,
                resolveLegacyManagedCoverEntry(
                    reference = "file:///storage/emulated/0/Old/Covers/Song.jpg",
                    persistedFileName = unsafeName,
                    coverEntriesByName = covers
                )
            )
            assertNull(
                unsafeName,
                resolveLegacyManagedCoverEntry(
                    reference = null,
                    persistedFileName = unsafeName,
                    coverEntriesByName = covers
                )
            )
        }
    }

    @Test
    fun `persisted cover names match case insensitively after trimming`() {
        assertEquals(
            managedCover,
            resolveLegacyManagedCoverEntry(
                reference = "content://old-provider/stale",
                persistedFileName = "  SONG.JPG ",
                coverEntriesByName = covers
            )
        )
    }

    @Test
    fun `cover file name hint rejects unsafe names after the Covers marker`() {
        assertEquals("Song.jpg", legacyManagedCoverFileNameHint("/storage/Music/Covers/Song.jpg?size=2#top"))
        assertNull(legacyManagedCoverFileNameHint("/storage/Music/Covers/"))
        assertNull(legacyManagedCoverFileNameHint("/storage/Music/Covers/."))
        assertNull(legacyManagedCoverFileNameHint("/storage/Music/Covers/.."))
        assertNull(legacyManagedCoverFileNameHint("/storage/Music/Covers/nested/Song.jpg"))
        assertNull(legacyManagedCoverFileNameHint("https://example.test/Covers/Song.jpg"))
    }

    @Test
    fun `structural metadata comparison treats JSON nulls and missing array values alike`() {
        val existing = JSONObject()
            .put("cover", JSONObject.NULL)
            .put("tags", JSONArray().put("a").put(null as Any?))
        val upgraded = JSONObject("{\"tags\":[\"a\",null],\"cover\":null}")

        assertTrue(legacyMetadataStructurallyEquals(existing, upgraded))
        assertFalse(legacyMetadataStructurallyEquals(null, upgraded))
    }

    @Test
    fun `structural metadata comparison keeps booleans distinct from strings`() {
        val upgraded = JSONObject("{\"enabled\":true,\"tags\":[\"a\",\"b\"]}")

        assertTrue(
            legacyMetadataStructurallyEquals(
                JSONObject().put("tags", JSONArray().put("a").put("b")).put("enabled", true),
                upgraded
            )
        )
        assertFalse(
            legacyMetadataStructurallyEquals(
                JSONObject("{\"enabled\":\"true\",\"tags\":[\"a\",\"b\"]}"),
                upgraded
            )
        )
        assertFalse(
            legacyMetadataStructurallyEquals(
                JSONObject("{\"enabled\":true,\"tags\":[\"b\",\"a\"]}"),
                upgraded
            )
        )
    }

    @Test
    fun `structural metadata comparison reads other values through their text`() {
        val existing = JSONObject().put("quality", StringBuilder("lossless"))

        assertTrue(legacyMetadataStructurallyEquals(existing, JSONObject("{\"quality\":\"lossless\"}")))
        assertFalse(legacyMetadataStructurallyEquals(existing, JSONObject("{\"quality\":\"standard\"}")))
    }
}
