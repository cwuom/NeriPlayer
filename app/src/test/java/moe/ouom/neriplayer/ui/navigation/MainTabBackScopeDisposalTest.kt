package moe.ouom.neriplayer.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainTabBackScopeDisposalTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `disposing an active child dispatcher releases every handler and keeps parent back`() {
        val parent = NavigationEventDispatcher()
        val input = BackInput().also(parent::addInput)
        var parentBackCount = 0
        parent.addHandler(backHandler { parentBackCount++ })
        val child = NavigationEventDispatcher(parent = parent)
        repeat(3) { child.addHandler(backHandler {}) }

        child.dispose()
        input.complete()

        assertEquals(1, parentBackCount)
        parent.dispose()
    }

    @Test
    fun `removing a tab with multiple back handlers leaves the parent usable`() {
        var showTab by mutableStateOf(true)
        var parentBackCount = 0
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        val input = BackInput().also(owner.navigationEventDispatcher::addInput)
        composeRule.setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                NavigationBackHandler(
                    state = rememberNavigationEventState(NavigationEventInfo.None),
                    onBackCompleted = { parentBackCount++ }
                )
                if (showTab) {
                    Box(Modifier.size(240.dp, 320.dp)) {
                        MainTabLayerHost(selectedRoute = "library") {
                            repeat(3) {
                                NavigationBackHandler(
                                    state = rememberNavigationEventState(NavigationEventInfo.None),
                                    onBackCompleted = {}
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { showTab = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { input.complete() }
        composeRule.runOnIdle { assertEquals(1, parentBackCount) }
    }

    private class BackInput : NavigationEventInput() {
        fun complete() = dispatchOnBackCompleted()
    }

    private fun backHandler(onBack: () -> Unit) =
        object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, true) {
            override fun onBackCompleted() = onBack()
        }
}
