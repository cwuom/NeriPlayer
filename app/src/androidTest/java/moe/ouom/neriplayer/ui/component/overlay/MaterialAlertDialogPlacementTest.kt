package moe.ouom.neriplayer.ui.component.overlay

import android.view.ViewTreeObserver
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.graphics.HardwareRendererCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaterialAlertDialogPlacementTest {
    @get:Rule val compose = createAndroidComposeRule<TestComposeActivity>()

    @Test
    fun reopenedDialogWindowKeepsItsFirstDrawPositionWithoutTheKeyboard() {
        assumeComposeHostAvailable()
        val open = mutableStateOf<WindowPositionSample?>(null)
        val drawingEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            HardwareRendererCompat.setDrawingEnabled(true)
            compose.setContent {
                NeriTheme(
                    followSystemDark = false, forceDark = false, dynamicColor = false,
                    seedColorHex = "6750A4", uiStyle = UiStyle.Material, animateColors = false
                ) {
                    open.value?.let { sample ->
                        MiuixSettingsDialog(
                            onDismissRequest = { open.value = null },
                            title = { Text("New playlist") },
                            text = {
                                SampleDialogWindowPosition(sample)
                                OutlinedTextField(value = "", onValueChange = {}, singleLine = true)
                            },
                            confirmButton = { TextButton(onClick = { open.value = null }) { Text("Close") } }
                        )
                    }
                }
            }
            repeat(3) {
                val sample = WindowPositionSample()
                compose.runOnIdle { open.value = sample }
                compose.onNodeWithText("Close").assertExists()
                assertTrue("Missing dialog draws", sample.draws.await(5, TimeUnit.SECONDS))
                val positions = sample.positions
                assertTrue("Dialog moved between frames: $positions", positions.max() - positions.min() <= 1)
                compose.onNodeWithText("Close").performClick()
                compose.onNodeWithText("New playlist").assertDoesNotExist()
            }
        } finally {
            HardwareRendererCompat.setDrawingEnabled(drawingEnabled)
        }
    }

    private class WindowPositionSample {
        val positions = mutableListOf<Int>()
        val draws = CountDownLatch(24)
    }

    @Composable
    private fun SampleDialogWindowPosition(sample: WindowPositionSample) {
        val decor = checkNotNull(LocalView.current.findOverlayDialogWindow()).decorView
        DisposableEffect(decor, sample) {
            val location = IntArray(2)
            val listener = ViewTreeObserver.OnDrawListener {
                if (sample.draws.count > 0) {
                    decor.getLocationOnScreen(location)
                    sample.positions.add(location[1])
                    sample.draws.countDown()
                    if (sample.draws.count > 0) decor.postInvalidateOnAnimation()
                }
            }
            decor.viewTreeObserver.addOnDrawListener(listener)
            onDispose { decor.viewTreeObserver.removeOnDrawListener(listener) }
        }
    }
}
