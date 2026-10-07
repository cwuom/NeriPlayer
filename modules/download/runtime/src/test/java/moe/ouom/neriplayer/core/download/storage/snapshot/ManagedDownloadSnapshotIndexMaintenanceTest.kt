package moe.ouom.neriplayer.core.download.storage.snapshot

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadSnapshotIndexMaintenanceTest {

    @Test
    fun `cover replacement keeps the old reference while another bucket still uses it`() {
        val shared = "content://provider/shared"
        val oldCover = entry("cover.jpg", shared)
        val bucketsSharingReference = listOf(
            compose(audio = listOf(entry("song.mp3", shared)), covers = listOf(oldCover)),
            compose(pendingAudio = listOf(entry("song.mp3.npdl_pending.op.pending", shared)), covers = listOf(oldCover)),
            compose(metadata = listOf(entry("song.mp3.npmeta.json", shared)), covers = listOf(oldCover)),
            compose(covers = listOf(oldCover), lyrics = listOf(entry("song.lrc", shared)))
        )
        val replacement = entry("cover.jpg", "content://provider/new-cover")

        bucketsSharingReference.forEach { snapshot ->
            val updated = ManagedDownloadSnapshotIndex.applyStoredEntryWrite(
                snapshot,
                replacement,
                ManagedDownloadStorage.SnapshotEntryBucket.COVER
            )

            assertEquals(mapOf("cover.jpg" to replacement), updated.coverEntriesByName)
            assertTrue(shared in updated.knownReferences)
            assertTrue(replacement.reference in updated.knownReferences)
        }
        val unshared = ManagedDownloadSnapshotIndex.applyStoredEntryWrite(
            compose(covers = listOf(oldCover)),
            replacement,
            ManagedDownloadStorage.SnapshotEntryBucket.COVER
        )
        assertEquals(setOf(replacement.reference), unshared.knownReferences)
    }

    @Test
    fun `sidecar refresh reuses the snapshot only when it is complete and unchanged`() {
        val cover = entry("cover.jpg", "content://provider/cover")
        val lyric = entry("song.lrc", "content://provider/lyric")
        val snapshot = compose(covers = listOf(cover), lyrics = listOf(lyric))
        val newCover = entry("new.jpg", "content://provider/new-cover")

        assertSame(snapshot, ManagedDownloadSnapshotIndex.applySidecarRefresh(snapshot, listOf(cover), listOf(lyric)))
        assertEquals(
            mapOf("new.jpg" to newCover),
            ManagedDownloadSnapshotIndex.applySidecarRefresh(snapshot, listOf(newCover), listOf(lyric))
                .coverEntriesByName
        )
        assertTrue(
            ManagedDownloadSnapshotIndex.applySidecarRefresh(snapshot, listOf(cover), emptyList())
                .lyricEntriesByName
                .isEmpty()
        )
        val incomplete = snapshot.copy(sidecarEntriesComplete = false)
        val refreshed = ManagedDownloadSnapshotIndex.applySidecarRefresh(incomplete, listOf(cover), listOf(lyric))
        assertNotSame(incomplete, refreshed)
        assertTrue(refreshed.sidecarEntriesComplete)
        assertEquals(snapshot.coverEntriesByName, refreshed.coverEntriesByName)
    }

    @Test
    fun `metadata rewrite keeps audio indexes when only display fields change`() {
        val snapshot = indexedSnapshot()

        val updated = rewrite(snapshot, baseMetadata.copy(name = "Renamed", coverUrl = "https://example.com/c.jpg"))

        assertSame(snapshot.audioEntriesByStableKey, updated.audioEntriesByStableKey)
        assertSame(snapshot.audioEntriesBySongId, updated.audioEntriesBySongId)
        assertEquals("Renamed", updated.metadataByAudioName[AUDIO_NAME]?.name)
    }

    @Test
    fun `metadata rewrite reindexes audio when an identity field changes`() {
        val snapshot = indexedSnapshot()
        val audio = snapshot.audioEntries.single()

        assertEquals(
            mapOf("stable-2" to listOf(audio)),
            rewrite(snapshot, baseMetadata.copy(stableKey = "stable-2")).audioEntriesByStableKey
        )
        assertEquals(
            mapOf(2L to listOf(audio)),
            rewrite(snapshot, baseMetadata.copy(songId = 2L)).audioEntriesBySongId
        )
        assertEquals(
            mapOf("https://example.com/2" to listOf(audio)),
            rewrite(snapshot, baseMetadata.copy(mediaUri = "https://example.com/2")).audioEntriesByMediaUri
        )
        assertEquals(
            mapOf("kuwo|1|" to listOf(audio)),
            rewrite(snapshot, baseMetadata.copy(channelId = "kuwo")).audioEntriesByRemoteTrackKey
        )
        assertEquals(
            mapOf("netease|2|" to listOf(audio)),
            rewrite(snapshot, baseMetadata.copy(audioId = "2")).audioEntriesByRemoteTrackKey
        )
        assertEquals(
            mapOf("netease|1|hq" to listOf(audio)),
            rewrite(snapshot, baseMetadata.copy(subAudioId = "hq")).audioEntriesByRemoteTrackKey
        )
        val renamedFile = rewrite(snapshot, baseMetadata.copy(audioFileName = "Other.flac"))
        assertNotSame(snapshot.audioEntriesByStableKey, renamedFile.audioEntriesByStableKey)
        assertEquals(snapshot.audioEntriesByStableKey, renamedFile.audioEntriesByStableKey)
        assertFalse(renamedFile.audioEntriesWithoutMetadata.contains(audio))
    }

    private fun indexedSnapshot(): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(entry(AUDIO_NAME, "content://provider/audio")),
            metadataEntries = listOf(metadataEntry),
            metadataByAudioName = mapOf(AUDIO_NAME to baseMetadata),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )
    }

    private fun rewrite(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        metadata: DownloadedAudioMetadata
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.applyMetadataWrite(snapshot, metadataEntry, metadata)
    }

    private fun compose(
        audio: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        pendingAudio: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        metadata: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        covers: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        lyrics: List<ManagedDownloadStorage.StoredEntry> = emptyList()
    ): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = audio,
            metadataEntries = metadata,
            metadataByAudioName = emptyMap(),
            coverEntries = covers,
            lyricEntries = lyrics,
            pendingAudioEntries = pendingAudio
        )
    }

    private fun entry(name: String, reference: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = reference,
        mediaUri = reference,
        localFilePath = null,
        sizeBytes = 128L,
        lastModifiedMs = 1L
    )

    private companion object {
        const val AUDIO_NAME = "Artist - Song.flac"
        val metadataEntry = ManagedDownloadStorage.StoredEntry(
            name = "$AUDIO_NAME.npmeta.json",
            reference = "content://provider/metadata",
            mediaUri = "content://provider/metadata",
            localFilePath = null,
            sizeBytes = 64L,
            lastModifiedMs = 1L
        )
        val baseMetadata = DownloadedAudioMetadata(
            stableKey = "stable-1",
            songId = 1L,
            name = "Song",
            mediaUri = "https://example.com/1",
            channelId = "netease",
            audioId = "1",
            audioFileName = AUDIO_NAME,
            downloadFinalized = true
        )
    }
}
