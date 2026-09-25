package moe.ouom.neriplayer.core.download

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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
import moe.ouom.neriplayer.core.download.storage.reference.ManagedMediaStoreDelete
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.testing.DocumentsFixture
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val tree = DocumentsFixture.createExternalTree()
        fixtureTree = tree
        fixtureDocument = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        check(context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }) { "real persisted SAF grant is required for benchmark tree $tree" }
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        assertTrue(childNames(rootDocument).isEmpty())
        report("fixture=$tree provider=$AUTHORITY hardware=${Build.HARDWARE} sdk=${Build.VERSION.SDK_INT}")
    }

    @After
    fun removeOnlyThisBenchmarkFixture() {
        if (configuredFixture && PersistentDownloadedSongDeleteIntentStore.hasPending(context)) {
            report("retained recovery fixture=$fixtureTree because durable delete intent is still pending")
            return
        }
        if (configuredFixture) ManagedDownloadStorage.updateCustomDirectoryUri(previousRoot)
        fixtureDocument?.let { check(DocumentsContract.deleteDocument(context.contentResolver, it)) }
        fixtureTree?.let { tree ->
            if (context.contentResolver.persistedUriPermissions.any { it.uri == tree }) {
                context.contentResolver.releasePersistableUriPermission(tree, READ_WRITE)
            }
            context.revokeUriPermission(tree, READ_WRITE)
        }
    }

    @Test
    fun mediaStoreMappedBatchConfirmsFilesAndPreservesDirectoriesAndForeignFiles() {
        val root = requireNotNull(fixtureDocument)
        val covers = create(root, DocumentsContract.Document.MIME_TYPE_DIR, "Covers")
        write(create(covers, "image/jpeg", "foreign.jpg"), "foreign")
        val targets = buildList {
            repeat(16) { index ->
                add(create(root, "audio/mpeg", "mapped-$index.mp3"))
                add(create(root, "application/json", "mapped-$index.npmeta.json"))
                add(create(covers, "image/jpeg", "mapped-$index.jpg"))
            }
        }
        targets.forEach { write(it, "fixture") }

        val confirmed = ManagedMediaStoreDelete.deleteConfirmed(context, targets + covers)

        assertEquals(targets.toSet(), confirmed)
        assertEquals(listOf("Covers"), childNames(root))
        assertEquals(listOf("foreign.jpg"), childNames(covers))
    }

    @Test
    fun mappedFileMovedWithinTheTreeIsNotRetargetedForDeletion() {
        val root = requireNotNull(fixtureDocument)
        val destination = create(root, DocumentsContract.Document.MIME_TYPE_DIR, "destination")
        val name = "moved.json"
        val original = create(root, "application/json", name)
        write(original, "fixture")
        val media = requireNotNull(MediaStore.getMediaUri(context, original))
        assertNotNull(ManagedMediaStoreDelete.resolveMappedTarget(context, original, media, name))

        // 通过原文档提供者移动文件，避免 MediaStore 对 JSON 文件的更新结果因系统版本而异
        requireNotNull(DocumentsContract.moveDocument(context.contentResolver, original, root, destination))

        assertNull(ManagedMediaStoreDelete.resolveMappedTarget(context, original, media, name))
        assertEquals(listOf(name), childNames(destination))
    }

    @Test
    fun largeMixedBatchKeepsConfirmedFilesAndBoundsDocumentsProviderFallback() {
        val root = requireNotNull(fixtureDocument)
        val foreign = create(root, "application/json", "foreign.json")
        write(foreign, "foreign")
        val targets = List(32) { create(root, "application/json", "mapped-$it.json") }
        targets.forEach { write(it, "fixture") }
        val directories = List(17) { create(root, DocumentsContract.Document.MIME_TYPE_DIR, "directory-$it") }

        val result = ManagedDownloadReferenceIo.deleteContentReferencesBatch(context, targets + directories)

        assertTrue(result.supported)
        assertEquals(List(targets.size) { ManagedDownloadReferenceIo.DeleteResult.Deleted }, result.results.take(targets.size))
        assertTrue(result.results.drop(targets.size).all { it is ManagedDownloadReferenceIo.DeleteResult.ProviderFailure })
        assertEquals((directories.indices.map { "directory-$it" } + "foreign.json").toSet(), childNames(root).toSet())
        // 小批次仍可以使用原 DocumentsProvider，不能把未执行的目录提前报告为成功
        directories.chunked(16).forEach { batch ->
            val fallback = ManagedDownloadReferenceIo.deleteContentReferencesBatch(context, batch)
            assertTrue(fallback.supported)
            assertEquals(List(batch.size) { ManagedDownloadReferenceIo.DeleteResult.Deleted }, fallback.results)
        }
        assertEquals(listOf("foreign.json"), childNames(root))
    }

    @Test
    fun publicFullDeleteHidesThousandSongsWithinFiveSecondsAndPhysicallyRemovesAll() = runBlocking<Unit> {
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
                    assertEquals(1_000, GlobalDownloadManager.downloadedSongsMutable.value.size)
                    val startedAt = SystemClock.elapsedRealtime()
                    val deletion = async(Dispatchers.IO) {
                        withTimeout(60_000) {
                            GlobalDownloadManager.deleteDownloadedSongsWithResult(context, songs, true)
                        }
                    }
                    withTimeout(5_000) {
                        GlobalDownloadManager.downloadedSongsMutable.first { it.isEmpty() }
                    }
                    val hiddenMs = SystemClock.elapsedRealtime() - startedAt
                    val result = deletion.await()
                    val physicalMs = SystemClock.elapsedRealtime() - startedAt
                    report("publicExternalStorageFullDelete songs=1000 refs=3000 hiddenMs=$hiddenMs physicalMs=$physicalMs " +
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
                    assertTrue("download list remained visible for $hiddenMs ms", hiddenMs <= 5_000)
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
