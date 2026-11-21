package moe.ouom.neriplayer.ui.screen.tab.settings

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsDetailScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsHomeScaffold
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [31, 35],
    application = Application::class,
    qualifiers = "zh-rCN-sw360dp-w360dp-h800dp-mdpi"
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScaffoldBottomViewportTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var composeView: View
    private lateinit var listState: LazyListState
    private var observedSystemBars = Insets.NONE
    private var density = 1f

    @Test
    fun materialHomeKeepsBottomViewportAndScrollableClearance() {
        assertBottomViewportAndClearance(UiStyle.Material, detail = false)
    }

    @Test
    fun cleanHomeKeepsBottomViewportAndScrollableClearance() {
        assertBottomViewportAndClearance(UiStyle.Clean, detail = false)
    }

    @Test
    fun materialDetailKeepsBottomViewportAndScrollableClearance() {
        assertBottomViewportAndClearance(UiStyle.Material, detail = true)
    }

    @Test
    fun cleanDetailKeepsBottomViewportAndScrollableClearance() {
        assertBottomViewportAndClearance(UiStyle.Clean, detail = true)
    }

    private fun assertBottomViewportAndClearance(style: UiStyle, detail: Boolean) {
        showScaffold(style, detail)
        dispatchSystemBars()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                "system bar insets must reach the real Compose hierarchy",
                SystemBars,
                observedSystemBars
            )
        }

        val host = composeRule.onNodeWithTag(HostTag).fetchSemanticsNode().boundsInRoot
        val scroller = composeRule.onNode(hasScrollToIndexAction())
        val viewport = scroller.fetchSemanticsNode().boundsInRoot
        assertEquals(
            "the scrolling viewport must reach its common parent's bottom",
            host.bottom,
            viewport.bottom,
            0.5f
        )
        assertEquals(
            "the left system bar inset must remain outside the viewport",
            host.left + SystemBars.left,
            viewport.left,
            0.5f
        )
        assertEquals(
            "the right system bar inset must remain outside the viewport",
            host.right - SystemBars.right,
            viewport.right,
            0.5f
        )
        assertTrue(
            "the top app bar must keep content below the status bar",
            viewport.top >= host.top + SystemBars.top
        )

        scroller.performScrollToIndex(RowCount - 1)
        composeRule.waitForIdle()
        val lastRow = composeRule.onNodeWithTag(rowTag(RowCount - 1))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            assertFalse("the list must be at its actual scroll limit", listState.canScrollForward)
        }
        assertEquals(
            "the final row must retain its full height",
            RowHeight.value * density,
            lastRow.height,
            0.5f
        )
        assertTrue(
            "the final row must be fully inside the viewport",
            lastRow.top >= viewport.top && lastRow.bottom <= viewport.bottom
        )
        assertTrue(
            "the final row must clear the mini player reserve",
            lastRow.bottom <= host.bottom - MiniPlayerReserve.value * density + 0.5f
        )
        assertTrue(
            "the final row must clear the system navigation safe area",
            lastRow.bottom <= host.bottom - SystemBars.bottom + 0.5f
        )
    }

    private fun showScaffold(style: UiStyle, detail: Boolean) {
        composeRule.setContent {
            val view = LocalView.current
            val localDensity = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val systemBars = WindowInsets.systemBars
            val currentSystemBars = Insets.of(
                systemBars.getLeft(localDensity, layoutDirection),
                systemBars.getTop(localDensity),
                systemBars.getRight(localDensity, layoutDirection),
                systemBars.getBottom(localDensity)
            )
            val state = rememberLazyListState()
            val topAppBarState = rememberTopAppBarState()
            SideEffect {
                composeView = view
                listState = state
                observedSystemBars = currentSystemBars
                density = localDensity.density
            }
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "885522",
                uiStyle = style,
                animateColors = false
            ) {
                // the app navigation owner includes the system safe area in this reserve
                CompositionLocalProvider(LocalMiniPlayerHeight provides MiniPlayerReserve) {
                    Box(Modifier.fillMaxSize().testTag(HostTag)) {
                        val content: LazyListScope.() -> Unit = {
                            items(count = RowCount, key = { it }) { index ->
                                Box(Modifier.fillMaxWidth().height(RowHeight).testTag(rowTag(index))) {
                                    Text("Settings row $index")
                                }
                            }
                        }
                        if (detail) {
                            MiuixSettingsDetailScaffold(
                                title = "Settings detail",
                                onBack = {},
                                listState = state,
                                topAppBarState = topAppBarState,
                                content = content
                            )
                        } else {
                            MiuixSettingsHomeScaffold(
                                listState = state,
                                topAppBarState = topAppBarState,
                                title = { Text("Settings") },
                                content = content
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun dispatchSystemBars() = composeRule.runOnIdle {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, SystemBars.top, 0, 0))
            .setInsets(
                WindowInsetsCompat.Type.navigationBars(),
                Insets.of(SystemBars.left, 0, SystemBars.right, SystemBars.bottom)
            )
            .setVisible(WindowInsetsCompat.Type.systemBars(), true)
            .build()
        ViewCompat.dispatchApplyWindowInsets(composeView, insets)
    }

    private fun rowTag(index: Int) = "settings-viewport-row-$index"

    private companion object {
        const val HostTag = "settings-viewport-host"
        const val RowCount = 24
        val RowHeight = 72.dp
        val MiniPlayerReserve = 112.dp
        val SystemBars = Insets.of(9, 24, 15, 32)
    }
}
