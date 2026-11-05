package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import android.app.Application
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.ui.sync.upgrade.SyncProtocolUpgradeViewModel
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncUiState
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsWebDavCredentialStorageTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `opening WebDAV configuration reports an unavailable draft store without crashing composition`() {
        val model = mock(WebDavSyncViewModel::class.java)
        `when`(model.uiState).thenReturn(MutableStateFlow(WebDavSyncUiState()))
        val upgrades = SyncProtocolUpgradeViewModel(flowOf(emptyList()), { _, _ -> })
        mockConstruction(WebDavStorage::class.java) { storage, _ ->
            doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).getServerUrl()
            doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).snapshot()
        }.use {
            composeRule.setContent {
                val owner = checkNotNull(LocalActivity.current as? ViewModelStoreOwner)
                remember(owner) {
                    val factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(modelClass.cast(
                            if (modelClass == WebDavSyncViewModel::class.java) model else upgrades
                        ))
                    }
                    ViewModelProvider(owner, factory)[WebDavSyncViewModel::class.java]
                    ViewModelProvider(owner, factory)[SyncProtocolUpgradeViewModel::class.java]
                }
                MaterialTheme { SettingsWebDavDialogs(true, {}, false, {}) }
            }

            composeRule.onNodeWithText(context.getString(CoreCommonR.string.sync_secure_storage_unavailable)).assertExists()
            composeRule.onNodeWithText(context.getString(CoreCommonR.string.webdav_server_url_label)).assertExists()
        }
    }
}
