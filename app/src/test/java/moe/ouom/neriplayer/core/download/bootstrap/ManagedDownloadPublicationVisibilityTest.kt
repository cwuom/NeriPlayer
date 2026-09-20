package moe.ouom.neriplayer.core.download.bootstrap

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.operation.content.preserveAudioPublicationReceipt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadPublicationVisibilityTest {
    @Test
    fun `publishing formal audio stays out of complete and preview rebuilds`() {
        val snapshot = snapshot(metadata(publishing = true))

        assertTrue(ManagedLibraryRebuilder.plan(snapshot).isEmpty())
        assertTrue(ManagedLibraryRebuilder.plan(snapshot.copy(rootEntriesComplete = false), true).isEmpty())
    }

    @Test
    fun `snapshot metadata roundtrip retains publishing visibility guard`() {
        val encoded = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(metadata(publishing = true))
        val restored = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataFromJsonObject(encoded)

        assertTrue(ManagedLibraryRebuilder.plan(snapshot(restored)).isEmpty())
    }

    @Test
    fun `sealed formal audio stays visible when pending deletion failed`() {
        val snapshot = snapshot(metadata(publishing = false)).let { current ->
            val pending = current.audioEntries.single().copy(
                name = "song.mp3.npdl_pending.operation.pending",
                reference = "/library/.tmp/song.mp3.npdl_pending.operation.pending"
            )
            current.copy(pendingAudioEntries = listOf(pending))
        }

        assertEquals(listOf("song.mp3"), ManagedLibraryRebuilder.plan(snapshot).map { it.audio.name })
    }

    @Test
    fun `publication marker does not block metadata finalization recovery`() {
        assertTrue(isFinalizedDownloadedMetadata(metadata(publishing = true)))
    }

    @Test
    fun `same owner metadata write preserves marker before receipt exists`() {
        val merged = JSONObject(preserveAudioPublicationReceipt(json().put("audioPublicationPending", true).toString(), json().toString()))

        assertTrue(merged.optBoolean("audioPublicationPending"))
    }

    @Test
    fun `same owner metadata write preserves marker and receipt`() {
        val previous = json().put("audioPublicationPending", true)
            .put("audioPublicationReceipt", JSONObject().put("sha256", "digest"))
        val merged = JSONObject(preserveAudioPublicationReceipt(previous.toString(), json().toString()))

        assertTrue(merged.optBoolean("audioPublicationPending"))
        assertEquals("digest", merged.getJSONObject("audioPublicationReceipt").getString("sha256"))
    }

    @Test
    fun `different operation never inherits publication state`() {
        val previous = json().put("audioPublicationPending", true)
            .put("audioPublicationReceipt", JSONObject().put("sha256", "digest"))
        val merged = JSONObject(preserveAudioPublicationReceipt(previous.toString(), json().put("operationId", "another-operation").toString()))

        assertFalse(merged.has("audioPublicationPending"))
        assertFalse(merged.has("audioPublicationReceipt"))
    }

    @Test
    fun `explicit sealed marker is not restored to pending by receipt merge`() {
        val previous = json().put("audioPublicationPending", true)
            .put("audioPublicationReceipt", JSONObject().put("sha256", "digest"))
        val merged = JSONObject(preserveAudioPublicationReceipt(
            previous.toString(), json().put("audioPublicationPending", false).toString(),
            allowPublicationCompletion = true
        ))

        assertFalse(merged.getBoolean("audioPublicationPending"))
        assertEquals("digest", merged.getJSONObject("audioPublicationReceipt").getString("sha256"))
    }

    @Test
    fun `ordinary metadata false cannot clear an unfinished publication`() {
        val merged = JSONObject(preserveAudioPublicationReceipt(
            json().put("audioPublicationPending", true).toString(),
            json().put("audioPublicationPending", false).toString()
        ))

        assertTrue(merged.getBoolean("audioPublicationPending"))
    }

    private fun json(): JSONObject = JSONObject()
        .put("stableKey", "song-stable-key")
        .put("operationId", "operation")
        .put("downloadFinalized", true)
        .put("metadataEmbeddingState", "EMBEDDED_VERIFIED")

    private fun metadata(publishing: Boolean) = ManagedDownloadStorageJsonCodec.downloadedAudioMetadataFromJsonObject(
        json().put("audioPublicationPending", publishing)
    )

    private fun snapshot(metadata: ManagedDownloadStorage.DownloadedAudioMetadata): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val audio = ManagedDownloadStorage.StoredEntry("song.mp3", "/library/song.mp3", "/library/song.mp3", "/library/song.mp3", 1L, 1L)
        return ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audio),
            audioEntriesByLookupKey = mapOf(audio.reference to audio),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = mapOf(audio.name to metadata),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap(),
            knownReferences = setOf(audio.reference)
        )
    }
}
