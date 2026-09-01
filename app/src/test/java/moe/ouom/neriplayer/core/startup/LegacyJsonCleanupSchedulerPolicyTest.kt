package moe.ouom.neriplayer.core.startup

import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.data.local.database.store.LegacyJsonCleanupPlan
import moe.ouom.neriplayer.data.local.database.store.LegacyJsonCleanupTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyJsonCleanupSchedulerPolicyTest {
    @Test
    fun `download upgrade runs once within one cleanup retry drain`() {
        val gate = LegacyDownloadUpgradeDrainGate()

        assertTrue(gate.claimAttempt())
        repeat(5) {
            assertFalse(gate.claimAttempt())
        }
    }

    @Test
    fun `a later cleanup drain can attempt download upgrade again`() {
        val completedDrain = LegacyDownloadUpgradeDrainGate()
        assertTrue(completedDrain.claimAttempt())
        assertFalse(completedDrain.claimAttempt())

        val laterDrain = LegacyDownloadUpgradeDrainGate()
        assertTrue(laterDrain.claimAttempt())
    }

    @Test
    fun `only user-cleared legacy download sources stop the retry drain`() {
        val plan = LegacyJsonCleanupPlan(
            targets = listOf(
                LegacyJsonCleanupTarget(
                    fileName = "pending_download_queue_v1.json",
                    cutoverStateKey = DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY,
                    exists = true,
                    eligible = false,
                    reason = "Room primary marker is user_cleared",
                    cutoverState = DownloadRecoveryRoomStore.USER_CLEARED_STATE
                ),
                LegacyJsonCleanupTarget(
                    fileName = "cancelled_download_keys_v1.json",
                    cutoverStateKey = DownloadRecoveryRoomStore.CANCELLED_KEYS_CUTOVER_STATE_KEY,
                    exists = true,
                    eligible = false,
                    reason = "Room primary marker is user_cleared",
                    cutoverState = DownloadRecoveryRoomStore.USER_CLEARED_STATE
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
                    cutoverStateKey = DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY,
                    exists = true,
                    eligible = false,
                    reason = "Room primary marker is user_cleared",
                    cutoverState = DownloadRecoveryRoomStore.USER_CLEARED_STATE
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
