package moe.ouom.neriplayer.ui.feedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriSnackbarHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun overlaySnackbarStaysNearBottomAndKeepsActionFeedbackCompact() {
        composeRule.setContent {
            MaterialTheme {
                val hostState = remember { SnackbarHostState() }
                Box(
                    modifier = Modifier
                        .size(TestContainerWidth, TestContainerHeight)
                        .testTag(TestContainerTag)
                ) {
                    NeriOverlaySnackbarHost(
                        hostState = hostState,
                        bottomPadding = TestBottomPadding,
                        applyNavigationBarsPadding = false,
                        applyImePadding = false
                    )
                    LaunchedEffect(Unit) {
                        hostState.showNeriSnackbar(
                            message = TestLongMessage,
                            actionLabel = "撤销",
                            withDismissAction = true,
                            duration = SnackbarDuration.Indefinite
                        )
                    }
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag(NeriSnackbarTestTag)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val containerBounds = composeRule.onNodeWithTag(TestContainerTag)
            .fetchSemanticsNode()
            .boundsInRoot
        val snackbarBounds = composeRule.onAllNodesWithTag(NeriSnackbarTestTag)
            .fetchSemanticsNodes()
            .single()
            .boundsInRoot

        assertTrue(
            "Snackbar was not anchored near the bottom: $snackbarBounds in $containerBounds",
            snackbarBounds.bottom >= containerBounds.bottom - containerBounds.height * 0.15f
        )
        assertTrue(
            "Snackbar extended past its container: $snackbarBounds in $containerBounds",
            snackbarBounds.bottom <= containerBounds.bottom + PositionTolerancePx
        )
        assertTrue(
            "Action feedback was not compact: $snackbarBounds in $containerBounds",
            snackbarBounds.height < containerBounds.height * 0.14f
        )
    }

    @Test
    fun overlaySnackbarDismissActionClosesDismissibleFeedback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            MaterialTheme {
                val hostState = remember { SnackbarHostState() }
                Box(modifier = Modifier.size(TestContainerWidth, TestContainerHeight)) {
                    NeriOverlaySnackbarHost(
                        hostState = hostState,
                        applyNavigationBarsPadding = false,
                        applyImePadding = false
                    )
                    LaunchedEffect(Unit) {
                        hostState.showNeriSnackbar(
                            message = "可关闭反馈",
                            withDismissAction = true,
                            duration = SnackbarDuration.Indefinite
                        )
                    }
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag(NeriSnackbarTestTag)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.cd_close)
        ).performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag(NeriSnackbarTestTag)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun overlaySnackbarStaysAboveReservedOperationArea() {
        composeRule.setContent {
            MaterialTheme {
                val hostState = remember { SnackbarHostState() }
                Box(
                    modifier = Modifier
                        .size(TestContainerWidth, TestContainerHeight)
                        .testTag(TestContainerTag)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(TestOperationControlsHeight)
                            .testTag(TestOperationControlsTag)
                    )
                    NeriOverlaySnackbarHost(
                        hostState = hostState,
                        bottomPadding = TestOperationControlsHeight + TestFeedbackGap,
                        applyNavigationBarsPadding = false,
                        applyImePadding = false
                    )
                    LaunchedEffect(Unit) {
                        hostState.showNeriSnackbar(
                            message = "本地音频标签回写失败",
                            withDismissAction = true,
                            duration = SnackbarDuration.Indefinite
                        )
                    }
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag(NeriSnackbarTestTag)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val operationBounds = composeRule.onNodeWithTag(TestOperationControlsTag)
            .fetchSemanticsNode()
            .boundsInRoot
        val snackbarBounds = composeRule.onAllNodesWithTag(NeriSnackbarTestTag)
            .fetchSemanticsNodes()
            .single()
            .boundsInRoot

        assertTrue(
            "Snackbar overlapped the reserved operation area: $snackbarBounds in $operationBounds",
            snackbarBounds.bottom <= operationBounds.top + PositionTolerancePx
        )
    }

    private companion object {
        const val TestContainerTag = "feedback_test_container"
        const val TestOperationControlsTag = "feedback_test_operation_controls"
        const val PositionTolerancePx = 1f
        val TestContainerWidth = 360.dp
        val TestContainerHeight = 640.dp
        val TestBottomPadding = 64.dp
        val TestOperationControlsHeight = 64.dp
        val TestFeedbackGap = 8.dp
        const val TestLongMessage =
            "已导出 837 首歌曲到「我喜欢的音乐」，正在同步本地索引和封面信息，请稍后查看结果"
    }
}
