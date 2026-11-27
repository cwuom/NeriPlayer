package moe.ouom.neriplayer.activity.auth

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import moe.ouom.neriplayer.core.di.networkRoute
import moe.ouom.neriplayer.data.model.settings.bootstrap.BootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.bootstrap.persistBootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.settings.bootstrap.readBootstrapSettingsSnapshotSync
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LoginNetworkRouteTest {
    @Test
    fun `QR and nested web login keep the main process route snapshot`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val previous = readBootstrapSettingsSnapshotSync(context)
        val configured = BootstrapSettingsSnapshot(proxyEndpoint = "socks5://localhost:1092", dohEndpoint = "https://dns.google/dns-query")
        try {
            persistBootstrapSettingsSnapshot(context, configured)
            val qrIntent = loginIntent(context, BiliQrLoginActivity::class.java)
            assertEquals(configured.networkRoute(), qrIntent.authRoute())
            // this process may still have a stale mirror on disk while the intent already has the new route
            persistBootstrapSettingsSnapshot(context, previous)
            val login = Robolectric.buildActivity(Activity::class.java, qrIntent).get()
            assertEquals(configured.networkRoute(), loginIntent(login, BiliWebLoginActivity::class.java).authRoute())
        } finally {
            persistBootstrapSettingsSnapshot(context, previous)
        }
    }
}
