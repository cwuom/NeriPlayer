package moe.ouom.neriplayer.core.player.service.lifecycle

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenInteractiveMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `screen actions map to interactive state`() {
        assertEquals(true, screenInteractiveForAction(Intent.ACTION_SCREEN_ON))
        assertEquals(false, screenInteractiveForAction(Intent.ACTION_SCREEN_OFF))
        assertNull(screenInteractiveForAction(Intent.ACTION_USER_PRESENT))
        assertNull(screenInteractiveForAction(null))
    }

    @Test
    fun `monitor reports the current state and broadcasts until stopped`() {
        val states = mutableListOf<Boolean>()
        val monitor = ScreenInteractiveMonitor(context) { states += it }

        monitor.start()
        monitor.start()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        monitor.stop()
        monitor.stop()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertEquals(listOf(true, false, true), states)
    }
}
