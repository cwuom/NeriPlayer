package moe.ouom.neriplayer.core.download

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadCommitSnapshotRetryTest {
    @Test
    fun completeScanLosingToOneMetadataDeltaRetriesWithoutReplacingForeignAudio() = runBlocking {
        withStorage {
            val foreignBytes = byteArrayOf(9, 8, 7)
            val foreign = createDocument(FILE_NAME, foreignBytes)
            val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = "concurrent|netease|",
                songId = 42L,
                name = "Concurrent song",
                artist = "Artist",
                customName = "Latest title",
                operationId = "concurrent-operation",
                downloadFinalized = true
            )
            val metadataDocument = createDocument(
                "concurrent.mp3.npmeta.json",
                JSONObject()
                    .put("stableKey", metadata.stableKey)
                    .put("songId", metadata.songId)
                    .put("name", metadata.name)
                    .put("artist", metadata.artist)
                    .put("customName", metadata.customName)
                    .put("operationId", metadata.operationId)
                    .put("downloadFinalized", true)
                    .toString().toByteArray()
            )
            val metadataEntry = requireNotNull(
                ManagedDownloadStoredEntryMapper.fromDocumentFile(metadataDocument)
            )
            val working = workingFile()
            val partialBeforeScan = ManagedDownloadSnapshotIndex.compose(
                audioEntries = emptyList(),
                metadataEntries = emptyList(),
                metadataByAudioName = emptyMap(),
                coverEntries = emptyList(),
                lyricEntries = emptyList(),
                rootEntriesComplete = false,
                sidecarEntriesComplete = false
            )
            ManagedDownloadStorage.snapshotCacheStore.putSnapshot(
                context,
                ManagedDownloadStorage.snapshotCacheStore.currentKey(context),
                partialBeforeScan
            )
            assertFalse(requireNotNull(ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(
                context, restorePersisted = false
            )).rootEntriesComplete)
            val revision = ManagedDownloadStorage.snapshotCacheStore.snapshotRevision()

            val result = commitAfterSnapshotCapture(working) {
                assertTrue(ManagedDownloadStorage.snapshotCacheStore.updateAfterMetadataWrite(
                    context, metadataEntry, metadata
                ))
                val partial = requireNotNull(ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(
                    context, restorePersisted = false
                ))
                assertFalse("the racing delta must start from a partial snapshot", partial.rootEntriesComplete)
                assertTrue(ManagedDownloadStorage.snapshotCacheStore.snapshotRevision() > revision)
            }
            val stored = result.getOrThrow()

            assertTrue(stored.isPendingAudioWrite)
            assertNotEquals("unknown same-name audio must not be claimed", FILE_NAME, stored.logicalName)
            assertArrayEquals(PAYLOAD, read(Uri.parse(stored.reference)))
            assertArrayEquals(foreignBytes, read(foreign.uri))
            assertFalse("successful core commit consumes the verified working copy", working.exists())
            val snapshot = requireNotNull(ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(
                context, restorePersisted = false
            ))
            assertTrue(snapshot.rootEntriesComplete)
            assertEquals("Latest title", snapshot.metadataByAudioName["concurrent.mp3"]?.customName)
            assertEquals(listOf(stored.reference), snapshot.pendingAudioEntries.map { it.reference })
        }
    }

    @Test
    fun persistentIncompleteProviderEnumerationStopsWithoutWritingOrDeletingVerifiedAudio() = runBlocking {
        withStorage {
            val foreignBytes = byteArrayOf(4, 5, 6)
            val foreign = createDocument(FILE_NAME, foreignBytes)
            val working = workingFile()
            val beforeQueries = counters().getInt("count")
            setQueryFault("null")
            val result = try {
                withTimeout(15_000L) { runCatching { commit(working) } }
            } finally {
                setQueryFault(null)
            }

            assertTrue("incomplete enumeration must still refuse core commit", result.exceptionOrNull() is IOException)
            assertTrue("verified transfer must remain available after rejected commit", working.isFile)
            assertArrayEquals(PAYLOAD, working.readBytes())
            assertArrayEquals(foreignBytes, read(foreign.uri))
            val after = counters()
            assertTrue("directory retries must be bounded", after.getInt("count") - beforeQueries in 1..6)
            assertEquals("no unknown entry may be deleted", 0, after.getInt("deleteCalls"))
            assertEquals(listOf(FILE_NAME), root().listFiles().mapNotNull { it.name })
        }
    }

    @Test
    fun rootSwitchDuringScanCannotCommitIntoThePreviouslyCapturedRoot() = runBlocking {
        withStorage {
            val foreignBytes = byteArrayOf(3, 2, 1)
            val foreign = createDocument(FILE_NAME, foreignBytes)
            val working = workingFile()
            val replacementTree = DocumentsContract.buildTreeDocumentUri(
                AUTHORITY, ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID
            )

            val result = commitAfterSnapshotCapture(working) {
                ManagedDownloadStorage.primeSettings(replacementTree.toString(), null)
            }

            assertTrue("a commit may not cross its captured root identity", result.exceptionOrNull() is IOException)
            assertArrayEquals(PAYLOAD, working.readBytes())
            assertArrayEquals(foreignBytes, read(foreign.uri))
            assertEquals(listOf(FILE_NAME), root().listFiles().mapNotNull { it.name })
            assertTrue(requireNotNull(DocumentFile.fromTreeUri(context, replacementTree)).listFiles().isEmpty())
            assertEquals(0, counters().getInt("deleteCalls"))
        }
    }

    private suspend fun withStorage(block: suspend Fixture.() -> Unit) =
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val previousDirectory = ManagedDownloadStorage.configuredDirectoryUri()
            val directory = File(base.cacheDir, "commit-snapshot-retry-${UUID.randomUUID()}")
                .apply { check(mkdirs()) }
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir(): File = File(directory, "noBackup").apply { mkdirs() }
                override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
                override fun getExternalFilesDir(type: String?): File =
                    File(directory, type ?: "external").apply { mkdirs() }
            }
            val tree = DocumentsContract.buildTreeDocumentUri(
                AUTHORITY, ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
            )
            context.contentResolver.call(tree, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
            ManagedDownloadStorage.primeSettings(tree.toString(), null)
            ManagedDownloadStorage.snapshotCacheStore.invalidate()
            ManagedDownloadStorage.treeChildRegistry.clear()
            val fixture = Fixture(context, tree)
            try {
                fixture.block()
            } finally {
                fixture.setQueryFault(null)
                ManagedDownloadStorage.primeSettings(previousDirectory, null)
                ManagedDownloadStorage.snapshotCacheStore.invalidate()
                ManagedDownloadStorage.treeChildRegistry.clear()
                context.contentResolver.call(tree, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
                check(directory.deleteRecursively())
            }
        }

    private class Fixture(val context: Context, private val tree: Uri) {
        private val operationId = UUID.randomUUID().toString()

        fun root(): DocumentFile = requireNotNull(DocumentFile.fromTreeUri(context, tree))

        fun createDocument(name: String, bytes: ByteArray): DocumentFile {
            val document = requireNotNull(root().createFile("application/octet-stream", name))
            requireNotNull(context.contentResolver.openOutputStream(document.uri, "w"))
                .use { it.write(bytes) }
            return document
        }

        fun workingFile(): File = File.createTempFile("verified-", ".mp3", context.cacheDir)
            .apply { writeBytes(PAYLOAD) }

        suspend fun commit(
            working: File,
            commitContext: Context = context
        ): ManagedDownloadStorage.StoredEntry {
            val metadata = JSONObject()
                .put("stableKey", "1234|netease|")
                .put("songId", 1234L)
                .put("name", "Snapshot retry")
                .put("artist", "Artist")
                .put("audioFileName", FILE_NAME)
                .put("operationId", operationId)
                .put("downloadFinalized", false)
                .put("artifactState", "COMMITTING")
            return ManagedDownloadStorage.saveAudioFromTemp(
                context = commitContext,
                tempFile = working,
                fileName = FILE_NAME,
                mimeType = "audio/mpeg",
                expectedSizeBytes = PAYLOAD.size.toLong(),
                transferSizeVerified = true,
                pendingMetadataJson = metadata.toString(),
                seedMetadataJson = metadata.put("artifactState", "CORE_COMMITTED").toString()
            )
        }

        suspend fun commitAfterSnapshotCapture(
            working: File,
            duringScan: () -> Unit
        ): Result<ManagedDownloadStorage.StoredEntry> = coroutineScope {
            val gate = SnapshotScanGate()
            val commitContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context {
                    val stack = Thread.currentThread().stackTrace
                    if (stack.any { it.methodName == "captureSnapshot" } &&
                        stack.any { it.methodName == "rebuildDownloadLibrarySnapshotBlocking" }
                    ) {
                        gate.captureObserved.set(true)
                    }
                    return this
                }

                override fun getContentResolver(): ContentResolver {
                    // 全局 Provider 计数可能来自后台扫描，门闩只接受此次提交使用的 context
                    if (Thread.currentThread().stackTrace.any {
                            it.className.endsWith("ManagedDownloadTreeChildQuery") &&
                                it.methodName == "queryChildrenBlocking"
                        }
                    ) {
                        gate.beforeDirectoryQuery()
                    }
                    return context.contentResolver
                }
            }
            val committing = async(Dispatchers.IO) {
                runCatching { commit(working, commitContext) }
            }
            try {
                withTimeout(5_000L) {
                    while (gate.entered.count != 0L) delay(5L)
                }
                assertTrue("the commit scan must have captured its revision before the gate", gate.captureObserved.get())
                assertEquals(gate.capturedRevision.get(), ManagedDownloadStorage.snapshotCacheStore.snapshotRevision())
                duringScan()
                assertTrue("the delta must advance the already captured revision",
                    ManagedDownloadStorage.snapshotCacheStore.snapshotRevision() > gate.capturedRevision.get())
            } finally {
                gate.release.countDown()
            }
            withTimeout(15_000L) { committing.await() }
        }

        fun setQueryFault(fault: String?) {
            context.contentResolver.call(tree, ManagedDownloadMigrationTestDocumentProvider.QUERY_FAULT, fault, null)
        }

        fun counters(): Bundle = requireNotNull(context.contentResolver.call(
            tree, ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT, null, null
        ))

        fun read(uri: Uri): ByteArray = requireNotNull(context.contentResolver.openInputStream(uri))
            .use { it.readBytes() }
    }

    private class SnapshotScanGate {
        val captureObserved = AtomicBoolean(false)
        val capturedRevision = AtomicLong(-1L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val firstQuery = AtomicBoolean(true)

        fun beforeDirectoryQuery() {
            if (!firstQuery.compareAndSet(true, false)) return
            capturedRevision.set(ManagedDownloadStorage.snapshotCacheStore.snapshotRevision())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "snapshot delta injection did not release the query" }
        }
    }

    private companion object {
        const val AUTHORITY = ManagedDownloadMigrationTestDocumentProvider.AUTHORITY
        const val FILE_NAME = "Snapshot retry.mp3"
        val PAYLOAD = ByteArray(417 * 4).apply {
            repeat(4) { frame ->
                byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64).copyInto(this, frame * 417)
            }
        }
    }
}
