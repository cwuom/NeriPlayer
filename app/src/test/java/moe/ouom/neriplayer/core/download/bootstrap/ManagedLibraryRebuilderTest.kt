package moe.ouom.neriplayer.core.download.bootstrap

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedLibraryRebuilderTest {
    @Test
    fun `rebuild plan keeps stable identity and durable logical time`() {
        val audio = audio(lastModifiedMs = 99L)
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable-song",
            artifactId = "artifact-song",
            downloadTimeMs = 11L,
            createdAtMs = 22L,
            libraryAddedAtMs = 33L,
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
        )
        val snapshot = snapshot(audio, metadata)

        val item = ManagedLibraryRebuilder.plan(snapshot).single()

        assertEquals("stable-song", item.stableKey)
        assertEquals("artifact-song", item.artifactId)
        assertEquals(11L, item.logicalTimeMs)
    }

    @Test
    fun `metadata-less audio never enters the completed library plan`() {
        val audio = audio(lastModifiedMs = 77L)

        assertEquals(emptyList<ManagedLibraryRebuildItem>(), ManagedLibraryRebuilder.plan(snapshot(audio, null)))
    }

    @Test
    fun `unfinalized metadata does not enter library rebuild plan`() {
        val audio = audio(lastModifiedMs = 77L)
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable-song",
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED
        )

        assertEquals(
            emptyList<ManagedLibraryRebuildItem>(),
            ManagedLibraryRebuilder.plan(snapshot(audio, metadata))
        )
    }

    @Test
    fun `shipped legacy finalized metadata remains in library rebuild plan`() {
        val audio = audio(lastModifiedMs = 77L)
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable-song",
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED
        )

        assertEquals(
            listOf(audio),
            ManagedLibraryRebuilder.plan(snapshot(audio, metadata)).map { it.audio }
        )
    }

    @Test
    fun `pending audio never enters library rebuild plan even with finalized metadata`() {
        val audio = audio(lastModifiedMs = 77L).copy(
            name = "song.mp3.npdl_pending.recovery.pending"
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable-song",
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
        )

        assertEquals(
            emptyList<ManagedLibraryRebuildItem>(),
            ManagedLibraryRebuilder.plan(snapshot(audio, metadata))
        )
    }

    @Test
    fun `fast index preview accepts finalized entries without claiming a complete root`() {
        val audio = audio(lastModifiedMs = 77L)
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable-song",
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
        )
        val previewSnapshot = snapshot(audio, metadata).copy(rootEntriesComplete = false)

        assertEquals(emptyList<ManagedLibraryRebuildItem>(), ManagedLibraryRebuilder.plan(previewSnapshot))
        assertEquals(
            listOf(audio),
            ManagedLibraryRebuilder.plan(
                snapshot = previewSnapshot,
                allowIncompleteRootPreview = true
            ).map { it.audio }
        )
    }

    @Test
    fun `fast index preview still rejects pending audio`() {
        val audio = audio(lastModifiedMs = 77L).copy(
            name = "song.mp3.npdl_pending.recovery.pending"
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable-song",
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
        )

        assertEquals(
            emptyList<ManagedLibraryRebuildItem>(),
            ManagedLibraryRebuilder.plan(
                snapshot = snapshot(audio, metadata).copy(rootEntriesComplete = false),
                allowIncompleteRootPreview = true
            )
        )
    }

    private fun snapshot(
        audio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audio),
            audioEntriesByLookupKey = mapOf(audio.reference to audio),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = metadata?.let { mapOf(audio.name to it) }.orEmpty(),
            audioEntriesWithoutMetadata = if (metadata == null) listOf(audio) else emptyList(),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap(),
            knownReferences = setOf(audio.reference)
        )
    }

    private fun audio(lastModifiedMs: Long): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "/library/song.mp3",
            mediaUri = "/library/song.mp3",
            localFilePath = "/library/song.mp3",
            sizeBytes = 10L,
            lastModifiedMs = lastModifiedMs
        )
    }
}
