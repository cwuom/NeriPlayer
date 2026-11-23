package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistHeroMetaTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `plain subtitle splits into one chip per dot separated part`() {
        assertEquals(
            listOf("12 songs", "YouTube Music"),
            playlistHeroMetaFromSubtitle(" 12 songs ·  · YouTube Music ").map { it.text }
        )
        assertTrue(playlistHeroMetaFromSubtitle("  ").isEmpty())
    }

    @Test
    @Config(qualifiers = "en")
    fun `local playlist meta has icons, a plural count and a spoken play count`() {
        var items: List<PlaylistHeroMeta> = emptyList()
        composeRule.setContent { items = localPlaylistHeroMeta("4 minutes", 1, "0") }
        composeRule.waitForIdle()
        assertEquals(listOf("4 minutes", "1 song", "0"), items.map { it.text })
        assertTrue(items.all { it.icon != null })
        assertEquals("0 plays", items.last().contentDescription)
    }

    @Test
    fun `material hero shows every long meta chip without truncation`() = checkChips(UiStyle.Material)

    @Test
    fun `clean hero shows every long meta chip without truncation`() = checkChips(UiStyle.Clean)

    private fun checkChips(style: UiStyle) {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "885522",
                uiStyle = style,
                animateColors = false
            ) {
                Box(Modifier.width(411.dp).testTag("hero")) {
                    PlaylistModernHeroHeader(
                        displayName = "我喜欢的音乐",
                        coverUrl = null,
                        subtitle = "",
                        offlineMode = true,
                        height = PlaylistModernHeroHeight,
                        metaItems = listOf(
                            PlaylistHeroMeta(DURATION, Icons.Rounded.Schedule),
                            PlaylistHeroMeta(COUNT, Icons.Rounded.MusicNote),
                            PlaylistHeroMeta(PLAYS, Icons.Rounded.Headphones, PLAYS_DESCRIPTION)
                        )
                    )
                }
            }
        }
        val hero = composeRule.onNodeWithTag("hero").getUnclippedBoundsInRoot()
        listOf(DURATION, COUNT, PLAYS).forEach { label ->
            val node = composeRule.onNodeWithText(label, useUnmergedTree = true)
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            // FlowRow 里按内容宽度测量, didOverflowWidth 会把亚像素取整误报成溢出, 直接看省略号和行宽
            assertFalse("$label must not be ellipsized", layout.isLineEllipsized(0))
            assertEquals(1, layout.lineCount)
            assertTrue("$label must fit its box", layout.getLineRight(0) <= layout.size.width + 0.5f)
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$label must stay inside the hero", bounds.right <= hero.right && bounds.bottom <= hero.bottom)
        }
        composeRule.onNodeWithContentDescription(PLAYS_DESCRIPTION).assertExists()
    }

    private companion object {
        const val DURATION = "56小时44分钟"
        const val COUNT = "888 首"
        const val PLAYS = "12.3万"
        const val PLAYS_DESCRIPTION = "播放 12.3万 次"
    }
}
