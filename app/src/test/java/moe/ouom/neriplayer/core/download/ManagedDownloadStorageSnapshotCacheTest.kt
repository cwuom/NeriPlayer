package moe.ouom.neriplayer.core.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ManagedDownloadStorageSnapshotCacheTest {

    @Test
    fun `snapshot cache payload round trips entries and metadata`() {
        val audioEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3",
            reference = "/music/Artist - Song.mp3",
            mediaUri = "file:///music/Artist%20-%20Song.mp3",
            localFilePath = "/music/Artist - Song.mp3",
            sizeBytes = 4096L,
            lastModifiedMs = 9999L
        )
        val metadataEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3.npmeta.json",
            reference = "/music/Artist - Song.mp3.npmeta.json",
            mediaUri = "file:///music/Artist%20-%20Song.mp3.npmeta.json",
            localFilePath = "/music/Artist - Song.mp3.npmeta.json",
            sizeBytes = 256L,
            lastModifiedMs = 9999L
        )
        val coverEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.jpg",
            reference = "/music/Covers/Artist - Song.jpg",
            mediaUri = "file:///music/Covers/Artist%20-%20Song.jpg",
            localFilePath = "/music/Covers/Artist - Song.jpg",
            sizeBytes = 128L,
            lastModifiedMs = 9999L
        )
        val lyricEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.lrc",
            reference = "/music/Lyrics/Artist - Song.lrc",
            mediaUri = "file:///music/Lyrics/Artist%20-%20Song.lrc",
            localFilePath = "/music/Lyrics/Artist - Song.lrc",
            sizeBytes = 64L,
            lastModifiedMs = 9999L
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable-key",
            songId = 12L,
            identityAlbum = "album-key",
            name = "Song",
            artist = "Artist",
            coverUrl = "https://example.com/cover.jpg",
            matchedLyricSource = "CLOUD_MUSIC",
            matchedSongId = "123",
            userLyricOffsetMs = 321L,
            customCoverUrl = "https://example.com/custom.jpg",
            customName = "Custom Song",
            customArtist = "Custom Artist",
            originalName = "Original Song",
            originalArtist = "Original Artist",
            originalCoverUrl = "https://example.com/original.jpg",
            mediaUri = "https://example.com/audio.mp3",
            channelId = "ytmusic",
            audioId = "video-id",
            subAudioId = "itag",
            coverPath = coverEntry.reference,
            lyricPath = lyricEntry.reference,
            translatedLyricPath = "/music/Lyrics/Artist - Song_trans.lrc",
            durationMs = 5000L
        )
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audioEntry),
            audioEntriesByLookupKey = mapOf(
                audioEntry.reference to audioEntry,
                audioEntry.mediaUri to audioEntry,
                audioEntry.localFilePath.orEmpty() to audioEntry
            ),
            metadataEntriesByAudioName = mapOf("Artist - Song.mp3" to metadataEntry),
            metadataByAudioName = mapOf("Artist - Song.mp3" to metadata),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = mapOf("stable-key" to listOf(audioEntry)),
            audioEntriesBySongId = mapOf(12L to listOf(audioEntry)),
            audioEntriesByMediaUri = mapOf("https://example.com/audio.mp3" to listOf(audioEntry)),
            audioEntriesByRemoteTrackKey = mapOf("ytmusic|video-id|itag" to listOf(audioEntry)),
            coverEntriesByName = mapOf(coverEntry.name to coverEntry),
            lyricEntriesByName = mapOf(lyricEntry.name to lyricEntry),
            knownReferences = setOf(
                audioEntry.reference,
                metadataEntry.reference,
                coverEntry.reference,
                lyricEntry.reference
            )
        )

        val payload = ManagedDownloadStorage.serializeSnapshotCachePayload(
            cacheKey = "tree:test",
            snapshot = snapshot
        )

        val restored = ManagedDownloadStorage.deserializeSnapshotCachePayload(
            raw = payload,
            expectedKey = "tree:test"
        )

        assertNotNull(restored)
        assertEquals("tree:test", restored?.first)
        assertEquals(listOf(audioEntry), restored?.second?.audioEntries)
        assertEquals(metadata, restored?.second?.metadataByAudioName?.get("Artist - Song.mp3"))
        assertEquals(coverEntry, restored?.second?.coverEntriesByName?.get(coverEntry.name))
        assertEquals(lyricEntry, restored?.second?.lyricEntriesByName?.get(lyricEntry.name))
    }

    @Test
    fun `metadata rewrite updates migrated sidecar references`() {
        val raw = JSONObject().apply {
            put("coverPath", "old-cover")
            put("lyricPath", "old-lyric")
            put("translatedLyricPath", "old-translated")
        }.toString()

        val rewritten = ManagedDownloadStorage.rewriteManagedMetadataReferences(
            rawJson = raw,
            referenceMap = mapOf(
                "old-cover" to "new-cover",
                "old-lyric" to "new-lyric",
                "old-translated" to "new-translated"
            )
        )
        val root = JSONObject(rewritten)

        assertEquals("new-cover", root.getString("coverPath"))
        assertEquals("new-lyric", root.getString("lyricPath"))
        assertEquals("new-translated", root.getString("translatedLyricPath"))
    }

    @Test
    fun `metadata write updates snapshot without rebuilding audio index`() {
        val audioEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.flac",
            reference = "/music/Artist - Song.flac",
            mediaUri = "file:///music/Artist%20-%20Song.flac",
            localFilePath = "/music/Artist - Song.flac",
            sizeBytes = 1024L,
            lastModifiedMs = 99L
        )
        val staleMetadataEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.flac.npmeta.json",
            reference = "/music/Artist - Song.flac.npmeta.json",
            mediaUri = "file:///music/Artist%20-%20Song.flac.npmeta.json",
            localFilePath = "/music/Artist - Song.flac.npmeta.json",
            sizeBytes = 128L,
            lastModifiedMs = 100L
        )
        val staleMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "old-stable",
            songId = 1L,
            name = "Old Song",
            artist = "Artist"
        )
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audioEntry),
            audioEntriesByLookupKey = mapOf(
                audioEntry.reference to audioEntry,
                audioEntry.mediaUri to audioEntry,
                audioEntry.localFilePath.orEmpty() to audioEntry
            ),
            metadataEntriesByAudioName = mapOf(audioEntry.name to staleMetadataEntry),
            metadataByAudioName = mapOf(audioEntry.name to staleMetadata),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = mapOf("old-stable" to listOf(audioEntry)),
            audioEntriesBySongId = mapOf(1L to listOf(audioEntry)),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap(),
            knownReferences = setOf(audioEntry.reference, staleMetadataEntry.reference)
        )
        val updatedMetadataEntry = staleMetadataEntry.copy(
            reference = "/music/new/Artist - Song.flac.npmeta.json",
            mediaUri = "file:///music/new/Artist%20-%20Song.flac.npmeta.json",
            localFilePath = "/music/new/Artist - Song.flac.npmeta.json",
            lastModifiedMs = 200L
        )
        val updatedMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "new-stable",
            songId = 2L,
            name = "New Song",
            artist = "New Artist"
        )

        val updatedSnapshot = ManagedDownloadStorage.applyMetadataWriteToSnapshot(
            snapshot = snapshot,
            metadataEntry = updatedMetadataEntry,
            metadata = updatedMetadata
        )

        assertEquals(updatedMetadata, updatedSnapshot.metadataByAudioName[audioEntry.name])
        assertEquals(updatedMetadataEntry, updatedSnapshot.metadataEntriesByAudioName[audioEntry.name])
        assertEquals(listOf(audioEntry), updatedSnapshot.audioEntriesByStableKey["new-stable"])
        assertEquals(listOf(audioEntry), updatedSnapshot.audioEntriesBySongId[2L])
    }
}
