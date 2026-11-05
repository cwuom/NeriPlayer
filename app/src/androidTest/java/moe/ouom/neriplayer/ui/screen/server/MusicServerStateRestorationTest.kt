package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.server.artist.ServerArtistDetailContent
import moe.ouom.neriplayer.ui.screen.server.artist.ServerArtistListState
import moe.ouom.neriplayer.ui.screen.server.artist.rememberServerArtistListState
import moe.ouom.neriplayer.ui.screen.server.artist.rememberServerArtistSearchInputState
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicServerStateRestorationTest {
    @get:Rule val composeRule = createComposeRule()

    private val artist = ServerArtist("profile / 中文", "artist / 中文", "Restored artist", null, 30)
    private val albums = (0 until 30).map {
        ServerAlbum(artist.profileId, "album / $it", "Album $it", artist.name, null, 2)
    }

    @Before
    fun requireComposeHost() = assumeComposeHostAvailable()

    @Test
    fun entrySelectionSurvivesRestoreAndReturnsThroughTheArtist() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var entry: MusicServerEntryState
        restoration.setContent {
            entry = rememberMusicServerEntryState()
            Column {
                if (entry.opened) {
                    Text(entry.album?.name ?: entry.artist?.name ?: "Browser")
                    Button(onClick = entry::back) { Text("Back") }
                } else Text("Closed")
            }
        }
        composeRule.runOnIdle { entry.opened = true; entry.artist = artist; entry.album = albums[17] }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Album 17").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(entry.opened)
            assertEquals(artist, entry.artist)
            assertEquals(albums[17], entry.album)
        }
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText(artist.name).assertIsDisplayed()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Browser").assertIsDisplayed()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Closed").assertIsDisplayed()
    }

    @Test
    fun coldArtistRestoreWaitsForRealAlbumsBeforeRestoringItsAnchor() {
        val restoration = StateRestorationTester(composeRule)
        var coldOnMount = false
        lateinit var payload: MutableState<List<ServerAlbum>?>
        lateinit var scroll: ServerArtistListState
        lateinit var scope: CoroutineScope
        restoration.setContent {
            payload = remember { mutableStateOf(if (coldOnMount) null else albums) }
            scroll = rememberServerArtistListState("cold-artist")
            scope = rememberCoroutineScope()
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.width(360.dp).height(420.dp)) {
                        ServerArtistDetailContent(artist, payload.value.orEmpty(), scroll,
                            loading = payload.value == null, error = null, showSearch = false, query = "",
                            onQueryChange = {}, focusRequester = remember { FocusRequester() }, onRetry = {},
                            onAlbumClick = {}, offlineMode = true)
                    }
                }
            }
        }
        composeRule.runOnIdle { scope.launch { scroll.listState.scrollToItem(15, 17) } }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(15, scroll.listState.firstVisibleItemIndex)
            assertEquals(17, scroll.listState.firstVisibleItemScrollOffset)
            coldOnMount = true
        }

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(15, scroll.savedPosition().index) }

        composeRule.runOnIdle { payload.value = albums }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(15, scroll.listState.firstVisibleItemIndex)
            assertEquals(17, scroll.listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun returningFromAlbumKeepsArtistQueryAndScrollWithTheSameSnapshot() {
        lateinit var scroll: ServerArtistListState
        lateinit var scope: CoroutineScope
        var showAlbum by mutableStateOf(false)
        var restoredQuery = ""
        composeRule.setContent {
            val holder = rememberSaveableStateHolder()
            scope = rememberCoroutineScope()
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = UiStyle.Material, animateColors = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (showAlbum) Button(onClick = { showAlbum = false }) { Text("Back to artist") }
                    else holder.SaveableStateProvider("artist") {
                        var query by rememberSaveable { mutableStateOf("Album") }
                        restoredQuery = query
                        scroll = rememberServerArtistListState("warm-artist")
                        Box(Modifier.width(360.dp).height(420.dp)) {
                            ServerArtistDetailContent(artist, albums, scroll, loading = false, error = null,
                                showSearch = true, query = query, onQueryChange = { query = it },
                                focusRequester = remember { FocusRequester() }, onRetry = {},
                                onAlbumClick = { showAlbum = true }, offlineMode = true)
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle { scope.launch { scroll.listState.scrollToItem(15, 17) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Album 14").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Back to artist").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals("Album", restoredQuery)
            assertEquals(15, scroll.listState.firstVisibleItemIndex)
            assertEquals(17, scroll.listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun artistDraftSurvivesImmediateNavigationAndSavedStateRestore() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var input: MutableState<TextFieldValue>
        lateinit var openAlbum: () -> Unit
        restoration.setContent {
            var showAlbum by rememberSaveable { mutableStateOf(false) }
            openAlbum = { showAlbum = true }
            val holder = rememberSaveableStateHolder()
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = UiStyle.Material, animateColors = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (showAlbum) Button(onClick = { showAlbum = false }) { Text("Back to artist") }
                    else holder.SaveableStateProvider("artist") {
                        input = rememberServerArtistSearchInputState("draft-artist")
                        val scroll = rememberServerArtistListState("draft-artist")
                        Box(Modifier.width(360.dp).height(420.dp)) {
                            ServerArtistDetailContent(artist, albums, scroll, loading = false, error = null,
                                showSearch = true, query = input.value.text,
                                onQueryChange = { input.value = TextFieldValue(it) },
                                focusRequester = remember { FocusRequester() }, onRetry = {},
                                onAlbumClick = { openAlbum() }, offlineMode = true, inputState = input)
                        }
                    }
                }
            }
        }
        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { setText ->
            assertTrue(setText(AnnotatedString("PR511")))
            // 同帧离开，避免测试先等完输入同步再保存页面
            openAlbum()
        }
        composeRule.onNodeWithText("Back to artist").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Back to artist").assertIsDisplayed().performClick()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed().assert(hasText("PR511"))
        composeRule.runOnIdle {
            assertEquals("PR511", input.value.text)
        }
    }
}
