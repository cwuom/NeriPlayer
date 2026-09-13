package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourceAvailabilityContractTest {

    @Test
    fun `batch fresh start reuses one storage snapshot while single download refreshes`() {
        assertFalse(shouldForceFreshStartStorageScan(isBatchOperation = true))
        assertTrue(shouldForceFreshStartStorageScan(isBatchOperation = false))
    }

    @Test
    fun `unavailable source persists terminal evidence before invalidating operation`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val startBody = source.substringAfter("private suspend fun startDownloadConfirmed(")
            .substringBefore("private suspend fun settleUnavailableDownloadSourceFailure(")
        val settlementBody = source.substringAfter(
            "private suspend fun settleUnavailableDownloadSourceFailure("
        ).substringBefore("private suspend fun requestStorageExhaustionCancellation(")
        val resultBody = source.substringAfter("private suspend fun executionResultForOperation(")
            .substringBefore("private suspend fun deferDownloadOperationExecutionForNetworkPolicyIfNeeded(")
        val unfinishedLeaseBody = source.substringAfter(
            "private suspend fun settleUnfinishedDownloadArtifactLease("
        ).substringBefore("fun startBatchDownload(context")

        val unavailableCatch = startBody.indexOf(
            "catch (error: DownloadSourceUnavailableException)"
        )
        val genericCatch = startBody.indexOf("catch (error: Exception)", unavailableCatch + 1)
        val artifactIndex = settlementBody.indexOf("settleLeaseAnyRoot(")
        val batchIndex = settlementBody.indexOf("markBatchMembersForOperation(")
        val pendingQueueIndex = settlementBody.indexOf(
            "forgetPendingDownloadQueueEntriesForOperation("
        )
        val invalidIndex = settlementBody.indexOf("state = \"INVALID\"")

        assertTrue(unavailableCatch >= 0)
        assertTrue(genericCatch > unavailableCatch)
        assertTrue(artifactIndex >= 0)
        assertTrue(batchIndex > artifactIndex)
        assertTrue(pendingQueueIndex > batchIndex)
        assertTrue(invalidIndex > pendingQueueIndex)
        assertTrue(settlementBody.contains("DownloadBatchMemberTerminal.FAILED"))
        assertTrue(settlementBody.contains("DOWNLOAD_SOURCE_UNAVAILABLE_ERROR_CODE"))
        assertTrue(settlementBody.contains("reason = \"download_source_unavailable\""))
        assertTrue(resultBody.contains("\"INVALID\" -> return DownloadExecutionResult.MissingOperation"))
        assertTrue(unfinishedLeaseBody.contains("ManagedDownloadArtifactState.FAILED_RETRYABLE"))
    }

    @Test
    fun `missing operation result releases the host song mapping`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/DownloadExecutionHost.kt"
        ).readText()
        val resultBody = source.substringAfter("when (result) {")
            .substringBefore("if (clearBlockedResult)")
        val missingBody = resultBody.substringAfter("DownloadExecutionResult.MissingOperation -> {")
            .substringBefore('}')

        assertTrue(missingBody.contains("operationIdsBySongKey.remove("))
    }

    @Test
    fun `resolved source clears the confirmed missing streak before transfer`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val attemptBody = source.substringAfter("private suspend fun executeDownloadAttempt(")
            .substringBefore("private suspend fun prepareDownloadAttempt(")
        val unavailableIndex = attemptBody.indexOf("throw DownloadSourceUnavailableException(")
        val resetIndex = attemptBody.indexOf("state.confirmedSourceMissCount = 0")
        val prepareIndex = attemptBody.indexOf("val prepared = try")

        assertTrue(unavailableIndex >= 0)
        assertTrue(resetIndex > unavailableIndex)
        assertTrue(prepareIndex > resetIndex)
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
