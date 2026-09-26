package moe.ouom.neriplayer.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.startup.app.InstrumentationTestRuntime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstrumentationTestRuntimeTest {

    @Test
    fun runnerMarksInstrumentationRuntimeBeforeApplicationStartup() {
        assertTrue(InstrumentationTestRuntime.isActive)
    }

    @Test
    fun networkRecoveryCallbackDoesNotRunAgainstSharedSafFixtures() {
        AudioDownloadManager.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        assertFalse(AudioDownloadManager.networkRecoveryMonitorRegistered)
    }
}
