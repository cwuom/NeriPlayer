package moe.ouom.neriplayer.ui.screen.tab.home

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeNeteasePlaylistSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeUiState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomePlaylistSource
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class HomeScreenGridLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private var originalProcessName: String? = null
    private val originalCleanMotion = NeriMotionStyle.clean
    private var contentVisible by mutableStateOf(true)

    @Before
    fun allowUserDatabaseInTestProcess() {
        originalProcessName = application.applicationInfo.processName
        application.applicationInfo.processName = Application.getProcessName()
    }

    @After
    fun restoreMotionStyle() {
        try {
            composeRule.runOnIdle { contentVisible = false }
            composeRule.waitForIdle()
        } finally {
            application.applicationInfo.processName = originalProcessName
            NeriMotionStyle.clean = originalCleanMotion
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp-mdpi")
    fun cleanHomeKeepsTwoClickablePlaylistCardsOnA320DpPhone() {
        assertTwoColumnHome(widthDp = 320f, density = 1f)
    }

    @Test
    fun cleanHomeKeepsTwoClickablePlaylistCardsOnA360DpPhone() {
        assertTwoColumnHome(widthDp = 360f, density = 1f)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp-440dpi")
    fun cleanHomeKeepsTwoColumnsWhenFractionalDensityRoundsTheGutters() {
        assertTwoColumnHome(widthDp = 320f, density = 2.75f)
    }

    private fun assertTwoColumnHome(widthDp: Float, density: Float) {
        val playlists = List(6) { index ->
            PlaylistSummary(
                id = index + 1L,
                name = "Grid playlist ${index + 1}",
                picUrl = "",
                playCount = 0L,
                trackCount = 1
            )
        }
        val state = MutableStateFlow(
            HomeUiState(
                playlistSections = listOf(
                    HomeNeteasePlaylistSectionState(
                        source = NeteaseHomePlaylistSource.PERSONALIZED,
                        section = HomeSectionState(items = playlists)
                    )
                )
            )
        )
        // 只替换会启动网络加载的数据源，页面和普通歌单卡片保持真实渲染
        val viewModel = mock(HomeViewModel::class.java)
        `when`(viewModel.uiState).thenReturn(state)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                requireNotNull(modelClass.cast(viewModel))
        }
        val selectedPlaylists = mutableListOf<PlaylistSummary>()
        var actualDensity = 0f
        var actualWindowWidthDp = 0f

        composeRule.setContent {
            if (contentVisible) {
                val owner = requireNotNull(LocalViewModelStoreOwner.current)
                remember(owner) { ViewModelProvider(owner, factory)[HomeViewModel::class.java] }
                val localDensity = LocalDensity.current
                val windowSize = LocalWindowInfo.current.containerSize
                SideEffect {
                    actualDensity = localDensity.density
                    actualWindowWidthDp = windowSize.width / localDensity.density
                }
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    uiStyle = UiStyle.Clean,
                    animateColors = false
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        HomeScreen(
                            showContinueCard = false,
                            showTrendingCard = false,
                            showRadarCard = false,
                            showRecommendedCard = true,
                            offlineMode = false,
                            gridState = rememberLazyGridState(),
                            radarPlaylistListState = rememberLazyListState(),
                            topAppBarState = rememberTopAppBarState(),
                            onItemClick = selectedPlaylists::add
                        )
                    }
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals("夹具必须使用指定的像素密度", density, actualDensity, 0.001f)
            assertEquals("夹具必须使用指定的真实窗口宽度", widthDp, actualWindowWidthDp, 0.5f)
        }
        val first = cardBounds(playlists[0].name)
        val second = cardBounds(playlists[1].name)
        assertEquals("窄屏前两张普通歌单卡片应在同一行", first.top, second.top, 1f)
        assertTrue("同一行的卡片应左右排列且不重叠", first.right < second.left)
        assertEquals("两列应均分实际网格宽度", first.width, second.width, 1f)
        val third = cardBounds(playlists[2].name)
        assertTrue("第三张普通卡片应从下一行开始", third.top >= maxOf(first.bottom, second.bottom))
        assertEquals("下一行应回到第一列", first.left, third.left, 1f)

        composeRule.onNodeWithText(playlists[1].name).assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals(listOf(playlists[1]), selectedPlaylists) }
    }

    private fun cardBounds(name: String): Rect = composeRule.onNodeWithText(name)
        .assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
}
