package moe.ouom.neriplayer.ui.screen.server

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.server.album.ServerAlbumDetailScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.common.R as CoreCommonR

@RunWith(AndroidJUnit4::class)
class AlbumSearchStateRestorationTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun requireComposeHost() {
        assumeComposeHostAvailable()
        instrumentation.runOnMainSync {
            if (!AppContainer.isInitialized()) AppContainer.initialize(context.applicationContext as Application)
        }
    }

    @Test fun cleanAlbumDraftSurvivesImmediateNavigationAndSavedStateRestore() = verifyDraft(UiStyle.Clean)

    @Test fun materialAlbumDraftSurvivesImmediateNavigationAndSavedStateRestore() = verifyDraft(UiStyle.Material)

    private fun verifyDraft(style: UiStyle) {
        val album = ServerAlbum(UUID.randomUUID().toString(), "album / 中文", "Draft album", "Artist", null, 0)
        val restoration = StateRestorationTester(composeRule)
        lateinit var leaveAlbum: () -> Unit
        restoration.setContent {
            var showAlbum by rememberSaveable { mutableStateOf(true) }
            leaveAlbum = { showAlbum = false }
            val holder = rememberSaveableStateHolder()
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = style, animateColors = false) {
                Surface(Modifier.fillMaxSize()) {
                    if (showAlbum) holder.SaveableStateProvider("album") {
                        ServerAlbumDetailScreen(album, onBack = leaveAlbum, onSongClick = { _, _ -> }, offlineMode = true)
                    } else Button(onClick = { showAlbum = true }) { Text("Back to album") }
                }
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_search_songs)).performClick()
        visibleInput().performSemanticsAction(SemanticsActions.SetText) { setText ->
            assertTrue(setText(AnnotatedString("PR511 pending / 中文")))
            // 同帧离开，不能先等 debounce 把草稿同步到保存的 query
            leaveAlbum()
        }
        composeRule.onNodeWithText("Back to album").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Back to album").assertIsDisplayed().performClick()
        visibleInput().assert(hasText("PR511 pending / 中文"))
    }

    private fun visibleInput(): SemanticsNodeInteraction {
        val inputs = composeRule.onAllNodes(hasSetTextAction())
        val index = inputs.fetchSemanticsNodes().indices.first { inputs[it].isDisplayed() }
        return inputs[index].assertIsDisplayed()
    }
}
