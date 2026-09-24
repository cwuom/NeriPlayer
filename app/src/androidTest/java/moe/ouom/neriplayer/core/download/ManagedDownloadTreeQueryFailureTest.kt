package moe.ouom.neriplayer.core.download

import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.core.download.storage.tree.query.ManagedDownloadTreeChildQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadTreeQueryFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rootId = ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    private val providerUri = DocumentsContract.buildDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY, rootId
    )

    @Before
    fun reset() {
        call(ManagedDownloadMigrationTestDocumentProvider.RESET)
    }

    @After
    fun releaseFixture() {
        call(ManagedDownloadMigrationTestDocumentProvider.QUERY_FAULT)
        // 先等已经进入 Binder 的测试查询结束，再清理其目录
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (!query().isComplete && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(10L)
        }
        call(ManagedDownloadMigrationTestDocumentProvider.RESET)
    }

    @Test
    fun binderFailureDoesNotFanOutIntoPerFileFallback() {
        call(ManagedDownloadMigrationTestDocumentProvider.QUERY_FAULT, "dead")
        var failure: Throwable? = null
        val result = ManagedDownloadTreeChildQuery.queryChildrenWithStatus(context, root()) { failure = it }
        assertFalse(result.isComplete)
        assertNotNull(failure)
        assertEquals(1, queryCount())
        call(ManagedDownloadMigrationTestDocumentProvider.QUERY_FAULT)
        assertTrue(query().isComplete)
    }

    @Test
    fun nullCursorCannotConfirmThatFilesAreMissing() {
        call(ManagedDownloadMigrationTestDocumentProvider.QUERY_FAULT, "null")
        assertFalse(query().isComplete)
        assertEquals(1, queryCount())
    }

    @Test
    fun stalledProviderIsBoundedAndOtherRootsCanStillRecover() {
        call(ManagedDownloadMigrationTestDocumentProvider.QUERY_FAULT, "blocked")
        val startedAt = SystemClock.elapsedRealtime()
        assertFalse(query().isComplete)
        assertTrue(SystemClock.elapsedRealtime() - startedAt < 15_000L)
        val initialQueryCount = queryCount()
        assertTrue(initialQueryCount > 0)
        repeat(10) { assertFalse(query().isComplete) }
        assertEquals(initialQueryCount, queryCount())
        assertTrue(query(ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID).isComplete)
        call(ManagedDownloadMigrationTestDocumentProvider.QUERY_FAULT)
        val document = requireNotNull(root().createFile("audio/mpeg", "after-recovery.mp3"))
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        var recovered = query()
        while (!recovered.isComplete && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(10L)
            recovered = query()
        }
        assertTrue(recovered.isComplete)
        assertEquals(listOf(DocumentsContract.getDocumentId(document.uri)),
            recovered.children.map { DocumentsContract.getDocumentId(it.documentUri) })
    }

    private fun root(id: String = rootId): DocumentFile = requireNotNull(DocumentFile.fromTreeUri(
        context, DocumentsContract.buildTreeDocumentUri(
            ManagedDownloadMigrationTestDocumentProvider.AUTHORITY, id
        )
    ))

    private fun query(id: String = rootId) =
        ManagedDownloadTreeChildQuery.queryChildrenWithStatus(context, root(id)) { }

    private fun call(method: String, argument: String? = null) =
        context.contentResolver.call(providerUri, method, argument, null)

    private fun queryCount() = requireNotNull(call(ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT))
        .getInt("count")
}
