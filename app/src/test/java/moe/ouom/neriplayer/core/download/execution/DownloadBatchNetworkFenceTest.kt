package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import java.io.File
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadBatchNetworkFenceTest {
    @Test
    fun `accepted batch consent survives network generation changes while waiting stays blocked`() {
        val allowed = batch(
            stateBits = DownloadBatchState.OPEN or DownloadBatchState.USER_MOBILE_ALLOWED,
            networkGeneration = 7L
        )
        assertTrue(DownloadExecutionRoomStore.canStartBatchForCurrentNetwork(allowed, 7L))
        assertTrue(DownloadExecutionRoomStore.canStartBatchForCurrentNetwork(allowed, 8L))
        assertTrue(DownloadExecutionRoomStore.canStartBatchForCurrentNetwork(allowed, null))
        assertFalse(DownloadExecutionRoomStore.canStartBatchForCurrentNetwork(allowed, -1L))

        val waiting = batch(
            stateBits = DownloadBatchState.OPEN or DownloadBatchState.NETWORK_WAIT,
            networkGeneration = 7L
        )
        assertFalse(DownloadExecutionRoomStore.canStartBatchForCurrentNetwork(waiting, 7L))
    }

    @Test
    fun `room start keeps the network generation fence inside its transaction`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/persistence/" +
                "DownloadExecutionRoomStore.kt"
        ).readText()
        val tryStartBody = methodBody(source, "tryStart")
        val fenceIndex = tryStartBody.indexOf("canStartBatchForCurrentNetwork(")
        val transitionIndex = tryStartBody.indexOf("dao.transitionState(", fenceIndex)

        assertTrue(source.contains("currentNetworkGeneration: Long? = null"))
        assertTrue(tryStartBody.contains("return database.withTransaction"))
        assertTrue(tryStartBody.contains("findBatch(batchId, batchGeneration)"))
        assertTrue(fenceIndex >= 0)
        assertTrue(transitionIndex > fenceIndex)
    }

    @Test
    fun `confirmed Wi-Fi bulk release clears wait without revoking batch consent`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/data/local/database/dao/DownloadBatchDao.kt"
        ).readText()
        val releaseQuery = source.substringBefore(
            "suspend fun clearAllOpenNetworkPolicyFencesAtOrBeforeGeneration"
        ).substringAfterLast("@Query(")

        assertTrue(releaseQuery.contains("DownloadBatchState.NETWORK_WAIT"))
        assertFalse(releaseQuery.contains("DownloadBatchState.USER_MOBILE_ALLOWED"))
        assertTrue(releaseQuery.contains("network_generation <= :networkGeneration"))
        assertTrue(releaseQuery.contains("DownloadBatchState.CLEARING"))
    }

    @Test
    fun `network policy cache never lets an older payload replace a newer one`() {
        val operationId = "network-policy-cache-${System.nanoTime()}"

        DownloadExecutionRoomStore.cacheNetworkPolicy(
            operationId = operationId,
            requiresWifiNetwork = true,
            updatedAtMs = 20L
        )
        DownloadExecutionRoomStore.cacheNetworkPolicy(
            operationId = operationId,
            requiresWifiNetwork = false,
            updatedAtMs = 19L
        )

        assertEquals(
            true,
            DownloadExecutionRoomStore.cachedNetworkPolicy(operationId)
        )

        DownloadExecutionRoomStore.cacheNetworkPolicy(
            operationId = operationId,
            requiresWifiNetwork = false,
            updatedAtMs = 21L
        )
        assertEquals(
            false,
            DownloadExecutionRoomStore.cachedNetworkPolicy(operationId)
        )
    }

    private fun batch(
        stateBits: Int,
        networkGeneration: Long?
    ): DownloadBatchEntity {
        return DownloadBatchEntity(
            batchId = "batch-network-fence",
            generation = 1L,
            totalCount = 1,
            stateBits = stateBits,
            clearEpoch = 0L,
            networkGeneration = networkGeneration,
            updatedAtMs = 0L,
            createdAtMs = 0L
        )
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
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
