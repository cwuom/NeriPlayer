package moe.ouom.neriplayer.ui.screen.debug

import android.app.Application
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class TextLogFileListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<String>()
    private var backCount = 0

    @Test
    fun `text logs are listed newest first and clearing deletes only text logs`() {
        val directory = tempFolder.newFolder("logs")
        val older = File(directory, "older.txt").apply {
            writeText("a")
            setLastModified(1_000_000L)
        }
        val newer = File(directory, "newer.txt").apply {
            writeText("b".repeat(4096))
            setLastModified(2_000_000L)
        }
        val otherLog = File(directory, "notes.log").apply { writeText("c") }
        File(directory, "nested.txt").mkdirs()
        assertEquals(listOf(newer, older), listTextLogFiles(directory))

        setScreen { directory }

        composeRule.onNodeWithText(string(CoreCommonR.string.log_app)).assertExists()
        composeRule.onNodeWithText("newer.txt").assertExists()
        composeRule.onNodeWithText(formatLogFileMeta(newer)).assertExists()
        composeRule.onNodeWithText("notes.log").assertDoesNotExist()
        composeRule.onNodeWithText("older.txt").performClick()
        assertEquals(listOf(older.absolutePath), opened)

        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.log_clear)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.log_delete_confirm)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.log_delete_confirm)).assertDoesNotExist()
        assertTrue(older.exists())

        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.log_clear)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.common_clear_all)).performClick()
        val clearedMessage = context.resources.getQuantityString(CoreCommonR.plurals.log_cleared_count, 2, 2)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(clearedMessage).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.log_no_file)).assertExists()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.log_clear)).assertDoesNotExist()
        assertFalse(older.exists())
        assertFalse(newer.exists())
        assertTrue(otherLog.exists())
    }

    @Test
    fun `missing log directory shows the empty hint without a clear action`() {
        setScreen { null }

        composeRule.onNodeWithText(string(CoreCommonR.string.log_no_file)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.log_enable_hint)).assertExists()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.log_clear)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.action_back)).performClick()
        assertEquals(1, backCount)

        val missing = File(tempFolder.root, "missing")
        assertEquals(emptyList<File>(), listTextLogFiles(missing))
        assertEquals(0, deleteTextLogFiles(missing))
        assertEquals(0, deleteTextLogFiles(null))
    }

    @Test
    fun `crash log screen lists files from the crash directory`() {
        val crashDirectory = crashLogDirectory(context).apply { mkdirs() }
        File(crashDirectory, "crash-1.txt").writeText("boom")

        composeRule.setContent {
            MaterialTheme {
                CrashLogListScreen(onBack = { backCount++ }, onLogFileClick = { opened += it })
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.crash_log_title)).assertExists()
        composeRule.onNodeWithText("crash-1.txt").performClick()
        assertEquals(listOf(File(crashDirectory, "crash-1.txt").absolutePath), opened)
        assertEquals(File(context.getExternalFilesDir(null), "crashes"), crashDirectory)
    }

    @Test
    fun `app log screen is empty while file logging is disabled`() {
        composeRule.setContent {
            MaterialTheme {
                LogListScreen(onBack = { backCount++ }, onLogFileClick = { opened += it })
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.log_app)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.log_no_file)).assertExists()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.log_clear)).assertDoesNotExist()
    }

    private fun setScreen(resolveDirectory: () -> File?) {
        composeRule.setContent {
            MaterialTheme {
                TextLogFileListScreen(
                    titleRes = CoreCommonR.string.log_app,
                    clearConfirmRes = CoreCommonR.string.log_delete_confirm,
                    clearedCountRes = CoreCommonR.plurals.log_cleared_count,
                    emptyTitleRes = CoreCommonR.string.log_no_file,
                    emptyHintRes = CoreCommonR.string.log_enable_hint,
                    resolveDirectory = resolveDirectory,
                    onBack = { backCount++ },
                    onLogFileClick = { opened += it },
                    fileIcon = { Icon(Icons.Outlined.Description, null) }
                )
            }
        }
    }

    private fun string(id: Int): String = context.getString(id)
}
