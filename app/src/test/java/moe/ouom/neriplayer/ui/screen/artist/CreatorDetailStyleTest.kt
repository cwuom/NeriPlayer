package moe.ouom.neriplayer.ui.screen.artist

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreatorDetailStyleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `expandable bio keeps its text clear of the rounded touch clip`() {
        val bio = "A long biography ".repeat(30)
        composeRule.setContent {
            Box(Modifier.width(320.dp).padding(16.dp).testTag("host")) {
                CreatorExpandableText(text = bio, collapsedMaxLines = 3)
            }
        }
        val host = composeRule.onNodeWithTag("host").getUnclippedBoundsInRoot()
        val text = composeRule.onNodeWithText(bio, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val touch = composeRule.onNode(hasClickAction()).getUnclippedBoundsInRoot()
        // 文字仍和页面其他内容左对齐
        assertEquals(host.left.value, text.left.value, 0.5f)
        // 圆角裁剪区四周都要比文字大出一圈
        assertTrue(touch.left <= text.left - 6.dp)
        assertTrue(touch.right >= text.right + 6.dp)
        assertTrue(touch.top <= text.top - 4.dp)
    }

    @Test
    fun `clean follow button drops its outline and fills with the theme color once followed`() {
        var followedColors: ButtonColors? = null
        var primary = Color.Unspecified
        var followedBorder: BorderStroke? = BorderStroke(1.dp, Color.Red)
        var unfollowedBorder: BorderStroke? = null
        composeRule.setContent {
            Theme(UiStyle.Clean) {
                primary = MaterialTheme.colorScheme.primary
                followedColors = creatorFollowButtonColors(followed = true)
                followedBorder = creatorFollowButtonBorder(followed = true, enabled = true)
                unfollowedBorder = creatorFollowButtonBorder(followed = false, enabled = true)
            }
        }
        composeRule.waitForIdle()
        assertNull(followedBorder)
        assertNotNull(unfollowedBorder)
        assertEquals(primary, followedColors!!.containerColor)
    }

    @Test
    fun `material follow button is filled with the theme color once followed and never outlined`() {
        var followedColors: ButtonColors? = null
        var unfollowedColors: ButtonColors? = null
        var primary = Color.Unspecified
        var borders = listOf<BorderStroke?>(BorderStroke(1.dp, Color.Red))
        composeRule.setContent {
            Theme(UiStyle.Material) {
                primary = MaterialTheme.colorScheme.primary
                followedColors = creatorFollowButtonColors(followed = true)
                unfollowedColors = creatorFollowButtonColors(followed = false)
                borders = listOf(
                    creatorFollowButtonBorder(followed = true, enabled = true),
                    creatorFollowButtonBorder(followed = false, enabled = true)
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(borders.all { it == null })
        assertEquals(primary, followedColors!!.containerColor)
        assertTrue(unfollowedColors!!.containerColor != primary)
    }

    @Composable
    private fun Theme(style: UiStyle, content: @Composable () -> Unit) {
        NeriTheme(
            followSystemDark = false,
            forceDark = false,
            dynamicColor = false,
            seedColorHex = "885522",
            uiStyle = style,
            animateColors = false,
            content = content
        )
    }
}
