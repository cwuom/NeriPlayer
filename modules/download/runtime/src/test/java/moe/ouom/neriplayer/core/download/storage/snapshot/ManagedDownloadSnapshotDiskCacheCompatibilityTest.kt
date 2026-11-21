package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import moe.ouom.neriplayer.core.download.storage.SNAPSHOT_CACHE_FILE_NAME
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedDownloadSnapshotDiskCacheCompatibilityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)

    @Before
    fun setUp() {
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
    }

    @Test
    fun `legacy cache restores absent optional fields with their original defaults`() {
        val audioName = "Song.flac"
        val reference = "content://provider/song"
        val metadata = JSONObject().put("stableKey", "legacy-song")
        val payload = JSONObject()
            .put("cacheKey", "tree:legacy")
            .put("audioEntries", JSONArray().put(storedEntry(audioName, reference)))
            .put("metadataByAudioName", JSONObject().put(audioName, metadata))
        cacheFile(SNAPSHOT_CACHE_FILE_NAME).writeText(payload.toString())

        val (key, snapshot) = checkNotNull(ManagedDownloadSnapshotDiskCache.restore(context, "tree:legacy"))

        val audio = snapshot.audioEntries.single()
        assertEquals("tree:legacy", key)
        assertEquals(audioName, audio.name)
        assertEquals(reference, audio.mediaUri)
        assertNull(audio.localFilePath)
        assertFalse(audio.sizeKnown)
        assertEquals(listOf(audio), snapshot.audioEntriesByStableKey["legacy-song"])
        assertTrue(snapshot.rootEntriesComplete)
        assertTrue(snapshot.sidecarEntriesComplete)
        assertTrue(snapshot.pendingAudioEntries.isEmpty())
        assertTrue(snapshot.pendingMetadataByAudioName.isEmpty())
    }

    @Test
    fun `current cache restores escaped pending metadata from the previous writer`() {
        val audioName = "夜曲 \"回声\" \\ mix.flac"
        val pendingFileName = "$audioName.npdl_pending.op.pending"
        val pendingFile = storedEntry(pendingFileName, "content://provider/pending")
        val lyric = "[00:01.20]第一行 \"回声\"\n[00:02.40]第二行 \\ 音樂"
        val pendingMetadata = JSONObject()
            .put("originalLyric", lyric)
            .put("audioPublicationPending", true)
        val payload = JSONObject()
            .put("cacheKey", "tree:current")
            .put("pendingAudioEntries", JSONArray().put(pendingFile))
            .put("pendingMetadataByAudioName", JSONObject().put(audioName, pendingMetadata))
            .put("rootEntriesComplete", false)
            .put("sidecarEntriesComplete", false)
        cacheFile(CURRENT_SNAPSHOT_CACHE_FILE_NAME).writeText(payload.toString())

        val (_, snapshot) = checkNotNull(ManagedDownloadSnapshotDiskCache.restore(context, "tree:current"))

        assertEquals(pendingFileName, snapshot.pendingAudioEntries.single().name)
        assertTrue(snapshot.audioEntries.isEmpty())
        val restoredMetadata = checkNotNull(snapshot.pendingMetadataByAudioName[audioName])
        assertEquals(lyric, restoredMetadata.originalLyric)
        assertTrue(restoredMetadata.audioPublicationPending)
        assertFalse(snapshot.rootEntriesComplete)
        assertFalse(snapshot.sidecarEntriesComplete)
    }

    @Test
    fun `truncated current cache does not restore a valid legacy cache`() {
        val payload = JSONObject().put("cacheKey", "tree:test").toString()
        cacheFile(SNAPSHOT_CACHE_FILE_NAME).writeText(payload)
        assertEquals("tree:test", ManagedDownloadSnapshotDiskCache.restore(context, "tree:test")?.first)

        cacheFile(CURRENT_SNAPSHOT_CACHE_FILE_NAME).writeText(payload.dropLast(1))

        assertNull(ManagedDownloadSnapshotDiskCache.restore(context, "tree:test"))
    }

    private fun storedEntry(name: String, reference: String): JSONObject =
        JSONObject().put("name", name).put("reference", reference)

    private fun cacheFile(name: String) = temporaryFolder.root.resolve(name)
}
