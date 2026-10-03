package moe.ouom.neriplayer.ui.screen.artist

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAncestors
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.host.LibraryHostScreen
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicLibraryGateway
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicUiDependencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryYouTubeCreatorScrollStateTest {
    @get:Rule
    val compose = createComposeRule()

    private val context by lazy {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        target.createConfigurationContext(Configuration(target.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
    }

    @Before
    fun requireUnlockedEmulator() {
        assumeComposeHostAvailable()
    }

    @Test
    fun realLibraryRoundTripRestoresBothAxesAndKeepsCreatorsIndependent() {
        val nonce = System.nanoTime().toString()
        val creators = listOf("A", "B").map { name ->
            YouTubeMusicCreatorSummary(
                browseId = "UCui_scroll_${nonce}_$name",
                title = "Scroll creator $name",
                subtitle = "Synthetic creator",
                coverUrl = ""
            )
        }
        val repository = FavoritePlaylistRepository.getInstance(context)
        val factory = LibraryCreatorFixtureFactory(creators, nonce)
        val gateway = EmptyScrollPlaylistGateway(nonce)
        val previousGateway = YouTubeMusicUiDependencies.libraryGateway
        try {
            runBlocking {
                check(repository.awaitInitialized())
                creators.forEach { creator ->
                    repository.addFavorite(
                        id = stableYouTubeMusicId(creator.browseId),
                        name = creator.title,
                        coverUrl = null,
                        trackCount = 0,
                        source = FAVORITE_SOURCE_YOUTUBE_ARTIST,
                        browseId = creator.browseId,
                        subtitle = creator.subtitle,
                        songs = emptyList()
                    )
                }
            }
            YouTubeMusicUiDependencies.libraryGateway = gateway
            compose.setContent {
                val owner = checkNotNull(LocalViewModelStoreOwner.current)
                val models = remember(owner) {
                    val provider = ViewModelProvider(owner, factory)
                    creators.map { creator ->
                        creator to provider.get(
                            youtubeMusicCreatorDetailViewModelKey(creator.browseId),
                            YouTubeMusicCreatorDetailViewModel::class.java
                        )
                    }
                }
                LaunchedEffect(models) {
                    models.forEach { (creator, viewModel) -> viewModel.start(creator) }
                }
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides context.resources.configuration
                ) {
                    NeriTheme(
                        followSystemDark = false,
                        forceDark = false,
                        dynamicColor = false,
                        seedColorHex = "7D5260"
                    ) {
                        Surface {
                            LibraryHostScreen(onOpenRecent = {}, offlineMode = true)
                        }
                    }
                }
            }
            compose.waitUntil(SCROLL_TIMEOUT_MS) {
                factory.models.size == 2 && factory.models.all {
                    it.uiState.value.detail != null
                }
            }
            compose.onNodeWithText(context.getString(CoreCommonR.string.library_tab_favorite))
                .performClick()
            compose.onNodeWithText(context.getString(CoreCommonR.string.library_favorite_tab_artists))
                .performClick()
            compose.onNode(
                hasText(context.getString(CoreCommonR.string.library_artist_platform_youtube)).and(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
                )
            )
                .performClick()
            compose.onNodeWithText(creators[0].title).performClick()
            waitForText("A horizontal item 0")

            compose.onNodeWithText("A horizontal item 0")
                .onAncestors().filterToOne(horizontalList).performScrollToIndex(8)
            compose.waitUntil(SCROLL_TIMEOUT_MS) { horizontalOffset() > 0f }
            val savedHorizontalOffset = horizontalOffset()
            compose.onNode(verticalList).performScrollToIndex(12)
            compose.waitUntil(SCROLL_TIMEOUT_MS) { verticalOffset() > 0f }
            val savedVerticalOffset = verticalOffset()
            compose.onNodeWithText("Related creator B").performClick()

            waitForText("B horizontal item 0")
            assertVerticalOffset(0f)
            assertHorizontalOffset(0f)
            compose.onNodeWithText("B horizontal item 0")
                .onAncestors().filterToOne(horizontalList).performScrollToIndex(10)
            compose.waitUntil(SCROLL_TIMEOUT_MS) { horizontalOffset() > 0f }
            val savedBHorizontalOffset = horizontalOffset()
            compose.onNode(verticalList).performScrollToIndex(12)
            compose.waitUntil(SCROLL_TIMEOUT_MS) { verticalOffset() > 0f }
            val savedBVerticalOffset = verticalOffset()
            compose.onNodeWithText("B section playlist 11").performClick()

            waitForText(SCROLL_CHILD_PLAYLIST_TITLE)
            assertTrue(gateway.requests.get() > 0)
            goBack()
            assertVerticalOffset(savedBVerticalOffset)
            compose.onNode(verticalList).performScrollToIndex(0)
            assertHorizontalOffset(savedBHorizontalOffset)
            compose.onNode(verticalList).performScrollToIndex(12)
            assertVerticalOffset(savedBVerticalOffset)
            goBack()

            assertVerticalOffset(savedVerticalOffset)
            compose.onNode(verticalList).performScrollToIndex(0)
            assertHorizontalOffset(savedHorizontalOffset)

            compose.onNode(verticalList).performScrollToIndex(12)
            assertVerticalOffset(savedVerticalOffset)
            goBack()
            waitForText(creators[1].title)
            compose.onNodeWithText(creators[1].title).performClick()
            assertVerticalOffset(savedBVerticalOffset)
            compose.onNode(verticalList).performScrollToIndex(0)
            assertHorizontalOffset(savedBHorizontalOffset)
            goBack()

            waitForText(creators[0].title)
            compose.onNodeWithText(creators[0].title).performClick()
            assertVerticalOffset(savedVerticalOffset)
            compose.onNode(verticalList).performScrollToIndex(0)
            assertHorizontalOffset(savedHorizontalOffset)
            creators.forEach { creator ->
                assertEquals(1, factory.loads.getValue(creator.browseId).get())
            }
        } finally {
            YouTubeMusicUiDependencies.libraryGateway = previousGateway
            runBlocking {
                creators.forEach { creator ->
                    repository.removeFavorite(
                        stableYouTubeMusicId(creator.browseId), FAVORITE_SOURCE_YOUTUBE_ARTIST
                    )
                }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(SCROLL_TIMEOUT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun goBack() {
        compose.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_back))
            .performClick()
        compose.waitForIdle()
    }

    private fun assertVerticalOffset(expected: Float) {
        compose.waitUntil(SCROLL_TIMEOUT_MS) { verticalOffset() == expected }
        assertEquals(expected, verticalOffset(), 0f)
    }

    private fun assertHorizontalOffset(expected: Float) {
        compose.waitUntil(SCROLL_TIMEOUT_MS) { horizontalOffset() == expected }
        assertEquals(expected, horizontalOffset(), 0f)
    }

    private fun verticalOffset(): Float = compose.onAllNodes(verticalList)
        .fetchSemanticsNodes().single().config[VerticalScrollAxisRange].value()

    private fun horizontalOffset(): Float = compose.onAllNodes(horizontalList)
        .fetchSemanticsNodes().maxOf { it.config[HorizontalScrollAxisRange].value() }
}

private const val SCROLL_TIMEOUT_MS = 5_000L
private const val SCROLL_CHILD_PLAYLIST_TITLE = "Scroll child playlist"

private val verticalList = hasScrollToIndexAction().and(
    SemanticsMatcher.keyIsDefined(VerticalScrollAxisRange)
)
private val horizontalList = hasScrollToIndexAction().and(
    SemanticsMatcher.keyIsDefined(HorizontalScrollAxisRange)
)

private class LibraryCreatorFixtureFactory(
    creators: List<YouTubeMusicCreatorSummary>,
    private val nonce: String
) : ViewModelProvider.Factory {
    val models = CopyOnWriteArrayList<YouTubeMusicCreatorDetailViewModel>()
    val loads = creators.associate { it.browseId to AtomicInteger() }
    private val relatedCreator = creators[1]

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            ?: error("application is required")
        val viewModel = YouTubeMusicCreatorDetailViewModel(
            application = application,
            loadDetail = { creator ->
                loads.getValue(creator.browseId).incrementAndGet()
                libraryCreatorDetail(creator, nonce, relatedCreator)
            }
        )
        models.add(viewModel)
        return requireNotNull(modelClass.cast(viewModel))
    }
}

private class EmptyScrollPlaylistGateway(private val nonce: String) : YouTubeMusicLibraryGateway {
    val requests = AtomicInteger()

    override suspend fun getLibraryPlaylists(): List<YouTubeMusicPlaylist> = emptyList()

    override suspend fun getPlaylistDetail(browseId: String): YouTubeMusicPlaylistDetail {
        check(browseId.startsWith("VLui_scroll_playlist_${nonce}_"))
        requests.incrementAndGet()
        return YouTubeMusicPlaylistDetail(
            playlistId = browseId.removePrefix("VL"),
            title = SCROLL_CHILD_PLAYLIST_TITLE,
            coverUrl = "",
            trackCount = 0,
            tracks = emptyList()
        )
    }

    override suspend fun getPlaylistDetailPreview(browseId: String): YouTubeMusicPlaylistDetail =
        getPlaylistDetail(browseId)
}

private fun libraryCreatorDetail(
    creator: YouTubeMusicCreatorSummary,
    nonce: String,
    relatedCreator: YouTubeMusicCreatorSummary
): YouTubeMusicCreatorDetail {
    val name = creator.title.takeLast(1)
    fun playlistItem(label: String, id: String) = YouTubeMusicCreatorItem(
        type = YouTubeMusicCreatorItemType.Playlist,
        title = "$name $label",
        subtitle = creator.title,
        coverUrl = "",
        browseId = "VLui_scroll_playlist_${nonce}_${name}_$id"
    )
    return YouTubeMusicCreatorDetail(
        header = YouTubeMusicCreatorHeader(
            browseId = creator.browseId,
            title = creator.title,
            subtitle = creator.subtitle,
            coverUrl = ""
        ),
        sections = listOf(
            YouTubeMusicCreatorSection(
                title = "$name horizontal section",
                items = (0..30).map { playlistItem("horizontal item $it", "horizontal_$it") }
            )
        ) + (1..31).map { index ->
            YouTubeMusicCreatorSection(
                title = "$name section $index",
                items = listOf(
                    if (name == "A" && index == 11) {
                        YouTubeMusicCreatorItem(
                            type = YouTubeMusicCreatorItemType.Creator,
                            title = "Related creator B",
                            subtitle = relatedCreator.subtitle,
                            coverUrl = "",
                            browseId = relatedCreator.browseId
                        )
                    } else {
                        playlistItem("section playlist $index", "section_$index")
                    }
                )
            )
        }
    )
}
