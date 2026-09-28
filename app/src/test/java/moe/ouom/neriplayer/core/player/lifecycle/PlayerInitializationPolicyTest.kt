package moe.ouom.neriplayer.core.player.lifecycle

import moe.ouom.neriplayer.data.settings.CacheSizePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInitializationPolicyTest {
    @Test
    fun `an initialized session wins over a concurrent initialization attempt`() {
        assertEquals(
            "initialize(): ignored because already initialized",
            initializationIgnoreReason(initialized = true, inProgress = true)
        )
        assertEquals(
            "initialize(): ignored because initialization is already running",
            initializationIgnoreReason(initialized = false, inProgress = true)
        )
        assertNull(initializationIgnoreReason(initialized = false, inProgress = false))
    }

    @Test
    fun `cache remains available for positive and unlimited budgets`() {
        assertFalse(shouldUsePlaybackMediaCache(0L))
        assertTrue(shouldUsePlaybackMediaCache(128L))
        assertTrue(shouldUsePlaybackMediaCache(CacheSizePolicy.UNLIMITED_CACHE_SIZE_BYTES))
        assertFalse(shouldUsePlaybackMediaCache(-2L))
    }

    @Test
    fun `USB exclusive output preserves integer PCM when high resolution is enabled`() {
        assertFalse(shouldEnableFloatPlaybackOutput(false, false))
        assertFalse(shouldEnableFloatPlaybackOutput(false, true))
        assertTrue(shouldEnableFloatPlaybackOutput(true, false))
        assertFalse(shouldEnableFloatPlaybackOutput(true, true))
    }
}
