package moe.ouom.neriplayer.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppNavigationVisibilityComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var hostOwner: LifecycleOwner
    private lateinit var visibility: AppNavigationVisibility

    @Test
    fun outgoingOwnersRemainUntilTheRealNavHostFinishesForwardAndBackTransitions() {
        composeRule.setContent {
            navController = rememberNavController()
            val owner = LocalLifecycleOwner.current
            val currentVisibility = rememberAppNavigationVisibility(navController, owner)
            SideEffect {
                hostOwner = owner
                visibility = currentVisibility
            }
            NavHost(
                navController = navController,
                startDestination = Home,
                enterTransition = { fadeIn(tween(TransitionDurationMs)) },
                exitTransition = { fadeOut(tween(TransitionDurationMs)) },
                popEnterTransition = { fadeIn(tween(TransitionDurationMs)) },
                popExitTransition = { fadeOut(tween(TransitionDurationMs)) }
            ) {
                composable(Home) { Text("Home screen") }
                composable(Detail) { Text("Detail screen") }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Home screen").assertIsDisplayed()
        val home = composeRule.runOnIdle { checkNotNull(navController.currentBackStackEntry) }
        assertSettled(Home, home)

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { navController.navigate(Detail) }
        advanceToMidTransition()
        val detail = composeRule.runOnIdle { checkNotNull(navController.currentBackStackEntry) }
        assertTransition(Detail, home, detail)

        finishTransition()
        composeRule.onNodeWithText("Detail screen").assertIsDisplayed()
        assertSettled(Detail, detail)

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        advanceToMidTransition()
        assertTransition(Home, home, detail)

        finishTransition()
        composeRule.onNodeWithText("Home screen").assertIsDisplayed()
        assertSettled(Home, home)
        composeRule.runOnIdle { assertSame(home, navController.currentBackStackEntry) }
    }

    private fun advanceToMidTransition() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(64L)
        composeRule.waitForIdle()
    }

    private fun finishTransition() {
        composeRule.mainClock.advanceTimeBy(TransitionDurationMs.toLong() + 100L)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    private fun assertTransition(
        currentRoute: String,
        home: NavBackStackEntry,
        detail: NavBackStackEntry
    ) = composeRule.runOnIdle {
        assertEquals(currentRoute, visibility.currentRoute)
        assertSame(navController.currentDestination, visibility.destination)
        assertEquals(setOf(Home, Detail), visibility.routes)
        assertEquals(setOf(home, detail, hostOwner), visibility.owners)
    }

    private fun assertSettled(route: String, entry: NavBackStackEntry) = composeRule.runOnIdle {
        assertEquals(route, visibility.currentRoute)
        assertSame(entry.destination, visibility.destination)
        assertEquals(setOf(route), visibility.routes)
        assertEquals(setOf(entry, hostOwner), visibility.owners)
    }

    private companion object {
        const val Home = "home"
        const val Detail = "detail"
        const val TransitionDurationMs = 400
    }
}
