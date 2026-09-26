package moe.ouom.neriplayer.core.download

import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.batch.replayFullLibraryDeleteWithoutCatalog
import moe.ouom.neriplayer.core.download.manager.batch.clearPersistedDownloadClearProgress
import moe.ouom.neriplayer.core.download.manager.batch.finishReleasedTaskClearState
import moe.ouom.neriplayer.core.download.manager.catalog.cancelScheduledDownloadedSongsCatalogPersist
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadPartialReplayCatalogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )

    @Test fun partialStartupReplayImmediatelyRemovesConfirmedAudioFromCatalog() = runBlocking {
        withFixture { fixture ->
            assertFalse(GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog(context))
            assertFalse(fixture.audio.exists())
            assertTrue(fixture.pending.exists())
            assertTrue(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
            assertTrue("successful audio deletion must update the visible catalog before residual cleanup",
                GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
        }
    }

    @Test fun replayWithNoNewDeleteResultsPrunesAudioDeletedBeforeRestart() = runBlocking {
        withFixture { fixture ->
            assertTrue(fixture.audio.delete())
            assertTrue(fixture.receipt.delete())
            assertFalse(GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog(context))
            assertTrue(fixture.pending.exists())
            assertTrue("an empty execution plan must not restore already missing audio",
                GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
        }
    }

    @Test fun repeatedPublicDeleteConfirmsMissingAudioWhileRetainingUnownedPending() = runBlocking {
        withFixture { fixture ->
            assertTrue(fixture.audio.delete())
            assertTrue(fixture.receipt.delete())
            val result = withTimeout(20_000) {
                GlobalDownloadManager.deleteDownloadedSongsWithResult(context, listOf(fixture.song), true)
            }
            assertEquals(listOf(fixture.song), result.deletedSongs)
            assertTrue(result.failedSongs.isEmpty())
            assertFalse(result.physicalCleanupPending)
            assertTrue(fixture.pending.exists())
            assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
            assertFalse(GlobalDownloadManager.isDownloadClearFenceActive(context))
            assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
        }
    }

    private data class Fixture(
        val audio: DocumentFile,
        val receipt: DocumentFile,
        val pending: DocumentFile,
        val song: DownloadedSong
    )

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            GlobalDownloadManager.pendingDownloadRecoverySlot.withLock {
                check(!PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                val previousRoot = ManagedDownloadStorage.configuredDirectoryUri()
                val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
                val previousProgress = GlobalDownloadManager.downloadedSongDeleteProgressMutable.value
                val previousCatalogRoot = GlobalDownloadManager.downloadedSongCatalogRootKey
                context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
                ManagedDownloadStorage.updateCustomDirectoryUri(treeUri.toString())
                val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri))
                val audio = requireNotNull(root.createFile("audio/mpeg", "replay.mp3"))
                val receipt = requireNotNull(root.createFile("application/json", "replay.mp3.npmeta.json"))
                val temporary = requireNotNull(root.createDirectory(".tmp"))
                val pending = requireNotNull(temporary.createFile(
                    "application/octet-stream", "unknown.mp3.npdl_pending.test-owner.pending"
                ))
                write(audio, "audio")
                write(pending, "unknown")
                write(receipt, JSONObject().put("stableKey", "replay")
                    .put("audioFileName", "replay.mp3").put("mediaUri", audio.uri.toString())
                    .put("downloadFinalized", true).toString())
                val song = DownloadedSong(1L, "replay", "artist", "album", audio.uri.toString(),
                    5L, 1L, stableKey = "replay")
                val fixture = Fixture(audio, receipt, pending, song)
                GlobalDownloadManager.publishDownloadedSongs(context, listOf(song), persistCatalog = false)
                assertTrue(PersistentDownloadedSongDeleteIntentStore.begin(context,
                    ManagedDownloadStorage.currentSnapshotCacheKey(context), listOf(song)))
                val fixtureIntent = requireNotNull(PersistentDownloadedSongDeleteIntentStore.read(context))
                try {
                    block(fixture)
                } finally {
                    GlobalDownloadManager.downloadedSongDeleteMutex.withLock {
                        PersistentDownloadedSongDeleteIntentStore.read(context)?.let { currentIntent ->
                            check(currentIntent.rootKey == fixtureIntent.rootKey &&
                                currentIntent.requestedAtMs == fixtureIntent.requestedAtMs &&
                                currentIntent.targets == fixtureIntent.targets)
                            assertTrue(PersistentDownloadedSongDeleteIntentStore.clear(context))
                            assertTrue(PersistentDownloadClearFenceStore.clear(context))
                        }
                    }
                    withTimeout(20_000) {
                        while (GlobalDownloadManager.deferredFullDeleteRecoveryScheduled.get()) delay(25)
                    }
                    GlobalDownloadManager.clearPersistedDownloadClearProgress(context)
                    GlobalDownloadManager.finishReleasedTaskClearState(context)
                    assertNotNull(GlobalDownloadManager.downloadAdmissionGate.openTicketOrNull())
                    GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                    GlobalDownloadManager.cancelScheduledDownloadedSongsCatalogPersist()
                    GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
                    GlobalDownloadManager.downloadedSongCatalogRootKey = previousCatalogRoot
                    GlobalDownloadManager.downloadedSongDeleteProgressMutable.value = previousProgress
                    ManagedDownloadStorage.updateCustomDirectoryUri(previousRoot)
                    context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
                }
            }
        }
    }

    private fun write(file: DocumentFile, value: String) {
        requireNotNull(context.contentResolver.openOutputStream(file.uri, "wt")).use {
            it.write(value.toByteArray())
        }
    }
}
