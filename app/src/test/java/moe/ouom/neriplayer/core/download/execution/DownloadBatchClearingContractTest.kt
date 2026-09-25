package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import java.io.File
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadBatchClearingContractTest {
    @Test
    fun `clearing batch cannot start or accept network continuation`() {
        val batch = DownloadBatchEntity(
            batchId = "batch-clearing",
            generation = 1L,
            totalCount = 1,
            stateBits = DownloadBatchState.OPEN or DownloadBatchState.CLEARING,
            clearEpoch = 1L,
            networkGeneration = null,
            updatedAtMs = 0L,
            createdAtMs = 0L
        )

        assertFalse(
            DownloadExecutionRoomStore.canStartBatchForCurrentNetwork(
                batch = batch,
                currentNetworkGeneration = null
            )
        )
    }

    @Test
    fun `batch dao fences ordinary mutations and exposes epoch scoped clear CAS`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/data/local/database/dao/" +
                "DownloadBatchDao.kt"
        )

        assertTrue(source.contains("findBatchesForClear(clearEpoch: Long)"))
        assertTrue(source.contains("suspend fun markBatchClearingCAS("))
        assertTrue(source.contains("suspend fun finalizeBatchClearingCAS("))
        assertTrue(source.contains("state_bits & \${DownloadBatchState.CLEARING} = 0"))
        assertTrue(source.contains("clear_epoch < :clearEpoch"))
        assertTrue(source.contains("state_bits & \${DownloadBatchState.CLEARING} != 0"))
        assertTrue(source.contains("clear_epoch <= :clearEpoch"))
    }

    @Test
    fun `pump excludes operations whose batch is clearing`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/data/local/database/dao/" +
                "DownloadOperationDao.kt"
        )

        assertTrue(
            source.contains("(batch.state_bits & \${DownloadBatchState.CLEARING}) = 0")
        )
        assertTrue(
            source.contains("AND (batch.state_bits & \${DownloadBatchState.CLEARING}) != 0")
        )
    }

    @Test
    fun `direct cached settlement releases a stale host admission`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/persistence/" +
                "DownloadExecutionRoomStore.kt"
        )
        val body = methodBody(source, "markAlreadyDownloadedCompleted")
        assertTrue(body.contains("dao.deleteHostAdmission(normalizedOperationId)"))
    }

    @Test
    fun `legacy batch members without an attempt can still settle by operation identity`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/data/local/database/dao/" +
                "DownloadBatchDao.kt"
        )
        assertTrue(source.contains("AND (attempt_id IS NULL OR attempt_id = :attemptId)"))
    }

    @Test
    fun `global clear marks batches before releasing durable fence`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val beginIndex = source.indexOf("DownloadExecutionRoomStore.beginBatchClear(")
        val finalizeIndex = source.indexOf("DownloadExecutionRoomStore.finalizeBatchClear(")
        val releaseIndex = source.indexOf("clearDownloadClearFence(")

        assertTrue(beginIndex >= 0)
        assertTrue(finalizeIndex > beginIndex)
        assertTrue(releaseIndex > finalizeIndex)
        assertFalse(source.contains("DownloadExecutionRoomStore.markAllOpenBatchesCancelled(appContext)"))
    }

    private fun readSource(path: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            val candidate = File(directory, path)
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate).readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )
}
