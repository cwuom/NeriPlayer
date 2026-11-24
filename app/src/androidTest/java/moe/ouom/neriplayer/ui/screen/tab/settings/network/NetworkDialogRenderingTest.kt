package moe.ouom.neriplayer.ui.screen.tab.settings.network

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.SoftwareKeyboardRule
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.awaitAboveKeyboard
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkDialogRenderingTest {
    @get:Rule val keyboard: TestRule = SoftwareKeyboardRule()
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun requireWindow() = assumeComposeHostAvailable()

    @Test fun cleanProxyFieldsStaySeparateAndSaveWithKeyboardOpen() = verifyProxy(UiStyle.Clean)
    @Test fun materialProxyFieldsStaySeparateAndSaveWithKeyboardOpen() = verifyProxy(UiStyle.Material)
    @Test fun cleanCustomDnsValidatesAndCancels() = verifyDns(UiStyle.Clean)
    @Test fun materialCustomDnsValidatesAndCancels() = verifyDns(UiStyle.Material)

    private fun verifyProxy(style: UiStyle) {
        val visible = mutableStateOf(true)
        var saved: String? = null
        render(style) {
            if (visible.value) ProxyDialog(null, { visible.value = false }) {
                saved = it
                visible.value = false
            }
        }
        compose.onNodeWithText("SOCKS5").performScrollTo().performClick()
        val fields = compose.onAllNodes(hasSetTextAction())
        val host = fields[0]
        val port = fields[1]
        val confirm = compose.onNodeWithText(context.getString(R.string.action_confirm))
        confirm.assertIsNotEnabled()
        val gap = port.getUnclippedBoundsInRoot().top - host.getUnclippedBoundsInRoot().bottom
        assertTrue("Host and port must be visually separate: $gap", gap >= 8.dp)
        capture(style, "proxy")
        host.performScrollTo().performNativeClick()
        compose.awaitAboveKeyboard(host)
        host.performTextReplacement("proxy.example.org")
        host.performImeAction()
        port.assertIsFocused().performTextReplacement("65536")
        confirm.assertIsNotEnabled()
        port.performTextReplacement("1080")
        compose.awaitAboveKeyboard(port)
        compose.awaitAboveKeyboard(confirm)
        capture(style, "keyboard")
        confirm.assertIsEnabled().assertIsDisplayed().performNativeClick()
        compose.waitUntil(5_000) { !visible.value }
        compose.runOnIdle { assertEquals("socks5://proxy.example.org:1080", saved) }
    }

    private fun verifyDns(style: UiStyle) {
        val visible = mutableStateOf(true)
        var saved: String? = null
        render(style) {
            if (visible.value) DohDialog("", { visible.value = false }) { saved = it }
        }
        compose.onNodeWithText(context.getString(R.string.network_custom_dns)).performScrollTo().performClick()
        val field = compose.onNode(hasSetTextAction())
        val confirm = compose.onNodeWithText(context.getString(R.string.action_confirm))
        field.performScrollTo().performNativeClick()
        compose.awaitAboveKeyboard(field)
        field.performTextReplacement("http://resolver.example/dns-query")
        confirm.assertIsNotEnabled()
        field.performTextReplacement("https://resolver.example/dns-query")
        confirm.assertIsEnabled()
        val cancel = compose.onNodeWithText(context.getString(R.string.action_cancel))
        compose.awaitAboveKeyboard(cancel)
        cancel.performNativeClick()
        compose.waitUntil(5_000) { !visible.value }
        field.assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, saved) }
    }

    private fun render(style: UiStyle, content: @Composable () -> Unit) {
        compose.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "934F2F", uiStyle = style, animateColors = false, content = content)
        }
    }

    private fun capture(style: UiStyle, stage: String) {
        if (InstrumentationRegistry.getArguments().getString("captureNetworkDialogs") != "true") return
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(450, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(context.getExternalFilesDir(null), "network-${style.storageValue}-$stage.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
