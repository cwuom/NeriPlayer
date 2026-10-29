package moe.ouom.neriplayer.ui.screen.tab.settings.page
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class LanSettingsSearchTest {
    @Test fun discoveryAndNudgeSearchLeadToVisibleListenTogetherPage() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val entries = buildSettingsSearchEntries(app.resources)
        assertTrue(searchSettingsEntries(entries, "LAN").any { it.page == SettingsPage.ListenTogether })
        assertTrue(searchSettingsEntries(entries, "局域网").any { it.page == SettingsPage.ListenTogether })
        val setting = AutoSettingsMetadata.settings.single { it.keyName == "lan_listen_nudge_ms" }
        assertEquals(SettingsPage.ListenTogether, setting.settingsPage())
        assertEquals("page:ListenTogether", setting.searchTargetId())
    }
}
