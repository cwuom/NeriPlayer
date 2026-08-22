package moe.ouom.neriplayer.core.download

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotCacheStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotPersistenceStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotRoomMapper
import moe.ouom.neriplayer.data.model.SongItem
import org.mockito.Mockito

class ManagedDownloadStorageSnapshotCacheTest {

    @Test
    fun `managed MediaStore relative path recognizes configured and legacy download roots`() {
        assertTrue(
            ManagedDownloadStorage.isManagedDownloadRelativePath(
                relativePath = "neriplayer-download/Albums/",
                treeDocumentId = "primary:neriplayer-download"
            )
        )
        assertTrue(
            ManagedDownloadStorage.isManagedDownloadRelativePath(
                relativePath = "neriplayer-download/",
                treeDocumentId = null
            )
        )
        assertFalse(
            ManagedDownloadStorage.isManagedDownloadRelativePath(
                relativePath = "Music/Albums/",
                treeDocumentId = "primary:neriplayer-download"
            )
        )
    }

    @Test
    fun `external storage document ids recognize the legacy managed download root`() {
        assertTrue(
            ManagedDownloadStorage.isKnownManagedDownloadDocumentId(
                documentId = "primary:neriplayer-download/Lyrics/song.lrc",
                treeDocumentId = null
            )
        )
        assertTrue(
            ManagedDownloadStorage.isKnownManagedDownloadDocumentId(
                documentId = "primary:custom-root/song.mp3",
                treeDocumentId = "primary:custom-root"
            )
        )
        assertFalse(
            ManagedDownloadStorage.isKnownManagedDownloadDocumentId(
                documentId = "primary:Music/song.mp3",
                treeDocumentId = null
            )
        )
    }

    @Test
    fun `unchanged metadata entry reuses its parsed snapshot value`() {
        val cachedEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3.npmeta.json",
            reference = "/music/Artist - Song.mp3.npmeta.json",
            mediaUri = "file:///music/Artist%20-%20Song.mp3.npmeta.json",
            localFilePath = "/music/Artist - Song.mp3.npmeta.json",
            sizeBytes = 512L,
            lastModifiedMs = 100L
        )

