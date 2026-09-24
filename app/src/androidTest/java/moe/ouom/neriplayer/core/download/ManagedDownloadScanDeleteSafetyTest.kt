package moe.ouom.neriplayer.core.download

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.testing.awaitProcessDeathAtSeedCheckpoint
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadDeletePlanner
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.catalog.isDownloadedSongDeletionActive
import moe.ouom.neriplayer.core.download.manager.catalog.beginDownloadedSongDeleteSession
import moe.ouom.neriplayer.core.download.manager.catalog.deleteDownloadedSongsOnIo
import moe.ouom.neriplayer.core.download.manager.catalog.endDownloadedSongDeletion
import moe.ouom.neriplayer.core.download.manager.catalog.awaitAllDownloadedSongDeletions
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.catalog.reloadDownloadedSongs
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshPreserveReason
import moe.ouom.neriplayer.core.download.manager.batch.replayFullLibraryDeleteWithoutCatalog
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeletePhase
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeleteProgress
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking

@RunWith(AndroidJUnit4::class)
class ManagedDownloadScanDeleteSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )
    private val root get() = requireNotNull(DocumentFile.fromTreeUri(context, treeUri))
    private var previousRoot: String? = null
    private val deletePhase = InstrumentationRegistry.getArguments().getString("task5DeletePhase")
    private var keepDeleteFixture = false
    private var keepActiveDeleteFixture = false
    private val deleteMarker get() = File(context.cacheDir, "pr396-task5-delete-phase.json")

    @Before fun setup() {
        previousRoot = ManagedDownloadStorage.configuredDirectoryUri()
        require(deletePhase == null || deletePhase == "seed" || deletePhase == "recover")
        val recoverInterrupted = InstrumentationRegistry.getArguments()
            .getString("task5RecoverInterruptedDelete") == "true"
        require(!recoverInterrupted || deletePhase == null)
        if (deletePhase != "recover") {
            check(!deleteMarker.exists()) { "recover the seeded delete fixture before resetting it" }
            if (recoverInterrupted) {
                ManagedDownloadStorage.updateCustomDirectoryUri(treeUri.toString())
                runBlocking {
                    GlobalDownloadManager.startupRecoveryMutex.withLock {
                        val intent = requireNotNull(PersistentDownloadedSongDeleteIntentStore.read(context))
                        assertEquals(ManagedDownloadStorage.currentSnapshotCacheKey(context), intent.rootKey)
                        assertTrue(GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog(context))
                        assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                        assertFalse(GlobalDownloadManager.isDownloadClearFenceActive(context))
                    }
                }
            }
            check(!PersistentDownloadedSongDeleteIntentStore.hasPending(context)) {
                "recover the interrupted delete before resetting its provider fixture"
            }
            context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
        }
        ManagedDownloadStorage.updateCustomDirectoryUri(treeUri.toString())
    }

    @After fun cleanup() {
        if (keepActiveDeleteFixture) return
        ManagedDownloadStorage.updateCustomDirectoryUri(previousRoot)
        if (!keepDeleteFixture) {
            context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
        }
    }

    @Test fun scannedCatalogKeepsExplicitOpaqueCoverBeforeFullDelete() = runBlocking<Unit> {
        val fixture = deletionFixture()
        val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
        try {
            assertTrue(GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true)
                is ManagedLibraryRefreshOutcome.Published)
            val song = GlobalDownloadManager.downloadedSongsMutable.value.single()
            val line = "opaque cover selected=${song.coverPath} owned=${fixture.cover.uri} foreign=" +
                fixture.foreign.joinToString { "${it.name}:${it.uri}" }
            android.util.Log.i("NeriScanDeleteSafety", line)
            File(context.cacheDir, "pr396-scan-delete-performance.log").appendText("$line\n")
            assertEquals("catalog must preserve explicit cover identity", DocumentsContract.getDocumentId(fixture.cover.uri),
                DocumentsContract.getDocumentId(android.net.Uri.parse(requireNotNull(song.coverPath))))
        } finally {
            GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
            GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
        }
    }

    @Test fun largeLibraryForcedScansAndFullDeletePreserveIdentityAndForeignFiles() = runBlocking<Unit> {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            GlobalDownloadManager.pendingDownloadRecoverySlot.withLock {
                withTimeout(15_000) {
                    while (GlobalDownloadManager.finalizedCoverRepairActive.get() ||
                        GlobalDownloadManager.catalogReconcileJob?.isActive == true ||
                        GlobalDownloadManager.refreshJob?.isActive == true
                    ) delay(25)
                }
                GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
                val fixture = deletionFixture()
                var deletionStarted = false
                repeat(4095) { seed("large-$it") }
                ManagedDownloadStorage.snapshotCacheStore.invalidate()
                ManagedDownloadStorage.treeChildRegistry.clear()
                try {
                    val firstCounters = counts()
                    val firstStart = System.nanoTime()
                    assertTrue(GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true)
                        is ManagedLibraryRefreshOutcome.Published)
                    val first = GlobalDownloadManager.downloadedSongsMutable.value
                    assertEquals("missing=" + ((setOf("a") + (0 until 4095).map { "large-$it" }) - first.map { it.name }.toSet()) +
                        " cover=" + fixture.cover.uri + " snapshotCovers=" + ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)?.coverEntriesByName,
                        4096, first.size)
                    assertEquals(4096, first.map { it.stableKey }.toSet().size)
                    reportCounters("large library=4096 firstScan elapsedMs=${(System.nanoTime() - firstStart) / 1_000_000}", firstCounters)
                    val secondCounters = counts()
                    val secondStart = System.nanoTime()
                    assertTrue(GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true)
                        is ManagedLibraryRefreshOutcome.Published)
                    val second = GlobalDownloadManager.downloadedSongsMutable.value
                    assertEquals(first.associateBy { it.stableKey }, second.associateBy { it.stableKey })
                    reportCounters("large library=4096 secondForcedScan elapsedMs=${(System.nanoTime() - secondStart) / 1_000_000}", secondCounters)
                    val deleteCounters = counts()
                    val deleteStart = System.nanoTime()
                    deletionStarted = true
                    GlobalDownloadManager.deleteDownloadedSongsWithResult(context, second, true)
                    awaitFixtureDeleteSettled()
                    reportCounters("large library=4096 fullDelete elapsedMs=${(System.nanoTime() - deleteStart) / 1_000_000}", deleteCounters)
                    assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
                    assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                    assertFalse(fixture.cover.exists())
                    fixture.foreign.forEach { assertTrue("foreign must survive: ${it.uri}", it.exists()) }
                    assertTrue(root.listFiles().none { it.name?.endsWith(".mp3") == true || it.name?.endsWith(".npmeta.json") == true })
                } finally {
                    if (deletionStarted) {
                        try {
                            awaitFixtureDeleteSettled()
                        } catch (failure: Throwable) {
                            keepActiveDeleteFixture = true
                            throw failure
                        }
                    }
                    GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                    GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
                }
            }
        }
    }

    private suspend fun awaitFixtureDeleteSettled() = withTimeout(300_000) {
        while (GlobalDownloadManager.isDownloadedSongDeletionActive() ||
            PersistentDownloadedSongDeleteIntentStore.hasPending(context) ||
            GlobalDownloadManager.isDownloadClearFenceActive(context)
        ) delay(50)
    }

    @Test fun unavailableMetadataPreservesPublishedSnapshotUntilRetry() = runBlocking {
        val metadataA = seed("a")
        seed("b")
        val before = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals(2, before.metadataByAudioName.size)
        write(metadataA, metadata("a").put("customName", "force different size").toString())
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.METADATA_READ_FAULT,
            metadataA.uri.toString(), Bundle().apply { putString("fault", "provider"); putInt("remaining", 1) })
        runCatching { ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true) }
        assertEquals(1, counts().getInt("metadataReadFaults"))
        assertEquals("failed read must not publish a smaller snapshot", before.metadataByAudioName,
            ManagedDownloadStorage.cachedDownloadLibrarySnapshot(context)?.metadataByAudioName)
        val recovered = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals("force different size", recovered.metadataByAudioName["a.mp3"]?.customName)
        assertTrue(metadataA.delete())
        root.findFile("a.mp3")?.delete()
        val removed = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals(setOf("b.mp3"), removed.metadataByAudioName.keys)
    }

    @Test fun malformedMetadataDoesNotHideHealthyNewSong() = runBlocking {
        val metadataA = seed("a")
        seed("b")
        ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        write(metadataA, "{broken")
        seed("c")
        val refreshed = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals(setOf("b.mp3", "c.mp3"), refreshed.metadataByAudioName.keys)
        val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
        try {
            val outcome = GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true)
            assertTrue(outcome.toString(), outcome is ManagedLibraryRefreshOutcome.Published)
            assertEquals(setOf("b", "c"), GlobalDownloadManager.downloadedSongsMutable.value.map { it.name }.toSet())
        } finally {
            GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
        }
    }

    @Test fun forcedUnavailableScanPreservesCatalogWithoutSchedulingRetryLoop() = runBlocking {
        val metadataA = seed("a")
        seed("b")
        val original = GlobalDownloadManager.downloadedSongsMutable.value
        val rows = listOf("a", "b").map { name ->
            DownloadedSong(name.hashCode().toLong(), name, "artist", "album",
                requireNotNull(root.findFile("$name.mp3")).uri.toString(), 7, 1, stableKey = name)
        }
        GlobalDownloadManager.publishDownloadedSongs(context, rows, persistCatalog = false)
        ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        write(metadataA, metadata("a").put("customName", "force new read").toString())
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.METADATA_READ_FAULT,
            metadataA.uri.toString(), Bundle().apply { putString("fault", "permission"); putInt("remaining", 2) })
        val originalReconcile = GlobalDownloadManager.catalogReconcileJob
        try {
            repeat(2) {
                assertEquals(ManagedLibraryRefreshOutcome.Preserved(ManagedLibraryRefreshPreserveReason.INCOMPLETE_METADATA_READ),
                    GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true))
                assertEquals(rows, GlobalDownloadManager.downloadedSongsMutable.value)
                assertSame(originalReconcile, GlobalDownloadManager.catalogReconcileJob)
            }
            assertEquals(2, counts().getInt("metadataReadFaults"))
        } finally {
            GlobalDownloadManager.publishDownloadedSongs(context, original, persistCatalog = false)
        }
    }

    @Test fun fullDeleteWaitsForCompletionCallbackAndPreservesForeignFiles() = runBlocking<Unit> {
        val fixture = deletionFixture()
        val orphanMetadata = seed("orphan")
        val orphanAudio = requireNotNull(root.findFile("orphan.mp3"))
        val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
        assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
        GlobalDownloadManager.publishDownloadedSongs(context, listOf(fixture.song), persistCatalog = false)
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val operationId = "scan-delete-callback-gate"
        GlobalDownloadManager.assetEnrichmentCoordinator.enqueue(operationId, onCompletion = {
            callbackEntered.countDown()
            check(callbackRelease.await(15, TimeUnit.SECONDS))
        }) { }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        try {
            val before = counts()
            val startedAt = System.nanoTime()
            val deletion = async {
                GlobalDownloadManager.deleteDownloadedSongsWithResult(context, listOf(fixture.song), true)
            }
            delay(300)
            assertFalse("delete result must wait for physical cleanup", deletion.isCompleted)
            assertEquals("physical executor must wait for callback settlement", 0, counts().getInt("deleteCalls"))
            assertTrue(fixture.audio.exists())
            val deletionStartedAt = System.nanoTime()
            callbackRelease.countDown()
            val result = withTimeout(20_000) { deletion.await() }
            assertFalse(result.physicalCleanupPending)
            assertTrue(result.failedSongs.isEmpty())
            assertTrue(withTimeout(20_000) { GlobalDownloadManager.awaitAllDownloadedSongDeletions() })
            assertFalse(fixture.audio.exists())
            assertFalse(fixture.metadata.exists())
            assertFalse(fixture.cover.exists())
            assertFalse(orphanAudio.exists())
            assertFalse(orphanMetadata.exists())
            fixture.foreign.forEach { assertTrue("foreign file must survive: ${it.name}", it.exists()) }
            assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
            reportCounters("normal full delete totalMs=${(System.nanoTime() - startedAt) / 1_000_000} physicalAndFinalizationMs=${(System.nanoTime() - deletionStartedAt) / 1_000_000}", before)
        } finally {
            callbackRelease.countDown()
            GlobalDownloadManager.assetEnrichmentCoordinator.cancelAndJoin(setOf(operationId), "test cleanup", 5_000)
            GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
            GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
        }
    }

    @Test fun durableExactSidecarsReplayWithoutAudioMetadataOrCatalog() = runBlocking<Unit> {
        val previousProgress = GlobalDownloadManager.downloadedSongDeleteProgressMutable.value
        try {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            if (deletePhase != "recover") {
                val fixture = deletionFixture()
                val rootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
                assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                assertTrue(PersistentDownloadedSongDeleteIntentStore.begin(context, rootKey, listOf(fixture.song)))
                val plan = ManagedDownloadDeletePlanner().buildFullLibraryDeletePlan(context)
                assertTrue(plan.snapshotComplete)
                assertTrue(PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(context, rootKey, plan.requestedReferences))
                val core = setOf(fixture.audio.uri.toString(), fixture.metadata.uri.toString())
                assertEquals(core, ManagedDownloadStorage.deleteFullLibraryReferences(context, core))
                assertTrue(fixture.cover.exists())
                deleteMarker.writeText(JSONObject().put("pid", android.os.Process.myPid())
                    .put("cover", fixture.cover.uri.toString())
                    .put("foreign", org.json.JSONArray(fixture.foreign.map { it.uri.toString() })).toString())
                if (deletePhase == "seed") {
                    keepDeleteFixture = true
                    awaitProcessDeathAtSeedCheckpoint()
                    return@withLock
                }
            }
            val state = JSONObject(deleteMarker.readText())
            if (deletePhase == "recover") {
                assertNotEquals("recovery requires a fresh target process", state.getInt("pid"), android.os.Process.myPid())
            }
            ManagedDownloadStorage.snapshotCacheStore.invalidate()
            ManagedDownloadStorage.treeChildRegistry.clear()
            val before = counts()
            val startedAt = System.nanoTime()
            val waitingProgress = DownloadedSongDeleteProgress(
                deleteId = GlobalDownloadManager.downloadedSongDeleteIdGenerator.incrementAndGet(),
                phase = DownloadedSongDeletePhase.WAITING_FOR_DOWNLOADS,
                requestedSongCount = 1,
                fullLibraryDelete = true
            )
            GlobalDownloadManager.downloadedSongDeleteProgressMutable.value = waitingProgress
            assertTrue(GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog(context))
            val finishedProgress = requireNotNull(GlobalDownloadManager.downloadedSongDeleteProgressMutable.value)
            assertEquals(waitingProgress.deleteId, finishedProgress.deleteId)
            assertEquals(DownloadedSongDeletePhase.COMPLETED, finishedProgress.phase)
            assertEquals(0, finishedProgress.failedReferenceCount)
            val cover = requireNotNull(DocumentFile.fromSingleUri(context, android.net.Uri.parse(state.getString("cover"))))
            assertFalse(cover.exists())
            val foreign = state.getJSONArray("foreign")
            repeat(foreign.length()) {
                assertTrue(requireNotNull(DocumentFile.fromSingleUri(context, android.net.Uri.parse(foreign.getString(it)))).exists())
            }
            assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
            val deleteCount = counts().getInt("deleteCalls")
            assertFalse("no durable intent remains to replay", GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog(context))
            assertEquals("repeat replay must not delete anything", deleteCount, counts().getInt("deleteCalls"))
            reportCounters("replay phase=$deletePhase seedPid=${state.getInt("pid")} recoverPid=${android.os.Process.myPid()} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}", before)
            assertTrue(deleteMarker.delete())
        }
        } finally {
            GlobalDownloadManager.downloadedSongDeleteProgressMutable.value = previousProgress
        }
    }

    @Test fun partialDeleteKeepsSameBasenameForeignSidecars() = runBlocking {
        val fixture = deletionFixture()
        ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        val plan = ManagedDownloadDeletePlanner().buildDeletePlans(context, listOf(fixture.song)).single()
        assertEquals(plan.requestedReferences, ManagedDownloadStorage.deleteReferences(context, plan.requestedReferences))
        assertFalse(fixture.audio.exists())
        assertFalse(fixture.cover.exists())
        assertFalse(fixture.metadata.exists())
        fixture.foreign.forEach { assertTrue(it.exists()) }
    }

    @Test fun partialDeleteBatchReusesRawInventoryForUniqueLegacyReceipts() = runBlocking {
        val receipts = listOf(seed("unique-a"), seed("unique-b"))
        val songs = listOf("unique-a", "unique-b").map { name ->
            DownloadedSong(name.hashCode().toLong(), name, "artist", "album",
                requireNotNull(root.findFile("$name.mp3")).uri.toString(), 7, 1, stableKey = name)
        }
        ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        val before = counts()
        val plans = ManagedDownloadDeletePlanner().buildDeletePlans(context, songs)
        assertEquals("one raw root query must serve the whole batch", 1,
            counts().getInt("count") - before.getInt("count"))
        assertEquals(listOf(2, 2), plans.map { it.requestedReferences.size })
        val references = plans.flatMap { it.requestedReferences }.toSet()
        assertEquals(references, ManagedDownloadStorage.deleteReferences(context, references))
        receipts.forEach { assertFalse(it.exists()) }
    }

    @Test fun partialDeleteKeepsOtherOpaqueAudioMetadataAndCover() = duplicateAudioDelete("B")

    @Test fun partialDeleteKeepsAmbiguousLegacyMetadataAndCover() = duplicateAudioDelete(null)

    @Test fun partialDeleteKeepsSameNameSharedCoverEvenWhenMetadataMatchesSelectedAudio() = duplicateAudioDelete("A")

    @Test fun partialDeleteKeepsAmbiguousMetadataWithReverseEnumerationOrder() = duplicateAudioDelete(null, false)

    private fun duplicateAudioDelete(metadataOwner: String?, cacheWinnerIsA: Boolean = true) = runBlocking {
        val receipt = seed("song")
        val audioA = requireNotNull(root.findFile("song.mp3"))
        write(audioA, "larger audio selects canonical A")
        val audioB = requireNotNull(root.createFile("audio/mpeg", "other.mp3"))
        write(audioB, "B")
        assertEquals(1, context.contentResolver.update(audioB.uri, android.content.ContentValues().apply {
            put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "song.mp3")
        }, null, null))
        val covers = requireNotNull(root.createDirectory("Covers"))
        val cover = requireNotNull(covers.createFile("image/jpeg", "B.jpg"))
        write(cover, "B cover")
        val body = metadata("song").put("coverPath", cover.uri.toString())
        if (metadataOwner != null) body.put("mediaUri", (if (metadataOwner == "A") audioA else audioB).uri.toString())
        val otherReceipt = if (metadataOwner == "A") {
            val otherReceipt = requireNotNull(root.createFile("application/json", "other.mp3.npmeta.json"))
            write(otherReceipt, metadata("song").put("mediaUri", audioB.uri.toString())
                .put("coverPath", cover.uri.toString()).toString())
            context.contentResolver.update(otherReceipt.uri, android.content.ContentValues().apply {
                put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "song.mp3.npmeta.json")
            }, null, null)
            otherReceipt
        } else null
        write(receipt, body.toString())
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.LAST_QUERIED_CHILD,
            (if (cacheWinnerIsA) audioA else audioB).uri.toString(), null)
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        assertEquals(1, snapshot.audioEntries.size)
        assertEquals(DocumentsContract.getDocumentId(audioA.uri),
            DocumentsContract.getDocumentId(android.net.Uri.parse(snapshot.audioEntries.single().reference)))
        assertTrue(snapshot.coverEntriesByName.containsKey("B.jpg"))
        val cachedAudio = requireNotNull(ManagedDownloadStorage.treeDirectories.cachedRootEntries(
            ManagedDownloadStorage.resolveRootBlocking(context))).entries.single { it.name == "song.mp3" }
        assertEquals(DocumentsContract.getDocumentId((if (cacheWinnerIsA) audioA else audioB).uri),
            DocumentsContract.getDocumentId(android.net.Uri.parse(cachedAudio.reference)))
        val song = DownloadedSong(1, "song", "artist", "album", audioA.uri.toString(), 32, 1,
            stableKey = "song", coverPath = cover.uri.toString())
        val plan = ManagedDownloadDeletePlanner().buildDeletePlans(context, listOf(song)).single()
        assertEquals("only selected opaque audio may be planned", setOf(snapshot.audioEntries.single().reference), plan.requestedReferences)
        assertEquals(plan.requestedReferences, ManagedDownloadStorage.deleteReferences(context, plan.requestedReferences))
        assertFalse(audioA.exists())
        assertTrue(audioB.exists())
        assertTrue(receipt.exists())
        otherReceipt?.let { assertTrue(it.exists()) }
        assertTrue(cover.exists())
    }

    @Test fun fullyFlatLibraryPreservesUnreferencedFiles() = runBlocking {
        val receipt = seed("flat")
        val cover = requireNotNull(root.createFile("image/jpeg", "explicit.jpg")).also { write(it, "owned") }
        val foreign = requireNotNull(root.createFile("image/jpeg", "flat.jpg")).also { write(it, "foreign") }
        write(receipt, metadata("flat").put("coverPath", cover.uri.toString()).toString())
        val plan = ManagedDownloadDeletePlanner().buildFullLibraryDeletePlan(context)
        assertTrue(plan.snapshotComplete)
        assertEquals(3, plan.requestedReferences.size)
        assertEquals(plan.requestedReferences, ManagedDownloadStorage.deleteFullLibraryReferences(context, plan.requestedReferences))
        assertFalse(cover.exists())
        assertFalse(receipt.exists())
        assertTrue(foreign.exists())
    }

    @Test fun intentExpansionFailurePreventsPhysicalExecutor() = runBlocking {
        val fixture = deletionFixture()
        val originalSongs = GlobalDownloadManager.downloadedSongsMutable.value
        assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
        GlobalDownloadManager.publishDownloadedSongs(context, listOf(fixture.song), persistCatalog = false)
        val session = GlobalDownloadManager.beginDownloadedSongDeleteSession(context, listOf(fixture.song), true)
        val journal = File(context.filesDir, "downloaded_song_delete_intent_v1.json")
        val durableIntent = journal.readText()
        assertTrue(journal.delete())
        assertTrue(journal.mkdir())
        try {
            val result = GlobalDownloadManager.deleteDownloadedSongsOnIo(context, session)
            assertEquals(listOf(fixture.song), result.failedSongs)
            assertEquals(0, counts().getInt("deleteCalls"))
            assertTrue(fixture.audio.exists())
            assertTrue(fixture.cover.exists())
        } finally {
            assertTrue(journal.delete())
            journal.writeText(durableIntent)
            GlobalDownloadManager.endDownloadedSongDeletion(session.deletionKeys, context)
            assertTrue(GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog(context))
            GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
            GlobalDownloadManager.publishDownloadedSongs(context, originalSongs, persistCatalog = false)
        }
    }

    @Test fun staleCatalogCoverCannotAuthorizePartialDelete() = catalogCoverDelete(full = false)

    @Test fun deletedOpaqueTextReferenceIsConfirmedMissing() = runBlocking {
        val receipt = seed("deleted-text")
        val reference = DocumentsContract.buildDocumentUriUsingTree(treeUri,
            DocumentsContract.getDocumentId(receipt.uri)).toString()
        assertNotNull(ManagedDownloadStorage.readText(context, reference))
        assertTrue(receipt.delete())
        assertNull(ManagedDownloadStorage.readText(context, reference))
    }

    @Test fun unavailableExistingTextReferenceCannotBeDowngradedToMissing() = runBlocking {
        val receipt = seed("unavailable-text")
        for (fault in listOf("provider", "not-found", "permission")) {
            context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.METADATA_READ_FAULT,
                receipt.uri.toString(), Bundle().apply { putString("fault", fault); putInt("remaining", 1) })
            val failure = runCatching { ManagedDownloadStorage.readText(context, receipt.uri.toString()) }.exceptionOrNull()
            if (fault == "permission") assertTrue(failure.toString(), failure is SecurityException)
            else assertTrue(failure.toString(), failure is moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException)
            assertTrue(receipt.exists())
        }
        assertNotNull(ManagedDownloadStorage.readText(context, receipt.uri.toString()))
    }

    @Test fun failedExactStatCannotConfirmMissingText() = runBlocking {
        val receipt = seed("unknown-text")
        assertTrue(receipt.delete())
        for (fault in listOf("null", "permission", "failure")) {
            context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.REFERENCE_QUERY_FAULT,
                receipt.uri.toString(), Bundle().apply { putString("fault", fault) })
            val failure = runCatching { ManagedDownloadStorage.readText(context, receipt.uri.toString()) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException)
        }
    }

    @Test fun restoredStaleCatalogCoverCannotEnterDurableFullDelete() = catalogCoverDelete(full = true)

    @Test fun missingReceiptCannotAuthorizeCatalogCoverPartialDelete() = catalogCoverDelete(false, "missing")

    @Test fun missingReceiptCannotAuthorizeCatalogCoverFullDelete() = catalogCoverDelete(true, "missing")

    @Test fun malformedReceiptCannotAuthorizeCatalogCoverPartialDelete() = catalogCoverDelete(false, "malformed")

    @Test fun malformedReceiptCannotAuthorizeCatalogCoverFullDelete() = catalogCoverDelete(true, "malformed")

    @Test fun bareReceiptAndTreeCatalogCoverRemainOwnedForPartialDelete() = catalogCoverDelete(false, "alias")

    @Test fun bareReceiptAndTreeCatalogCoverRemainOwnedForFullDelete() = catalogCoverDelete(true, "alias")

    @Test fun unavailableReceiptAllowsExactAudioButKeepsStaleCatalogCoverOutOfFullDelete() = runBlocking {
        val fixture = deletionFixture()
        val stale = fixture.song.copy(coverPath = fixture.foreign.first().uri.toString())
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.METADATA_READ_FAULT,
            fixture.metadata.uri.toString(), Bundle().apply { putString("fault", "permission"); putInt("remaining", 1) })
        val plan = ManagedDownloadDeletePlanner().buildFullLibraryDeletePlan(context, listOf(stale))
        assertFalse(plan.snapshotComplete)
        assertEquals(1, plan.requestedReferences.size)
        assertEquals(DocumentsContract.getDocumentId(fixture.audio.uri),
            DocumentsContract.getDocumentId(android.net.Uri.parse(plan.requestedReferences.single())))
        assertEquals(0, counts().getInt("deleteCalls"))
        assertTrue(fixture.audio.exists())
        assertTrue(fixture.cover.exists())
        fixture.foreign.forEach { assertTrue(it.exists()) }
    }

    @Test fun conflictingPendingReceiptDoesNotBlockDeletingUnrelatedOwnedFiles() = runBlocking {
        val fixture = deletionFixture()
        val temporary = requireNotNull(root.findFile(".tmp"))
        val pending = requireNotNull(temporary.createFile("application/json", "a.mp3.npmeta.pending.json"))
        write(fixture.metadata, metadata("a").put("operationId", "formal-owner")
            .put("coverPath", fixture.cover.uri.toString()).toString())
        write(pending, metadata("a").put("operationId", "old-owner")
            .put("coverPath", fixture.cover.uri.toString()).toString())
        val healthyReceipt = seed("healthy")
        val healthyAudio = requireNotNull(root.findFile("healthy.mp3"))

        val plan = ManagedDownloadDeletePlanner().buildFullLibraryDeletePlan(context)

        assertFalse(plan.snapshotComplete)
        assertEquals(2, plan.requestedReferences.size)
        assertEquals(plan.requestedReferences, ManagedDownloadStorage.deleteFullLibraryReferences(context, plan.requestedReferences))
        assertFalse(healthyAudio.exists())
        assertFalse(healthyReceipt.exists())
        assertTrue(fixture.audio.exists())
        assertTrue(fixture.metadata.exists())
        assertTrue(pending.exists())
        assertTrue(fixture.cover.exists())
        fixture.foreign.forEach { assertTrue("foreign must survive: ${it.uri}", it.exists()) }
    }

    @Test fun fullDeleteWithUnknownPendingDoesNotRepublishPhysicallyDeletedSong() = runBlocking<Unit> {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            val fixture = deletionFixture()
            val temporary = requireNotNull(root.findFile(".tmp"))
            val unknownPending = requireNotNull(temporary.createFile(
                "application/octet-stream", "unknown.mp3.npdl_pending.test-owner.pending"
            ))
            write(unknownPending, "unconfirmed test core")
            val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
            val previousProgress = GlobalDownloadManager.downloadedSongDeleteProgressMutable.value
            val previousReconcile = GlobalDownloadManager.catalogReconcileJob
            try {
                GlobalDownloadManager.publishDownloadedSongs(context, listOf(fixture.song), persistCatalog = false)
                val result = withTimeout(20_000) {
                    GlobalDownloadManager.deleteDownloadedSongsWithResult(context, listOf(fixture.song), true)
                }
                assertEquals(listOf(fixture.song), result.deletedSongs)
                assertTrue(result.failedSongs.isEmpty())
                assertFalse(result.physicalCleanupPending)
                assertFalse(fixture.audio.exists())
                assertFalse(fixture.metadata.exists())
                assertFalse(fixture.cover.exists())
                assertTrue(unknownPending.exists())
                assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                assertFalse(GlobalDownloadManager.isDownloadClearFenceActive(context))
                assertEquals(DownloadedSongDeletePhase.FAILED,
                    GlobalDownloadManager.downloadedSongDeleteProgressMutable.value?.phase)
                assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
                withTimeout(20_000) { GlobalDownloadManager.reloadDownloadedSongs(context, forceRefresh = true) }
                assertTrue("refresh cannot resurrect the physically deleted song", GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
                fixture.foreign.forEach { assertTrue("foreign must survive: ${it.uri}", it.exists()) }
                assertTrue(unknownPending.delete())
                assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
            } finally {
                if (PersistentDownloadedSongDeleteIntentStore.hasPending(context)) {
                    if (unknownPending.exists()) unknownPending.delete()
                    if (!GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog(context)) {
                        keepActiveDeleteFixture = true
                    }
                }
                if (GlobalDownloadManager.catalogReconcileJob !== previousReconcile) {
                    GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                }
                GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
                GlobalDownloadManager.downloadedSongDeleteProgressMutable.value = previousProgress
            }
        }
    }

    private fun catalogCoverDelete(full: Boolean, receipt: String = "valid") = runBlocking<Unit> {
        val fixture = deletionFixture()
        val foreignCover = fixture.foreign.first()
        val stale = fixture.song.copy(coverPath = if (receipt == "alias")
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getDocumentId(fixture.cover.uri)).toString()
            else foreignCover.uri.toString())
        fun isDocument(reference: String, document: DocumentFile): Boolean {
            val uri = android.net.Uri.parse(reference)
            return uri.authority == document.uri.authority &&
                DocumentsContract.getDocumentId(uri) == DocumentsContract.getDocumentId(document.uri)
        }
        when (receipt) {
            "missing" -> assertTrue(fixture.metadata.delete())
            "malformed" -> write(fixture.metadata, "{broken")
            else -> write(fixture.metadata, metadata("a").put("mediaUri", fixture.audio.uri.toString())
                .put("coverPath", if (receipt == "alias") DocumentsContract.buildDocumentUri(
                    ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
                    DocumentsContract.getDocumentId(fixture.cover.uri)).toString() else fixture.cover.uri.toString()).toString())
        }
        val receiptOwnsCover = receipt == "valid" || receipt == "alias"
        if (!full) {
            val otherReceipt = seed("unselected")
            val otherAudio = requireNotNull(root.findFile("unselected.mp3"))
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
            val plan = ManagedDownloadDeletePlanner().buildDeletePlans(context, listOf(stale)).single()
            assertFalse("catalog cover is not a receipt", plan.requestedReferences.any { isDocument(it, foreignCover) })
            assertEquals(receiptOwnsCover, plan.requestedReferences.any { isDocument(it, fixture.cover) })
            assertEquals(plan.requestedReferences, ManagedDownloadStorage.deleteReferences(context, plan.requestedReferences))
            assertTrue(otherReceipt.exists())
            assertTrue(otherAudio.exists())
        } else {
            GlobalDownloadManager.startupRecoveryMutex.withLock {
                val rootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
                val catalogDirectory = File(context.cacheDir, "catalog-cover-${java.util.UUID.randomUUID()}").apply { mkdirs() }
                val catalogContext = object : android.content.ContextWrapper(context) {
                    override fun getApplicationContext(): android.content.Context = this
                    override fun getFilesDir(): File = catalogDirectory
                }
                val database = androidx.room.Room.inMemoryDatabaseBuilder(context,
                    moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase::class.java).build()
                val store = moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogRoomStore(
                    catalogContext, database, "catalog.json", { rootKey }, "CatalogCoverSafety")
                try {
                    store.persist(listOf(stale))
                    val restored = requireNotNull(store.restore()).single()
                    assertEquals(stale.coverPath, restored.coverPath)
                    assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                    assertTrue(PersistentDownloadedSongDeleteIntentStore.begin(context, rootKey, listOf(restored)))
                    val plan = ManagedDownloadDeletePlanner().buildFullLibraryDeletePlan(context, listOf(restored))
                    assertTrue(plan.snapshotComplete)
                    assertFalse("catalog cover is not a receipt", plan.requestedReferences.any { isDocument(it, foreignCover) })
                    assertEquals(receiptOwnsCover, plan.requestedReferences.any { isDocument(it, fixture.cover) })
                    assertTrue(PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(context, rootKey, plan.requestedReferences))
                    assertFalse(requireNotNull(PersistentDownloadedSongDeleteIntentStore.read(context)).ownedReferences
                        .any { isDocument(it, foreignCover) })
                    val core = plan.requestedReferences.filterTo(linkedSetOf()) {
                        isDocument(it, fixture.audio) || isDocument(it, fixture.metadata)
                    }
                    assertEquals(if (receiptOwnsCover) 2 else 1, core.size)
                    assertEquals(core, ManagedDownloadStorage.deleteFullLibraryReferences(context, core))
                    assertFalse(fixture.audio.exists())
                    if (receiptOwnsCover) assertFalse(fixture.metadata.exists())
                    ManagedDownloadStorage.snapshotCacheStore.invalidate()
                    ManagedDownloadStorage.treeChildRegistry.clear()
                    assertTrue(GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog(context))
                    assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                } finally {
                    assertTrue(PersistentDownloadedSongDeleteIntentStore.clear(context))
                    database.close()
                    assertTrue(catalogDirectory.deleteRecursively())
                }
            }
        }
        assertFalse(fixture.audio.exists())
        assertEquals(!receiptOwnsCover, fixture.cover.exists())
        fixture.foreign.forEach { assertTrue("foreign must survive: ${it.uri}", it.exists()) }
    }

    private data class DeleteFixture(val song: DownloadedSong, val audio: DocumentFile,
        val metadata: DocumentFile, val cover: DocumentFile, val foreign: List<DocumentFile>)

    private fun deletionFixture(): DeleteFixture {
        val metadata = seed("a")
        val audio = requireNotNull(root.findFile("a.mp3"))
        val covers = requireNotNull(root.createDirectory("Covers"))
        val lyrics = requireNotNull(root.createDirectory("Lyrics"))
        val tmp = requireNotNull(root.createDirectory(".tmp"))
        fun file(parent: DocumentFile, name: String) = requireNotNull(parent.createFile("text/plain", name)).also { write(it, "fixture") }
        val cover = file(covers, "owned.jpg")
        val foreign = listOf(file(covers, "a.jpg"), file(lyrics, "a.lrc"), file(root, "flat-foreign.jpg"),
            file(requireNotNull(covers.createDirectory("Personal")), "photos.txt"),
            file(requireNotNull(lyrics.createDirectory("Backup")), "notes.txt"),
            file(requireNotNull(tmp.createDirectory("foreign")), "file"))
        write(metadata, metadata("a").put("coverPath", cover.uri.toString()).toString())
        return DeleteFixture(DownloadedSong(1, "a", "artist", "album", audio.uri.toString(), 7, 1,
            coverPath = cover.uri.toString(), stableKey = "a"), audio, metadata, cover, foreign)
    }

    private fun seed(name: String): DocumentFile {
        write(requireNotNull(root.createFile("audio/mpeg", "$name.mp3")), "audio $name")
        return requireNotNull(root.createFile("application/json", "$name.mp3.npmeta.json")).also {
            write(it, metadata(name).toString())
        }
    }

    private fun metadata(name: String) = JSONObject().put("stableKey", name)
        .put("audioFileName", "$name.mp3").put("downloadFinalized", true).put("name", name)
        .put("metadataEmbeddingState", "EMBEDDED_VERIFIED")

    private fun write(file: DocumentFile, text: String) {
        requireNotNull(context.contentResolver.openOutputStream(file.uri, "wt")).use { it.write(text.toByteArray()) }
    }

    private fun counts() = requireNotNull(context.contentResolver.call(treeUri,
        ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT, null, null))

    private fun reportCounters(label: String, before: Bundle) {
        val values = counts()
        val line = "$label " +
            listOf("count", "allChildQueries", "metadataReads", "deleteCalls", "documentQueries", "documentPaths")
                .joinToString { "$it=${values.getInt(it) - before.getInt(it)}" }
        android.util.Log.i("NeriScanDeleteSafety", line)
        File(context.cacheDir, "pr396-scan-delete-performance.log").appendText("$line\n")
    }
}
