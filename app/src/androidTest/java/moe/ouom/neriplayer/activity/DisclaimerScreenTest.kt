package moe.ouom.neriplayer.activity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.onboarding.StartupActionBar
import moe.ouom.neriplayer.ui.onboarding.StartupPrivacyPolicyOverlay
import moe.ouom.neriplayer.ui.onboarding.StartupPrivacyStepBody
import moe.ouom.neriplayer.ui.onboarding.resolveStartupPrivacyCountdownProgress
import moe.ouom.neriplayer.ui.onboarding.startupPrivacyAgreeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
class DisclaimerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun agreeButton_remainsDisabledUntilCountdownFinishes_thenCallsCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initialButtonLabel = context.getString(AppR.string.disclaimer_read_countdown, 1)
        val agreeButtonLabel = context.getString(AppR.string.disclaimer_agree_countdown)
        var agreed = false

        composeRule.setContent {
            MaterialTheme {
                var remaining by remember { mutableIntStateOf(1) }
                LaunchedEffect(remaining) {
                    if (remaining > 0) {
                        delay(1_000L)
                        remaining -= 1
                    }
                }
                StartupActionBar(
                    showBack = false,
                    backEnabled = false,
                    onBack = {},
                    primaryLabel = startupPrivacyAgreeLabel(accepted = false, remainingSeconds = remaining),
                    primaryEnabled = remaining <= 0,
                    onPrimary = { agreed = true },
                    primaryProgress = resolveStartupPrivacyCountdownProgress(remaining)
                )
            }
        }

        composeRule.onNodeWithText(initialButtonLabel).assertIsNotEnabled()
        composeRule.waitUntil(timeoutMillis = 3_000) { hasText(agreeButtonLabel) }
        composeRule.onNodeWithText(agreeButtonLabel)
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertTrue("倒计时结束后点击同意应触发回调", agreed)
        }
    }

    @Test
    fun fullDetails_areHiddenUntilUserOpensThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val showDetails = context.getString(AppR.string.disclaimer_show_details)
        val firstDetailTitle = context.getString(AppR.string.disclaimer_section1_title)

        composeRule.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    StartupPrivacyStepBody(accepted = false, policyOpen = open, onOpenPolicy = { open = true })
                    StartupPrivacyPolicyOverlay(
                        visible = open,
                        onClose = { open = false },
                        agreeLabel = context.getString(AppR.string.disclaimer_agree_countdown),
                        agreeEnabled = true,
                        agreeProgress = null,
                        onAgree = {}
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) { hasText(showDetails) }
        composeRule.onAllNodesWithText(firstDetailTitle).assertCountEquals(0)

        composeRule.onNodeWithText(showDetails).performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) { hasText(firstDetailTitle) }
    }

    @Test
    fun shortLandscape_openedDetailsKeepAgreeActionFixed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lastDetailTitle = context.getString(AppR.string.disclaimer_section9_title)
        val agreeButtonLabel = context.getString(AppR.string.disclaimer_agree_countdown)
        var agreed = false

        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(720.dp, 320.dp)) {
                    StartupPrivacyPolicyOverlay(
                        visible = true,
                        onClose = {},
                        agreeLabel = agreeButtonLabel,
                        agreeEnabled = true,
                        agreeProgress = null,
                        onAgree = { agreed = true }
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 3_000) { hasText(agreeButtonLabel) }
        val initialAgreeBounds = composeRule.onNodeWithText(agreeButtonLabel)
            .fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithText(lastDetailTitle).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText(agreeButtonLabel).assertIsDisplayed()
        assertEquals(
            "横屏滚动完整说明不应移动同意按钮",
            initialAgreeBounds,
            composeRule.onNodeWithText(agreeButtonLabel).fetchSemanticsNode().boundsInRoot
        )
        composeRule.onNodeWithText(agreeButtonLabel).performClick()
        composeRule.runOnIdle { assertTrue(agreed) }
    }

    private fun hasText(text: String): Boolean {
        return runCatching {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }
}
