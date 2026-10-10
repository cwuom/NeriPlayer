package moe.ouom.neriplayer.core.player.audio.wake

import android.content.Context
import android.os.PowerManager
import moe.ouom.neriplayer.core.player.policy.wake.PLAYBACK_TRANSITION_WAKE_LOCK_LEASE_MS
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

class PlaybackTransitionWakeLockTest {
    private var held = false
    private val wakeLock = mock(PowerManager.WakeLock::class.java)
    private val powerManager = mock(PowerManager::class.java).also {
        `when`(it.newWakeLock(anyInt(), anyString())).thenReturn(wakeLock)
    }

    @After
    fun tearDown() {
        stubHealthyWakeLock()
        PlaybackTransitionWakeLock.releaseAll("test_teardown")
    }

    // The object caches its wake lock for the whole process, so the lifecycle runs as one ordered scenario.
    @Test
    fun `transition wake lock is created once released by the newest request and contains failures`() {
        stubHealthyWakeLock()
        PlaybackTransitionWakeLock.releaseAll("before_create")
        PlaybackTransitionWakeLock.acquire(contextWith(null), requestToken = 1L, reason = "no_power_service")
        PlaybackTransitionWakeLock.release(1L, "no_power_service")
        verifyNoInteractions(wakeLock)

        PlaybackTransitionWakeLock.acquire(contextWith(powerManager), requestToken = 2L, reason = "play")
        verify(powerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeriPlayer:PlaybackTransition")
        verify(wakeLock).setReferenceCounted(false)
        verify(wakeLock).acquire(PLAYBACK_TRANSITION_WAKE_LOCK_LEASE_MS)
        assertTrue(held)

        PlaybackTransitionWakeLock.acquire(contextWith(powerManager), requestToken = 3L, reason = "next")
        verify(powerManager, times(1)).newWakeLock(anyInt(), anyString())
        PlaybackTransitionWakeLock.release(2L, "stale_request")
        assertTrue(held)
        PlaybackTransitionWakeLock.release(3L, "request_finished")
        assertFalse(held)
        PlaybackTransitionWakeLock.release(3L, "duplicate_release")
        verify(wakeLock, times(1)).release()

        PlaybackTransitionWakeLock.acquire(contextWith(powerManager), requestToken = 4L, reason = "seek")
        held = false
        PlaybackTransitionWakeLock.release(4L, "lease_expired")
        PlaybackTransitionWakeLock.releaseAll("lease_expired")
        verify(wakeLock, times(1)).release()

        PlaybackTransitionWakeLock.acquire(contextWith(powerManager), requestToken = 5L, reason = "resume")
        PlaybackTransitionWakeLock.releaseAll("service_stopped")
        assertFalse(held)
        verify(wakeLock, times(2)).release()

        doThrow(SecurityException("missing WAKE_LOCK")).`when`(wakeLock).acquire(anyLong())
        PlaybackTransitionWakeLock.acquire(contextWith(powerManager), requestToken = 6L, reason = "denied")
        assertFalse(held)

        held = true
        doThrow(IllegalStateException("binder died")).`when`(wakeLock).isHeld
        PlaybackTransitionWakeLock.release(6L, "held_query_failed")
        PlaybackTransitionWakeLock.releaseAll("held_query_failed")
        verify(wakeLock, times(2)).release()

        stubHealthyWakeLock()
        doThrow(IllegalStateException("already released")).`when`(wakeLock).release()
        PlaybackTransitionWakeLock.acquire(contextWith(powerManager), requestToken = 7L, reason = "retry")
        PlaybackTransitionWakeLock.release(7L, "release_failed")
        PlaybackTransitionWakeLock.acquire(contextWith(powerManager), requestToken = 8L, reason = "retry")
        PlaybackTransitionWakeLock.releaseAll("release_failed")
        assertTrue(held)
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
}
