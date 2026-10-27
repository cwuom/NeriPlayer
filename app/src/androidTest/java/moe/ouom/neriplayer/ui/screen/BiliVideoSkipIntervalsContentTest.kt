package moe.ouom.neriplayer.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTargetOption
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTarget
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.BILI_VIDEO_SKIP_ADD_BUTTON_TEST_TAG
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.BILI_VIDEO_SKIP_END_INPUT_TEST_TAG
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.BILI_VIDEO_SKIP_START_INPUT_TEST_TAG
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.BiliVideoSkipIntervalsContent
import moe.ouom.neriplayer.activity.LocalizedAppContent
import moe.ouom.neriplayer.common.locale.LanguageManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiliVideoSkipIntervalsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun validationErrorChangesLanguageWithoutReloadingTargetsOrLosingInput() {
        val target = BiliVideoSkipTarget("bili-skip-language-${System.nanoTime()}", 1L)
        val language = mutableStateOf(LanguageManager.Language.CHINESE)
        var loads = 0
        composeRule.setContent {
            LocalizedAppContent(language.value) {
                MaterialTheme {
                    BiliVideoSkipIntervalsContent(
                        title = "Skip intervals",
                        targetResolverKey = target,
                        loadTargetOptions = {
                            loads++
                            listOf(BiliVideoSkipTargetOption(target, "Test P1", 60_000L))
                        },
                        initialTarget = target,
                        onDismiss = {}
                    )
                }
            }
        }
        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_START_INPUT_TEST_TAG).performTextInput("bad")
        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_END_INPUT_TEST_TAG).performTextInput("2")
        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_ADD_BUTTON_TEST_TAG).performClick()
        composeRule.onNodeWithText("请输入秒数、MM:SS 或 HH:MM:SS").assertExists()

        composeRule.runOnIdle { language.value = LanguageManager.Language.ENGLISH }

        composeRule.onNodeWithText("Enter seconds, MM:SS, or HH:MM:SS").assertExists()
        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_START_INPUT_TEST_TAG).assertTextContains("bad")
        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_END_INPUT_TEST_TAG).assertTextContains("2")
        composeRule.runOnIdle { assertEquals(1, loads) }
    }

    @Test
    fun wholeSeconds_addedThroughTheUiAppearInTheIntervalList() {
        val target = BiliVideoSkipTarget(
            bvid = "bili-skip-ui-${System.nanoTime()}",
            cid = 1L
        )
        composeRule.setContent {
            MaterialTheme {
                BiliVideoSkipIntervalsContent(
                    title = "Skip intervals",
                    targetResolverKey = target,
                    loadTargetOptions = {
                        listOf(
                            BiliVideoSkipTargetOption(
                                target = target,
                                label = "Test P1",
                                durationMs = 60_000L
                            )
                        )
                    },
                    initialTarget = target,
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_START_INPUT_TEST_TAG)
            .performTextInput("1")
        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_END_INPUT_TEST_TAG)
            .performTextInput("2")
        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_ADD_BUTTON_TEST_TAG).performClick()

        composeRule.onNodeWithText("00:01 - 00:02").assertExists()
    }

    @Test
    fun latePlaybackTargetDoesNotReplaceTheTargetBeingEdited() {
        val firstTarget = BiliVideoSkipTarget(
            bvid = "bili-skip-ui-first-${System.nanoTime()}",
            cid = 1L
        )
        val latePlaybackTarget = firstTarget.copy(cid = 2L)
        val initialTarget = mutableStateOf<BiliVideoSkipTarget?>(null)

        composeRule.setContent {
            MaterialTheme {
                BiliVideoSkipIntervalsContent(
                    title = "Skip intervals",
                    targetResolverKey = firstTarget.bvid,
                    loadTargetOptions = {
                        listOf(
                            BiliVideoSkipTargetOption(
                                target = firstTarget,
                                label = "Test P1",
                                durationMs = 60_000L
                            ),
                            BiliVideoSkipTargetOption(
                                target = latePlaybackTarget,
                                label = "Test P2",
                                durationMs = 60_000L
                            )
                        )
                    },
                    initialTarget = initialTarget.value,
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_START_INPUT_TEST_TAG)
            .performTextInput("1")
        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_END_INPUT_TEST_TAG)
            .performTextInput("2")
        composeRule.onNodeWithTag(BILI_VIDEO_SKIP_ADD_BUTTON_TEST_TAG).performClick()
        composeRule.runOnIdle { initialTarget.value = latePlaybackTarget }

        composeRule.onNodeWithText("00:01 - 00:02").assertExists()
    }
}
