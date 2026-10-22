package moe.ouom.neriplayer.ui.screen.safemode

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeModeConfigExportDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val messages = CopyOnWriteArrayList<String>()
    private var requestCode: Int? = null
    private var launchCount = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            check(contract is ActivityResultContracts.CreateDocument)
            this@SafeModeConfigExportDeviceTest.requestCode = requestCode
            launchCount++
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @Before
    fun requireComposeHost() {
        assumeComposeHostAvailable()
    }

    @Test
    fun recoveryExportWritesSelectedLoginSectionsInsideThePasswordProtectedFile() {
        render()
        button(CoreCommonR.string.safe_mode_export_config).performClick()
        button(CoreCommonR.string.settings_config_include_login_state).performClick()
        button(CoreCommonR.string.settings_config_encrypt_file).performScrollTo().performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_password))
            .performScrollTo().performTextReplacement(PASSWORD)
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_confirm_password))
            .performScrollTo().performTextReplacement(PASSWORD)
        onView(isRoot()).inRoot(isDialog()).perform(closeSoftKeyboard())
        button(CoreCommonR.string.settings_backup_action_export).performNativeClick()
        composeRule.runOnIdle { assertEquals(1, launchCount) }
        val destination = File.createTempFile("safe-mode-protected-config-", ".json", context.cacheDir)
        try {
            composeRule.runOnIdle { registry.dispatchResult(requireNotNull(requestCode), Uri.fromFile(destination)) }
            composeRule.waitUntil(timeoutMillis = 15_000) { messages.isNotEmpty() }
            composeRule.waitForIdle()
            assertTrue("recovery export did not write its file: $messages", destination.length() > 0)

            val envelope = Json.parseToJsonElement(destination.readText()).jsonObject
            assertEquals("moe.ouom.neriplayer.config.encrypted", envelope.getValue("kind").jsonPrimitive.content)
            val root = Json.parseToJsonElement(decrypt(destination)).jsonObject
            for (section in ACCOUNT_SECTIONS) assertTrue("missing $section", section in root)
            assertFalse(destination.readText().contains("neteaseAuth"))
        } finally {
            assertTrue("cannot remove the test backup", !destination.exists() || destination.delete())
        }
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

    private fun decrypt(destination: File): String {
        val envelope = Json.parseToJsonElement(destination.readText()).jsonObject
        fun bytes(name: String) = Base64.getDecoder().decode(envelope.getValue(name).jsonPrimitive.content)
        val spec = PBEKeySpec(PASSWORD.toCharArray(), bytes("salt"), 600_000, 256)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, bytes("iv")))
            cipher.updateAAD("moe.ouom.neriplayer.config.encrypted|1|PBKDF2-HMAC-SHA256|600000|AES-256-GCM".toByteArray())
            cipher.doFinal(bytes("ciphertext")).toString(Charsets.UTF_8)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun button(id: Int) = composeRule.onNode(hasText(string(id)) and hasClickAction())

    private fun string(id: Int): String = context.getString(id)

    private companion object {
        const val PASSWORD = "Synthetic-safe-mode-password"
        val ACCOUNT_SECTIONS = listOf("neteaseAuth", "biliAuth", "youTubeAuth", "gitHubSync", "webDavSync")
    }
}
