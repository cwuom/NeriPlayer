package moe.ouom.neriplayer.testutil

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTouchInputTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nativeClickRequiresOwningWindowFocusAndRecoversAfterDialog() {
        assumeComposeHostAvailable()
        var clicks = 0
        composeRule.setContent {
            Box(Modifier.fillMaxSize().testTag("native-target").clickable { clicks++ },
                contentAlignment = Alignment.Center) {
                Text("Native click target")
            }
        }
        val activity = composeRule.activity
        var dialog: AlertDialog? = null
        try {
            composeRule.runOnIdle {
                dialog = AlertDialog.Builder(activity).setMessage("Window focus fixture").create().also { it.show() }
            }
            composeRule.waitUntil(5_000) {
                composeRule.runOnIdle {
                    dialog?.window?.decorView?.hasWindowFocus() == true && !activity.hasWindowFocus()
                }
            }
            // 保持另一个窗口聚焦，不能把注入成功误当成目标窗口已经收到点击
            val failure = assertThrows(AssertionError::class.java) {
                composeRule.onNodeWithTag("native-target").performNativeClick()
            }
            assertTrue(failure.message.orEmpty().startsWith("原生触摸目标窗口未就绪:"))
            composeRule.runOnIdle {
                assertEquals(0, clicks)
                assertTrue(dialog?.isShowing == true)
                dialog?.dismiss()
            }
            composeRule.onNodeWithTag("native-target").performNativeClick()
            composeRule.runOnIdle { assertEquals("一次原生点击应送达目标窗口", 1, clicks) }
        } finally {
            composeRule.runOnIdle {
                dialog?.dismiss()
            }
        }
    }
}
