package moe.ouom.neriplayer.core.download.storage

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorageJsonCodecTest {

    @Test
    fun `stored entries skip non objects and entries without a name or reference`() {
        val entry = ManagedDownloadStorage.StoredEntry(
            name = "Song.flac",
            reference = "content://tree/song",
            mediaUri = "content://media/song",
            localFilePath = null,
            sizeBytes = 42L,
            lastModifiedMs = 7L
        )
        val array = ManagedDownloadStorageJsonCodec.storedEntriesToJsonArray(listOf(entry))
            .put("not an object")
            .put(JSONObject().put("name", "Orphan.flac"))
            .put(JSONObject().put("reference", "content://tree/nameless"))

        assertEquals(listOf(entry), ManagedDownloadStorageJsonCodec.storedEntriesFromJsonArray(array))
        assertEquals(
            emptyList<ManagedDownloadStorage.StoredEntry>(),
            ManagedDownloadStorageJsonCodec.storedEntriesFromJsonArray(null)
        )
    }

    @Test
    fun `fingerprint merge preserves other fields and replaces the previous fingerprint`() {
        val raw = JSONObject()
            .put("operationId", "op-1")
            .put("resumeFingerprint", JSONObject().put("etag", "\"old\""))
            .toString()

        val merged = ManagedDownloadStorageJsonCodec.mergeWorkingResumeFingerprint(
            raw,
            ManagedDownloadStorage.WorkingResumeFingerprint(etag = "\"new\"", expectedContentLength = 0L)
        )

        assertEquals("op-1", merged.getString("operationId"))
        assertEquals("\"new\"", merged.getJSONObject("resumeFingerprint").getString("etag"))
        assertFalse(merged.getJSONObject("resumeFingerprint").has("expectedContentLength"))
        assertEquals(
            ManagedDownloadStorage.WorkingResumeFingerprint(etag = "\"new\""),
            ManagedDownloadStorageJsonCodec.workingResumeFingerprintFromJson(merged.toString())
        )
    }

    @Test
    fun `fingerprint merge drops empty fingerprints and recovers from unreadable metadata`() {
        val raw = JSONObject()
            .put("operationId", "op-1")
            .put("resumeFingerprint", JSONObject().put("etag", "\"old\""))
            .toString()

        val cleared = ManagedDownloadStorageJsonCodec.mergeWorkingResumeFingerprint(raw, null)
        assertEquals("op-1", cleared.getString("operationId"))
        assertFalse(cleared.has("resumeFingerprint"))

        val emptyFingerprint = ManagedDownloadStorageJsonCodec.mergeWorkingResumeFingerprint(
            raw,
            ManagedDownloadStorage.WorkingResumeFingerprint(sourceUrl = " ", expectedContentLength = -1L)
        )
        assertFalse(emptyFingerprint.has("resumeFingerprint"))

        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(sourceUrl = "https://example.com/a")
        listOf(null, "  ", "{broken").forEach { unreadable ->
            val rebuilt = ManagedDownloadStorageJsonCodec.mergeWorkingResumeFingerprint(unreadable, fingerprint)
            assertEquals(listOf("resumeFingerprint"), rebuilt.keys().asSequence().toList())
            assertEquals("https://example.com/a", rebuilt.getJSONObject("resumeFingerprint").getString("sourceUrl"))
        }
    }

    @Test
    fun `cancelled keys ignore blank and null entries`() {
        assertEquals(emptySet<String>(), ManagedDownloadStorageJsonCodec.parseCancelledDownloadKeysPayload("{}"))

        val raw = JSONObject()
            .put("keys", JSONArray().put("b").put(" ").put(JSONObject.NULL).put("a"))
            .toString()
        assertEquals(setOf("a", "b"), ManagedDownloadStorageJsonCodec.parseCancelledDownloadKeysPayload(raw))

        val serialized = ManagedDownloadStorageJsonCodec.serializeCancelledDownloadKeysPayload(
            setOf("z", "", "m"),
            updatedAtMs = 9L
        )
        assertEquals(listOf("m", "z"), JSONObject(serialized).getJSONArray("keys").let { keys ->
            (0 until keys.length()).map(keys::getString)
        })
        assertEquals(setOf("m", "z"), ManagedDownloadStorageJsonCodec.parseCancelledDownloadKeysPayload(serialized))
    }

    @Test
    fun `working resume song keeps only identifiable named netease artists`() {
        val raw = JSONObject()
            .put("id", 7L)
            .put("name", "Song")
            .put(
                "neteaseArtists",
                JSONArray()
                    .put(JSONObject().put("id", 1L).put("name", "First"))
                    .put("not an object")
                    .put(JSONObject().put("name", "No id"))
                    .put(JSONObject().put("id", 3L).put("name", " "))
                    .put(JSONObject().put("id", 4L).put("name", "Second"))
            )
            .toString()

        val song = ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(raw)

        assertEquals(
            listOf(NeteaseArtistSummary(id = 1L, name = "First"), NeteaseArtistSummary(id = 4L, name = "Second")),
            song?.neteaseArtists
        )
        val withoutArtists = ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(
            JSONObject().put("id", 8L).put("name", "Other").toString()
        )
        assertTrue(withoutArtists?.neteaseArtists.orEmpty().isEmpty())
        assertEquals("Other", withoutArtists?.name)
    }
}
