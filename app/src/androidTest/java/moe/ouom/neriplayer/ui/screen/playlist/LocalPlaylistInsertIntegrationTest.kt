package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LocalPlaylistInsertIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { LocalPlaylistRepository.getInstance(context) }
    private val screenVisible = mutableStateOf(false)
    private var ownedPlaylistId: Long? = null
    private var screenMounted = false

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun removeOwnedPlaylist() {
        composeRule.mainClock.autoAdvance = true
        val playlistId = ownedPlaylistId ?: return
        runBlocking {
            withTimeout(RepositoryTimeoutMs) {
                repository.deletePlaylist(playlistId)
            }
        }
        if (screenMounted) {
            // 让生产页面按歌单删除退出，避免 onDispose 又写入这份测试歌单的使用记录
            composeRule.waitUntil(UiTimeoutMs) { !screenVisible.value }
        }
        AppContainer.playlistUsageRepo.removeEntry(playlistId, "local")
    }

    @Test
    fun insertScrollsToCommittedSongAndUndoRestoresPersistedOrder() {
        val songs = createAndShowPlaylist()
        val originalOrder = songs.map { it.id }
        val movedSong = songs[1]
        selectSong(movedSong)
        openInsertPreview(TargetPosition)
        assertEquals(originalOrder, currentOrder())

        confirmInsert()
        val insertedOrder = moveSong(originalOrder, movedSong.id, TargetPosition)
        awaitOrder(insertedOrder)
        awaitUndo()
        awaitTargetScroll(movedSong)
        composeRule.onNodeWithText(movedSong.name).assertIsDisplayed()
        val listBounds = playlistList().fetchSemanticsNode().boundsInRoot
        val movedBounds = composeRule.onNodeWithText(movedSong.name).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "inserted song was not positioned at the top of the list: ${scrollDiagnostics(movedSong)}",
            abs(movedBounds.top - listBounds.top) < 2f
        )
        assertPersistedOrder(insertedOrder)

        undoButton().performClick()
        awaitOrder(originalOrder)
        assertPersistedOrder(originalOrder)
        composeRule.waitUntil(UiTimeoutMs) {
            composeRule.onAllNodesWithText(text(CoreCommonR.string.playlist_insert_undone))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_undone)).assertIsDisplayed()
    }

    @Test
    fun userScrollInterruptsAutomaticPositioningWithoutLosingUndo() {
        val songs = createAndShowPlaylist()
        val originalOrder = songs.map { it.id }
        val movedSong = songs[1]
        selectSong(movedSong)
        openInsertPreview(TargetPosition)
        composeRule.mainClock.autoAdvance = false

        confirmInsert()
        awaitOrder(moveSong(originalOrder, movedSong.id, TargetPosition))
        var interruptedPosition: Float? = null
        val observedPositions = mutableListOf<Float>()
        repeat(20) {
            if (interruptedPosition == null) {
                advanceFrame()
                val position = scrollPosition()
                observedPositions += position
                if (position > 0.1f && position < TargetScrollOffset - 1f) {
                    interruptedPosition = position
                }
            }
        }
        assertTrue(
            "automatic positioning did not start before the touch interruption: " +
                "samples=$observedPositions, ${scrollDiagnostics(movedSong)}",
            interruptedPosition != null
        )
        playlistList().performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + 100f), delayMillis = 500)
            cancel()
        }
        repeat(30) { advanceFrame() }
        assertTrue(
            "automatic positioning continued after the user drag: ${scrollDiagnostics(movedSong)}",
            scrollPosition() < TargetScrollOffset - 1f
        )
        undoButton().assertIsDisplayed().performClick()

        awaitOrder(originalOrder)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertPersistedOrder(originalOrder)
    }

    @Test
    fun repeatedInsertionAtTheSamePositionUndoesOnlyTheLatestReceipt() {
        val songs = createAndShowPlaylist()
        val originalOrder = songs.map { it.id }
        val firstSong = songs[1]
        selectSong(firstSong)
        openInsertPreview(TargetPosition)
        confirmInsert()
        val firstInsertedOrder = moveSong(originalOrder, firstSong.id, TargetPosition)
        awaitOrder(firstInsertedOrder)
        awaitUndo()
        awaitTargetScroll(firstSong)

        composeRule.onNodeWithText(firstSong.name).performClick()
        val secondSong = songs[3]
        playlistList().performScrollToNode(hasText(secondSong.name))
        composeRule.onNodeWithText(secondSong.name).performClick()
        openInsertPreview(TargetPosition)
        confirmInsert()
        awaitOrder(moveSong(firstInsertedOrder, secondSong.id, TargetPosition))
        awaitTargetScroll(secondSong)
        composeRule.onNodeWithText(secondSong.name).assertIsDisplayed()
        awaitUndo()
        undoButton().performClick()

        awaitOrder(firstInsertedOrder)
        assertPersistedOrder(firstInsertedOrder)
    }

    private fun createAndShowPlaylist(): List<SongItem> {
        val token = UUID.randomUUID().toString().take(8)
        val baseId = System.currentTimeMillis() * 1_000L
        val songs = List(SongCount) { index ->
            SongItem(
                id = baseId + index,
                name = "insert-$token-song-${index + 1}",
                artist = "synthetic artist",
                album = "insert-integration-$token",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = null
            )
        }
        val playlist = runBlocking {
            withTimeout(RepositoryTimeoutMs) {
                assertTrue("local playlist repository failed to initialize", repository.awaitInitialized())
                repository.createPlaylistWithPreparedSongs("UI$token", songs)
            }
        }
        ownedPlaylistId = playlist.id
        screenVisible.value = true
        composeRule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides AdvancedGlassOverscrollFactory) {
                MaterialTheme {
                    val navController = rememberNavController()
                    NavHost(navController, startDestination = FixtureRoute) {
                        composable(FixtureRoute) {
                            if (screenVisible.value) {
                                LocalPlaylistDetailScreen(
                                    playlistId = playlist.id,
                                    onBack = { screenVisible.value = false },
                                    onDeleted = { screenVisible.value = false },
                                    offlineMode = true
                                )
                            }
                        }
                    }
                }
            }
        }
        screenMounted = true
        composeRule.waitUntil(UiTimeoutMs) {
            composeRule.onAllNodesWithText(playlist.name).fetchSemanticsNodes().isNotEmpty()
        }
        return playlist.songs
    }

    private fun selectSong(song: SongItem) {
        playlistList().performScrollToNode(hasText(song.name))
        composeRule.onNodeWithText(song.name).performTouchInput { longClick() }
        playlistList().performScrollToIndex(0)
        assertEquals("fixture should start at the fixed playlist header", 0f, scrollPosition(), 0.01f)
    }

    private fun openInsertPreview(position: Int) {
        composeRule.onNodeWithContentDescription(text(CoreCommonR.string.cd_more_actions)).performClick()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_action)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement(position.toString())
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_title)).assertIsDisplayed()
    }

    private fun confirmInsert() {
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).performClick()
    }

    private fun awaitUndo() {
        composeRule.waitUntil(UiTimeoutMs) {
            composeRule.onAllNodesWithText(text(CoreCommonR.string.playlist_batch_export_undo))
                .fetchSemanticsNodes().isNotEmpty()
        }
        undoButton().assertIsDisplayed()
    }

    private fun undoButton(): SemanticsNodeInteraction =
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_batch_export_undo))

    private fun playlistList(): SemanticsNodeInteraction = composeRule.onNode(
        hasScrollToIndexAction().and(SemanticsMatcher.keyIsDefined(VerticalScrollAxisRange))
    )

    private fun scrollPosition(): Float =
        playlistList().fetchSemanticsNode().config[VerticalScrollAxisRange].value()

    private fun awaitTargetScroll(song: SongItem) {
        try {
            composeRule.waitUntil(UiTimeoutMs) {
                abs(scrollPosition() - TargetScrollOffset) < 0.05f
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("automatic positioning timed out: ${scrollDiagnostics(song)}", error)
        }
    }

    private fun scrollDiagnostics(song: SongItem): String {
        val listNode = playlistList().fetchSemanticsNode()
        val range = listNode.config[VerticalScrollAxisRange]
        val songBounds = composeRule.onAllNodesWithText(song.name).fetchSemanticsNodes()
            .map { it.boundsInRoot }
        val undoCount = composeRule.onAllNodesWithText(text(CoreCommonR.string.playlist_batch_export_undo))
            .fetchSemanticsNodes().size
        return "expectedOffset=$TargetScrollOffset, actualOffset=${range.value()}, " +
            "maxOffset=${range.maxValue()}, repoSongPosition=${currentOrder().indexOf(song.id) + 1}, " +
            "listBounds=${listNode.boundsInRoot}, songBounds=$songBounds, undoNodes=$undoCount"
    }

    private fun currentOrder(): List<Long> =
        repository.playlists.value.single { it.id == ownedPlaylistId }.songs.map { it.id }

    private fun awaitOrder(expected: List<Long>) {
        composeRule.waitUntil(RepositoryTimeoutMs) { currentOrder() == expected }
    }

    private fun assertPersistedOrder(expected: List<Long>) {
        val restored = runBlocking {
            withTimeout(RepositoryTimeoutMs) {
                repository.readFastPlaylist(requireNotNull(ownedPlaylistId))
            }
        }
        assertEquals("authoritative persisted order did not match the UI mutation", expected, restored?.songs?.map { it.id })
    }

    private fun advanceFrame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun text(resourceId: Int): String = context.getString(resourceId)

    private fun moveSong(order: List<Long>, songId: Long, position: Int): List<Long> =
        order.filterNot { it == songId }.toMutableList().apply { add(position - 1, songId) }

    private companion object {
        const val FixtureRoute = "insert-integration"
        const val SongCount = 120
        const val TargetPosition = 90
        // Foundation 1.11.4 的滚动语义以 index * 500 + 像素偏移表示，不能当成列表序号
        const val LazyItemSemanticsStep = 500f
        const val TargetScrollOffset = 91 * LazyItemSemanticsStep
        const val UiTimeoutMs = 5_000L
        const val RepositoryTimeoutMs = 10_000L
    }
}
