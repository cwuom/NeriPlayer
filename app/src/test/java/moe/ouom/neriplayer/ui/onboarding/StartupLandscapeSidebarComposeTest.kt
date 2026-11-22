package moe.ouom.neriplayer.ui.onboarding

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.R as AppR
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 平板横屏左栏要同时放标题、迷你播放器和操作栏, 说明文字不能被挤到只剩半行 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w950dp-h552dp-land-xhdpi")
class StartupLandscapeSidebarComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `landscape now playing description stays fully inside the sidebar`() {
        val steps = startupStepsFor(tabletLandscape = true)
        val step = StartupStep.PlaybackControls
        val summaries = listOf(
            StartupChoiceSummary(StartupChoice.Language, Icons.Rounded.Translate, "System"),
            StartupChoiceSummary(StartupChoice.Style, Icons.Rounded.Palette, "Material You"),
            StartupChoiceSummary(StartupChoice.Motion, Icons.Rounded.Tune, "Immersive")
        )
        composeRule.setContent {
            StartupOnboardingLayout(
                header = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        StartupProgressHeader(steps = steps, stepIndex = steps.indexOf(step))
                        Spacer(Modifier.height(10.dp))
                        StartupChoiceRail(currentStep = step, summaries = summaries, onChoiceClick = {})
                        Spacer(Modifier.height(if (LocalStartupOnboardingShortSidebar.current) 16.dp else 28.dp))
                        StartupStepHero(
                            spec = startupHeroSpec(step, tabletLandscape = true),
                            layout = StartupHeroLayout.Pane,
                            visibilityScope = null
                        )
                    }
                },
                actions = {
                    StartupMiniPlayerSlot(visible = true, anchor = StartupMiniPlayerAnchor())
                    StartupActionBar(
                        showBack = true,
                        backEnabled = true,
                        onBack = {},
                        primaryLabel = context.getString(AppR.string.onboarding_action_next),
                        primaryEnabled = true,
                        onPrimary = {}
                    )
                }
            ) {
                Box(Modifier.fillMaxSize())
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        val header = composeRule.onNodeWithTag(StartupOnboardingSidebarHeaderTag).fetchSemanticsNode()
        val description = composeRule.onNodeWithText(
            context.getString(AppR.string.onboarding_controls_landscape_desc)
        ).fetchSemanticsNode()
        // 滚动容器会裁剪 boundsInRoot, 被截断时也看不出来, 这里比较未裁剪的位置
        val headerBottom = header.positionInRoot.y + header.size.height
        val descriptionBottom = description.positionInRoot.y + description.size.height
        assertTrue(
            "说明文字不能超出左栏标题区: description=$descriptionBottom header=$headerBottom",
            descriptionBottom <= headerBottom + 1f
        )
    }
}
