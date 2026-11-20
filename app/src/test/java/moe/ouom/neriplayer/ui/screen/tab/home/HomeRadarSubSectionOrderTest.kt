package moe.ouom.neriplayer.ui.screen.tab.home

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeLayoutState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeNeteaseSongSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeUiState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 首页雷达大模块内的小模块顺序测试。
 *
 * 设置页允许把「雷达歌单」拖到任意位置，首页必须按同样的顺序渲染。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class HomeRadarSubSectionOrderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private var originalProcessName: String? = null
    private var contentVisible by mutableStateOf(true)

    @Before
    fun allowUserDatabaseInTestProcess() {
        originalProcessName = application.applicationInfo.processName
        application.applicationInfo.processName = Application.getProcessName()
    }

    @After
    fun restoreProcessName() {
        application.applicationInfo.processName = originalProcessName
    }

    @Test
    fun radarPlaylistsIsRenderedLastWhenConfiguredLast() {
        val order = listOf(
            HomeRadarSubSectionIds.PERSONAL_RADAR,
            HomeRadarSubSectionIds.DAILY_RECOMMEND,
            HomeRadarSubSectionIds.PRIVATE_FM,
            HomeRadarSubSectionIds.RADAR_PLAYLISTS
        )
        val radarPlaylistTop = renderRadarHome(order)
        val personalRadarTop = headerTop(
            moe.ouom.neriplayer.common.R.string.recommend_radar
        )
        assertTrue(
            "雷达歌单应排在私人雷达之后，实际 radarPlaylist=$radarPlaylistTop personalRadar=$personalRadarTop",
            radarPlaylistTop > personalRadarTop
        )
    }

    @Test
    fun radarPlaylistsIsRenderedFirstWhenConfiguredFirst() {
        val order = listOf(
            HomeRadarSubSectionIds.RADAR_PLAYLISTS,
            HomeRadarSubSectionIds.PERSONAL_RADAR,
            HomeRadarSubSectionIds.DAILY_RECOMMEND,
            HomeRadarSubSectionIds.PRIVATE_FM
        )
        val radarPlaylistTop = renderRadarHome(order)
        val personalRadarTop = headerTop(
            moe.ouom.neriplayer.common.R.string.recommend_radar
        )
        assertTrue(
            "雷达歌单应排在私人雷达之前，实际 radarPlaylist=$radarPlaylistTop personalRadar=$personalRadarTop",
            radarPlaylistTop < personalRadarTop
        )
    }

    private fun headerTop(titleRes: Int): Float = composeRule
        .onNodeWithText(application.getString(titleRes))
        .fetchSemanticsNode().boundsInRoot.top

    /**
     * 渲染只含雷达大模块的首页，返回「雷达歌单」横滑条里第一张卡片的顶部坐标。
     */
    private fun renderRadarHome(radarOrder: List<String>): Float {
        val state = MutableStateFlow(
            HomeUiState(
                radarSongSections = listOf(
                    HomeNeteaseSongSectionState(
                        source = NeteaseHomeSongSource.PERSONAL_RADAR,
                        section = HomeSectionState(items = emptyList())
                    ),
                    HomeNeteaseSongSectionState(
                        source = NeteaseHomeSongSource.DAILY_RECOMMEND,
                        section = HomeSectionState(items = emptyList())
                    ),
                    HomeNeteaseSongSectionState(
                        source = NeteaseHomeSongSource.PRIVATE_FM,
                        section = HomeSectionState(items = emptyList())
                    )
                ),
                radarPlaylists = HomeSectionState(
                    items = listOf(
                        PlaylistSummary(
                            id = 1L,
                            name = "Radar playlist",
                            picUrl = "",
                            playCount = 0L,
                            trackCount = 1
                        )
                    )
                ),
                layout = HomeLayoutState(radarOrder = radarOrder)
            )
        )
        val viewModel = mock(HomeViewModel::class.java)
        `when`(viewModel.uiState).thenReturn(state)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                requireNotNull(modelClass.cast(viewModel))
        }

        composeRule.setContent {
            if (contentVisible) {
                val owner = requireNotNull(LocalViewModelStoreOwner.current)
                androidx.compose.runtime.remember(owner) {
                    ViewModelProvider(owner, factory)[HomeViewModel::class.java]
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
                            showRadarCard = true,
                            showRecommendedCard = false,
                            offlineMode = false,
                            gridState = rememberLazyGridState(),
                            radarPlaylistListState = rememberLazyListState(),
                            topAppBarState = rememberTopAppBarState()
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        return composeRule.onNodeWithText("Radar playlist")
            .fetchSemanticsNode().boundsInRoot.top
    }
}
