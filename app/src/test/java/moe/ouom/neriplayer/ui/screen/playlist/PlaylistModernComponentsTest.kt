package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.outlined.Repeat
import moe.ouom.neriplayer.data.model.artwork.CoverArtColorSample
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlaylistModernComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `playback actions route playback mode changes and disable empty playlists`() {
        var shuffleEnabled by mutableStateOf(false)
        var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        var songCount by mutableIntStateOf(3)
        val events = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                PlaylistModernPlaybackActions(
                    songCount = songCount,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onPlayInOrder = { events += "order" },
                    onShufflePlay = { events += "shuffle" },
                    onToggleShuffle = { events += "toggle" },
                    onCycleRepeatMode = { events += "repeat" },
                    onExportToLocalPlaylist = { events += "export" }
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.player_play_all)).performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.playlist_mode_order)).performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.playlist_mode_repeat_off)).performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.playlist_export_to_local)).performClick()

        shuffleEnabled = true
        repeatMode = Player.REPEAT_MODE_ONE
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.player_shuffle_play)).performClick()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.playlist_mode_shuffle)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.playlist_mode_repeat_one)).assertExists()

        songCount = 0
        repeatMode = Player.REPEAT_MODE_ALL
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.player_shuffle_play)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.playlist_export_to_local)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.playlist_mode_repeat_all)).assertIsEnabled()

        assertEquals(listOf("order", "toggle", "repeat", "export", "shuffle"), events)
    }

    @Test
    fun `hero chrome renders with resolved colors in light and dark themes`() {
        var dark by mutableStateOf(false)
        val exports = mutableListOf<Int>()
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                PlaylistModernVisualColorsProvider(coverUrl = null, offlineMode = true) {
                    Column {
                        PlaylistModernHeroHeader(
                            displayName = "Mix",
                            coverUrl = null,
                            subtitle = "3 songs",
                            offlineMode = true,
                            height = 220.dp,
                            actions = { Text("hero-actions") }
                        )
                        PlaylistModernHeroSearchField(
                            query = "",
                            onQueryChange = {},
                            placeholder = "hero-search",
                            focusRequester = FocusRequester()
                        )
                        PlaylistModernDockedSearchField(
                            query = "",
                            onQueryChange = {},
                            placeholder = "docked-search",
                            focusRequester = FocusRequester()
                        )
                        PlaylistModernActionSheet(
                            coverUrl = null,
                            offlineMode = true,
                            hasCustomBackground = dark
                        ) {
                            PlaylistModernPlaybackActions(
                                songCount = 2,
                                shuffleEnabled = dark,
                                repeatMode = Player.REPEAT_MODE_ALL,
                                onPlayInOrder = {},
                                onShufflePlay = {},
                                onToggleShuffle = {},
                                onCycleRepeatMode = {},
                                onExportToLocalPlaylist = { exports += 1 }
                            )
                        }
                    }
                }
            }
        }

        listOf("Mix", "3 songs", "hero-actions", "hero-search", "docked-search").forEach { text ->
            composeRule.onNodeWithText(text).assertExists()
        }
        composeRule.onNodeWithContentDescription("Mix").assertExists()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.playlist_export_to_local)).performClick()

        dark = true
        listOf("Mix", "hero-search", "docked-search").forEach { text ->
            composeRule.onNodeWithText(text).assertExists()
        }
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.playlist_export_to_local)).performClick()
        assertEquals(listOf(1, 1), exports)
    }

    @Test
    fun `standalone sheet and list surface resolve their own hero colors`() {
        var dark by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Column {
                    PlaylistModernActionSheet(coverUrl = " ", offlineMode = true) {
                        Text("standalone-sheet")
                    }
                    PlaylistModernListItemSurface(coverUrl = null, offlineMode = true) {
                        Text("list-surface")
                    }
                    PlaylistModernStableSearchField(
                        query = "",
                        onQueryChange = {},
                        placeholder = "stable-search",
                        focusRequester = null,
                        dockedProgress = 0.5f
                    )
                }
            }
        }

        composeRule.onNodeWithText("standalone-sheet").assertExists()
        composeRule.onNodeWithText("list-surface").assertExists()
        composeRule.onNodeWithText("stable-search").assertExists()
        dark = true
        composeRule.onNodeWithText("standalone-sheet").assertExists()
        composeRule.onNodeWithText("stable-search").assertExists()
    }

    @Test
    fun `docked search slot composes only while revealed and adapts to the glass tone`() {
        var reveal by mutableFloatStateOf(0f)
        var glassBackground by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme(colorScheme = if (glassBackground) darkColorScheme() else lightColorScheme()) {
                Column {
                    PlaylistModernDockedSearchSlot(
                        revealProgress = reveal,
                        coverUrl = null,
                        offlineMode = true,
                        query = "",
                        onQueryChange = {},
                        placeholder = "slot-search",
                        focusRequester = FocusRequester(),
                        dockedProgress = reveal
                    )
                    PlaylistModernStableSearchField(
                        query = "",
                        onQueryChange = {},
                        placeholder = "glass-search",
                        focusRequester = FocusRequester(),
                        dockedProgress = 1f,
                        glassColor = if (glassBackground) Color.Black else Color.White,
                        onFocusChanged = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("slot-search").assertDoesNotExist()
        reveal = 1f
        composeRule.onNodeWithText("slot-search").assertExists()
        glassBackground = true
        composeRule.onNodeWithText("glass-search").assertExists()
        reveal = 0f
        composeRule.onNodeWithText("slot-search").assertDoesNotExist()
    }

    @Test
    fun `collapsed top bar colors fall back to the list palette without a playlist color`() {
        val colors = mutableMapOf<String, Color>()
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                colors["bar"] = playlistModernCollapsedTopBarColor()
                colors["barTinted"] = playlistModernCollapsedTopBarColor(Color.Red)
                colors["content"] = playlistModernCollapsedTopBarContentColor()
                colors["contentOnWhite"] = playlistModernCollapsedTopBarContentColor(Color.White)
                colors["contentOnBlack"] = playlistModernCollapsedTopBarContentColor(Color.Black)
            }
        }
        composeRule.waitForIdle()

        assertEquals(Color.Transparent, colors["bar"])
        assertEquals(Color.Red.copy(alpha = 0f), colors["barTinted"])
        assertEquals(Color(0xFF17191F), colors["content"])
        assertEquals(Color(0xFF17191F), colors["contentOnWhite"])
        assertEquals(Color.White.copy(alpha = 0.95f), colors["contentOnBlack"])
    }

    @Test
    fun `search input debounces typing and follows external query resets`() {
        var query by mutableStateOf("")
        val changes = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                PlaylistModernHeroSearchField(
                    query = query,
                    onQueryChange = {
                        changes += it
                        query = it
                    },
                    placeholder = "debounced-search",
                    onFocusChanged = {}
                )
            }
        }

        composeRule.mainClock.autoAdvance = false
        composeRule.onNode(hasSetTextAction()).performTextInput("beta")
        composeRule.mainClock.advanceTimeBy(40L)
        assertEquals(emptyList<String>(), changes)
        composeRule.mainClock.advanceTimeBy(120L)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(listOf("beta"), changes)

        query = ""
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assert(editableText(""))
        assertEquals(listOf("beta"), changes)
    }

    @Test
    fun `search results return all items until the index filters the query`() {
        var query by mutableStateOf("")
        var buildIndex by mutableStateOf(false)
        composeRule.setContent {
            val results = rememberPlaylistSearchResults(
                query = query,
                items = SEARCH_ITEMS,
                tokens = { listOf(it) },
                buildIndex = buildIndex
            )
            Text(results.joinToString(","), modifier = Modifier.testTag("results"))
        }

        query = "beta"
        composeRule.onNodeWithTag("results").assert(hasText("Alpha,Beta"))
        buildIndex = true
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("results") and hasText("Beta")).fetchSemanticsNodes().isNotEmpty()
        }
        query = ""
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("results") and hasText("Alpha,Beta")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `hero components skip unchanged recompositions and refresh changed inputs`() {
        var tick by mutableIntStateOf(0)
        var title by mutableStateOf("Mix")
        var dark by mutableStateOf(true)
        val inputState = mutableStateOf(TextFieldValue("preset"))
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Column {
                    Text("tick=$tick")
                    PlaylistModernHeroHeader(
                        displayName = title,
                        coverUrl = "file:///missing/hero-cover.jpg",
                        subtitle = "subtitle",
                        offlineMode = true,
                        height = 200.dp,
                        coverContentDescription = "$title cover"
                    )
                    PlaylistModernHeroSearchField(
                        query = title,
                        onQueryChange = {},
                        placeholder = "hero-$title",
                        modifier = Modifier.testTag("hero-field"),
                        inputState = inputState
                    )
                    PlaylistModernDockedSearchField(
                        query = title,
                        onQueryChange = {},
                        placeholder = "docked-$title",
                        modifier = Modifier.testTag("docked-field"),
                        focusRequester = null,
                        onFocusChanged = {},
                        inputState = inputState
                    )
                    PlaylistModernDockedSearchSlot(
                        revealProgress = 1f,
                        coverUrl = null,
                        offlineMode = true,
                        query = title,
                        onQueryChange = {},
                        placeholder = "slot-$title",
                        focusRequester = null,
                        modifier = Modifier.testTag("slot"),
                        onFocusChanged = {},
                        dockedProgress = 0.5f,
                        inputState = inputState
                    )
                    PlaylistModernActionSheet(
                        coverUrl = null,
                        offlineMode = true,
                        modifier = Modifier.testTag("sheet"),
                        shape = RectangleShape,
                        cornerGapHeight = 12.dp,
                        hasCustomBackground = false
                    ) {
                        Text("sheet-$title")
                    }
                    PlaylistModernPlaybackActions(
                        songCount = title.length,
                        shuffleEnabled = false,
                        repeatMode = Player.REPEAT_MODE_OFF,
                        modifier = Modifier.testTag("actions"),
                        onPlayInOrder = {},
                        onShufflePlay = {},
                        onToggleShuffle = {},
                        onCycleRepeatMode = {},
                        onExportToLocalPlaylist = {}
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Mix cover").assertExists()
        tick = 1
        composeRule.onNodeWithText("tick=1").assertExists()
        composeRule.onNodeWithContentDescription("Mix cover").assertExists()
        composeRule.onAllNodes(hasSetTextAction() and hasText("preset")).assertCountEquals(3)

        title = "Chill"
        listOf("Chill", "sheet-Chill").forEach { text -> composeRule.onNodeWithText(text).assertExists() }
        composeRule.onNodeWithContentDescription("Chill cover").assertExists()
        composeRule.onNodeWithTag("docked-field").assertExists()
        composeRule.onNodeWithTag("slot").assertExists()
        composeRule.onNodeWithTag("actions").assertExists()

        dark = false
        composeRule.onNodeWithContentDescription("Chill cover").assertExists()
        composeRule.onNodeWithText("sheet-Chill").assertExists()
    }

    @Test
    fun `hero color helpers prefer cached samples and pick readable control content`() {
        val cached = CoverArtColorSample(seedHex = "112233", baseColorArgb = 0xFF112233.toInt())
        val loaded = mutableStateOf<CoverArtColorSample?>(
            CoverArtColorSample(seedHex = "445566", baseColorArgb = 0xFF445566.toInt())
        )

        assertEquals(0xFF112233.toInt(), resolvePlaylistHeroCoverColorArgb(true, cached, loaded))
        assertEquals(0xFF445566.toInt(), resolvePlaylistHeroCoverColorArgb(true, null, loaded))
        assertNull(resolvePlaylistHeroCoverColorArgb(false, cached, loaded))
        loaded.value = null
        assertNull(resolvePlaylistHeroCoverColorArgb(true, null, loaded))

        assertEquals(Color.White.copy(alpha = 0.94f), playlistHeroControlContentColor(isDarkTheme = true))
        assertEquals(Color(0xFF191712), playlistHeroControlContentColor(isDarkTheme = false))
    }

    @Test
    fun `playback labels and icons follow shuffle and repeat modes`() {
        assertEquals(CoreCommonR.string.player_shuffle_play, playlistPlayLabelRes(shuffleEnabled = true))
        assertEquals(CoreCommonR.string.player_play_all, playlistPlayLabelRes(shuffleEnabled = false))
        assertEquals(CoreCommonR.string.playlist_mode_shuffle, playlistShuffleModeLabelRes(shuffleEnabled = true))
        assertEquals(CoreCommonR.string.playlist_mode_order, playlistShuffleModeLabelRes(shuffleEnabled = false))
        assertEquals(Icons.Filled.RepeatOne, playlistRepeatModeIcon(Player.REPEAT_MODE_ONE))
        assertEquals(Icons.Outlined.Repeat, playlistRepeatModeIcon(Player.REPEAT_MODE_ALL))
    }

    private fun editableText(text: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    private fun string(id: Int): String = context.getString(id)

    private companion object {
        val SEARCH_ITEMS = listOf("Alpha", "Beta")
    }
}
