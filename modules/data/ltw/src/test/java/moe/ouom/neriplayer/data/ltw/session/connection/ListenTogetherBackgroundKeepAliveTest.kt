package moe.ouom.neriplayer.data.ltw.session.connection

import android.content.Context
import android.os.PowerManager
import org.junit.Test
import org.mockito.Mockito.*

class ListenTogetherBackgroundKeepAliveTest {
    @Test
    fun `wake lease is bounded reused and released only when held`() {
        val context = mock(Context::class.java)
        val manager = mock(PowerManager::class.java)
        val wakeLock = mock(PowerManager.WakeLock::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.POWER_SERVICE)).thenReturn(manager)
        doReturn(wakeLock).`when`(manager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeriPlayer:ListenTogether")
        val owner = ListenTogetherBackgroundKeepAlive()
        owner.renew(context, "background")
        owner.renew(context, "keepalive")
        verify(manager, times(1)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeriPlayer:ListenTogether")
        verify(wakeLock).setReferenceCounted(false)
        verify(wakeLock, times(2)).acquire(120_000L)
        owner.release("idle")
        verify(wakeLock, never()).release()
        `when`(wakeLock.isHeld).thenReturn(true)
        owner.release("foreground")
        verify(wakeLock).release()
    }

    @Test
    fun `unavailable power service and wake lock failures do not escape lifecycle callbacks`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val owner = ListenTogetherBackgroundKeepAlive()
        owner.release("unused")
        owner.renew(context, "no_service")
        val manager = mock(PowerManager::class.java)
        `when`(context.getSystemService(Context.POWER_SERVICE)).thenReturn(manager)
        `when`(manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeriPlayer:ListenTogether")).thenThrow(IllegalStateException("create unavailable"))
        owner.renew(context, "create_failed")
        val wakeLock = mock(PowerManager.WakeLock::class.java)
        doReturn(wakeLock).`when`(manager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeriPlayer:ListenTogether")
        doThrow(IllegalStateException("acquire unavailable")).`when`(wakeLock).acquire(120_000L)
        owner.renew(context, "acquire_failed")
        `when`(wakeLock.isHeld).thenThrow(IllegalStateException("held unavailable"))
        owner.release("held_failed")
        doReturn(true).`when`(wakeLock).isHeld
        doThrow(IllegalStateException("release unavailable")).`when`(wakeLock).release()
        owner.release("release_failed")
    }
}
