package moe.ouom.neriplayer.ui.sync.upgrade

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SyncProtocolStartupStorageFailureTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val model = SyncProtocolUpgradeViewModel(
        pendingFlow = flowOf(emptyList()), saveConfirmation = { _, _ -> },
        initializeStartupTargets = { throw SyncStorageUnavailableException() }, logFailure = {}
    )

    @After
    fun cleanUp() {
        model.viewModelScope.cancel()
    }

    @Test
    fun `startup configuration gate explains local secure storage failure`() {
        composeRule.setContent {
            MaterialTheme {
                syncProtocolStartupConfigurationGate(dialogRequested = true, onDismiss = {}, viewModel = model)
            }
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.sync_secure_storage_unavailable)).assertExists()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.sync_upgrade_failed)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_retry)).assertExists()
    }
}
