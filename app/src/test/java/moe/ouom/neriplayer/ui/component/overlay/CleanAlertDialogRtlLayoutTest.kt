package moe.ouom.neriplayer.ui.component.overlay

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp")
class CleanAlertDialogRtlLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sideBySideActionsMirrorTheirRealBoundsFromLtrToRtl() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var confirmations = 0
        var cancellations = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    uiStyle = UiStyle.Clean,
                    animateColors = false
                ) {
                    CleanAlertDialog(
                        onDismissRequest = {},
                        confirmButton = {
                            TextButton(onClick = { confirmations++ }, modifier = Modifier.testTag(ConfirmTag)) {
                                Text("Confirm")
                            }
                        },
                        modifier = Modifier.width(340.dp).testTag(DialogTag),
                        dismissButton = {
                            TextButton(onClick = { cancellations++ }, modifier = Modifier.testTag(DismissTag)) {
                                Text("Cancel")
                            }
                        },
                        icon = null,
                        title = { Text("Review changes") },
                        text = { Text("Both actions must follow the reading direction") },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    )
                }
            }
        }

        val ltrDialog = bounds(DialogTag)
        val ltrConfirm = bounds(ConfirmTag)
        val ltrDismiss = bounds(DismissTag)
        assertEquals(ltrConfirm.top, ltrDismiss.top, 1f)
        assertTrue("LTR dismiss must lead and confirm must trail", ltrDismiss.right < ltrConfirm.left)

        composeRule.runOnIdle { direction.value = LayoutDirection.Rtl }
        val rtlDialog = bounds(DialogTag)
        val rtlConfirm = bounds(ConfirmTag)
        val rtlDismiss = bounds(DismissTag)
        assertEquals(rtlConfirm.top, rtlDismiss.top, 1f)
        assertTrue("RTL confirm must trail on the left and dismiss must lead on the right",
            rtlConfirm.right < rtlDismiss.left)
        assertMirrored(ltrDialog, ltrConfirm, rtlDialog, rtlConfirm)
        assertMirrored(ltrDialog, ltrDismiss, rtlDialog, rtlDismiss)

        composeRule.onNodeWithTag(ConfirmTag).performClick()
        composeRule.onNodeWithTag(DismissTag).performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirmations)
            assertEquals(1, cancellations)
        }
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    private fun assertMirrored(ltrDialog: Rect, ltr: Rect, rtlDialog: Rect, rtl: Rect) {
        assertEquals(ltr.width, rtl.width, 1f)
        assertEquals(ltr.height, rtl.height, 1f)
        assertEquals(ltrDialog.right - ltr.right, rtl.left - rtlDialog.left, 1f)
        assertEquals(ltrDialog.right - ltr.left, rtl.right - rtlDialog.left, 1f)
    }

    private companion object {
        const val DialogTag = "clean-dialog-rtl-fixture"
        const val ConfirmTag = "clean-dialog-confirm"
        const val DismissTag = "clean-dialog-dismiss"
    }
}
