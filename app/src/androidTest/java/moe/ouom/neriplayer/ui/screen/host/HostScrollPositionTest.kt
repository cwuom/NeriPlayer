package moe.ouom.neriplayer.ui.screen.host

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostScrollPositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun drawerRoundTripRestoresCapturedListPosition() {
        lateinit var navigationDepth: androidx.compose.runtime.MutableIntState
        lateinit var listState: LazyListState
        lateinit var requestRestore: (HostScrollPosition) -> Unit
        lateinit var scrollList: (Int, Int) -> Unit
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            navigationDepth = remember { mutableIntStateOf(0) }
            listState = remember { LazyListState() }
            var pendingRestore by remember { mutableStateOf<HostScrollPosition?>(null) }
            val scope = rememberCoroutineScope()
            requestRestore = { position -> pendingRestore = position }
            scrollList = { index, offset ->
                scope.launch { listState.scrollToItem(index, offset) }
            }
            LaunchedEffect(navigationDepth.intValue, pendingRestore) {
                val position = pendingRestore ?: return@LaunchedEffect
                if (navigationDepth.intValue != 0) return@LaunchedEffect
                listState.restoreHostScrollPosition(position)
                pendingRestore = null
            }
            MaterialTheme {
                val transition = updateTransition(
                    targetState = navigationDepth.intValue,
                    label = "host_scroll_round_trip"
                )
                transition.AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        advancedGlassHostNavigationTransition(
                            forward = targetState > initialState,
                            coherentFeedbackEnabled = false
                        ).using(SizeTransform(clip = true))
                    }
                ) { depth ->
                    if (depth == 0) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState
                        ) {
                            items((0..80).toList()) { item ->
                                Text(
                                    text = "item $item",
                                    fontSize = 20.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                )
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { scrollList(24, 11) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        lateinit var captured: HostScrollPosition
        composeRule.runOnIdle { captured = listState.captureHostScrollPosition() }

        composeRule.runOnIdle {
            requestRestore(captured)
            navigationDepth.intValue = 1
            scrollList(40, 3)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(40, listState.firstVisibleItemIndex)
            assertEquals(3, listState.firstVisibleItemScrollOffset)
        }
        finishCurrentTransition()

        composeRule.runOnIdle { navigationDepth.intValue = 0 }
        finishCurrentTransition()
        composeRule.runOnIdle {
            assertEquals(captured.index, listState.firstVisibleItemIndex)
            assertEquals(captured.offset, listState.firstVisibleItemScrollOffset)
        }
    }

    private fun finishCurrentTransition() {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }
}
