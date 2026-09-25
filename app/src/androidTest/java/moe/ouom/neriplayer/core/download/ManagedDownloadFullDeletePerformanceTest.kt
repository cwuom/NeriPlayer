package moe.ouom.neriplayer.core.download

import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadDeletePlanner
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadFullDeletePerformanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )
    private var previousRoot: String? = null
    private var fixtureStarted = false

    @Before
    fun prepareFixture() {
        check(!PersistentDownloadedSongDeleteIntentStore.hasPending(context)) {
            "recover the existing delete intent before resetting the performance fixture"
        }
        previousRoot = ManagedDownloadStorage.configuredDirectoryUri()
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
        fixtureStarted = true
        ManagedDownloadStorage.updateCustomDirectoryUri(treeUri.toString())
    }

    @After
    fun releaseFixture() {
        if (!fixtureStarted) return
        if (PersistentDownloadedSongDeleteIntentStore.hasPending(context)) return
        ManagedDownloadStorage.updateCustomDirectoryUri(previousRoot)
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
    }

    @Test
    fun thousandSongsArePhysicallyDeletedWithinFiveSecondsOnLocalProvider() = runBlocking {
        val fixture = seedLibrary()
        val planner = ManagedDownloadDeletePlanner()
        val before = counters()
        val startedAt = SystemClock.elapsedRealtime()
        val plan = planner.buildFullLibraryDeletePlan(context)
        val plannedAt = SystemClock.elapsedRealtime()
        assertTrue(plan.snapshotComplete)
        assertEquals(3_000, plan.requestedReferences.size)
        val deleted = ManagedDownloadStorage.deleteFullLibraryReferences(context, plan.requestedReferences)
        val deletedAt = SystemClock.elapsedRealtime()
        val verification = planner.buildFullLibraryDeletePlan(context)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val after = counters()
        val report = "songs=1000 references=3000 planMs=${plannedAt - startedAt} " +
            "deleteMs=${deletedAt - plannedAt} totalWithVerificationMs=$elapsedMs " +
            "deleteCalls=${after.getInt("deleteCalls") - before.getInt("deleteCalls")} " +
            "childQueries=${after.getInt("allChildQueries") - before.getInt("allChildQueries")}"
        report(report)
        assertEquals(plan.requestedReferences, deleted)
        assertTrue(verification.snapshotComplete)
        assertTrue(verification.requestedReferences.isEmpty())
        assertPhysicalDeletion(fixture)
        assertTrue("physical planning, deletion and verification exceeded five seconds: $report", elapsedMs <= 5_000)
    }

    @Test
    fun publicFullDeleteFinishesThousandSongsAndDurableCleanupWithinFiveSeconds() = runBlocking<Unit> {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            GlobalDownloadManager.pendingDownloadRecoverySlot.withLock {
                val fixture = seedLibrary()
                val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
                val previousProgress = GlobalDownloadManager.downloadedSongDeleteProgressMutable.value
                val previousReconcile = GlobalDownloadManager.catalogReconcileJob
                try {
                    GlobalDownloadManager.publishDownloadedSongs(context, fixture.songs, persistCatalog = false)
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = withTimeout(30_000) {
                        GlobalDownloadManager.deleteDownloadedSongsWithResult(context, fixture.songs, true)
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    val report = "publicFullDelete songs=1000 references=3000 totalMs=$elapsedMs " +
                        "deletedSongs=${result.deletedSongs.size} failedSongs=${result.failedSongs.size} " +
                        "pending=${result.physicalCleanupPending}"
                    report(report)
                    assertEquals(1_000, result.deletedSongs.size)
                    assertTrue(result.failedSongs.isEmpty())
                    assertFalse(result.physicalCleanupPending)
                    assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                    assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
                    assertPhysicalDeletion(fixture)
                    assertTrue("public full deletion exceeded five seconds: $report", elapsedMs <= 5_000)
                } finally {
                    if (GlobalDownloadManager.catalogReconcileJob !== previousReconcile) {
                        GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                    }
                    GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
                    GlobalDownloadManager.downloadedSongDeleteProgressMutable.value = previousProgress
                }
            }
        }
    }

    private data class Fixture(val root: DocumentFile, val covers: DocumentFile, val songs: List<DownloadedSong>)

    private fun seedLibrary(): Fixture {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri))
        val covers = requireNotNull(root.createDirectory("Covers"))
        val foreign = requireNotNull(covers.createFile("image/jpeg", "foreign.jpg"))
        write(foreign, "foreign")
        val songs = ArrayList<DownloadedSong>(1_000)
        repeat(1_000) { index ->
            val audioName = "performance-$index.mp3"
            val audio = requireNotNull(root.createFile("audio/mpeg", audioName))
            val cover = requireNotNull(covers.createFile("image/jpeg", "owned-$index.jpg"))
            val metadata = requireNotNull(root.createFile("application/json", "$audioName.npmeta.json"))
            write(audio, "audio-$index")
            write(cover, "cover-$index")
            write(metadata, JSONObject()
                .put("stableKey", "performance-$index")
                .put("operationId", "operation-$index")
                .put("artifactId", "artifact-$index")
                .put("libraryId", "performance-library")
                .put("audioFileName", audioName)
                .put("mediaUri", audio.uri.toString())
                .put("downloadFinalized", true)
                .put("coverPath", cover.uri.toString())
                .toString())
            songs += DownloadedSong(index.toLong(), "performance-$index", "artist", "album",
                audio.uri.toString(), 10, 1, stableKey = "performance-$index")
        }
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        ManagedDownloadStorage.treeChildRegistry.clear()
        return Fixture(root, covers, songs)
    }

    private fun assertPhysicalDeletion(fixture: Fixture) {
        assertTrue(fixture.root.listFiles().none { it.name?.endsWith(".mp3") == true || it.name?.endsWith(".npmeta.json") == true })
        val coverChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(fixture.covers.uri)
        )
        val remainingCoverNames = requireNotNull(context.contentResolver.query(
            coverChildrenUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(listOf("foreign.jpg"), remainingCoverNames)
    }

    private fun report(message: String) {
        android.util.Log.i("NeriFullDeletePerformance", message)
        File(context.cacheDir, "full-delete-1000-performance.log").appendText("$message\n")
    }

    private fun write(file: DocumentFile, value: String) {
        requireNotNull(context.contentResolver.openOutputStream(file.uri, "wt")).use { output ->
            output.write(value.toByteArray())
        }
    }

    private fun counters() = requireNotNull(context.contentResolver.call(
        treeUri, ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT, null, null
    ))
}
