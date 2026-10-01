package moe.ouom.neriplayer.core.player.usb.confirmation

import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveLoudPlaybackRisk
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveLoudPlaybackConfirmationOwnerTest {
    private val owner = UsbExclusiveLoudPlaybackConfirmationOwner()

    @Test
    fun `bypass never samples volume or changes confirmation state`() {
        var snapshots = 0
        val deferred = owner.request(
            commandSource = PlaybackCommandSource.LOCAL,
            bypassWarning = true,
            snapshot = { snapshots++; loudSnapshot() },
            continuePlayback = { error("unexpected playback") },
            cancelPlayback = null
        )

        assertFalse(deferred)
        assertEquals(0, snapshots)
        assertNull(owner.confirmationFlow.value)
    }

    @Test
    fun `bypass leaves an existing confirmation available to its original callback`() {
        val calls = mutableListOf<String>()
        assertTrue(request(loudSnapshot(), continuePlayback = { calls += "original" }))
        val existing = owner.confirmationFlow.value!!

        assertFalse(owner.request(
            commandSource = PlaybackCommandSource.LOCAL,
            bypassWarning = true,
            snapshot = { error("bypass should not sample") },
            continuePlayback = { calls += "bypassed" },
            cancelPlayback = null
        ))
        assertEquals(existing, owner.confirmationFlow.value)
        assertFalse(request(loudSnapshot().copy(usbExclusiveEnabled = false)))
        assertEquals(existing, owner.confirmationFlow.value)

        owner.confirm(existing.id)
        assertEquals(listOf("original"), calls)
    }

    @Test
    fun `manual high volume request publishes confirmation and consumes only matching id`() {
        val calls = mutableListOf<String>()
        assertTrue(request(loudSnapshot(), { calls += "play" }, { calls += "cancel" }))
        val first = owner.confirmationFlow.value!!
        assertEquals(1L, first.id)
        assertEquals("DAC", first.deviceName)
        assertEquals(UsbExclusiveLoudPlaybackRisk.Critical, first.risk)

        owner.confirm(first.id + 1)
        assertEquals(first, owner.confirmationFlow.value)
        assertTrue(calls.isEmpty())

        owner.confirm(first.id)
        assertNull(owner.confirmationFlow.value)
        assertEquals(listOf("play"), calls)
        owner.confirm(first.id)
        owner.cancel(first.id)
        assertEquals(listOf("play"), calls)
    }

    @Test
    fun `new request replaces old callback and cancellation clears state before callback`() {
        val calls = mutableListOf<String>()
        assertTrue(request(loudSnapshot(), { calls += "old play" }, { calls += "old cancel" }))
        val oldId = owner.confirmationFlow.value!!.id
        assertTrue(request(
            loudSnapshot(),
            continuePlayback = { calls += "new play" },
            cancelPlayback = {
                assertNull(owner.confirmationFlow.value)
                calls += "new cancel"
            }
        ))
        val newId = owner.confirmationFlow.value!!.id

        owner.cancel(oldId)
        owner.confirm(oldId)
        assertTrue(calls.isEmpty())
        owner.cancel(newId)
        assertNull(owner.confirmationFlow.value)
        assertEquals(listOf("new cancel"), calls)
    }

    @Test
    fun `only foreground local playback that is not already audible requires confirmation`() {
        assertFalse(request(loudSnapshot().copy(usbExclusiveEnabled = false)))
        assertFalse(request(loudSnapshot().copy(appInForeground = false)))
        assertFalse(request(loudSnapshot().copy(playbackAlreadyAudible = true)))
        assertFalse(request(loudSnapshot(), commandSource = PlaybackCommandSource.REMOTE_SYNC))
        assertNull(owner.confirmationFlow.value)
    }

    @Test
    fun `unavailable system volume uses conservative full scale`() {
        assertTrue(request(loudSnapshot().copy(systemVolumePercent = null)))
        assertEquals(100, owner.confirmationFlow.value?.systemVolumePercent)
    }

    private fun request(
        snapshot: UsbExclusiveLoudPlaybackSnapshot,
        continuePlayback: () -> Unit = {},
        cancelPlayback: (() -> Unit)? = null,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ): Boolean = owner.request(
        commandSource = commandSource,
        bypassWarning = false,
        snapshot = { snapshot },
        continuePlayback = continuePlayback,
        cancelPlayback = cancelPlayback
    )

    private fun loudSnapshot() = UsbExclusiveLoudPlaybackSnapshot(
        systemVolumePercent = 100,
        usbExclusiveEnabled = true,
        appInForeground = true,
        playbackAlreadyAudible = false,
        currentPlayerVolume = 0.3f,
        bitPerfect = false,
        riskThresholdDbfs = -12,
        deviceName = "DAC",
        outputRouteKey = "3:DAC:2:A",
        outputSampleRate = 96_000,
        metrics = UsbExclusiveRuntimeMetrics(uacVersion = "2", subslotBytes = 3)
    )
}
