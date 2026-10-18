package moe.ouom.neriplayer.ui.screen.debug

import android.app.Application
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
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
    private var screenLocaleTags: String? = null
    private var screenClearDescription: String? = null

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
        waitForDisplayedWithDiagnostics(
            directory,
            "log files loaded",
            hasContentDescription(string(CoreCommonR.string.log_clear)),
            hasText("newer.txt"),
            hasText("older.txt")
        )

        composeRule.onNodeWithText(string(CoreCommonR.string.log_app)).assertExists()
        composeRule.onNodeWithText("newer.txt").assertExists()
        assertTrue(
            composeRule.onNodeWithText("newer.txt").fetchSemanticsNode().boundsInRoot.top <
                composeRule.onNodeWithText("older.txt").fetchSemanticsNode().boundsInRoot.top
        )
        composeRule.onNodeWithText(formatLogFileMeta(newer)).assertExists()
        composeRule.onNodeWithText("notes.log").assertDoesNotExist()
        composeRule.onNodeWithText("older.txt").performClick()
        assertEquals(listOf(older.absolutePath), opened)

        clickClearWithDiagnostics(directory, "open confirmation")
        composeRule.onNodeWithText(string(CoreCommonR.string.log_delete_confirm)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.log_delete_confirm)).assertDoesNotExist()
        assertTrue(older.exists())

        clickClearWithDiagnostics(directory, "reopen after cancellation")
        composeRule.onNodeWithText(string(CoreCommonR.string.common_clear_all)).performClick()
        val clearedMessage = context.resources.getQuantityString(CoreCommonR.plurals.log_cleared_count, 2, 2)
        waitForDisplayedWithDiagnostics(
            directory,
            "text log deletion completed",
            hasText(clearedMessage),
            hasText(string(CoreCommonR.string.log_no_file))
        )
        composeRule.onNodeWithText(string(CoreCommonR.string.log_no_file)).assertExists()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.log_clear)).assertDoesNotExist()
        assertFalse(older.exists())
        assertFalse(newer.exists())
        assertTrue(otherLog.exists())
    }

    @Test
    fun `missing log directory shows the empty hint without a clear action`() {
        setScreen { null }
        waitForDisplayedWithDiagnostics(
            null,
            "missing directory loaded",
            hasText(string(CoreCommonR.string.log_no_file)),
            hasText(string(CoreCommonR.string.log_enable_hint))
        )

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
        waitForDisplayedWithDiagnostics(
            crashDirectory,
            "crash log files loaded",
            hasContentDescription(string(CoreCommonR.string.log_clear)),
            hasText("crash-1.txt")
        )

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
        waitForDisplayedWithDiagnostics(
            null,
            "disabled file logging loaded",
            hasText(string(CoreCommonR.string.log_no_file)),
            hasText(string(CoreCommonR.string.log_enable_hint))
        )

        composeRule.onNodeWithText(string(CoreCommonR.string.log_app)).assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.log_no_file)).assertExists()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.log_clear)).assertDoesNotExist()
    }

    private fun setScreen(resolveDirectory: () -> File?) {
        composeRule.setContent {
            val localeTags = LocalConfiguration.current.locales.toLanguageTags()
            val clearDescription = stringResource(CoreCommonR.string.log_clear)
            SideEffect {
                screenLocaleTags = localeTags
                screenClearDescription = clearDescription
            }
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
                    fileIcon = Icons.Rounded.Description,
                    fileHue = Color(0xFF0A84FF)
                )
            }
        }
    }

    private fun waitForDisplayedWithDiagnostics(
        directory: File?,
        stage: String,
        vararg matchers: SemanticsMatcher
    ) = withScreenDiagnostics(directory, stage) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            matchers.all { composeRule.onNode(it).isDisplayed() }
        }
    }

    private fun clickClearWithDiagnostics(directory: File, stage: String) =
        withScreenDiagnostics(directory, stage) {
            composeRule.onNodeWithContentDescription(string(CoreCommonR.string.log_clear)).performClick()
        }

    private fun withScreenDiagnostics(directory: File?, stage: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            if (failure !is AssertionError && failure !is ComposeTimeoutException) throw failure
            val diagnostics = buildString {
                appendLine("stage=$stage clockMs=${composeRule.mainClock.currentTime} autoAdvance=${composeRule.mainClock.autoAdvance}")
                appendLine("expectedClear=${string(CoreCommonR.string.log_clear)} screenClear=$screenClearDescription")
                appendLine("contextLocales=${context.resources.configuration.locales.toLanguageTags()} screenLocales=$screenLocaleTags")
                appendLine("opened=$opened backCount=$backCount")
                appendLine("files=${directory?.listFiles()?.map { "${it.name}:isFile=${it.isFile}:exists=${it.exists()}" }}")
                for (unmerged in listOf(false, true)) {
                    val tree = runCatching {
                        composeRule.onAllNodes(isRoot(), useUnmergedTree = unmerged)
                            .printToString(maxDepth = Int.MAX_VALUE)
                    }.getOrElse { "semantics unavailable: $it" }
                    appendLine("unmerged=$unmerged\n$tree")
                }
            }
            throw AssertionError("Log screen synchronization or interaction failed\n$diagnostics", failure)
        }
    }

    private fun string(id: Int): String = context.getString(id)
}
