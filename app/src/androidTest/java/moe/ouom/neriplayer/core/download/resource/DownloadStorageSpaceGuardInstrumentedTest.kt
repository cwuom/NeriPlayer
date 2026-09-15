package moe.ouom.neriplayer.core.download.resource

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadStorageSpaceGuardInstrumentedTest {
    @Test
    fun defaultProbeReportsUsableSpaceOnApplicationVolume() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val snapshot = DownloadStorageSpaceGuard().snapshot(context.cacheDir)

        assertTrue(snapshot.usableSpaceKnown)
        assertTrue(snapshot.usableBytes > 0L)
    }
}
