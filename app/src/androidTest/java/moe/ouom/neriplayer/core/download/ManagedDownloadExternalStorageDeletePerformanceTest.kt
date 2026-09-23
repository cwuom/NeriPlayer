package moe.ouom.neriplayer.core.download

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.manager.catalog.cancelScheduledDownloadedSongsCatalogPersist
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.catalog.findConfirmedMissingDownloadedSongs
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshOutcome
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 仅在模拟器创建专属目录，计时覆盖真实 ExternalStorageProvider 和公开删除入口 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
class ManagedDownloadExternalStorageDeletePerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var fixtureDocument: Uri? = null
    private var fixtureTree: Uri? = null
    private var previousRoot: String? = null
    private var configuredFixture = false

    @Before
    fun createIsolatedExternalStorageFixture() = runBlocking<Unit> {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "this benchmark must run on an emulator, hardware=${Build.HARDWARE}"
        }
        awaitInitialDownloadWork()
        check(!PersistentDownloadedSongDeleteIntentStore.hasPending(context)) {
            "recover the existing delete intent before starting the benchmark"
        }
        previousRoot = ManagedDownloadStorage.configuredDirectoryUri()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        val primary = DocumentsContract.buildDocumentUri(AUTHORITY, "primary:")
        val created = requireNotNull(DocumentsContract.createDocument(context.contentResolver, primary,
            DocumentsContract.Document.MIME_TYPE_DIR, "NeriPlayer-delete-benchmark-${UUID.randomUUID()}"))
        fixtureDocument = created
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, DocumentsContract.getDocumentId(created))
        fixtureTree = tree
        context.grantUriPermission(context.packageName, tree, READ_WRITE or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        context.contentResolver.takePersistableUriPermission(tree, READ_WRITE)
        check(context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }) { "real persisted SAF grant is required for benchmark tree $tree" }
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        assertTrue(childNames(rootDocument).isEmpty())
        report("fixture=$tree provider=$AUTHORITY hardware=${Build.HARDWARE} sdk=${Build.VERSION.SDK_INT}")
    }

    @After
    fun removeOnlyThisBenchmarkFixture() {
        try {
            if (configuredFixture && PersistentDownloadedSongDeleteIntentStore.hasPending(context)) {
                report("retained recovery fixture=$fixtureTree because durable delete intent is still pending")
                return
            }
            if (configuredFixture) ManagedDownloadStorage.updateCustomDirectoryUri(previousRoot)
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            fixtureDocument?.let { check(DocumentsContract.deleteDocument(context.contentResolver, it)) }
            fixtureTree?.let { tree ->
                if (context.contentResolver.persistedUriPermissions.any { it.uri == tree }) {
                    context.contentResolver.releasePersistableUriPermission(tree, READ_WRITE)
                }
                context.revokeUriPermission(tree, READ_WRITE)
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun publicFullDeletePhysicallyRemovesThousandSongsOnExternalStorageWithinFiveSeconds() = runBlocking<Unit> {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            GlobalDownloadManager.pendingDownloadRecoverySlot.withLock {
                configureFixtureRoot()
                val tree = requireNotNull(fixtureTree)
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val covers = create(root, DocumentsContract.Document.MIME_TYPE_DIR, "Covers")
                write(create(covers, "image/jpeg", "foreign.jpg"), "foreign")
                val songs = ArrayList<DownloadedSong>(1_000)
                repeat(1_000) { index ->
                    val name = "external-$index.mp3"
                    val audio = create(root, "audio/mpeg", name)
                    val cover = create(covers, "image/jpeg", "owned-$index.jpg")
                    val metadata = create(root, "application/json", "$name.npmeta.json")
                    write(audio, "audio-$index")
                    write(cover, "cover-$index")
                    write(metadata, JSONObject()
                        .put("stableKey", "external-$index")
                        .put("operationId", "external-operation-$index")
                        .put("artifactId", "external-artifact-$index")
                        .put("libraryId", "external-benchmark-library")
                        .put("audioFileName", name)
                        .put("mediaUri", audio.toString())
                        .put("coverPath", cover.toString())
                        .put("downloadFinalized", true).toString())
                    songs += DownloadedSong(index.toLong(), "external-$index", "artist", "album",
                        audio.toString(), 10, 1, stableKey = "external-$index")
                }
                ManagedDownloadStorage.snapshotCacheStore.invalidate()
                ManagedDownloadStorage.treeChildRegistry.clear()
                val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
                val previousProgress = GlobalDownloadManager.downloadedSongDeleteProgressMutable.value
                val previousReconcile = GlobalDownloadManager.catalogReconcileJob
                try {
                    GlobalDownloadManager.publishDownloadedSongs(context, songs, persistCatalog = false)
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = withTimeout(60_000) {
                        GlobalDownloadManager.deleteDownloadedSongsWithResult(context, songs, true)
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    report("publicExternalStorageFullDelete songs=1000 refs=3000 elapsedMs=$elapsedMs " +
                        "deletedSongs=${result.deletedSongs.size} failedSongs=${result.failedSongs.size} " +
                        "cleanupPending=${result.physicalCleanupPending}")
                    assertEquals(1_000, result.deletedSongs.size)
                    assertTrue(result.failedSongs.isEmpty())
                    assertFalse(result.physicalCleanupPending)
                    assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                    assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
                    assertTrue(childNames(root).none { it.endsWith(".mp3") || it.endsWith(".npmeta.json") })
                    assertEquals(listOf("foreign.jpg"), childNames(covers))
                    assertEquals(1_000, findConfirmedMissingDownloadedSongs(context, songs).size)
                    assertTrue("real ExternalStorageProvider exceeded five seconds: $elapsedMs ms", elapsedMs <= 5_000)
                } finally {
                    if (GlobalDownloadManager.catalogReconcileJob !== previousReconcile) {
                        GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                    }
                    GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
                    GlobalDownloadManager.downloadedSongDeleteProgressMutable.value = previousProgress
                    if (!PersistentDownloadedSongDeleteIntentStore.hasPending(context)) {
                        ManagedDownloadStorage.updateCustomDirectoryUri(previousRoot)
                    }
                }
            }
        }
    }

    @Test
    fun publicForcedRefreshConfirmsExternallyDeletedAudioAndSidecarDirectory() = runBlocking<Unit> {
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            GlobalDownloadManager.pendingDownloadRecoverySlot.withLock {
                configureFixtureRoot()
                val previousSongs = GlobalDownloadManager.downloadedSongsMutable.value
                val previousCatalogRoot = GlobalDownloadManager.downloadedSongCatalogRootKey
                val previousReconcile = GlobalDownloadManager.catalogReconcileJob
                val previousPersist = GlobalDownloadManager.catalogPersistJob
                val previousFastIndex = GlobalDownloadManager.fastIndexPersistenceJob
                try {
                    val tree = requireNotNull(fixtureTree)
                    val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val audio = create(root, "audio/mpeg", "external-refresh.mp3")
                    val metadata = create(root, "application/json", "external-refresh.mp3.npmeta.json")
                    val covers = create(root, DocumentsContract.Document.MIME_TYPE_DIR, "Covers")
                    val cover = create(covers, "image/jpeg", "external-refresh.jpg")
                    write(audio, "fixture audio")
                    write(cover, "fixture cover")
                    write(metadata, JSONObject()
                        .put("stableKey", "external-refresh")
                        .put("operationId", "external-refresh-operation")
                        .put("artifactId", "external-refresh-artifact")
                        .put("libraryId", "external-refresh-library")
                        .put("audioFileName", "external-refresh.mp3")
                        .put("mediaUri", audio.toString())
                        .put("coverPath", cover.toString())
                        .put("downloadFinalized", true).toString())
                    val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
                    assertEquals(1, snapshot.audioEntries.size)
                    assertEquals(1, snapshot.coverEntriesByName.size)
                    val song = DownloadedSong(1, "external-refresh", "artist", "album", audio.toString(),
                        13, 1, stableKey = "external-refresh")
                    GlobalDownloadManager.publishDownloadedSongs(context, listOf(song), persistCatalog = false)
                    GlobalDownloadManager.downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
                    GlobalDownloadManager.managedLibraryReconciler.reset()
                    assertTrue(DocumentsContract.deleteDocument(context.contentResolver, audio))
                    assertTrue(DocumentsContract.deleteDocument(context.contentResolver, metadata))
                    assertTrue(DocumentsContract.deleteDocument(context.contentResolver, covers))
                    val childrenBeforeRefresh = childNames(root)
                    assertTrue("unexpected files before refresh: $childrenBeforeRefresh", childrenBeforeRefresh.isEmpty())
                    val initialScanId = GlobalDownloadManager.emptyScanSequence.get()

                    val result = GlobalDownloadManager.scanLocalFilesAwait(context, forceRefresh = true)

                    assertTrue(result.toString(), result is ManagedLibraryRefreshOutcome.Published)
                    assertEquals(0, (result as ManagedLibraryRefreshOutcome.Published).songCount)
                    assertTrue(GlobalDownloadManager.downloadedSongsMutable.value.isEmpty())
                    assertFalse(PersistentDownloadedSongDeleteIntentStore.hasPending(context))
                    val scanCount = GlobalDownloadManager.emptyScanSequence.get() - initialScanId
                    assertTrue("confirmation must use bounded independent scans: $scanCount", scanCount in 2L..3L)
                    assertTrue(childNames(root).none { it.endsWith(".mp3") || it.endsWith(".npmeta.json") || it == "Covers" })
                } finally {
                    GlobalDownloadManager.refreshJob?.join()
                    if (GlobalDownloadManager.catalogReconcileJob !== previousReconcile) {
                        GlobalDownloadManager.catalogReconcileJob?.cancelAndJoin()
                    }
                    if (GlobalDownloadManager.fastIndexPersistenceJob !== previousFastIndex) {
                        GlobalDownloadManager.fastIndexPersistenceJob?.cancelAndJoin()
                    }
                    if (GlobalDownloadManager.catalogPersistJob !== previousPersist) {
                        val fixturePersist = GlobalDownloadManager.catalogPersistJob
                        GlobalDownloadManager.cancelScheduledDownloadedSongsCatalogPersist()
                        fixturePersist?.join()
                    }
                    GlobalDownloadManager.publishDownloadedSongs(context, previousSongs, persistCatalog = false)
                    GlobalDownloadManager.downloadedSongCatalogRootKey = previousCatalogRoot
                    ManagedDownloadStorage.updateCustomDirectoryUri(previousRoot)
                    GlobalDownloadManager.managedLibraryReconciler.reset()
                    assertTrue(GlobalDownloadManager.downloadedSongCatalogStore.persist(context, previousSongs))
                }
            }
        }
    }

    private suspend fun awaitInitialDownloadWork() = withTimeout(60_000) {
        GlobalDownloadManager.initialize(context)
        GlobalDownloadManager.startupProgressRestoreReady.await()
        // ready 只代表任务进度已恢复，初始目录扫描仍在这个锁内继续执行
        GlobalDownloadManager.startupRecoveryMutex.withLock { }
        val snapshotParent = requireNotNull(ManagedDownloadStorage.snapshotScope.coroutineContext[Job])
        fun pendingJobs(): List<Job> = (snapshotParent.children.toList() + listOfNotNull(
            GlobalDownloadManager.refreshJob,
            GlobalDownloadManager.catalogReconcileJob,
            GlobalDownloadManager.fastIndexPersistenceJob,
            GlobalDownloadManager.catalogPersistJob,
            GlobalDownloadManager.terminalTemporaryWriteCleanupJob
        )).distinct()
        while (true) {
            val observedJobs = pendingJobs()
            observedJobs.joinAll()
            GlobalDownloadManager.downloadedSongMetadataSyncMutex.withLock { }
            // storage cleanup 可在结束时派生对账任务，重新取任务集合确认也已完成
            yield()
            val currentJobs = pendingJobs()
            if (currentJobs.all { it.isCompleted && it in observedJobs } &&
                !GlobalDownloadManager.startupArtifactRecoveryActive.get() &&
                !GlobalDownloadManager.finalizedCoverRepairActive.get() &&
                !GlobalDownloadManager.pendingRefresh &&
                !GlobalDownloadManager.pendingForceRefresh
            ) {
                break
            }
        }
    }

    private fun configureFixtureRoot() {
        check(!configuredFixture)
        // 测试持有恢复锁后才换根，排队的启动收尾不能写入 fixture
        ManagedDownloadStorage.updateCustomDirectoryUri(requireNotNull(fixtureTree).toString())
        configuredFixture = true
    }

    private fun childNames(parent: Uri): List<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(requireNotNull(fixtureTree),
            DocumentsContract.getDocumentId(parent))
        return requireNotNull(context.contentResolver.query(children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun create(parent: Uri, mimeType: String, name: String): Uri =
        requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, mimeType, name))

    private fun write(uri: Uri, value: String) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(value.toByteArray()) }
    }

    private fun report(message: String) {
        android.util.Log.i("NeriFullDeletePerformance", message)
        File(context.cacheDir, "external-storage-delete-1000-performance.log").appendText("$message\n")
    }

    companion object {
        private const val AUTHORITY = "com.android.externalstorage.documents"
        private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
