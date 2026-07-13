package moe.ouom.neriplayer.core.startup.safemode

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeModeRecoveryCoordinatorTest {
    @Test
    fun `restores normal components before clearing safe mode marker`() {
        val calls = mutableListOf<String>()
        val coordinator = SafeModeRecoveryCoordinator(
            initializeNormalComponents = {
                calls += "initialize"
            },
            restoreNormalStartup = {
                calls += "restore"
            }
        )

        coordinator.restore()

        assertEquals(listOf("initialize", "restore"), calls)
    }
}
