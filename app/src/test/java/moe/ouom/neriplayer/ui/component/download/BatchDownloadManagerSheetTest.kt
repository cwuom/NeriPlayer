package moe.ouom.neriplayer.ui.component.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDownloadManagerSheetTest {

    @Test
    fun `visible task cards follow configured parallelism plus manual retry slot`() {
        assertEquals(2, maxVisibleDownloadTaskCards(1))
        assertEquals(5, maxVisibleDownloadTaskCards(4))
    }

    @Test
    fun `active batch admission remains cancellable before rows are created`() {
        assertTrue(
            canCancelBatchDownload(
                hasPendingBatchSongs = false,
                pendingTaskCount = 0,
                hasActiveDownloadOperations = true
            )
        )
    }

    @Test
    fun `empty inactive manager has no cancellation action`() {
        assertFalse(
            canCancelBatchDownload(
                hasPendingBatchSongs = false,
                pendingTaskCount = 0,
                hasActiveDownloadOperations = false
            )
        )
    }

    @Test
    fun `failed downloads use a separate count and retain manual retry`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/ui/component/download/BatchDownloadManagerSheet.kt"
        ).readText()

        assertTrue(source.contains("countFailedDownloadTasks(downloadTasks)"))
        assertTrue(source.contains("R.plurals.download_failed_songs_count"))
        assertTrue(source.contains("FailedDownloadTaskList("))
        assertTrue(source.contains("GlobalDownloadManager.resumeDownloadTask(context, songKey)"))
        assertTrue(source.contains("maxVisibleTasks = maxVisibleTaskCards"))
        assertFalse(source.contains("maxVisibleTasks = Int.MAX_VALUE"))
    }

    private fun locateProjectFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, path)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
