package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.worker.DownloadStorageRecoveryWorker
import androidx.work.BackoffPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageRecoveryWorkerTest {
    @Test
    fun `空间恢复任务使用短首检和指数退避`() {
        val request = DownloadStorageRecoveryWorker.buildRequest(initialDelayMs = 5_000L)

        assertEquals(5_000L, request.workSpec.initialDelay)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertTrue(request.tags.contains("download_execution_all"))
        assertTrue(request.tags.contains("download_storage_recovery"))
    }

    @Test
    fun `空间恢复任务的退避单位保持毫秒`() {
        val request = DownloadStorageRecoveryWorker.buildRequest()

        assertEquals(TimeUnit.SECONDS.toMillis(30L), request.workSpec.backoffDelayDuration)
    }
}
