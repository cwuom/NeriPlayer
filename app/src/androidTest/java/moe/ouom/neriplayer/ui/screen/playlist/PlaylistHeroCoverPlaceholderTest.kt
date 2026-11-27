package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistHeroCoverPlaceholderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun materialHeroWithoutCoverShowsOpaqueLighterPlaceholder() = check(UiStyle.Material)

    @Test
    fun cleanHeroWithoutCoverShowsOpaqueLighterPlaceholder() = check(UiStyle.Clean)

    private fun check(style: UiStyle) {
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
                        displayName = "Daily",
                        coverUrl = null,
                        subtitle = "0 songs",
                        offlineMode = true,
                        height = PlaylistModernHeroHeight
                    )
                }
            }
        }
        val density = composeRule.density.density
        val hero = composeRule.onNodeWithTag("hero").getUnclippedBoundsInRoot()
        val cover = composeRule.onNodeWithTag(PlaylistHeroCoverTag).getUnclippedBoundsInRoot()
        val pixels = composeRule.onNodeWithTag("hero").captureToImage().toPixelMap()
        fun at(xDp: Float, yDp: Float): Color =
            pixels[((xDp - hero.left.value) * density).toInt(), ((yDp - hero.top.value) * density).toInt()]
        val center = at((cover.left.value + cover.right.value) / 2f, cover.top.value + 6f)
        val heroBackground = at(hero.left.value + 4f, hero.bottom.value - 4f)
        assertEquals(1f, center.alpha, 0.01f)
        // 之前封面区域透明, 只剩阴影, 比头部底色还暗
        assertTrue(
            "placeholder ${center.luminance()} must be lighter than hero ${heroBackground.luminance()}",
            center.luminance() > heroBackground.luminance()
        )
    }
}
