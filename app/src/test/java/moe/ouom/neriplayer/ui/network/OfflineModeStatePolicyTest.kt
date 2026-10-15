package moe.ouom.neriplayer.ui.network

import androidx.lifecycle.Lifecycle
import moe.ouom.neriplayer.data.traffic.LikelyNetworkTransportAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModeStatePolicyTest {
    @Test
    fun `online observation clears offline state immediately`() {
        assertEquals(
            OfflineModeStateUpdate.SET_ONLINE,
            resolveOfflineModeStateUpdate(
                currentlyOffline = true,
                availability = LikelyNetworkTransportAvailability.ONLINE,
                isOfflineConfirmation = false
            )
        )
    }

    @Test
    fun `first offline observation waits for confirmation`() {
        assertEquals(
            OfflineModeStateUpdate.CONFIRM_OFFLINE,
            resolveOfflineModeStateUpdate(
                currentlyOffline = false,
                availability = LikelyNetworkTransportAvailability.OFFLINE,
                isOfflineConfirmation = false
            )
        )
    }

    @Test
    fun `indeterminate capability snapshot keeps the current state`() {
        assertEquals(
            OfflineModeStateUpdate.KEEP_CURRENT,
            resolveOfflineModeStateUpdate(
                currentlyOffline = false,
                availability = LikelyNetworkTransportAvailability.INDETERMINATE,
                isOfflineConfirmation = false
            )
        )
    }

    @Test
    fun `long indeterminate capability gap cannot turn an online state offline`() {
        assertEquals(
            OfflineModeStateUpdate.KEEP_CURRENT,
            resolveOfflineModeStateUpdate(
                currentlyOffline = false,
                availability = LikelyNetworkTransportAvailability.INDETERMINATE,
                isOfflineConfirmation = true
            )
        )
    }

    @Test
    fun `confirmed offline observation enters offline state`() {
        assertEquals(
            OfflineModeStateUpdate.SET_OFFLINE,
            resolveOfflineModeStateUpdate(
                currentlyOffline = false,
                availability = LikelyNetworkTransportAvailability.OFFLINE,
                isOfflineConfirmation = true
            )
        )
    }

    @Test
    fun `already offline state does not schedule duplicate confirmation`() {
        assertEquals(
            OfflineModeStateUpdate.KEEP_CURRENT,
            resolveOfflineModeStateUpdate(
                currentlyOffline = true,
                availability = LikelyNetworkTransportAvailability.INDETERMINATE,
                isOfflineConfirmation = false
            )
        )
    }

    @Test
    fun `only resume lifecycle event triggers an immediate network refresh`() {
        assertTrue(shouldRefreshOfflineStateForLifecycleEvent(Lifecycle.Event.ON_RESUME))
        assertFalse(shouldRefreshOfflineStateForLifecycleEvent(Lifecycle.Event.ON_PAUSE))
        assertFalse(shouldRefreshOfflineStateForLifecycleEvent(Lifecycle.Event.ON_STOP))
    }

    @Test
    fun `reachability signature is stable for equal state and changes on each connectivity bit`() {
        val base = networkReachabilitySignature(
            hasInternet = true, validated = true, notSuspended = true, transportMask = 0b10
        )
        assertEquals(
            base,
            networkReachabilitySignature(hasInternet = true, validated = true, notSuspended = true, transportMask = 0b10)
        )
        val variants = listOf(
            networkReachabilitySignature(false, true, true, 0b10),
            networkReachabilitySignature(true, false, true, 0b10),
            networkReachabilitySignature(true, true, false, 0b10),
            networkReachabilitySignature(true, true, true, 0b01),
        )
        assertTrue(variants.none { it == base })
        assertEquals(variants.size, variants.toSet().size)
    }
}
