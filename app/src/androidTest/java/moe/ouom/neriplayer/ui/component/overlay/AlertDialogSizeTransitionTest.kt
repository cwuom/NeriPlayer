package moe.ouom.neriplayer.ui.component.overlay

import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.graphics.HardwareRendererCompat
import kotlin.math.roundToInt
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.SoftwareKeyboardRule
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.awaitAboveKeyboard
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlertDialogSizeTransitionTest {
    @get:Rule val keyboard: TestRule = SoftwareKeyboardRule()
    @get:Rule val compose = createAndroidComposeRule<TestComposeActivity>()

    @Test fun cleanCardKeepsItsHorizontalEdges() = checkCard(UiStyle.Clean)
    @Test fun materialCardKeepsItsHorizontalEdges() = checkCard(UiStyle.Material)

    private fun checkCard(style: UiStyle) {
        assumeComposeHostAvailable()
        val expanded = mutableStateOf(false)
        lateinit var window: Window
        lateinit var card: LayoutCoordinates
        val frames = mutableListOf<Pair<Int, Int>>()
        val location = IntArray(2)
        val draw = ViewTreeObserver.OnDrawListener {
            if (card.isAttached) {
                window.decorView.getLocationOnScreen(location)
                val bounds = card.boundsInWindow()
                frames += (location[0] + bounds.left.roundToInt()) to (location[0] + bounds.right.roundToInt())
            }
        }
        val drawingEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            HardwareRendererCompat.setDrawingEnabled(true)
            compose.setContent {
                NeriTheme(
                    followSystemDark = false, forceDark = false, dynamicColor = false,
                    seedColorHex = "6750A4", uiStyle = style, animateColors = false
                ) {
                    CompositionLocalProvider(LocalOverlaySurfaceScale provides 0.95f) {
                        MiuixSettingsDialog(
                            onDismissRequest = {},
                            modifier = Modifier.onGloballyPositioned { card = it },
                            title = { Text("Proxy") },
                            text = {
                                window = checkNotNull(LocalView.current.findOverlayDialogWindow())
                                Column(Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { expanded.value = !expanded.value }) { Text("Options") }
                                    if (expanded.value) {
                                        OutlinedTextField(
                                            value = "", onValueChange = {}, singleLine = true,
                                            modifier = Modifier.fillMaxWidth().testTag("host")
                                        )
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = {}) { Text("Close") } }
                        )
                    }
                }
            }
            compose.runOnIdle {
                window.decorView.viewTreeObserver.addOnDrawListener(draw)
                draw.onDraw()
            }
            try {
                compose.onNodeWithText("Options").performClick()
                val host = compose.onNodeWithTag("host")
                host.performNativeClick()
                compose.awaitAboveKeyboard(host)
                compose.runOnIdle {
                    WindowInsetsControllerCompat(window, window.decorView)
                        .hide(WindowInsetsCompat.Type.ime())
                }
                compose.waitUntil(5_000) {
                    ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
                compose.onNodeWithText("Options").performClick()
                compose.onNodeWithTag("host").assertDoesNotExist()
                compose.runOnIdle {
                    draw.onDraw()
                    assertTrue("Expected draws while the content and keyboard changed", frames.size > 2)
                    val leftRange = frames.maxOf { it.first } - frames.minOf { it.first }
                    val rightRange = frames.maxOf { it.second } - frames.minOf { it.second }
                    assertTrue("Card edges changed: ${frames.distinct()}", leftRange <= 1 && rightRange <= 1)
                }
            } finally {
                compose.runOnIdle { window.decorView.viewTreeObserver.removeOnDrawListener(draw) }
            }
        } finally {
            HardwareRendererCompat.setDrawingEnabled(drawingEnabled)
        }
    }
}
