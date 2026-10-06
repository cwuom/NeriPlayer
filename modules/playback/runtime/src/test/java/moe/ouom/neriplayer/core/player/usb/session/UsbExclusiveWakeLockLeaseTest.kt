package moe.ouom.neriplayer.core.player.usb.session

import android.content.Context
import android.os.PowerManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class UsbExclusiveWakeLockLeaseTest {
    private var held = false
    private val wakeLock = mock(PowerManager.WakeLock::class.java)
    private val powerManager = mock(PowerManager::class.java).also {
        `when`(it.newWakeLock(anyInt(), anyString())).thenReturn(wakeLock)
    }

    @After
    fun tearDown() {
        stubHealthyWakeLock()
        UsbExclusiveWakeLock.release("test_teardown")
    }

    // The object caches its wake lock for the whole process, so the lifecycle runs as one ordered scenario.
    @Test
    fun `usb wake lock lease is renewed only after half of it elapsed and failures are contained`() {
        stubHealthyWakeLock()
        assertFalse(UsbExclusiveWakeLock.isHeld())
        UsbExclusiveWakeLock.release("before_create")
        UsbExclusiveWakeLock.acquire(contextWith(null), "no_power_service", nowElapsedMs = 0L)
        assertFalse(UsbExclusiveWakeLock.isHeld())
        verifyNoInteractions(wakeLock)

        UsbExclusiveWakeLock.acquire(contextWith(powerManager), "tone_started", nowElapsedMs = 1_000L)
        verify(powerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeriPlayer:UsbExclusivePlayback")
        verify(wakeLock).setReferenceCounted(false)
        verify(wakeLock).acquire(LEASE_TIMEOUT_MS)
        assertTrue(UsbExclusiveWakeLock.isHeld())

        UsbExclusiveWakeLock.acquire(contextWith(powerManager), "keep_alive", nowElapsedMs = 1_000L + 60_000L)
        verify(wakeLock, times(1)).acquire(anyLong())
        UsbExclusiveWakeLock.acquire(contextWith(powerManager), "keep_alive", nowElapsedMs = 1_000L + LEASE_TIMEOUT_MS / 2)
        verify(wakeLock, times(2)).acquire(anyLong())
        UsbExclusiveWakeLock.acquire(contextWith(powerManager), "clock_reset", nowElapsedMs = 0L)
        verify(wakeLock, times(3)).acquire(anyLong())
        verify(powerManager, times(1)).newWakeLock(anyInt(), anyString())

        UsbExclusiveWakeLock.release("stopped")
        assertFalse(UsbExclusiveWakeLock.isHeld())
        UsbExclusiveWakeLock.release("stopped_again")
        verify(wakeLock, times(1)).release()

        doThrow(SecurityException("missing WAKE_LOCK")).`when`(wakeLock).acquire(anyLong())
        UsbExclusiveWakeLock.acquire(contextWith(powerManager), "denied", nowElapsedMs = 10_000L)
        assertFalse(UsbExclusiveWakeLock.isHeld())

        stubHealthyWakeLock()
        UsbExclusiveWakeLock.acquire(contextWith(powerManager), "retry", nowElapsedMs = 10_001L)
        assertTrue(UsbExclusiveWakeLock.isHeld())

        doThrow(IllegalStateException("binder died")).`when`(wakeLock).isHeld
        assertFalse(UsbExclusiveWakeLock.isHeld())
        UsbExclusiveWakeLock.release("held_query_failed")
        verify(wakeLock, times(1)).release()

        stubHealthyWakeLock()
        doThrow(IllegalStateException("already released")).`when`(wakeLock).release()
        UsbExclusiveWakeLock.release("release_failed")
        assertTrue(UsbExclusiveWakeLock.isHeld())
    }

    private fun stubHealthyWakeLock() {
        doAnswer { held = true; null }.`when`(wakeLock).acquire(anyLong())
        doAnswer { held = false; null }.`when`(wakeLock).release()
        doAnswer { held }.`when`(wakeLock).isHeld
    }

    private fun contextWith(powerManager: PowerManager?): Context = mock(Context::class.java).also {
        `when`(it.applicationContext).thenReturn(it)
        `when`(it.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager)
    }

    private companion object {
        const val LEASE_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
