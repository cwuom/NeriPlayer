package moe.ouom.neriplayer.data.model.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundBehaviorAllowanceTest {

    @Test
    fun `background work is fully allowed only when both system gates are open`() {
        assertTrue(BackgroundBehaviorAllowance(ignoringBatteryOptimizations = true, backgroundAppOpsAllowed = true).fullyAllowed)
        assertFalse(BackgroundBehaviorAllowance(ignoringBatteryOptimizations = true, backgroundAppOpsAllowed = false).fullyAllowed)
        assertFalse(BackgroundBehaviorAllowance(ignoringBatteryOptimizations = false, backgroundAppOpsAllowed = true).fullyAllowed)
    }
}
