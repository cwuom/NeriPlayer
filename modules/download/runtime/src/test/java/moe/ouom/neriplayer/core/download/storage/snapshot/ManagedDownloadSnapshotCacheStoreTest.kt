package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ManagedDownloadSnapshotCacheStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = TestScope()
    private val rootKey = AtomicReference("root-a")
    private var persisted: Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? = null
    private val restoreRequests = mutableListOf<String?>()
    private val persistedKeys = mutableListOf<String>()
    private var clearCount = 0
    private lateinit var context: Context
    private lateinit var cache: ManagedDownloadSnapshotCacheStore

    @Before
    fun createCache() {
        context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        val persistence = object : ManagedDownloadSnapshotPersistenceStore {
            override suspend fun restore(expectedKey: String?): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? {
                restoreRequests += expectedKey
                return persisted?.takeIf { expectedKey == null || it.first == expectedKey }
            }

            override suspend fun persist(
                cacheKey: String,
                snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
            ): Boolean {
                persistedKeys += cacheKey
                return true
            }

            override suspend fun clear() {
                persisted = null
                clearCount++
            }
        }
        cache = ManagedDownloadSnapshotCacheStore(
            scope = scope,
            cacheKeyProvider = { rootKey.get() },
            persistenceStoreProvider = { persistence }
        )
    }

    @After
    fun cancelScope() {
        scope.cancel()
    }

    @Test
    fun `reads ignore a cached snapshot that belongs to another root`() {
        val rootASnapshot = snapshot(audio())
        cache.putSnapshot(context, "root-a", rootASnapshot)
        rootKey.set("root-b")

        val captured = cache.captureSnapshot(context, restorePersisted = false)

        assertNull(captured.snapshot)
        assertEquals("root-b", captured.cacheKey)
        assertEquals(cache.snapshotRevision(), captured.revision)
        assertNull(cache.cachedSnapshot(context, restorePersisted = false))
        assertTrue(restoreRequests.isEmpty())
        assertSame(rootASnapshot, cache.peekSnapshot())

        val rootBSnapshot = snapshot()
        persisted = "root-b" to rootBSnapshot
        assertSame(rootBSnapshot, cache.cachedSnapshot(context))
        assertEquals(listOf<String?>("root-b"), restoreRequests)
    }

    @Test
    fun `ensure ready restores the active root only when it is not cached`() {
        assertFalse(cache.ensureReady(context))

        persisted = "root-a" to snapshot(audio())
        assertTrue(cache.ensureReady(context))
        assertTrue(cache.ensureReady(context))
        assertEquals(listOf<String?>("root-a", "root-a"), restoreRequests)

        rootKey.set("root-b")
        assertFalse(cache.ensureReady(context))
        assertEquals(listOf<String?>("root-a", "root-a", "root-b"), restoreRequests)
    }

    @Test
    fun `metadata writes without a snapshot for the active root start from a partial snapshot`() {
        val metadataEntry = audio().copy(
            name = "song.flac.npmeta.json",
            reference = "/music/song.flac.npmeta.json"
        )
        val metadata = DownloadedAudioMetadata(stableKey = "stable", customName = "Title")

        assertTrue(cache.updateAfterMetadataWrite(context, metadataEntry, metadata))
        val coldSnapshot = requireNotNull(cache.peekSnapshot())
        assertEquals(metadata, coldSnapshot.metadataByAudioName["song.flac"])
        assertFalse(coldSnapshot.rootEntriesComplete)

        cache.putSnapshot(context, "root-a", snapshot(audio()))
        rootKey.set("root-b")
        val renamed = metadata.copy(customName = "Other title")
        assertTrue(cache.updateAfterMetadataWrite(context, metadataEntry, renamed))

        val switchedSnapshot = requireNotNull(cache.peekSnapshot())
        assertTrue(switchedSnapshot.audioEntries.isEmpty())
        assertEquals(renamed, switchedSnapshot.metadataByAudioName["song.flac"])
        assertEquals(listOf<String?>("root-a", "root-b"), restoreRequests)
        assertEquals("root-b", cache.captureSnapshot(context, restorePersisted = false).cacheKey)
    }

    @Test
    fun `stored entry writes replace a stale root snapshot and recover after invalidation`() {
        cache.putSnapshot(context, "root-a", snapshot(audio()))
        rootKey.set("root-b")
        val other = audio().copy(name = "other.flac", reference = "/music/other.flac")

        assertTrue(
            cache.updateAfterStoredEntryWrite(context, other, ManagedDownloadStorage.SnapshotEntryBucket.AUDIO)
        )
        assertEquals(listOf(other), requireNotNull(cache.peekSnapshot()).audioEntries)
        assertFalse(requireNotNull(cache.peekSnapshot()).rootEntriesComplete)

        cache.invalidate()
        assertTrue(
            cache.updateAfterStoredEntryWrite(context, audio(), ManagedDownloadStorage.SnapshotEntryBucket.AUDIO)
        )
        assertEquals(listOf(audio()), requireNotNull(cache.peekSnapshot()).audioEntries)
        assertEquals(listOf<String?>("root-b", "root-b"), restoreRequests)
    }

    @Test
    fun `repeated invalidation while a clear is pending clears persistence once`() {
        cache.putSnapshot(context, "root-a", snapshot(audio()))
        cache.invalidate(context)
        cache.invalidate(context)
        persisted = "root-a" to snapshot()

        assertNull(cache.peekSnapshot())
        assertNull(cache.restorePersisted(context, "root-a"))
        assertEquals(0, clearCount)

        scope.testScheduler.advanceUntilIdle()

        assertEquals(1, clearCount)
        assertTrue(persistedKeys.isEmpty())
        persisted = "root-a" to snapshot()
        assertSame(persisted?.second, cache.restorePersisted(context, "root-a"))
    }

    @Test
    fun `published snapshots are persisted once after the debounce`() {
        cache.putSnapshot(context, "root-a", snapshot())
        cache.putSnapshot(context, "root-a", snapshot(audio()))

        scope.testScheduler.advanceUntilIdle()

        assertEquals(listOf("root-a"), persistedKeys)
    }

    @Test
    fun `cover revision ignores unchanged publications and unrelated lyrics`() {
        val audio = audio()
        val lyric = storedEntry("song.lrc")
        val cover = storedEntry("song.png")
        val metadata = DownloadedAudioMetadata(stableKey = "stable", coverPath = cover.reference)
        val initial = snapshot(audio, metadata = metadata, covers = listOf(cover))
        assertEquals(0L, cache.coverRevision.value)
        cache.putSnapshot(context, "root-a", initial)
        val coverRevision = cache.coverRevision.value
        val snapshotRevision = cache.snapshotRevision()
        assertTrue(coverRevision > 0L)

        cache.putSnapshot(context, "root-a", initial)
        cache.updateAfterStoredEntryWrite(context, lyric, ManagedDownloadStorage.SnapshotEntryBucket.LYRIC)
        val refreshed = snapshot(
            audio,
            metadata = metadata.copy(lyricPath = lyric.reference),
            covers = listOf(cover),
            lyrics = listOf(lyric)
        )
        assertTrue(
            cache.publishSnapshotIfUnchanged(context, "root-a", refreshed, cache.snapshotRevision()).published
        )

        assertEquals(coverRevision, cache.coverRevision.value)
        assertTrue(cache.snapshotRevision() > snapshotRevision)
    }

    @Test
    fun `cover revision advances when a cover is written and deleted`() {
        cache.putSnapshot(context, "root-a", snapshot(audio()))
        val cover = storedEntry("song.png")
        val beforeWrite = cache.coverRevision.value

        assertTrue(cache.updateAfterStoredEntryWrite(context, cover, ManagedDownloadStorage.SnapshotEntryBucket.COVER))
        val afterWrite = cache.coverRevision.value
        assertTrue(afterWrite > beforeWrite)

        assertTrue(cache.updateAfterDelete(context, setOf(cover.reference)))
        assertTrue(cache.coverRevision.value > afterWrite)
    }

    @Test
    fun `cover revision follows a legacy metadata reference resolved from another bucket`() {
        val legacyCover = storedEntry("legacy.png")
        val metadata = DownloadedAudioMetadata(stableKey = "stable", coverPath = legacyCover.mediaUri)
        cache.putSnapshot(context, "root-a", snapshot(audio(), metadata = metadata))
        val beforeWrite = cache.coverRevision.value

        cache.updateAfterStoredEntryWrite(context, legacyCover, ManagedDownloadStorage.SnapshotEntryBucket.LYRIC)

        assertTrue(cache.coverRevision.value > beforeWrite)
    }

    @Test
    fun `cover revision advances when reordered aliases change the resolved legacy reference`() {
        val first = "content://fixture/document/music%3Acover.png"
        val second = "content://fixture/tree/music%3Afirst/document/music%3Acover.png"
        val source = "content://fixture/tree/music%3Asecond/document/music%3Acover.png"
        val aliases = listOf(
            storedEntry("first.png").copy(reference = first, mediaUri = first),
            storedEntry("second.png").copy(reference = second, mediaUri = second)
        )
        val metadata = DownloadedAudioMetadata(stableKey = "stable", coverPath = source)
        cache.putSnapshot(context, "root-a", snapshot(audio(), metadata = metadata, lyrics = aliases))
        val beforeReorder = cache.coverRevision.value

        cache.putSnapshot(context, "root-a", snapshot(audio(), metadata = metadata, lyrics = aliases.reversed()))

        assertTrue(cache.coverRevision.value > beforeReorder)
    }

    @Test
    fun `cover revision advances on restore and invalidation of resident artwork`() {
        persisted = "root-a" to snapshot(audio(), covers = listOf(storedEntry("song.png")))

        assertTrue(cache.ensureReady(context))
        val restoredRevision = cache.coverRevision.value
        assertTrue(restoredRevision > 0L)

        cache.invalidate()
        val invalidatedRevision = cache.coverRevision.value
        assertTrue(invalidatedRevision > restoredRevision)
        cache.invalidate()
        assertEquals(invalidatedRevision, cache.coverRevision.value)
    }

    private fun snapshot(
        vararg audioEntries: ManagedDownloadStorage.StoredEntry,
        metadata: DownloadedAudioMetadata? = null,
        covers: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        lyrics: List<ManagedDownloadStorage.StoredEntry> = emptyList()
    ) =
        ManagedDownloadSnapshotIndex.compose(
            audioEntries = audioEntries.toList(),
            metadataEntries = emptyList(),
            metadataByAudioName = metadata?.let { mapOf(audio().name to it) }.orEmpty(),
            coverEntries = covers,
            lyricEntries = lyrics
        )

    private fun audio() = storedEntry("song.flac")

    private fun storedEntry(name: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = "/music/$name",
        mediaUri = "file:///music/$name",
        localFilePath = "/music/$name",
        sizeBytes = 128L,
        lastModifiedMs = 1L
    )
}
