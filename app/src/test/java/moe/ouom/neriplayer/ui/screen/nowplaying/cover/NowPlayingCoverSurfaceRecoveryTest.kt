package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalSharedTransitionApi::class)
class NowPlayingCoverSurfaceRecoveryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var owner: NowPlayingCoverOwner
    private var source by mutableStateOf(NowPlayingCoverSource(null, null, emptyList(), "empty", ""))
    private var generation by mutableIntStateOf(0)
    private var appliedGeneration = -1

    @Test
    fun decodedArtworkStaysVisibleAcrossPassiveHandoffAndPanelReentry() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            owner = rememberNowPlayingCoverOwner()
            MaterialTheme(colorScheme = lightColorScheme(
                primaryContainer = Color.Green, onPrimaryContainer = Color.Green
            )) {
                Row(Modifier.requiredSize(240.dp, 120.dp).background(Color.Black)) {
                    Box(Modifier.size(120.dp).testTag(PanelTag)) {
                        PanelContent()
                    }
                    PassiveNowPlayingCoverImage(owner, source, Modifier.size(120.dp).testTag(PassiveTag))
                }
            }
        }
        advance(32)
        for (tag in Tags) assertEquals("未解码时不能伪造封面图片节点 $tag", 0, artworkNodeCount(tag))

        val red = artwork(android.graphics.Color.RED)
        publish("first", red)
        advance(320)
        for (tag in Tags) assertEquals("实际端点和被动层都挂载已解码 A $tag", 1, artworkNodeCount(tag, "first"))

        val blue = artwork(android.graphics.Color.BLUE)
        publish("second", blue)
        repeat(24) { frame ->
            advance(16)
            for (tag in Tags) {
                assertTrue("已解码 A 到 B 的第 $frame 帧不能卸载全部图片节点 $tag",
                    artworkNodeCount(tag, "first", "second") > 0)
            }
        }
        for (tag in Tags) assertEquals("渐变结束后只保留已解码 B 节点 $tag", 1, artworkNodeCount(tag, "second"))

        composeRule.runOnIdle { generation++ }
        advance(32)
        assertEquals("必须实际重建封面 Panel 后再检查恢复", 1, appliedGeneration)
        assertEquals("新端点从同曲解码缓存恢复图片节点", 1, artworkNodeCount(PanelTag, "second"))
        composeRule.runOnIdle { assertSame("被动层与重建端点保留同一张解码图", blue, owner.displayedFrame?.decodedBitmap) }
    }

    @Composable
    private fun PanelContent() {
        SharedTransitionLayout {
            AnimatedVisibility(visible = true) {
                key(generation) {
                    NowPlayingCoverPanel(
                        owner, source, null, null, true,
                        this@SharedTransitionLayout, this@AnimatedVisibility,
                        false, false, null, {}, Modifier.size(120.dp),
                        preferredSize = 96.dp
                    )
                    SideEffect { appliedGeneration = generation }
                }
            }
        }
    }

    private fun publish(id: String, bitmap: ImageBitmap) {
        composeRule.runOnIdle {
            val next = NowPlayingCoverSource("content://surface-recovery/$id", "surface-recovery-$id",
                emptyList(), "cache-$id", id)
            val request = requireNotNull(owner.requestFor(
                NowPlayingCoverRequestInput(next.coverUrl, next.songKey, next.cacheKey)
            ))
            owner.presentation(request, next.songKey, next.songKeyAliases)
            owner.publishDecodedFrame(request, request.frame.copy(decodedBitmap = bitmap))
            source = next
        }
    }

    private fun advance(millis: Long) {
        val end = composeRule.mainClock.currentTime + millis
        while (composeRule.mainClock.currentTime < end) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private fun artworkNodeCount(tag: String, vararg labels: String): Int {
        val descriptions = if (labels.isEmpty()) listOf("first", "second") else labels.toList()
        val matcher = descriptions.map { hasContentDescription(it) }.reduce { left, right -> left or right }
        val nodes = composeRule.onAllNodes(matcher and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNodes()
        for (node in nodes) {
            assertTrue("实际图片节点必须已完成布局 $tag", node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f)
        }
        return nodes.size
    }

    private fun artwork(color: Int): ImageBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }.asImageBitmap()

    private companion object {
        const val PanelTag = "recovered-panel"
        const val PassiveTag = "passive-surface"
        val Tags = listOf(PanelTag, PassiveTag)
    }
}
