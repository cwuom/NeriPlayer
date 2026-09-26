package moe.ouom.neriplayer.core.download

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadDeletePolicy
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadReferenceDeleteExecutor
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.testing.DocumentsFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// CI 模拟器的冷启动和调度抖动需要余量，批次进度和并发边界另有结构性断言
private const val FIRST_PROGRESS_TIMEOUT_MS = 2_000L
private const val DELETE_COMPLETION_TIMEOUT_MS = 5_000L

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
class ManagedDownloadDocumentsProviderDeleteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val providerUri = Uri.parse("content://${ManagedDownloadDelayedDocumentsProvider.AUTHORITY}")
    private var fixtureStarted = false

    @After
    fun cleanupFixture() {
        if (fixtureStarted) context.contentResolver.call(providerUri,
            ManagedDownloadDelayedDocumentsProvider.CLEANUP, null, null)
    }

    @Test
    fun delayedStandardDocumentsProviderReportsProgressAndDeletesAllFiles() = runBlocking {
        val references = setup(count = 256, delayMs = 8)
        val firstProgressAt = AtomicLong()
        val startedAt = SystemClock.elapsedRealtime()
        val result = executor().deleteReferencesConcurrently(
            context, references, policy(references), parallelism = 16,
            onDeleteAttemptFinished = { _, deleted ->
                if (deleted) firstProgressAt.compareAndSet(0, SystemClock.elapsedRealtime())
            }
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val firstProgressMs = firstProgressAt.get() - startedAt
        val counters = counters()
        android.util.Log.i("NeriFullDeletePerformance", "standardDocumentsProvider count=256 delayMs=8 " +
            "elapsedMs=$elapsedMs firstProgressMs=$firstProgressMs counters=$counters")
        assertEquals(references.map { it.externalReference }.toSet(), result.deletedReferences)
        assertFalse(result.hasUnconfirmedDeletes)
        assertEquals(0, counters.getInt("remainingFiles"))
        assertTrue("first progress exceeded CI budget: $firstProgressMs ms (limit=$FIRST_PROGRESS_TIMEOUT_MS ms)",
            firstProgressMs in 1..FIRST_PROGRESS_TIMEOUT_MS)
        assertTrue("provider work exceeded CI budget: $elapsedMs ms (limit=$DELETE_COMPLETION_TIMEOUT_MS ms)",
            elapsedMs <= DELETE_COMPLETION_TIMEOUT_MS)
        assertTrue(counters.getInt("maximumActiveDeletes") in 2..16)
    }

    @Test
    fun unsupportedBatchFallsBackButPermissionDeniedDocumentSurvives() = runBlocking {
        val references = setup(count = 20, delayMs = 0, rejectBatch = true, deniedName = "song-5.mp3")
        val result = executor().deleteReferencesConcurrently(context, references, policy(references), parallelism = 4)
        assertEquals(19, result.deletedReferences.size)
        assertTrue(result.hasUnconfirmedDeletes)
        assertEquals(1, counters().getInt("remainingFiles"))
        assertTrue(counters().getInt("batchCalls") > 0)
        val denied = references.single { DocumentsContract.getDocumentId((it.reference as StorageReference.SafRef).uri).endsWith("/song-5.mp3") }
        assertFalse(denied.externalReference in result.deletedReferences)
    }

    private fun setup(count: Int, delayMs: Long, rejectBatch: Boolean = false, deniedName: String? = null): List<TrustedManagedRef> {
        val fixture = DocumentsFixture.setupDelayedProvider(Bundle().apply {
            putInt("count", count)
            putLong("delayMs", delayMs)
            putBoolean("rejectBatch", rejectBatch)
            putString("deniedName", deniedName)
            putString("packageName", context.packageName)
        })
        fixtureStarted = true
        val treeUri = Uri.parse(requireNotNull(fixture.getString("treeUri")))
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return requireNotNull(context.contentResolver.query(childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                    add(TrustedManagedRef(StorageReference.SafRef(uri), uri.toString()))
                }
            }
        }
    }

    private fun counters(): Bundle = requireNotNull(context.contentResolver.call(providerUri,
        ManagedDownloadDelayedDocumentsProvider.COUNTERS, null, null))

    private fun policy(references: List<TrustedManagedRef>) = ManagedDownloadDeletePolicy(
        managedFileRoots = emptyList(), managedTreeRoots = emptyList(), trustedReferences = references.toSet()
    )

    private fun executor() = ManagedDownloadReferenceDeleteExecutor(
        tag = "ManagedDownloadDocumentsProviderDeleteTest",
        isReferenceAllowed = { _, _, _, _ -> true },
        contentReferenceBatchDeleteOperation = { _, references ->
            val result = ManagedDownloadReferenceIo.deleteContentReferencesBatch(context,
                references.map { (it.reference as StorageReference.SafRef).uri })
            result.results.takeIf { result.supported }?.map { entry ->
                when (entry) {
                    ManagedDownloadReferenceIo.DeleteResult.Deleted -> StorageMutationResult.Deleted
                    ManagedDownloadReferenceIo.DeleteResult.Missing -> StorageMutationResult.Missing
                    ManagedDownloadReferenceIo.DeleteResult.PermissionLost -> StorageMutationResult.PermissionLost
                    is ManagedDownloadReferenceIo.DeleteResult.ProviderFailure -> StorageMutationResult.ProviderFailure(entry.error)
                }
            }
        }
    )
}
