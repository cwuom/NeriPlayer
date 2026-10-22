package moe.ouom.neriplayer.ui.screen.safemode

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h2000dp")
class SafeModeConfigExportTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val messages = CopyOnWriteArrayList<String>()
    private var requestCode: Int? = null
    private var launchCount = 0
    private var failLaunch = false
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            check(contract is ActivityResultContracts.CreateDocument)
            if (failLaunch) throw IllegalStateException("synthetic picker failure")
            this@SafeModeConfigExportTest.requestCode = requestCode
            launchCount++
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @Test
    fun `cancelled file picker discards the previous choice before a default recovery export`() {
        render()
        openOptions()
        button(CoreCommonR.string.settings_config_include_login_state).performClick()
        button(CoreCommonR.string.settings_backup_action_export).performClick()
        completePicker(null)

        openOptions()
        button(CoreCommonR.string.settings_backup_action_export).performClick()
        val destination = folder.newFile("config-without-login.json")
        completePicker(Uri.fromFile(destination))
        waitForExport(destination)

        val root = Json.parseToJsonElement(destination.readText()).jsonObject
        assertEquals("moe.ouom.neriplayer.config", root.getValue("kind").jsonPrimitive.content)
        for (section in ACCOUNT_SECTIONS) assertFalse("unexpected $section", section in root)
    }

    @Test
    fun `picker launch failure releases the prepared selection so recovery export can retry`() {
        render()
        failLaunch = true
        openOptions()
        button(CoreCommonR.string.settings_backup_action_export).performClick()

        composeRule.runOnIdle { assertEquals(1, messages.size) }
        button(CoreCommonR.string.safe_mode_export_config).assertIsEnabled()
        failLaunch = false
        openOptions()
        button(CoreCommonR.string.settings_backup_action_export).performClick()
        assertEquals(1, launchCount)
        completePicker(null)
    }

    private fun render() {
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    animateColors = false
                ) {
                    SafeModeExportCard(busy = false, onShowMessage = { message, _ -> messages += message })
                }
            }
        }
    }

    private fun openOptions() {
        button(CoreCommonR.string.safe_mode_export_config).performClick()
    }

    private fun completePicker(uri: Uri?) {
        composeRule.runOnIdle { registry.dispatchResult(requireNotNull(requestCode), uri) }
    }

    private fun waitForExport(destination: File) {
        composeRule.waitUntil(timeoutMillis = 15_000) { messages.isNotEmpty() }
        composeRule.waitForIdle()
        assertTrue("recovery export did not write its file: $messages", destination.length() > 0)
    }

    private fun button(id: Int) = composeRule.onNode(hasText(string(id)) and hasClickAction())

    private fun string(id: Int): String = context.getString(id)

    private companion object {
        val ACCOUNT_SECTIONS = listOf("neteaseAuth", "biliAuth", "youTubeAuth", "gitHubSync", "webDavSync")
    }
}
