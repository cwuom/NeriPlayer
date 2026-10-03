package moe.ouom.neriplayer.testing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentsFixtureRecoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun timedOutPickerReleasesFocusClipboardAndSubsequentDirectoryGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val focused = AtomicBoolean(false)
        val text = mutableStateOf("")
        compose.setContent {
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            SideEffect { focused.set(windowFocused) }
            MaterialTheme {
                TextField(text.value, { text.value = it }, Modifier.testTag("fixture-recovery-input"))
            }
        }
        UiFailureDiagnostics.onFailure("documents-recovery-initial-focus") {
            compose.waitUntil(5_000) { focused.get() }
        }
        val failure = assertThrows(IllegalStateException::class.java) {
            DocumentsFixture.createExternalTree(confirmPicker = false, timeoutMillis = 3_000)
        }
        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertTrue("fixture cleanup must be acknowledged", failure.suppressed.isEmpty())
        compose.waitUntil(5_000) { focused.get() }
        compose.onNodeWithTag("fixture-recovery-input").performClick().performTextInput("recovered")
        compose.runOnIdle {
            assertEquals("recovered", text.value)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "recovered"))
            assertEquals("recovered", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        val tree = DocumentsFixture.createExternalTree()
        try {
            compose.waitUntil(5_000) { focused.get() }
            assertTrue(requireNotNull(DocumentFile.fromTreeUri(context, tree)).delete())
        } finally {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (context.contentResolver.persistedUriPermissions.any { it.uri == tree }) {
                context.contentResolver.releasePersistableUriPermission(tree, flags)
            }
            context.revokeUriPermission(tree, flags)
        }
    }
}
