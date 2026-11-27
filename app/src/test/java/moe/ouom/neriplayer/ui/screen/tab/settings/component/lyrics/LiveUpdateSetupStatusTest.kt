package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateSetupStatusTest {
    private fun status(
        sdkInt: Int = 36,
        notificationsEnabled: Boolean = true,
        channelBlocked: Boolean = false,
        canPostPromoted: Boolean? = true,
        systemDeclined: Boolean = false,
    ) = resolveLiveUpdateSetupStatus(sdkInt, notificationsEnabled, channelBlocked, canPostPromoted, systemDeclined)

    @Test
    fun `older systems are reported as unsupported before anything else`() {
        assertEquals(LiveUpdateSetupStatus.Unsupported, status(sdkInt = 35, notificationsEnabled = false))
    }

    @Test
    fun `blockers are reported in the order the user has to fix them`() {
        assertEquals(
            LiveUpdateSetupStatus.NotificationsDisabled,
            status(notificationsEnabled = false, channelBlocked = true, canPostPromoted = false),
        )
        assertEquals(LiveUpdateSetupStatus.ChannelBlocked, status(channelBlocked = true, canPostPromoted = false))
        assertEquals(LiveUpdateSetupStatus.PromotionDisabled, status(canPostPromoted = false, systemDeclined = true))
        assertEquals(LiveUpdateSetupStatus.SystemDeclined, status(systemDeclined = true))
    }

    @Test
    fun `an unknown promotion answer does not block and ready is not actionable`() {
        assertEquals(LiveUpdateSetupStatus.Ready, status(canPostPromoted = null))
        assertFalse(LiveUpdateSetupStatus.Ready.actionable)
        assertTrue(LiveUpdateSetupStatus.PromotionDisabled.actionable)
        assertFalse(LiveUpdateSetupStatus.SystemDeclined.actionable)
    }
}
