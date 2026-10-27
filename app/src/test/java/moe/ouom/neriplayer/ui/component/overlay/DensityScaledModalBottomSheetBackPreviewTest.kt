package moe.ouom.neriplayer.ui.component.overlay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w800dp-h1000dp-mdpi")
class DensityScaledModalBottomSheetBackPreviewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var dialogDispatcher: NavigationEventDispatcher
    private val gesture = GestureInput()
    private var dismissals = 0

    private fun setSheet(previewEnabled: Boolean) {
        composeRule.setContent {
            CompositionLocalProvider(LocalPredictiveBackPreviewEnabled provides previewEnabled) {
                MaterialTheme {
                    DensityScaledModalBottomSheet(
                        onDismissRequest = { dismissals++ },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        dragHandle = null
                    ) {
                        val dialogView = LocalView.current
                        SideEffect {
                            dialogDispatcher = checkNotNull(
                                dialogView.findViewTreeNavigationEventDispatcherOwner()
                            ).navigationEventDispatcher
                        }
                        Box(Modifier.fillMaxWidth().height(240.dp).testTag("sheetContent"))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { dialogDispatcher.addInput(gesture) }
    }

    private fun sheetWidth(): Float = composeRule.onNodeWithTag("sheetContent")
        .fetchSemanticsNode().boundsInRoot.width

    private fun startGesture() {
        composeRule.runOnIdle { gesture.start() }
        composeRule.runOnIdle { gesture.progress(0.8f) }
        composeRule.waitForIdle()
    }

    @Test
    fun `disabled preview keeps the material sheet full width until release dismisses it`() {
        setSheet(previewEnabled = false)
        val initialWidth = sheetWidth()

        startGesture()

        assertEquals(initialWidth, sheetWidth(), 0.5f)
        assertEquals(0, dismissals)

        composeRule.runOnIdle { gesture.complete() }
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
    }

    @Test
    fun `enabled preview shrinks the material sheet before release dismisses it`() {
        setSheet(previewEnabled = true)
        val initialWidth = sheetWidth()

        startGesture()

        assertTrue(sheetWidth() < initialWidth - 1f)
        assertEquals(0, dismissals)

        composeRule.runOnIdle { gesture.complete() }
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
    }

    @Test
    fun `disabled preview cancellation keeps the sheet open and the next release dismisses once`() {
        setSheet(previewEnabled = false)
        val initialWidth = sheetWidth()
        startGesture()

        composeRule.runOnIdle { gesture.cancel() }
        composeRule.waitForIdle()

        assertEquals(initialWidth, sheetWidth(), 0.5f)
        assertEquals(0, dismissals)

        startGesture()
        composeRule.runOnIdle { gesture.complete() }
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
    }

    private class GestureInput : NavigationEventInput() {
        fun start() = dispatchOnBackStarted(NavigationEvent(progress = 0f))
        fun progress(progress: Float) = dispatchOnBackProgressed(NavigationEvent(progress = progress))
        fun complete() = dispatchOnBackCompleted()
        fun cancel() = dispatchOnBackCancelled()
    }
}
