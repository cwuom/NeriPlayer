package moe.ouom.neriplayer.ui.screen.tab.settings.component.floating

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_LONG_LINE_WRAP
import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingLyricsPreviewLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `narrow three sentence preview displays every wrapped sentence and translation`() {
        showPreview()
        assertFullTextVisible()
    }

    @Test
    fun `preview shrinks and keeps all sentences visible after width and font changes`() {
        val preferences = showPreview()
        assertFullTextVisible()
        val previousHeight = composeRule.onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot.height
        composeRule.runOnIdle {
            preferences.value = preferences.value.copy(fontSizeSp = 8f, maxWidthDp = 360f)
        }
        composeRule.waitForIdle()
        val nextHeight = composeRule.onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot.height
        assertTrue(nextHeight < previousHeight)
        assertFullTextVisible(expectedLines = null)
    }

    private fun showPreview() = mutableStateOf(FloatingLyricsPreferences(
        fontSizeSp = 32f,
        maxWidthDp = 80f,
        sentenceCount = 3,
        showTranslation = true,
        longLineMode = FLOATING_LYRICS_LONG_LINE_WRAP,
        positionY = 1f
    )).also { preferences ->
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(220.dp).verticalScroll(rememberScrollState())) {
                        FloatingLyricsPreview(preferences.value, modifier = Modifier.testTag("preview"))
                    }
                }
            }
        }
    }

    private fun assertFullTextVisible(expectedLines: Int? = 3) {
        val previewBounds = composeRule.onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot
        for (resource in listOf(
            CoreCommonR.string.settings_floating_lyrics_preview_line,
            CoreCommonR.string.settings_floating_lyrics_preview_translation,
            CoreCommonR.string.settings_floating_lyrics_preview_next,
            CoreCommonR.string.settings_floating_lyrics_preview_second_next
        )) {
            val node = composeRule.onNodeWithText(context.getString(resource), useUnmergedTree = true)
            node.assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            expectedLines?.let { assertEquals(it, layout.lineCount) }
            assertTrue(layout.size.height > 0)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height + 1f >= layout.size.height)
            assertTrue(bounds.top >= previewBounds.top)
            assertTrue(bounds.bottom <= previewBounds.bottom)
        }
    }
}
