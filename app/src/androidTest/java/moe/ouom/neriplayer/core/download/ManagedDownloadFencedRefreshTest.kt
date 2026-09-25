package moe.ouom.neriplayer.core.download

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongDeleteIntent
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.catalog.cancelScheduledDownloadedSongsCatalogPersist
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.catalog.reloadDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.catalog.restorePersistedDownloadedSongs
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshPreserveReason
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadFencedRefreshTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )
    private val root get() = requireNotNull(DocumentFile.fromTreeUri(context, treeUri))
    private var fixtureIntent: DownloadedSongDeleteIntent? = null

    @Test fun forcedRefreshRemoves537ExternallyDeletedSongsWhileKeepingCleanupIntent() = runBlocking {
        withFixture {
            val temporary = requireNotNull(root.createDirectory(".tmp"))
            val files = (0 until 536).map { audio("song-$it.mp3") } +
                audio("song-536.mp3.npdl_pending.owner.pending", temporary)
            val songs = files.mapIndexed { index, file -> song(index, file) }
            assertTrue(GlobalDownloadManager.downloadedSongCatalogStore.persist(context, songs))
            beginIntent(songs)
            val formal = metadata("song-0.mp3.npmeta.json", "current-owner", files.first())
            val pending = metadata("song-0.mp3.npmeta.pending.json", "old-owner", files.first(), temporary)
            files.forEach { assertTrue(it.delete()) }
            val deleteCalls = counts().getInt("deleteCalls")

            val result = GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)

            assertTrue(result.toString(), result is ManagedLibraryRefreshOutcome.Published)
            assertEquals(0, (result as ManagedLibraryRefreshOutcome.Published).songCount)
            assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
            assertEquals(0L, GlobalDownloadManager.downloadedSongsMutable.value.sumOf { it.fileSize })
            assertTrue(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
            assertTrue(GlobalDownloadManager.isDownloadClearFenceActive(context))
            assertEquals("refresh must not delete physical files", deleteCalls, counts().getInt("deleteCalls"))
            assertTrue(formal.exists())
            assertTrue(pending.exists())
            GlobalDownloadManager.cancelScheduledDownloadedSongsCatalogPersist()
            assertTrue(GlobalDownloadManager.restorePersistedDownloadedSongs(context))
            assertTrue("startup must not republish missing recovery rows",
                GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
            assertTrue(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
        }
    }

    @Test fun forcedRefreshKeepsExistingMetadataLessAndPendingAudioAndNeverImportsNewSongs() = runBlocking {
        withFixture {
            val missing = audio("missing.mp3")
            val present = audio("present.mp3")
            val temporary = requireNotNull(root.createDirectory(".tmp"))
            val pending = audio("pending.mp3.npdl_pending.owner.pending", temporary)
            val songs = listOf(missing, present, pending).mapIndexed { index, file -> song(index, file) }
            beginIntent(songs)
            assertTrue(missing.delete())
            audio("new-unselected.mp3")

            val result = GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)

            assertTrue(result.toString(), result is ManagedLibraryRefreshOutcome.Published)
            assertEquals(songs.drop(1), GlobalDownloadManager.downloadedSongsMutable.value)
            assertTrue(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
        }
    }

    @Test fun incompleteRootEnumerationRetainsCatalogUntilSuccessfulForcedRefresh() = runBlocking {
        withFixture {
            val file = audio("missing.mp3")
            val songs = listOf(song(0, file))
            beginIntent(songs)
            assertTrue(file.delete())
            context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.QUERY_FAULT, "null", null)
            val failed = GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)
            assertFalse(failed.toString(), failed is ManagedLibraryRefreshOutcome.Published)
            assertEquals(songs, GlobalDownloadManager.downloadedSongsMutable.value)
            context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.QUERY_FAULT, null, null)

            val recovered = GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)

            assertTrue(recovered.toString(), recovered is ManagedLibraryRefreshOutcome.Published)
            assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
        }
    }

    @Test fun unavailableMetadataRetainsCatalogUntilSuccessfulForcedRefresh() = runBlocking {
        withFixture {
            val file = audio("missing.mp3")
            val receipt = metadata("missing.mp3.npmeta.json", "owner", file)
            val songs = listOf(song(0, file))
            beginIntent(songs)
            assertTrue(file.delete())
            context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.METADATA_READ_FAULT,
                receipt.uri.toString(), Bundle().apply { putString("fault", "permission"); putInt("remaining", 1) })

            val unavailable = GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true)

            assertFalse(unavailable.toString(), unavailable is ManagedLibraryRefreshOutcome.Published)
            assertEquals(songs, GlobalDownloadManager.downloadedSongsMutable.value)
            assertEquals(1, counts().getInt("metadataReadFaults"))
            val recovered = GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true)
            assertTrue(recovered.toString(), recovered is ManagedLibraryRefreshOutcome.Published)
            assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
        }
    }

    @Test fun activePhysicalDeleteOrDirectoryMutationDoesNotPublishFencedRefresh() = runBlocking {
        withFixture {
            val file = audio("missing.mp3")
            val songs = listOf(song(0, file))
            beginIntent(songs)
            assertTrue(file.delete())
            GlobalDownloadManager.downloadedSongDeleteMutex.withLock {
                val result = GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true)
                assertFalse(result.toString(), result is ManagedLibraryRefreshOutcome.Published)
                assertEquals(songs, GlobalDownloadManager.downloadedSongsMutable.value)
            }
            val mutation = ManagedDownloadDirectoryMutationFence.closeAndDrain()
            try {
                val result = GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true)
                assertFalse(result.toString(), result is ManagedLibraryRefreshOutcome.Published)
                assertEquals(songs, GlobalDownloadManager.downloadedSongsMutable.value)
            } finally {
                mutation.close()
            }
        }
    }

    @Test fun unfencedForcedRefreshConfirmsExternallyEmptiedRootAndSidecarInOneRequest() = runBlocking {
        withFixture {
            val file = audio("external.mp3")
            val receipt = metadata("external.mp3.npmeta.json", "owner", file)
            val covers = requireNotNull(root.createDirectory("Covers"))
            val cover = requireNotNull(covers.createFile("image/jpeg", "external.jpg"))
            write(cover, "fixture cover")
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
            val songs = listOf(song(0, file))
            GlobalDownloadManager.publishDownloadedSongs(context, songs, persistCatalog = false)
            GlobalDownloadManager.downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
            assertTrue(file.delete())
            assertTrue(receipt.delete())
            assertTrue(cover.delete())
            assertTrue(covers.delete())
            val initialScanId = GlobalDownloadManager.emptyScanSequence.get()

            val result = GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)

            assertTrue(result.toString(), result is ManagedLibraryRefreshOutcome.Published)
            assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
            val scanCount = GlobalDownloadManager.emptyScanSequence.get() - initialScanId
            assertTrue("confirmation must use independent bounded scans: $scanCount", scanCount in 2L..3L)
        }
    }

    @Test fun unfencedForcedRefreshStopsAfterMetadataPermissionFailure() = runBlocking {
        withFixture {
            val file = audio("unavailable.mp3")
            val receipt = metadata("unavailable.mp3.npmeta.json", "owner", file)
            val songs = listOf(song(0, file))
            GlobalDownloadManager.publishDownloadedSongs(context, songs, persistCatalog = false)
            GlobalDownloadManager.downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
            context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.METADATA_READ_FAULT,
                receipt.uri.toString(), Bundle().apply { putString("fault", "permission"); putInt("remaining", 3) })
            val initialScanId = GlobalDownloadManager.emptyScanSequence.get()

            val result = GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)

            assertEquals(ManagedLibraryRefreshOutcome.Preserved(
                ManagedLibraryRefreshPreserveReason.INCOMPLETE_METADATA_READ), result)
            assertEquals(songs, GlobalDownloadManager.downloadedSongsMutable.value)
            assertEquals(1, counts().getInt("metadataReadFaults"))
            assertEquals(1L, GlobalDownloadManager.emptyScanSequence.get() - initialScanId)
        }
    }

    @Test fun unfencedSuspiciousEmptyRefreshStopsAfterThreeScansAndPreservesPresentPendingAudio() = runBlocking {
        withFixture {
            val temporary = requireNotNull(root.createDirectory(".tmp"))
            audio("retained.mp3.npdl_pending.owner.pending", temporary)
            val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
            val pending = snapshot.pendingAudioEntries.single()
            val songs = listOf(DownloadedSong(1L, "retained", "artist", "album", pending.reference,
                pending.sizeBytes, 1L, stableKey = "retained"))
            GlobalDownloadManager.publishDownloadedSongs(context, songs, persistCatalog = false)
            GlobalDownloadManager.downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
            val initialScanId = GlobalDownloadManager.emptyScanSequence.get()

            val result = GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)

            assertEquals(ManagedLibraryRefreshOutcome.Preserved(
                ManagedLibraryRefreshPreserveReason.SUSPICIOUS_EMPTY_RESULT), result)
            assertEquals(songs, GlobalDownloadManager.downloadedSongsMutable.value)
            assertEquals(3L, GlobalDownloadManager.emptyScanSequence.get() - initialScanId)
        }
    }

    private suspend fun withFixture(block: suspend () -> Unit) {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            GlobalDownloadManager.pendingDownloadRecoverySlot.withLock {
                check(!PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                check(!GlobalDownloadManager.isDownloadClearFenceActive(context))
                GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                GlobalDownloadManager.refreshJob?.join()
                GlobalDownloadManager.fastIndexPersistenceJob?.join()
                val previousRoot = ManagedDownloadStorage.configuredDirectoryUri()
                val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
                val previousCatalogRoot = GlobalDownloadManager.downloadedSongCatalogRootKey
                context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
                ManagedDownloadStorage.updateCustomDirectoryUri(treeUri.toString())
                GlobalDownloadManager.managedLibraryReconciler.reset()
                try {
                    block()
                } finally {
                    GlobalDownloadManager.refreshJob?.join()
                    GlobalDownloadManager.fastIndexPersistenceJob?.join()
                    GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                    GlobalDownloadManager.cancelScheduledDownloadedSongsCatalogPersist()
                    if (PersistentDownloadedSongDeleteIntentStore.hasPending(context)) {
                        val currentIntent = requireNotNull(PersistentDownloadedSongDeleteIntentStore.read(context))
                        val ownedIntent = requireNotNull(fixtureIntent)
                        check(currentIntent.rootKey == ownedIntent.rootKey &&
                            currentIntent.requestedAtMs == ownedIntent.requestedAtMs &&
                            currentIntent.targets == ownedIntent.targets) {
                            "a different delete intent must not be removed by the fixture"
                        }
                        assertTrue(PersistentDownloadedSongDeleteIntentStore.clear(context))
                    }
                    GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
                    GlobalDownloadManager.downloadedSongCatalogRootKey = previousCatalogRoot
                    ManagedDownloadStorage.updateCustomDirectoryUri(previousRoot)
                    GlobalDownloadManager.managedLibraryReconciler.reset()
                    assertTrue(GlobalDownloadManager.downloadedSongCatalogStore.persist(context, previousSongs))
                    context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
                }
            }
        }
    }

    private fun beginIntent(songs: List<DownloadedSong>) {
        GlobalDownloadManager.publishDownloadedSongs(context, songs, persistCatalog = false)
        val rootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        GlobalDownloadManager.downloadedSongCatalogRootKey = rootKey
        assertTrue(PersistentDownloadedSongDeleteIntentStore.begin(context, rootKey, songs))
        fixtureIntent = requireNotNull(PersistentDownloadedSongDeleteIntentStore.read(context))
    }

    private fun audio(name: String, parent: DocumentFile = root): DocumentFile =
        requireNotNull(parent.createFile("audio/mpeg", name)).also { write(it, "fixture audio") }

    private fun song(index: Int, file: DocumentFile) = DownloadedSong(
        index.toLong(), "song-$index", "artist", "album", file.uri.toString(),
        5_000_000L, 1L, stableKey = "song-$index"
    )

    private fun metadata(name: String, owner: String, file: DocumentFile, parent: DocumentFile = root): DocumentFile =
        requireNotNull(parent.createFile("application/json", name)).also {
            write(it, JSONObject().put("stableKey", "song-0").put("libraryId", "fixture-library")
                .put("audioFileName", file.name).put("operationId", owner).put("artifactId", owner)
                .put("mediaUri", file.uri.toString()).put("downloadFinalized", true).toString())
        }

    private fun write(file: DocumentFile, contents: String) {
        requireNotNull(context.contentResolver.openOutputStream(file.uri)).use { it.write(contents.toByteArray()) }
    }

    private fun counts() = requireNotNull(context.contentResolver.call(
        treeUri, ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT, null, null
    ))
}
