package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.R as AppR
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class StartupChoiceRailComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var step by mutableStateOf(StartupStep.Motion)

    @Test
    fun `newest choice scrolls into view when the rail is too narrow`() {
        val summaries = listOf(
            StartupChoiceSummary(StartupChoice.Language, Icons.Rounded.Translate, "跟随系统 Follow system"),
            StartupChoiceSummary(StartupChoice.Style, Icons.Rounded.Palette, "简洁 Clean Style"),
            StartupChoiceSummary(StartupChoice.Motion, Icons.Rounded.Tune, "沉浸 Immersive")
        )
        composeRule.setContent {
            Box(modifier = Modifier.width(160.dp).testTag(RAIL_TAG)) {
                StartupChoiceRail(currentStep = step, summaries = summaries, onChoiceClick = {})
            }
        }
        composeRule.waitForIdle()

        // 到下一步时动效选择才出现, 窄屏下它必须被自动滑进可见范围
        step = StartupStep.Privacy
        composeRule.mainClock.advanceTimeBy(1_200L)
        composeRule.waitForIdle()

        // boundsInRoot 会被滚动容器裁剪, 截断时也看不出来, 这里用未裁剪的位置和尺寸
        val rail = composeRule.onNodeWithTag(RAIL_TAG).fetchSemanticsNode()
        val newest = composeRule.onNodeWithContentDescription(
            context.getString(AppR.string.onboarding_choice_edit, "沉浸 Immersive")
        ).fetchSemanticsNode()
        val railLeft = rail.positionInRoot.x
        val railRight = railLeft + rail.size.width
        val newestLeft = newest.positionInRoot.x
        val newestRight = newestLeft + newest.size.width
        assertTrue("最新的选择不能被截在右侧: $newestRight > $railRight", newestRight <= railRight + 1f)
        assertTrue("最新的选择要完整可见: $newestLeft < $railLeft", newestLeft >= railLeft - 1f)
    }

    private companion object {
        const val RAIL_TAG = "choiceRail"
    }
}
