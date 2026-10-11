package moe.ouom.neriplayer.ui.screen.safemode

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.util.ClipboardCopyResult
import moe.ouom.neriplayer.util.crash.CrashReportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h2000dp")
class SafeModeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val events = mutableListOf<String>()

    @Test
    fun `screen without a pending crash opens and cancels both reset confirmations`() {
        composeRule.setContent {
            MaterialTheme {
                SafeModeScreen(onRestoreNormal = { events += "restore" })
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(string(CoreCommonR.string.safe_mode_log_empty))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.safe_mode_title)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.safe_mode_data_export_title)).assertExists()

        button(CoreCommonR.string.safe_mode_reset_login).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.safe_mode_reset_login_confirm_message)).assertExists()
        button(CoreCommonR.string.action_cancel).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.safe_mode_reset_login_confirm_message)).assertDoesNotExist()

        button(CoreCommonR.string.safe_mode_reset_settings).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.safe_mode_reset_settings_confirm_message)).assertExists()
        button(CoreCommonR.string.action_cancel).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.safe_mode_reset_settings_confirm_message)).assertDoesNotExist()

        button(CoreCommonR.string.safe_mode_restore_normal).performClick()
        assertEquals(listOf("restore"), events)
    }

    @Test
    fun `crash preview shows the report file truncation notice and unreadable fallback`() {
        val file = File(context.cacheDir, "crash-2026.txt")
        var report by mutableStateOf<CrashReportStore.PendingCrashReport?>(
            CrashReportStore.PendingCrashReport(
                origin = CrashReportStore.CrashOrigin.Jvm,
                file = file,
                previewContent = "java.lang.IllegalStateException: boom",
                previewTruncated = false
            )
        )
        composeRule.setContent {
            MaterialTheme {
                CrashLogPreviewCard(
                    report = report,
                    onCopy = { events += "copy" },
                    onExport = { events += "export" }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.safe_mode_log_file, file.name)).assertExists()
        composeRule.onNodeWithText("java.lang.IllegalStateException: boom").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.startup_crash_report_truncated)).assertDoesNotExist()
        button(CoreCommonR.string.safe_mode_copy_log).performClick()
        button(CoreCommonR.string.safe_mode_export_log).performClick()

        report = report?.copy(previewContent = " ", previewTruncated = true)
        composeRule.onNodeWithText(string(CoreCommonR.string.startup_crash_report_truncated)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.log_cannot_read)).assertExists()

        report = null
        composeRule.onNodeWithText(string(CoreCommonR.string.safe_mode_log_empty)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.safe_mode_copy_log)).assertDoesNotExist()
        assertEquals(listOf("copy", "export"), events)
    }

    @Test
    fun `recovery actions are disabled while another safe mode action is busy`() {
        var busy by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                RecoveryActionCard(
                    busy = busy,
                    onResetLogin = { events += "login" },
                    onResetSettings = { events += "settings" },
                    onRestoreNormal = { events += "restore" }
                )
            }
        }

        button(CoreCommonR.string.safe_mode_reset_login).performClick()
        button(CoreCommonR.string.safe_mode_reset_settings).performClick()
        button(CoreCommonR.string.safe_mode_restore_normal).assertIsEnabled().performClick()

        busy = true
        button(CoreCommonR.string.safe_mode_reset_login).assertIsNotEnabled()
        button(CoreCommonR.string.safe_mode_reset_settings).assertIsNotEnabled()
        button(CoreCommonR.string.safe_mode_restore_normal).assertIsNotEnabled()
        assertEquals(listOf("login", "settings", "restore"), events)
    }

    @Test
    fun `crash log copy skips blank content and a missing clipboard`() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)

        assertNull(copyLogToClipboard(clipboard, "  "))
        assertNull(copyLogToClipboard(null, "trace"))
        assertEquals(ClipboardCopyResult.Copied(wasTruncated = false), copyLogToClipboard(clipboard, "trace"))
        assertEquals("trace", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    private fun button(id: Int) = composeRule.onNode(hasText(string(id)) and hasClickAction())

    private fun string(id: Int): String = context.getString(id)
}
