package moe.ouom.neriplayer.core.startup

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
}
