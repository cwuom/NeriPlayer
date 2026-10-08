package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.testing.FakeLibraryEntry
import moe.ouom.neriplayer.data.testing.FakeLibrarySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedEditableMetadataSelectionTest {
    private val audio = FakeLibraryEntry(
        name = "Song.flac",
        reference = "content://tree/document/song",
        mediaUri = "content://media/external/audio/media/9"
    )
    private val metadata = FakeLibraryEntry(name = "Song.flac$LOCAL_METADATA_SUFFIX")

    @Test
    fun `the indexed metadata sidecar is selected for the audio document or its media uri`() {
        listOf(audio.reference, audio.mediaUri).forEach { reference ->
            assertEquals(metadata.reference, select(snapshot(lookupKey = reference), reference))
        }
    }

    @Test
    fun `incomplete or missing snapshots have no cached metadata`() {
        assertNull(LocalMediaSupport.selectCachedEditableMetadataReference(null, audio.reference, "Song.flac"))
        assertNull(select(snapshot().copy(rootEntriesComplete = false)))
        assertNull(select(snapshot(lookupKey = "content://tree/document/other")))
    }

    @Test
    fun `audio entries that do not describe the requested song are not trusted`() {
        assertNull(select(snapshot(audioEntry = audio.copy(isPendingAudioWrite = true))))
        assertNull(select(snapshot(audioEntry = audio.copy(name = "Other.flac"))))
        assertNull(
            select(
                snapshot(audioEntry = audio.copy(reference = "content://tree/document/moved", mediaUri = "content://x")),
                reference = audio.reference
            )
        )
    }

    @Test
    fun `metadata entries must be content sidecars named after the audio`() {
        assertNull(select(snapshot(metadataEntry = null)))
        assertNull(select(snapshot(metadataEntry = metadata.copy(name = "Song.flac.json"))))
        assertNull(select(snapshot(metadataEntry = metadata.copy(reference = "/music/Song.flac$LOCAL_METADATA_SUFFIX"))))
        assertEquals(
            "CONTENT://tree/document/meta",
            select(snapshot(metadataEntry = metadata.copy(reference = "CONTENT://tree/document/meta")))
        )
    }

    private fun select(snapshot: FakeLibrarySnapshot, reference: String = audio.reference): String? =
        LocalMediaSupport.selectCachedEditableMetadataReference(snapshot, reference, "Song.flac")

    private fun snapshot(
        lookupKey: String = audio.reference,
        audioEntry: FakeLibraryEntry = audio,
        metadataEntry: FakeLibraryEntry? = metadata
    ) = FakeLibrarySnapshot(
        audioEntries = listOf(audioEntry),
        audioEntriesByLookupKey = mapOf(lookupKey to audioEntry),
        metadataEntriesByAudioName = listOfNotNull(metadataEntry).associateBy { "Song.flac" }
    )
}