        assertTrue(
            ManagedDownloadStorage.canReuseCachedDownloadedMetadata(
                cachedEntry = cachedEntry,
                currentEntry = cachedEntry,
                cachedMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(songId = 42L)
            )
        )
    }

    @Test
    fun `force refresh can reuse metadata when the entry fingerprint is unchanged`() {
        val entry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3.npmeta.json",
            reference = "/music/Artist - Song.mp3.npmeta.json",
            mediaUri = "file:///music/Artist%20-%20Song.mp3.npmeta.json",
            localFilePath = "/music/Artist - Song.mp3.npmeta.json",
            sizeBytes = 1_024L,
            lastModifiedMs = 100L
        )

        assertTrue(
            ManagedDownloadStorage.canReuseCachedDownloadedMetadata(
                cachedEntry = entry,
                currentEntry = entry,
                cachedMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(songId = 42L)
            )
        )
    }

    @Test
    fun `forced sidecar refresh skips the snapshot that was just published`() {
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot()

        assertTrue(
            ManagedDownloadStorage.shouldSkipRedundantForcedSidecarRefresh(
                requestedSnapshot = snapshot,
                activeSnapshot = snapshot,
                respectThrottle = false
            )
        )
        assertFalse(
            ManagedDownloadStorage.shouldSkipRedundantForcedSidecarRefresh(
                requestedSnapshot = snapshot,
                activeSnapshot = snapshot,
                respectThrottle = true
            )
        )
        assertFalse(
            ManagedDownloadStorage.shouldSkipRedundantForcedSidecarRefresh(
                requestedSnapshot = snapshot,
                activeSnapshot = snapshot.copy(),
                respectThrottle = false
            )
        )
    }

    @Test
    fun `metadata refresh reparses entries without a stable fingerprint`() {
        val cachedEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3.npmeta.json",
            reference = "/music/Artist - Song.mp3.npmeta.json",
            mediaUri = "file:///music/Artist%20-%20Song.mp3.npmeta.json",
            localFilePath = "/music/Artist - Song.mp3.npmeta.json",
            sizeBytes = 512L,
            lastModifiedMs = 0L
        )

        assertFalse(
            ManagedDownloadStorage.canReuseCachedDownloadedMetadata(
                cachedEntry = cachedEntry,
                currentEntry = cachedEntry,
                cachedMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(songId = 42L)
            )
        )
        assertFalse(
            ManagedDownloadStorage.canReuseCachedDownloadedMetadata(
                cachedEntry = cachedEntry.copy(lastModifiedMs = 100L),
                currentEntry = cachedEntry.copy(lastModifiedMs = 101L),
                cachedMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(songId = 42L)
            )
        )
    }

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
            matchedRomanizedLyric = "[00:01.00]yi",
            matchedLyricSource = "CLOUD_MUSIC",
            matchedSongId = "123",
            userLyricOffsetMs = 321L,
            customCoverUrl = "https://example.com/custom.jpg",
            customName = "Custom Song",
            customArtist = "Custom Artist",
            originalName = "Original Song",
            originalArtist = "Original Artist",
            originalCoverUrl = "https://example.com/original.jpg",
            originalRomanizedLyric = "[00:01.00]yuan",
            mediaUri = "https://example.com/audio.mp3",
            channelId = "ytmusic",
            audioId = "video-id",
            subAudioId = "itag",
            coverPath = coverEntry.reference,
            lyricPath = lyricEntry.reference,
            translatedLyricPath = "/music/Lyrics/Artist - Song_trans.lrc",
            romanizedLyricPath = "/music/Lyrics/Artist - Song_roma.lrc",
            durationMs = 5000L,
            downloadFinalized = false
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
        val restoredMetadata = restored?.second?.metadataByAudioName?.get("Artist - Song.mp3")
        assertEquals(metadata, restoredMetadata?.copy(restorableMetadata = null))
        assertNotNull(restoredMetadata?.restorableMetadata)
        assertEquals(coverEntry, restored?.second?.coverEntriesByName?.get(coverEntry.name))
        assertEquals(lyricEntry, restored?.second?.lyricEntriesByName?.get(lyricEntry.name))
    }

    @Test
    fun `room snapshot mapper round trips entries and indexes`() {
        val audioEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Room Song.flac",
            reference = "/music/Artist - Room Song.flac",
            mediaUri = "file:///music/Artist%20-%20Room%20Song.flac",
            localFilePath = "/music/Artist - Room Song.flac",
            sizeBytes = 8192L,
            lastModifiedMs = 123L
        )
        val metadataEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Room Song.flac.npmeta.json",
            reference = "/music/Artist - Room Song.flac.npmeta.json",
            mediaUri = "file:///music/Artist%20-%20Room%20Song.flac.npmeta.json",
            localFilePath = "/music/Artist - Room Song.flac.npmeta.json",
            sizeBytes = 512L,
            lastModifiedMs = 124L
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "room-stable",
            songId = 44L,
            identityAlbum = "NeteaseAlbum",
            name = "Room Song",
            artist = "Artist",
            matchedRomanizedLyric = "[00:01.00]room yi",
            originalRomanizedLyric = "[00:01.00]room yuan",
            mediaUri = "https://example.com/room.flac",
            channelId = "netease",
            audioId = "44",
            romanizedLyricPath = "/music/Lyrics/Artist - Room Song_roma.lrc",
            durationMs = 240_000L,
            downloadFinalized = true
        )
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audioEntry),
            audioEntriesByLookupKey = mapOf(audioEntry.reference to audioEntry),
            metadataEntriesByAudioName = mapOf(audioEntry.name to metadataEntry),
            metadataByAudioName = mapOf(audioEntry.name to metadata),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = mapOf("room-stable" to listOf(audioEntry)),
            audioEntriesBySongId = mapOf(44L to listOf(audioEntry)),
            audioEntriesByMediaUri = mapOf("https://example.com/room.flac" to listOf(audioEntry)),
            audioEntriesByRemoteTrackKey = mapOf("netease|44|" to listOf(audioEntry)),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap(),
            knownReferences = setOf(audioEntry.reference, metadataEntry.reference)
        )
        val entries = ManagedDownloadSnapshotRoomMapper.toEntryEntities("root", snapshot)
        val restored = ManagedDownloadSnapshotRoomMapper.toSnapshot(
            audioEntries = entries.filter {
                it.bucket == ManagedDownloadSnapshotRoomMapper.BUCKET_AUDIO
            },
            metadataEntries = entries.filter {
                it.bucket == ManagedDownloadSnapshotRoomMapper.BUCKET_METADATA
            },
            metadata = ManagedDownloadSnapshotRoomMapper.toMetadataEntities("root", snapshot),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )

        assertEquals(snapshot.audioEntries, restored.audioEntries)
        assertEquals(metadata, restored.metadataByAudioName[audioEntry.name])
        assertEquals(
            "/music/Lyrics/Artist - Room Song_roma.lrc",
            restored.metadataByAudioName[audioEntry.name]?.romanizedLyricPath
        )
        assertEquals(listOf(audioEntry), restored.audioEntriesByStableKey["room-stable"])
        assertEquals(listOf(audioEntry), restored.audioEntriesByRemoteTrackKey["netease|44|"])
        assertTrue(restored.knownReferences.contains(metadataEntry.reference))
    }

    @Test
    fun `invalidation rejects stale persisted restore and resumes after clear`() {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        val snapshot = emptySnapshot()
        val persistenceStore = BlockingSnapshotPersistenceStore("root" to snapshot)
        val cacheStore = ManagedDownloadSnapshotCacheStore(
            scope = CoroutineScope(Dispatchers.Unconfined),
            cacheKeyProvider = { "root" },
            persistenceStoreProvider = { persistenceStore }
        )
        val restoreExecutor = Executors.newSingleThreadExecutor()
        val invalidationExecutor = Executors.newSingleThreadExecutor()

        try {
            val restoreFuture = restoreExecutor.submit<ManagedDownloadStorage.DownloadLibrarySnapshot?> {
                cacheStore.restorePersisted(context, expectedKey = "root")
            }
            assertTrue(persistenceStore.restoreStarted.await(5, TimeUnit.SECONDS))

            val invalidationFuture = invalidationExecutor.submit {
                cacheStore.invalidate(context)
            }

            assertTrue(persistenceStore.clearStarted.await(5, TimeUnit.SECONDS))
            persistenceStore.releaseRestore.countDown()

            assertNull(restoreFuture.get(5, TimeUnit.SECONDS))
            assertNull(cacheStore.peekSnapshot())

            persistenceStore.releaseClear.countDown()
            assertTrue(persistenceStore.clearFinished.await(5, TimeUnit.SECONDS))
            invalidationFuture.get(5, TimeUnit.SECONDS)
            assertEquals(snapshot, cacheStore.restorePersisted(context, expectedKey = "root"))
        } finally {
            restoreExecutor.shutdownNow()
            invalidationExecutor.shutdownNow()
            persistenceStore.releaseRestore.countDown()
            persistenceStore.releaseClear.countDown()
        }
    }

    @Test
    fun `empty snapshot keeps all lookup indexes empty`() {
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot()

        assertTrue(snapshot.audioEntries.isEmpty())
        assertTrue(snapshot.audioEntriesByLookupKey.isEmpty())
        assertTrue(snapshot.metadataEntriesByAudioName.isEmpty())
        assertTrue(snapshot.metadataByAudioName.isEmpty())
        assertTrue(snapshot.knownReferences.isEmpty())
        assertNull(
            ManagedDownloadStorage.findDownloadedAudio(
                snapshot,
                SongItem(
                    id = 1L,
                    name = "Missing",
                    artist = "Artist",
                    album = "Album",
                    albumId = 1L,
                    durationMs = 1_000L,
                    coverUrl = null
                )
            )
        )
    }

    @Test
    fun `metadata reference is derived from stored audio reference without scanning`() {
        val audioEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.flac",
            reference = "/music/Artist - Song.flac",
            mediaUri = "file:///music/Artist%20-%20Song.flac",
            localFilePath = "/music/Artist - Song.flac",
            sizeBytes = 1024L,
            lastModifiedMs = 99L
        )

        assertEquals(
            "/music/Artist - Song.flac.npmeta.json",
            ManagedDownloadStorage.metadataReferenceForAudio(audioEntry)
        )
        assertNull(
            ManagedDownloadStorage.metadataReferenceForAudio(
                audioEntry.copy(reference = "")
            )
        )
    }

    @Test
    fun `encoded saf document ids resolve to the real audio file name`() {
        assertEquals(
            "track.mp3",
            ManagedDownloadStorage.normalizeManagedAudioFileName(
                "primary%3ANeriPlayer%2Ftrack.mp3"
            )
        )
        assertEquals(
            "track.mp3",
            ManagedDownloadStorage.normalizeManagedAudioFileName(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3ANeriPlayer%2Ftrack.mp3"
            )
        )
        assertEquals(
            "track.mp3",
            ManagedDownloadStorage.normalizeManagedAudioFileName(
                "/storage/emulated/0/NeriPlayer/track.mp3"
            )
        )
    }

    @Test
    fun `metadata rewrite updates migrated sidecar references`() {
        val raw = JSONObject().apply {
            put("coverPath", "old-cover")
            put("lyricPath", "old-lyric")
            put("translatedLyricPath", "old-translated")
            put("romanizedLyricPath", "old-romanized")
        }.toString()

        val rewritten = ManagedDownloadStorage.rewriteManagedMetadataReferences(
            rawJson = raw,
            referenceMap = mapOf(
                "old-cover" to "new-cover",
                "old-lyric" to "new-lyric",
                "old-translated" to "new-translated",
                "old-romanized" to "new-romanized"
            )
        )
        val root = JSONObject(rewritten)

        assertEquals("new-cover", root.getString("coverPath"))
        assertEquals("new-lyric", root.getString("lyricPath"))
        assertEquals("new-translated", root.getString("translatedLyricPath"))
        assertEquals("new-romanized", root.getString("romanizedLyricPath"))
    }

    @Test
    fun `tree child stored entry keeps SAF reference metadata`() {
        val entry = ManagedDownloadStorage.storedEntryFromTreeChild(
            name = "Artist - Song.flac",
            documentReference = "content://downloads/tree/root/document/root%2FArtist%20-%20Song.flac",
            sizeBytes = 2048L,
            lastModifiedMs = 1234L,
            isDirectory = false
        )

        assertEquals("Artist - Song.flac", entry.name)
        assertEquals("content://downloads/tree/root/document/root%2FArtist%20-%20Song.flac", entry.reference)
        assertEquals(entry.reference, entry.mediaUri)
        assertNull(entry.localFilePath)
        assertEquals(2048L, entry.sizeBytes)
        assertEquals(1234L, entry.lastModifiedMs)
        assertFalse(entry.isDirectory)
    }

    @Test
    fun `tree child refresh keeps reserved names until SAF confirms them`() {
        val refresh = ManagedDownloadStorage.mergeTreeChildNamesAfterRefresh(
            refreshedNames = listOf("confirmed.flac"),
            cachedNames = listOf("confirmed.flac", "reserved.flac"),
            cachedNamesComplete = false,
            refreshedComplete = true
        )

        assertEquals(setOf("confirmed.flac", "reserved.flac"), refresh.names)
        assertFalse(refresh.isComplete)

        val completeRefresh = ManagedDownloadStorage.mergeTreeChildNamesAfterRefresh(
            refreshedNames = listOf("confirmed.flac"),
            cachedNames = listOf("confirmed.flac", "stale-reservation.flac"),
            cachedNamesComplete = true,
            refreshedComplete = true
        )

        assertEquals(setOf("confirmed.flac"), completeRefresh.names)
        assertTrue(completeRefresh.isComplete)
    }

    @Test
    fun `incomplete tree refresh keeps previously confirmed names`() {
        val refresh = ManagedDownloadStorage.mergeTreeChildNamesAfterRefresh(
            refreshedNames = listOf("new.txt"),
            cachedNames = listOf("old.txt"),
            cachedNamesComplete = true,
            refreshedComplete = false
        )

        assertEquals(setOf("new.txt", "old.txt"), refresh.names)
        assertFalse(refresh.isComplete)
    }

    @Test
    fun `provider numbered name is not an exact replace target`() {
        assertFalse(
            moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
                .isExactTreeStoredName(
                    actualName = "song.npmeta.json (1)",
                    expectedName = "song.npmeta.json"
                )
        )
        assertTrue(
            moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
                .isExactTreeStoredName(
                    actualName = "song.npmeta.json",
                    expectedName = "song.npmeta.json"
                )
        )
    }

    @Test
    fun `reference delete updates snapshot without dropping unrelated SAF indexes`() {
        val audioEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.flac",
            reference = "content://downloads/tree/root/document/audio",
            mediaUri = "content://downloads/tree/root/document/audio",
            localFilePath = null,
            sizeBytes = 4096L,
            lastModifiedMs = 100L
        )
        val metadataEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.flac.npmeta.json",
            reference = "content://downloads/tree/root/document/meta",
            mediaUri = "content://downloads/tree/root/document/meta",
            localFilePath = null,
            sizeBytes = 128L,
            lastModifiedMs = 101L
        )
        val coverEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.jpg",
            reference = "content://downloads/tree/root/document/cover",
            mediaUri = "content://downloads/tree/root/document/cover",
            localFilePath = null,
            sizeBytes = 64L,
            lastModifiedMs = 102L
        )
        val lyricEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.lrc",
            reference = "content://downloads/tree/root/document/lyric",
            mediaUri = "content://downloads/tree/root/document/lyric",
            localFilePath = null,
            sizeBytes = 32L,
            lastModifiedMs = 103L
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable",
            songId = 7L,
            name = "Song",
            artist = "Artist",
            coverPath = coverEntry.reference,
            lyricPath = lyricEntry.reference
        )
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audioEntry),
            audioEntriesByLookupKey = mapOf(audioEntry.reference to audioEntry),
            metadataEntriesByAudioName = mapOf(audioEntry.name to metadataEntry),
            metadataByAudioName = mapOf(audioEntry.name to metadata),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = mapOf("stable" to listOf(audioEntry)),
            audioEntriesBySongId = mapOf(7L to listOf(audioEntry)),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = mapOf(coverEntry.name to coverEntry),
            lyricEntriesByName = mapOf(lyricEntry.name to lyricEntry),
            knownReferences = setOf(
                audioEntry.reference,
                metadataEntry.reference,
                coverEntry.reference,
                lyricEntry.reference
            )
        )

        val updatedSnapshot = ManagedDownloadStorage.applyReferenceDeletesToSnapshot(
            snapshot = snapshot,
            references = setOf(metadataEntry.reference, coverEntry.reference)
        )

        assertEquals(listOf(audioEntry), updatedSnapshot.audioEntries)
        assertTrue(updatedSnapshot.metadataEntriesByAudioName.isEmpty())
        assertTrue(updatedSnapshot.metadataByAudioName.isEmpty())
        assertFalse(updatedSnapshot.coverEntriesByName.containsKey(coverEntry.name))
        assertEquals(lyricEntry, updatedSnapshot.lyricEntriesByName[lyricEntry.name])
        assertFalse(updatedSnapshot.knownReferences.contains(metadataEntry.reference))
        assertFalse(updatedSnapshot.knownReferences.contains(coverEntry.reference))
        assertTrue(updatedSnapshot.knownReferences.contains(audioEntry.reference))
        assertTrue(updatedSnapshot.knownReferences.contains(lyricEntry.reference))
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

    @Test
    fun `stored entry write updates snapshot bucket without disturbing other indexes`() {
        val audioEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.flac",
            reference = "/music/Artist - Song.flac",
            mediaUri = "file:///music/Artist%20-%20Song.flac",
            localFilePath = "/music/Artist - Song.flac",
            sizeBytes = 1024L,
            lastModifiedMs = 99L
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable",
            songId = 7L,
            name = "Song",
            artist = "Artist"
        )
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audioEntry),
            audioEntriesByLookupKey = mapOf(
                audioEntry.reference to audioEntry,
                audioEntry.mediaUri to audioEntry,
                audioEntry.localFilePath.orEmpty() to audioEntry
            ),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = mapOf(audioEntry.name to metadata),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = mapOf("stable" to listOf(audioEntry)),
            audioEntriesBySongId = mapOf(7L to listOf(audioEntry)),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap(),
            knownReferences = setOf(audioEntry.reference)
        )
        val coverEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.jpg",
            reference = "/music/Covers/Artist - Song.jpg",
            mediaUri = "file:///music/Covers/Artist%20-%20Song.jpg",
            localFilePath = "/music/Covers/Artist - Song.jpg",
            sizeBytes = 64L,
            lastModifiedMs = 120L
        )

        val updatedSnapshot = ManagedDownloadStorage.applyStoredEntryWriteToSnapshot(
            snapshot = snapshot,
            storedEntry = coverEntry,
            bucket = ManagedDownloadStorage.SnapshotEntryBucket.COVER
        )

        assertEquals(audioEntry, updatedSnapshot.audioEntries.single())
        assertEquals(coverEntry, updatedSnapshot.coverEntriesByName[coverEntry.name])
        assertEquals(listOf(audioEntry), updatedSnapshot.audioEntriesByStableKey["stable"])
        assertEquals(listOf(audioEntry), updatedSnapshot.audioEntriesBySongId[7L])
    }

    @Test
    fun `sidecar refresh replaces external lyric and cover changes without rebuilding audio indexes`() {
        val audioEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.flac",
            reference = "/music/Artist - Song.flac",
            mediaUri = "file:///music/Artist%20-%20Song.flac",
            localFilePath = "/music/Artist - Song.flac",
            sizeBytes = 1024L,
            lastModifiedMs = 99L
        )
        val metadataEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.flac.npmeta.json",
            reference = "/music/Artist - Song.flac.npmeta.json",
            mediaUri = "file:///music/Artist%20-%20Song.flac.npmeta.json",
            localFilePath = "/music/Artist - Song.flac.npmeta.json",
            sizeBytes = 128L,
            lastModifiedMs = 100L
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "stable",
            songId = 7L,
            name = "Song",
            artist = "Artist"
        )
        val oldCover = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.jpg",
            reference = "/music/Covers/Artist - Song.jpg",
            mediaUri = "file:///music/Covers/Artist%20-%20Song.jpg",
            localFilePath = "/music/Covers/Artist - Song.jpg",
            sizeBytes = 64L,
            lastModifiedMs = 101L
        )
        val oldLyric = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.lrc",
            reference = "/music/Lyrics/Artist - Song.lrc",
            mediaUri = "file:///music/Lyrics/Artist%20-%20Song.lrc",
            localFilePath = "/music/Lyrics/Artist - Song.lrc",
            sizeBytes = 32L,
            lastModifiedMs = 102L
        )
        val newLyric = oldLyric.copy(
            reference = "/music/Lyrics/Artist - Song_roma.lrc",
            mediaUri = "file:///music/Lyrics/Artist%20-%20Song_roma.lrc",
            localFilePath = "/music/Lyrics/Artist - Song_roma.lrc",
            name = "Artist - Song_roma.lrc",
            sizeBytes = 48L,
            lastModifiedMs = 103L
        )
        val newCover = oldCover.copy(
            reference = "/music/Covers/Artist - Song.webp",
            mediaUri = "file:///music/Covers/Artist%20-%20Song.webp",
            localFilePath = "/music/Covers/Artist - Song.webp",
            name = "Artist - Song.webp",
            sizeBytes = 96L,
            lastModifiedMs = 104L
        )
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audioEntry),
            audioEntriesByLookupKey = mapOf(
                audioEntry.reference to audioEntry,
                audioEntry.mediaUri to audioEntry,
                audioEntry.localFilePath.orEmpty() to audioEntry
            ),
            metadataEntriesByAudioName = mapOf(audioEntry.name to metadataEntry),
            metadataByAudioName = mapOf(audioEntry.name to metadata),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = mapOf(metadata.stableKey.orEmpty() to listOf(audioEntry)),
            audioEntriesBySongId = mapOf(7L to listOf(audioEntry)),
            audioEntriesByMediaUri = mapOf(metadata.mediaUri.orEmpty() to listOf(audioEntry)),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = mapOf(oldCover.name to oldCover),
            lyricEntriesByName = mapOf(oldLyric.name to oldLyric),
            knownReferences = setOf(
                audioEntry.reference,
                metadataEntry.reference,
                oldCover.reference,
                oldLyric.reference
            )
        )

        val refreshed = ManagedDownloadStorage.applySidecarRefreshToSnapshot(
            snapshot = snapshot,
            coverEntries = listOf(newCover),
            lyricEntries = listOf(newLyric)
        )

        assertEquals(listOf(audioEntry), refreshed.audioEntries)
        assertEquals(metadata, refreshed.metadataByAudioName[audioEntry.name])
        assertEquals(newCover, refreshed.coverEntriesByName[newCover.name])
        assertEquals(newLyric, refreshed.lyricEntriesByName[newLyric.name])
        assertFalse(refreshed.knownReferences.contains(oldCover.reference))
        assertFalse(refreshed.knownReferences.contains(oldLyric.reference))
        assertTrue(refreshed.knownReferences.contains(newCover.reference))
        assertTrue(refreshed.knownReferences.contains(newLyric.reference))
        assertEquals(listOf(audioEntry), refreshed.audioEntriesByStableKey[metadata.stableKey])
        assertEquals(listOf(audioEntry), refreshed.audioEntriesBySongId[7L])
    }

    @Test
    fun `lyrics bundle resolves original translated and romanized sidecars from one snapshot`() {
        val audioEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3",
            reference = "/music/Artist - Song.mp3",
            mediaUri = "file:///music/Artist%20-%20Song.mp3",
            localFilePath = "/music/Artist - Song.mp3",
            sizeBytes = 1024L,
            lastModifiedMs = 10L
        )
        val originalEntry = audioEntry.lyricEntry("Artist - Song.lrc")
        val translatedEntry = audioEntry.lyricEntry("Artist - Song_trans.lrc")
        val romanizedEntry = audioEntry.lyricEntry("Artist - Song_roma.lrc")
        val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = listOf(audioEntry),
            audioEntriesByLookupKey = mapOf(audioEntry.reference to audioEntry),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = emptyMap(),
            audioEntriesWithoutMetadata = listOf(audioEntry),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = mapOf(
                originalEntry.name to originalEntry,
                translatedEntry.name to translatedEntry,
                romanizedEntry.name to romanizedEntry
            ),
            knownReferences = setOf(
                audioEntry.reference,
                originalEntry.reference,
                translatedEntry.reference,
                romanizedEntry.reference
            )
        )
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioEntry.mediaUri,
            localFilePath = audioEntry.localFilePath
        )
        val reads = AtomicInteger()
        val bundle = ManagedDownloadStorage.resolveDownloadedLyricsBundle(
            context = Mockito.mock(Context::class.java),
            song = song,
            snapshot = snapshot,
            readText = { reference ->
                reads.incrementAndGet()
                when (reference) {
                    originalEntry.reference -> "original"
                    translatedEntry.reference -> "translated"
                    romanizedEntry.reference -> "romanized"
                    else -> null
                }
            },
            exists = { _, _ -> true }
        )

        assertEquals(3, reads.get())
        assertEquals("original", bundle.lyric)
        assertEquals("translated", bundle.translatedLyric)
        assertEquals("romanized", bundle.romanizedLyric)
        assertTrue(bundle.hasOriginalSidecar)
        assertTrue(bundle.hasTranslatedSidecar)
        assertTrue(bundle.hasRomanizedSidecar)
    }

    @Test
    fun `fast lyric entry resolver reads all sidecar variants without metadata scan`() {
        val song = SongItem(
            id = 7L,
            name = "Song",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            localFileName = "Artist - Song.mp3",
            matchedLyric = "embedded original"
        )
        val entries = listOf(
            fastLyricEntry("Artist - Song.lrc"),
            fastLyricEntry("Artist - Song_trans.lrc"),
            fastLyricEntry("Artist - Song_roma.lrc")
        )
        val bundle = ManagedDownloadStorage.resolveLyricsBundleFromEntries(
            song = song,
            candidateBaseNames = listOf("Artist - Song"),
            lyricEntries = entries,
            readText = { reference ->
                when (reference.substringAfterLast('/')) {
                    "Artist - Song.lrc" -> "sidecar original"
                    "Artist - Song_trans.lrc" -> "sidecar translation"
                    "Artist - Song_roma.lrc" -> "sidecar romanized"
                    else -> null
                }
            }
        )

        assertEquals("sidecar original", bundle.lyric)
        assertEquals("sidecar translation", bundle.translatedLyric)
        assertEquals("sidecar romanized", bundle.romanizedLyric)
        assertTrue(bundle.hasOriginalSidecar)
        assertTrue(bundle.hasTranslatedSidecar)
        assertTrue(bundle.hasRomanizedSidecar)
    }

    @Test
    fun `fast lyric entry resolver treats a deleted reference as absent`() {
        val song = SongItem(
            id = 7L,
            name = "Song",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            localFileName = "Artist - Song.mp3"
        )
        val bundle = ManagedDownloadStorage.resolveLyricsBundleFromEntries(
            song = song,
            candidateBaseNames = listOf("Artist - Song"),
            lyricEntries = listOf(fastLyricEntry("Artist - Song.lrc")),
            readText = { null }
        )

        assertNull(bundle.lyric)
        assertFalse(bundle.hasOriginalSidecar)
    }

    @Test
    fun `fast lyric references read original translated and romanized sidecars together`() {
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            matchedLyric = "metadata original",
            matchedTranslatedLyric = "metadata translated",
            matchedRomanizedLyric = "metadata romanized"
        )
        val contents = mapOf(
            "original" to "sidecar original",
            "translated" to "sidecar translated",
            "romanized" to "sidecar romanized"
        )

        val bundle = ManagedDownloadStorage.resolveLyricsBundleFromReferences(
            metadata = metadata,
            originalReference = "original",
            translatedReference = "translated",
            romanizedReference = "romanized",
            readText = contents::get
        )

        assertEquals("sidecar original", bundle.lyric)
        assertEquals("sidecar translated", bundle.translatedLyric)
        assertEquals("sidecar romanized", bundle.romanizedLyric)
        assertTrue(bundle.hasOriginalSidecar)
        assertTrue(bundle.hasTranslatedSidecar)
        assertTrue(bundle.hasRomanizedSidecar)
    }

    @Test
    fun `fast lyric references fall back to metadata after the sidecar is deleted`() {
        val bundle = ManagedDownloadStorage.resolveLyricsBundleFromReferences(
            metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                matchedLyric = "metadata original",
                originalTranslatedLyric = "metadata translated",
                originalRomanizedLyric = "metadata romanized"
            ),
            originalReference = "deleted-original",
            translatedReference = "deleted-translated",
            romanizedReference = "deleted-romanized",
            readText = { null }
        )

        assertEquals("metadata original", bundle.lyric)
        assertEquals("metadata translated", bundle.translatedLyric)
        assertEquals("metadata romanized", bundle.romanizedLyric)
        assertFalse(bundle.hasOriginalSidecar)
        assertFalse(bundle.hasTranslatedSidecar)
        assertFalse(bundle.hasRomanizedSidecar)
    }

    @Test(expected = SecurityException::class)
    fun `fast lyric references do not hide a revoked SAF permission`() {
        ManagedDownloadStorage.resolveLyricsBundleFromReferences(
            metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                matchedLyric = "metadata original"
            ),
            originalReference = "content://com.android.externalstorage.documents/document/" +
                "primary%3Aneriplayer-download%2FLyrics%2FSong.lrc",
            translatedReference = null,
            romanizedReference = null,
            readText = { throw SecurityException("permission revoked") }
        )
    }

    private fun ManagedDownloadStorage.StoredEntry.lyricEntry(name: String):
        ManagedDownloadStorage.StoredEntry {
        return copy(
            name = name,
            reference = "/music/Lyrics/$name",
            mediaUri = "file:///music/Lyrics/${name.replace(" ", "%20")}",
            localFilePath = "/music/Lyrics/$name",
            sizeBytes = 32L,
            lastModifiedMs = 11L
        )
    }

    private fun fastLyricEntry(name: String): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = "/music/Lyrics/$name",
            mediaUri = "file:///music/Lyrics/${name.replace(" ", "%20")}",
            localFilePath = "/music/Lyrics/$name",
            sizeBytes = 32L,
            lastModifiedMs = 11L
        )
    }

    private fun emptySnapshot(): ManagedDownloadStorage.DownloadLibrarySnapshot {
        return ManagedDownloadStorage.DownloadLibrarySnapshot(
            audioEntries = emptyList(),
            audioEntriesByLookupKey = emptyMap(),
            metadataEntriesByAudioName = emptyMap(),
            metadataByAudioName = emptyMap(),
            audioEntriesWithoutMetadata = emptyList(),
            audioEntriesByStableKey = emptyMap(),
            audioEntriesBySongId = emptyMap(),
            audioEntriesByMediaUri = emptyMap(),
            audioEntriesByRemoteTrackKey = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap(),
            knownReferences = emptySet()
        )
    }

    private class BlockingSnapshotPersistenceStore(
        private val restoredSnapshot:
            Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>
    ) : ManagedDownloadSnapshotPersistenceStore {
        val restoreStarted = CountDownLatch(1)
        val releaseRestore = CountDownLatch(1)
        val clearStarted = CountDownLatch(1)
        val releaseClear = CountDownLatch(1)
        val clearFinished = CountDownLatch(1)

        override suspend fun restore(
            expectedKey: String?
        ): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? {
            restoreStarted.countDown()
            assertTrue(releaseRestore.await(5, TimeUnit.SECONDS))
            return restoredSnapshot.takeIf { expectedKey == null || it.first == expectedKey }
        }

        override suspend fun persist(
            cacheKey: String,
            snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
        ): Boolean = true

        override suspend fun clear() {
            clearStarted.countDown()
            try {
                assertTrue(releaseClear.await(5, TimeUnit.SECONDS))
            } finally {
                clearFinished.countDown()
            }
        }
    }
}
