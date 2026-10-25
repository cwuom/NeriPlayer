package moe.ouom.neriplayer.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.navigation.AppNavigationGraph
import moe.ouom.neriplayer.ui.navigation.AppNavigationGraphOwner
import moe.ouom.neriplayer.ui.navigation.AppNavigationGraphPresentation
import moe.ouom.neriplayer.ui.navigation.AppNavigationMediaActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-sw360dp-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerUnderlayContinuityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var playbackOpen by mutableStateOf(false)
    private lateinit var navController: NavHostController
    private lateinit var detailListState: LazyListState
    private var detailMounts = 0
    private var detailDisposals = 0
    private var ownerCompositions = 0

    @Test
    fun playbackStateRecompositionKeepsUnsavedDetailStateAndItsOriginalBackStack() {
        composeRule.setContent {
            val playerOpen = playbackOpen
            navController = rememberNavController()
            // NeriApp 的播放状态重组会创建新的 owner，导航页不应因此重新挂载
            val owner = AppNavigationGraphOwner(
                navController = navController,
                presentation = AppNavigationGraphPresentation(
                    startDestination = Destinations.Debug.route,
                    coherentFeedbackEnabled = false,
                    offlineMode = true,
                    renderScene = { route, _ ->
                        assertEquals(Destinations.DebugSearch.route, route)
                        StatefulDetail()
                    }
                ),
                mediaActions = AppNavigationMediaActions(
                    playSongs = { _, _, _ -> },
                    playBiliAudio = { _, _, _ -> },
                    playBiliParts = { _, _, _, _ -> },
                    onNeteaseAlbumClick = {},
                    onYouTubePlaylistClick = {},
                    onYouTubeCreatorClick = {}
                )
            )
            SideEffect { ownerCompositions++ }
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Text(if (playerOpen) "播放器已打开" else "播放器已关闭")
                    Box(Modifier.weight(1f)) {
                        AppNavigationGraph(owner)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val rootEntry = composeRule.runOnIdle { checkNotNull(navController.currentBackStackEntry) }
        composeRule.runOnIdle { navController.navigate(Destinations.DebugSearch.route) }
        composeRule.waitForIdle()
        val detailEntry = composeRule.runOnIdle { checkNotNull(navController.currentBackStackEntry) }

        composeRule.onNodeWithTag(SearchTag).performTextInput("保留搜索词")
        composeRule.onNodeWithText("选择详情").performClick()
        composeRule.onNodeWithTag(ResultsTag).performScrollToIndex(25)
        composeRule.waitForIdle()
        assertDetailState(rootEntry, detailEntry)
        val initialCompositions = composeRule.runOnIdle { ownerCompositions }

        composeRule.runOnIdle { playbackOpen = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("播放器已打开").assertExists()
        assertDetailState(rootEntry, detailEntry)

        composeRule.runOnIdle { playbackOpen = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("播放器已关闭").assertExists()
        assertDetailState(rootEntry, detailEntry)
        composeRule.runOnIdle {
            assertTrue("测试必须实际重组导航 owner", ownerCompositions >= initialCompositions + 2)
            assertTrue(navController.popBackStack())
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Destinations.Debug.route, navController.currentDestination?.route)
            assertEquals("返回应回到原有根 entry", rootEntry.id, navController.currentBackStackEntry?.id)
            assertFalse("打开播放器不能插入额外导航 entry", navController.popBackStack())
        }
    }

    @Composable
    private fun StatefulDetail() {
        // 通过生产 renderScene 边界观察 entry 内容身份，避免启动探针页的账号与网络依赖
        var query by remember { mutableStateOf("") }
        var detailSelected by remember { mutableStateOf(false) }
        val listState = remember { LazyListState() }
        DisposableEffect(Unit) {
            detailMounts++
            onDispose { detailDisposals++ }
        }
        SideEffect { detailListState = listState }
        Column(Modifier.fillMaxSize()) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag(SearchTag)
            )
            Button(onClick = { detailSelected = true }) { Text("选择详情") }
            Text(if (detailSelected) "已选择详情" else "未选择详情", Modifier.testTag(SelectionTag))
            LazyColumn(Modifier.weight(1f).testTag(ResultsTag), state = listState) {
                items((0 until 80).toList()) { index ->
                    Text("搜索结果 $index", Modifier.fillMaxWidth().height(48.dp))
                }
            }
        }
    }

    private fun assertDetailState(rootEntry: NavBackStackEntry, detailEntry: NavBackStackEntry) {
        composeRule.onNodeWithTag(SearchTag).assertTextEquals("保留搜索词")
        composeRule.onNodeWithTag(SelectionTag).assertTextEquals("已选择详情")
        composeRule.runOnIdle {
            assertEquals("详情内容只能挂载一次", 1, detailMounts)
            assertEquals("开关播放器不能卸载详情内容", 0, detailDisposals)
            assertEquals("详情滚动位置必须保留", 25, detailListState.firstVisibleItemIndex)
            assertEquals("当前详情 entry 必须保留", detailEntry.id, navController.currentBackStackEntry?.id)
            assertEquals("返回目标 entry 必须保留", rootEntry.id, navController.previousBackStackEntry?.id)
        }
    }

    private companion object {
        const val SearchTag = "underlaySearch"
        const val SelectionTag = "underlaySelection"
        const val ResultsTag = "underlayResults"
    }
}
