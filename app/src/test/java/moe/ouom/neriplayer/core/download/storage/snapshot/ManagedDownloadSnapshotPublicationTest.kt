package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class ManagedDownloadSnapshotPublicationTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var cache: ManagedDownloadSnapshotCacheStore
    private val rootKey = AtomicReference("root-a")
    private var persisted: Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? = null
    private var restoreAvailable = true
    private val clearCount = AtomicInteger()
    private var beforeNextKeyRead: (() -> Unit)? = null

    @Before
    fun createCache() {
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        Mockito.`when`(context.filesDir).thenReturn(
            File(System.getProperty("java.io.tmpdir"), "snapshot-publication-${UUID.randomUUID()}")
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        cache = ManagedDownloadSnapshotCacheStore(
            scope = scope,
            cacheKeyProvider = {
                val beforeRead = beforeNextKeyRead
                beforeNextKeyRead = null
                beforeRead?.invoke()
                rootKey.get()
            },
            persistenceStoreProvider = {
                object : ManagedDownloadSnapshotPersistenceStore {
                    override suspend fun restore(expectedKey: String?) = persisted
                        ?.takeIf { restoreAvailable }
                        ?.takeIf { expectedKey == null || it.first == expectedKey }

                    override suspend fun persist(
                        cacheKey: String,
                        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
                    ) = true

                    override suspend fun clear() {
                        persisted = null
                        clearCount.incrementAndGet()
                    }
                }
            }
        )
    }

    @After
    fun cancelOwnedScope() {
        scope.cancel()
    }

    @Test
    fun `scan publication cannot overwrite metadata written while scanning`() {
        val original = snapshot(audio())
        cache.putSnapshot(context, "root-a", original)
        val scanStarted = CountDownLatch(1)
        val finishScan = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val published = executor.submit<Boolean> {
                val revision = cache.snapshotRevision()
                val scanned = checkNotNull(cache.cachedSnapshot(context, restorePersisted = false))
                scanStarted.countDown()
                check(finishScan.await(5, TimeUnit.SECONDS))
                cache.putSnapshotIfUnchanged(context, "root-a", scanned, revision)
            }
            assertTrue(scanStarted.await(5, TimeUnit.SECONDS))
            val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = "new-stable",
                customName = "New title",
                originalLyric = "[00:01.00]new original",
                originalTranslatedLyric = "[00:01.00]new translation",
                originalRomanizedLyric = "[00:01.00]new romanization"
            )
            cache.updateAfterMetadataWrite(
                context,
                audio().copy(name = "song.flac.npmeta.json", reference = "/music/song.flac.npmeta.json"),
                metadata
            )
            finishScan.countDown()

            val accepted = published.get(5, TimeUnit.SECONDS)
            assertEquals(metadata, cache.peekSnapshot()?.metadataByAudioName?.get("song.flac"))
            assertFalse(accepted)
        } finally {
            finishScan.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `scan publication cannot hide a newly committed audio entry`() {
        val scanned = snapshot()
        cache.putSnapshot(context, "root-a", scanned)
        val revision = cache.snapshotRevision()
        cache.updateAfterStoredEntryWrite(context, audio(), ManagedDownloadStorage.SnapshotEntryBucket.AUDIO)

        val publication = cache.publishSnapshotIfUnchanged(
            context,
            "root-a",
            scanned,
            revision
        )

        assertEquals(listOf(audio()), publication.snapshot.audioEntries)
        assertSame(cache.peekSnapshot(), publication.snapshot)
        assertFalse(publication.published)
    }

    @Test
    fun `scan publication cannot resurrect a deleted entry`() {
        val scanned = snapshot(audio())
        cache.putSnapshot(context, "root-a", scanned)
        val revision = cache.snapshotRevision()
        cache.updateAfterDelete(context, setOf(audio().reference))

        val accepted = cache.putSnapshotIfUnchanged(context, "root-a", scanned, revision)

        assertTrue(checkNotNull(cache.peekSnapshot()).audioEntries.isEmpty())
        assertFalse(accepted)
    }

    @Test
    fun `a delete during the first cold scan prevents deleted audio publication`() {
        val revision = cache.snapshotRevision()
        val scannedBeforeDelete = snapshot(audio())
        cache.updateAfterDelete(context, setOf(audio().reference))

        val accepted = cache.putSnapshotIfUnchanged(context, "root-a", scannedBeforeDelete, revision)

        assertNull(cache.peekSnapshot())
        assertFalse(accepted)
    }

    @Test
    fun `delete without a restorable memory snapshot clears stale persistence`() {
        persisted = "root-a" to snapshot(audio())
        restoreAvailable = false
        val revision = cache.snapshotRevision()

        assertTrue(cache.updateAfterDelete(context, setOf(audio().reference)))

        assertNull(cache.peekSnapshot())
        assertNull(persisted)
        assertEquals(1, clearCount.get())
        assertFalse(cache.putSnapshotIfUnchanged(
            context,
            "root-a",
            snapshot(audio()),
            revision
        ))
    }

    @Test
    fun `invalidation rejects a scan even when the cache was already empty`() {
        val revision = cache.snapshotRevision()
        cache.invalidate()

        val accepted = cache.putSnapshotIfUnchanged(context, "root-a", snapshot(audio()), revision)

        assertNull(cache.peekSnapshot())
        assertFalse(accepted)
    }

    @Test
    fun `changing the configured root rejects the previous root scan`() {
        val scanned = snapshot(audio())
        val revision = cache.snapshotRevision()
        rootKey.set("root-b")

        val publication = cache.publishSnapshotIfUnchanged(
            context,
            "root-a",
            scanned,
            revision
        )

        assertNull(cache.peekSnapshot())
        assertFalse(publication.published)
        val fallback = publication.snapshot
        assertTrue(fallback.audioEntries.isEmpty())
        assertFalse(fallback.rootEntriesComplete)
        assertFalse(fallback.sidecarEntriesComplete)
    }

    @Test
    fun `sidecar selection cannot return a snapshot from the previous root`() {
        val scanned = snapshot(audio())
        val revision = cache.snapshotRevision()
        rootKey.set("root-b")

        val selection = cache.selectSnapshotIfUnchanged(
            context,
            "root-a",
            scanned,
            revision
        )

        assertFalse(selection.accepted)
        assertTrue(selection.snapshot.audioEntries.isEmpty())
        assertFalse(selection.snapshot.rootEntriesComplete)
        assertFalse(selection.snapshot.sidecarEntriesComplete)
    }

    @Test
    fun `sidecar selection returns a concurrent delta without publishing the stale view`() {
        val scanned = snapshot()
        cache.putSnapshot(context, "root-a", scanned)
        val revision = cache.snapshotRevision()
        cache.updateAfterStoredEntryWrite(
            context,
            audio(),
            ManagedDownloadStorage.SnapshotEntryBucket.AUDIO
        )

        val selection = cache.selectSnapshotIfUnchanged(
            context,
            "root-a",
            scanned,
            revision
        )

        assertFalse(selection.accepted)
        assertEquals(listOf(audio()), selection.snapshot.audioEntries)
        assertSame(cache.peekSnapshot(), selection.snapshot)
    }

    @Test
    fun `unchanged sidecar selection does not advance the cache revision`() {
        val scanned = snapshot(audio())
        cache.putSnapshot(context, "root-a", scanned)
        val revision = cache.snapshotRevision()

        val selection = cache.selectSnapshotIfUnchanged(
            context,
            "root-a",
            scanned,
            revision
        )

        assertTrue(selection.accepted)
        assertSame(scanned, selection.snapshot)
        assertEquals(revision, cache.snapshotRevision())
    }

    @Test
    fun `an unchanged scan publishes and advances the revision`() {
        val revision = cache.snapshotRevision()
        val scanned = snapshot(audio())

        assertTrue(cache.putSnapshotIfUnchanged(context, "root-a", scanned, revision))

        assertSame(scanned, cache.peekSnapshot())
        assertTrue(cache.snapshotRevision() > revision)
    }

    @Test
    fun `restoring a new snapshot invalidates a scan but rereading it preserves the revision`() {
        val revision = cache.snapshotRevision()
        val restored = snapshot(audio())
        persisted = "root-a" to restored
        assertSame(restored, cache.restorePersisted(context, "root-a"))
        val restoredRevision = cache.snapshotRevision()
        assertTrue(restoredRevision > revision)
        assertSame(restored, cache.restorePersisted(context, "root-a"))
        assertEquals(restoredRevision, cache.snapshotRevision())

        val accepted = cache.putSnapshotIfUnchanged(context, "root-a", snapshot(), revision)

        assertSame(restored, cache.peekSnapshot())
        assertFalse(accepted)
    }

    @Test
    fun `cold restore capture permits an uncontended forced scan publication`() {
        val oldDiskSnapshot = snapshot()
        persisted = "root-a" to oldDiskSnapshot

        val captured = cache.captureSnapshot(context)
        val scanned = snapshot(audio())

        assertSame(oldDiskSnapshot, captured.snapshot)
        assertTrue(cache.putSnapshotIfUnchanged(context, "root-a", scanned, captured.revision))
        assertSame(scanned, cache.peekSnapshot())
    }

    @Test
    fun `capture pairs a delta written during the read with its actual revision`() {
        cache.putSnapshot(context, "root-a", snapshot())
        beforeNextKeyRead = {
            cache.updateAfterStoredEntryWrite(
                context,
                audio(),
                ManagedDownloadStorage.SnapshotEntryBucket.AUDIO
            )
        }

        val captured = cache.captureSnapshot(context, restorePersisted = false)

        assertSame(cache.peekSnapshot(), captured.snapshot)
        assertEquals(listOf(audio()), captured.snapshot?.audioEntries)
        assertEquals(cache.snapshotRevision(), captured.revision)
        assertTrue(cache.putSnapshotIfUnchanged(
            context, "root-a", checkNotNull(captured.snapshot), captured.revision
        ))
    }

    private fun snapshot(vararg audioEntries: ManagedDownloadStorage.StoredEntry) =
        ManagedDownloadSnapshotIndex.compose(
            audioEntries = audioEntries.toList(),
            metadataEntries = emptyList(),
            metadataByAudioName = emptyMap(),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )

    private fun audio() = ManagedDownloadStorage.StoredEntry(
        name = "song.flac",
        reference = "/music/song.flac",
        mediaUri = "file:///music/song.flac",
        localFilePath = "/music/song.flac",
        sizeBytes = 128L,
        lastModifiedMs = 1L
    )
}
