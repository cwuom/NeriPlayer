package moe.ouom.neriplayer.core.player.usb.route

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbRouteTransitionOwnerTest {
    @Test
    fun `generation and first completion attempts are owned across replacement`() = runTest {
        val owner = UsbRouteTransitionOwner(backgroundScope, {}, initialGeneration = 7L)
        assertEquals(8L, owner.advanceGeneration())
        assertEquals(1, owner.claimRecoveryAttempt(1))
        assertNull(owner.claimRecoveryAttempt(1))
        owner.resetRecoveryAttempts()
        assertEquals(1, owner.claimRecoveryAttempt(1))
    }

    @Test
    fun `new toggle reason prevents old timeout from unlocking it`() = runTest {
        val timeouts = mutableListOf<String>()
        val owner = UsbRouteTransitionOwner(backgroundScope, timeouts::add)
        assertTrue(owner.beginUiToggle(true))
        owner.beginSettingToggle(true, false)
        advanceTimeBy(8_000L)
        runCurrent()

        assertFalse(owner.toggleActive)
        assertEquals(listOf("usb_toggle_timeout"), timeouts)
    }

    @Test
    fun `cancelled release and watchdog cannot act after route reactivation`() = runTest {
        val owner = UsbRouteTransitionOwner(backgroundScope, {})
        var releaseCompleted = false
        var watchdogFired = false
        owner.beginSystemAudioRelease()
        owner.launchSystemAudioRelease {
            delay(4_000L)
            releaseCompleted = true
        }
        owner.launchSystemAudioWatchdog(1_600L) { watchdogFired = true }
        runCurrent()
        assertTrue(owner.systemAudioReleaseInProgress)
        assertTrue(owner.hasActiveSystemAudioRelease())

        owner.cancelSystemAudioRelease("usb_exclusive_enabled")
        advanceTimeBy(5_000L)
        runCurrent()

        assertFalse(owner.systemAudioReleaseInProgress)
        assertFalse(owner.hasActiveSystemAudioRelease())
        assertFalse(releaseCompleted)
        assertFalse(watchdogFired)
    }

    @Test
    fun `release cleanup cancels open gate and toggle jobs`() = runTest {
        val timeouts = mutableListOf<String>()
        val owner = UsbRouteTransitionOwner(backgroundScope, timeouts::add)
        var openGateCompleted = false
        owner.beginSettingToggle(true, true)
        owner.launchOpenGatePlayback {
            delay(1_000L)
            openGateCompleted = true
        }
        owner.cancelRouteJobs()
        advanceTimeBy(8_000L)
        runCurrent()

        assertFalse(owner.toggleActive)
        assertFalse(openGateCompleted)
        assertTrue(timeouts.isEmpty())
    }

    @Test
    fun `cancel without an active release remains idle`() = runTest {
        val owner = UsbRouteTransitionOwner(backgroundScope, {})
        assertFalse(owner.hasActiveSystemAudioRelease())
        owner.cancelSystemAudioRelease("nothing_pending")
        assertFalse(owner.systemAudioReleaseInProgress)
    }
}
