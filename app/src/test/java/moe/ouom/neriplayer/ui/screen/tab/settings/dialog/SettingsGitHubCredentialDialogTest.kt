package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.network.DataHttpClients
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.ui.sync.upgrade.SyncProtocolUpgradeViewModel
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsGitHubCredentialDialogTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val showConfig = mutableStateOf(true)
    @Volatile private var savedToken: String? = null
    private val providerField = DataHttpClients::class.java.getDeclaredField("provider").apply { isAccessible = true }
    private val originalProvider = providerField.get(DataHttpClients)
    private lateinit var github: GitHubSyncViewModel

    @After
    fun restoreClientProvider() {
        providerField.set(DataHttpClients, originalProvider)
    }

    @Test
    fun `editing a verified token requires validating the new text`() {
        render(configured = false)
        tokenField().performTextReplacement("first-token")
        button(CoreCommonR.string.sync_verify_token).performClick()
        awaitSavedToken("first-token")
        button(CoreCommonR.string.action_done).assertExists()

        tokenField().performTextReplacement("second-token")

        button(CoreCommonR.string.sync_verify_token).assertExists().performClick()
        awaitSavedToken("second-token")
        assertEquals("second-token", savedToken)
    }

    @Test
    fun `reopening configuration clears verification together with the token draft`() {
        render(configured = false)
        tokenField().performTextReplacement("first-token")
        button(CoreCommonR.string.sync_verify_token).performClick()
        awaitSavedToken("first-token")
        button(CoreCommonR.string.action_done).assertExists()

        button(CoreCommonR.string.action_cancel).performClick()
        composeRule.runOnIdle { showConfig.value = true }

        button(CoreCommonR.string.sync_verify_token).assertIsNotEnabled()
        button(CoreCommonR.string.action_done).assertDoesNotExist()
    }

    @Test
    fun `an existing configuration can verify and save a replacement token`() {
        render(configured = true)

        tokenField().performTextReplacement("replacement-token")
        button(CoreCommonR.string.sync_verify_token).assertExists().performClick()

        awaitSavedToken("replacement-token")
        assertEquals("replacement-token", savedToken)
        button(CoreCommonR.string.action_close).assertExists()
    }

    private fun render(configured: Boolean) {
        val storage = mock(SecureTokenStorage::class.java)
        `when`(storage.isPersistent).thenReturn(true)
        `when`(storage.isConfigured()).thenReturn(configured)
        `when`(storage.getRepoOwner()).thenReturn(if (configured) "octocat" else "")
        `when`(storage.getRepoName()).thenReturn(if (configured) "backup" else "")
        `when`(storage.observeLastCompletedSyncTime()).thenReturn(MutableStateFlow(0L))
        doAnswer { savedToken = it.getArgument(0); null }.`when`(storage).saveToken(org.mockito.ArgumentMatchers.anyString())
        github = GitHubSyncViewModel().also {
            it.ioDispatcher = Dispatchers.Main.immediate
            GitHubSyncViewModel::class.java.getDeclaredField("storage").apply { isAccessible = true }.set(it, storage)
        }
        val upgrades = SyncProtocolUpgradeViewModel(
            pendingFlow = flowOf(emptyList()), saveConfirmation = { _, _ -> }
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"login":"octocat"}""".toResponseBody()).build()
        }.build()
        DataHttpClients.bind { client }
        composeRule.setContent {
            val owner = checkNotNull(LocalActivity.current as? ViewModelStoreOwner)
            remember(owner) {
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(modelClass.cast(
                        if (modelClass == GitHubSyncViewModel::class.java) github else upgrades
                    ))
                }
                ViewModelProvider(owner, factory)[GitHubSyncViewModel::class.java]
                ViewModelProvider(owner, factory)[SyncProtocolUpgradeViewModel::class.java]
            }
            MaterialTheme {
                SettingsGitHubDialogs(showConfig.value, { showConfig.value = it }, false, {})
            }
        }
        tokenField().assertExists()
    }

    private fun awaitSavedToken(token: String) {
        composeRule.waitUntil {
            shadowOf(Looper.getMainLooper()).idle()
            savedToken == token || github.uiState.value.errorMessage != null
        }
        assertEquals("configuration state: ${github.uiState.value}", token, savedToken)
        composeRule.waitForIdle()
    }

    private fun tokenField() = composeRule.onNodeWithText(context.getString(CoreCommonR.string.settings_github_token_label))
    private fun button(id: Int) = composeRule.onNodeWithText(context.getString(id))
}
