package moe.ouom.neriplayer.ui.playback.visual

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1200dp-h800dp")
class NowPlayingContainerMorphTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingMiniPlayerUsesTheCurrentViewportForItsFade() {
        val morph = NowPlayingMorphState(initiallyVisible = false)
        runBlocking { morph.progress.snapTo(0.5f) }
        composeRule.setContent {
            NowPlayingContainerMorph(
                morph = morph,
                flight = null,
                capsuleInWindow = { null },
                artwork = { null }
            ) {
                Box(Modifier.fillMaxSize().testTag("page"))
            }
        }

        val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val page = composeRule.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport, page)
    }
}
