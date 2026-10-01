package moe.ouom.neriplayer.core.player.usb.session

import java.util.concurrent.atomic.AtomicBoolean

internal class UsbExclusiveTransportCommandGate {
    private val commandInFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = commandInFlight.compareAndSet(false, true)

    fun release() {
        commandInFlight.set(false)
    }

    fun isHeld(): Boolean = commandInFlight.get()
}
