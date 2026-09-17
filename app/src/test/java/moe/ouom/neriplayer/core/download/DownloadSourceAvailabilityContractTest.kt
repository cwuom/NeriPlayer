package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.manager.batch.forgetPendingDownloadQueueEntriesForOperation
import moe.ouom.neriplayer.core.download.manager.runtime.executionResultForOperation
import moe.ouom.neriplayer.core.download.manager.runtime.settleUnavailableDownloadSourceFailure
import moe.ouom.neriplayer.core.download.manager.runtime.settleUnfinishedDownloadArtifactLease
import moe.ouom.neriplayer.core.download.manager.runtime.startDownloadConfirmed
import moe.ouom.neriplayer.core.download.policy.shouldForceFreshStartStorageScan
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
        val startBody = methodBody(source, "startDownloadConfirmed")
        val settlementBody = methodBody(source, "settleUnavailableDownloadSourceFailure")
        val resultBody = methodBody(source, "executionResultForOperation")
        val unfinishedLeaseBody = methodBody(source, "settleUnfinishedDownloadArtifactLease")

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
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/host/DownloadExecutionHost.kt"
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
        val attemptBody = methodBody(source, "executeDownloadAttempt")
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
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            current = current.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )
}
