package moe.ouom.neriplayer.ui.feedback

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class NeriSnackbarHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val results = mutableListOf<SnackbarResult>()
    private lateinit var hostState: SnackbarHostState
    private lateinit var scope: CoroutineScope

    @Test
    fun `docked host shows action and dismiss buttons with default and explicit insets`() {
        var explicitInsets by mutableStateOf(false)
        composeRule.setContent {
            hostState = remember { SnackbarHostState() }
            scope = rememberCoroutineScope()
            MaterialTheme {
                if (explicitInsets) {
                    NeriSnackbarHost(
                        hostState = hostState,
                        modifier = Modifier,
                        bottomPadding = 24.dp,
                        applyNavigationBarsPadding = false,
                        applyImePadding = false
                    )
                } else {
                    NeriSnackbarHost(hostState = hostState)
                }
            }
        }

        show("Undo removal", actionLabel = "Undo", withDismissAction = true)
        composeRule.onNodeWithText("Undo removal").assertExists()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { results.size == 1 }

        explicitInsets = true
        show("Saved", withDismissAction = true)
        composeRule.onNodeWithText("Saved").assertExists()
        composeRule.onNodeWithText("Undo").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_close)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { results.size == 2 }

        assertEquals(listOf(SnackbarResult.ActionPerformed, SnackbarResult.Dismissed), results)
    }

    @Test
    fun `overlay host renders a plain snackbar without actions`() {
        var explicitInsets by mutableStateOf(false)
        composeRule.setContent {
            hostState = remember { SnackbarHostState() }
            scope = rememberCoroutineScope()
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    if (explicitInsets) {
                        NeriOverlaySnackbarHost(
                            hostState = hostState,
                            bottomPadding = 16.dp,
                            applyNavigationBarsPadding = false,
                            applyImePadding = false
                        )
                    } else {
                        NeriOverlaySnackbarHost(hostState = hostState)
                    }
                }
            }
        }

        show("Copied")
        composeRule.onNodeWithText("Copied").assertExists()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_close)).assertDoesNotExist()
        composeRule.onAllNodesWithTag(NeriSnackbarTestTag).fetchSemanticsNodes().let { nodes ->
            assertEquals(1, nodes.size)
        }

        explicitInsets = true
        composeRule.onNodeWithText("Copied").assertExists()
    }

    private fun show(message: String, actionLabel: String? = null, withDismissAction: Boolean = false) {
        composeRule.runOnIdle {
            scope.launch {
                results += hostState.showNeriSnackbar(
                    message = message,
                    actionLabel = actionLabel,
                    withDismissAction = withDismissAction,
                    duration = SnackbarDuration.Indefinite
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(NeriSnackbarTestTag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
