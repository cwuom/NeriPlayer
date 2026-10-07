package moe.ouom.neriplayer.core.player.usb.system

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UsbExclusiveSystemVolumeBridgeSubscriptionTest {

    private val subscriptions = mutableListOf<UsbExclusiveSystemVolumeBridgeSubscription>()

    @Before
    fun startWithoutSessionVolume() {
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
    }

    @After
    fun releaseBridge() {
        subscriptions.forEach(UsbExclusiveSystemVolumeBridge::unsubscribe)
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
    }

    @Test
    fun `missing or stale tokens cannot cancel the newest listener`() {
        val replaced = mutableListOf<Float?>()
        val current = mutableListOf<Float?>()
        val staleToken = subscribe(replaced::add)
        subscribe(current::add)

        UsbExclusiveSystemVolumeBridge.unsubscribe(null)
        UsbExclusiveSystemVolumeBridge.unsubscribe(staleToken)
        UsbExclusiveSystemVolumeBridge.updateSessionVolumeFraction(0.4f)

        assertEquals(listOf<Float?>(null), replaced)
        assertEquals(listOf(null, 0.4f), current)
    }

    @Test
    fun `cancelling the newest listener stops delivery but keeps the session volume`() {
        val delivered = mutableListOf<Float?>()
        val token = subscribe(delivered::add)

        UsbExclusiveSystemVolumeBridge.unsubscribe(token)
        UsbExclusiveSystemVolumeBridge.unsubscribe(token)
        UsbExclusiveSystemVolumeBridge.updateSessionVolumeFraction(0.9f)

        assertEquals(listOf<Float?>(null), delivered)
        assertEquals(0.9f, UsbExclusiveSystemVolumeBridge.currentSessionVolumeFractionOrNull())
    }

    private fun subscribe(listener: (Float?) -> Unit): UsbExclusiveSystemVolumeBridgeSubscription =
        UsbExclusiveSystemVolumeBridge.subscribe(listener).also(subscriptions::add)
}
