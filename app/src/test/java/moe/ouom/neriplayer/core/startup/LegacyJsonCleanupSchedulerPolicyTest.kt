package moe.ouom.neriplayer.core.startup

import moe.ouom.neriplayer.core.download.integration.legacy.DownloadLegacyStorageAccess
import moe.ouom.neriplayer.core.startup.legacy.LegacyJsonCleanupPlan
import moe.ouom.neriplayer.core.startup.legacy.LegacyJsonCleanupTarget
import moe.ouom.neriplayer.core.startup.legacy.LegacyDownloadUpgradeResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyJsonCleanupSchedulerPolicyTest {
    @Test
    fun `unsettled upgrade and transient failures are retried within the drain`() {
        val gate = LegacyDownloadUpgradeDrainGate()
        repeat(6) {
            assertTrue(gate.shouldAttempt())
            gate.recordResult(upgradeResult(rowsPending = 1))
        }
        gate.recordResult(null)
        assertTrue(gate.shouldAttempt())
    }

    @Test
    fun `settled database rows are not rewritten while catalog publication retries`() {
        val gate = LegacyDownloadUpgradeDrainGate()
        assertTrue(gate.shouldAttempt())
        gate.recordResult(upgradeResult(rowsPending = 0))
        repeat(5) { assertFalse(gate.shouldAttempt()) }
    }

    @Test
    fun `a later cleanup drain can attempt download upgrade again`() {
        val completedDrain = LegacyDownloadUpgradeDrainGate()
        assertTrue(completedDrain.shouldAttempt())
        completedDrain.recordResult(upgradeResult(rowsPending = 0))
        assertFalse(completedDrain.shouldAttempt())

        val laterDrain = LegacyDownloadUpgradeDrainGate()
        assertTrue(laterDrain.shouldAttempt())
    }

    private fun upgradeResult(rowsPending: Int) = LegacyDownloadUpgradeResult(
        tableFound = true,
        rowsSeen = 1,
        rowsCompleted = 1 - rowsPending,
        rowsPending = rowsPending,
        rowResults = emptyList(),
        temporaryTableCleaned = rowsPending == 0,
        legacyProjectionTablesCleaned = rowsPending == 0
    )

    @Test
    fun `only user-cleared legacy download sources stop the retry drain`() {
        val plan = LegacyJsonCleanupPlan(
            targets = listOf(
                LegacyJsonCleanupTarget(
                    fileName = "pending_download_queue_v1.json",
                    cutoverStateKey = DownloadLegacyStorageAccess.PENDING_QUEUE_CUTOVER_STATE_KEY,
                    exists = true,
                    eligible = false,
                    reason = "Room primary marker is user_cleared",
                    cutoverState = DownloadLegacyStorageAccess.USER_CLEARED_STATE
                ),
                LegacyJsonCleanupTarget(
                    fileName = "cancelled_download_keys_v1.json",
                    cutoverStateKey = DownloadLegacyStorageAccess.CANCELLED_KEYS_CUTOVER_STATE_KEY,
                    exists = true,
                    eligible = false,
                    reason = "Room primary marker is user_cleared",
                    cutoverState = DownloadLegacyStorageAccess.USER_CLEARED_STATE
                )
            )
        )

        assertTrue(plan.isBlockedOnlyByUserClearedDownloadQueues)
    }

    @Test
    fun `other blocked legacy sources keep the cleanup retry drain active`() {
        val plan = LegacyJsonCleanupPlan(
            targets = listOf(
                LegacyJsonCleanupTarget(
                    fileName = "pending_download_queue_v1.json",
                    cutoverStateKey = DownloadLegacyStorageAccess.PENDING_QUEUE_CUTOVER_STATE_KEY,
                    exists = true,
                    eligible = false,
                    reason = "Room primary marker is user_cleared",
                    cutoverState = DownloadLegacyStorageAccess.USER_CLEARED_STATE
                ),
                LegacyJsonCleanupTarget(
                    fileName = "play_history.json",
                    cutoverStateKey = "play_history_cutover_state",
                    exists = true,
                    eligible = false,
                    reason = "Room primary marker is missing"
                )
            )
        )

        assertFalse(plan.isBlockedOnlyByUserClearedDownloadQueues)
    }
}
