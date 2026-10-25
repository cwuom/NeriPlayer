package moe.ouom.neriplayer.ui.screen.playlist

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollFactory
import moe.ouom.neriplayer.ui.effect.glass.advancedGlassHostNavigationTransition
import moe.ouom.neriplayer.ui.effect.glass.animateAdvancedGlassSceneMotion
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.navigation.AppNavigationSceneRenderer
import moe.ouom.neriplayer.ui.navigation.MainTabBackgroundMotion
import moe.ouom.neriplayer.ui.navigation.resolveMainTabNavigationMotionState
import moe.ouom.neriplayer.ui.screen.host.LibrarySelectedItem
import moe.ouom.neriplayer.ui.screen.host.libraryNavigationScene
import moe.ouom.neriplayer.ui.screen.host.rememberHostPredictiveBackTransition
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
class LocalPlaylistHeaderScrollRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { LocalPlaylistRepository.getInstance(context) }
    private val fixtureVisible = mutableStateOf(true)
    private val screenVisible = mutableStateOf(false)
    private val overscrollEffects = CopyOnWriteArrayList<OverscrollEffect>()
    private val flingSamples = CopyOnWriteArrayList<FlingVelocitySample>()
    private var ownedPlaylistId: Long? = null
    private var evidenceDirectory: File? = null
    private var coverFile: File? = null
    private var backgroundFile: File? = null
    private var backgroundBitmap: Bitmap? = null
    private var previousBackgroundUri: String? = null
    private var backgroundWasChanged = false
    private var previousAutoShowKeyboard: Boolean? = null
    private var hostView: View? = null
    private var screenMounted = false
    private var oneDpPx = 0f
    private var coverInsetPx = 0f
    private lateinit var playlistName: String
    private lateinit var songs: List<SongItem>

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun removeOwnedFixture() {
        try {
            composeRule.mainClock.autoAdvance = true
            ownedPlaylistId?.let { playlistId ->
                runBlocking {
                    withTimeout(RepositoryTimeoutMs) { repository.deletePlaylist(playlistId) }
                }
                if (screenMounted) {
                    composeRule.waitUntil(UiTimeoutMs) { !screenVisible.value }
                }
                AppContainer.playlistUsageRepo.removeEntry(playlistId, "local")
            }
            if (screenMounted) {
                composeRule.runOnIdle { fixtureVisible.value = false }
                composeRule.waitForIdle()
            }
            if (backgroundWasChanged) {
                runBlocking { AppContainer.settingsRepo.setBackgroundImageUri(previousBackgroundUri) }
            }
            backgroundBitmap?.recycle()
            coverFile?.delete()
            backgroundFile?.delete()
        } finally {
            previousAutoShowKeyboard?.let { previous ->
                runBlocking { AppContainer.settingsRepo.setAutoShowKeyboard(previous) }
            }
        }
    }

    @Test
    fun materialPlaylistHeaderReentersWithoutAnEmptyGapDuringPullAndReverse() {
        verifyHeaderReentry(UiStyle.Material)
    }

    @Test
    fun cleanPlaylistHeaderReentersWithoutAnEmptyGapDuringPullAndReverse() {
        verifyHeaderReentry(UiStyle.Clean)
    }

    @Test
    fun materialPlaylistShortReverseReleaseDoesNotSeparateTheHeaderFromItsViewport() {
        verifyHeaderReentry(UiStyle.Material, releaseDuringOverscroll = true)
    }

    @Test
    fun cleanPlaylistShortReverseReleaseDoesNotSeparateTheHeaderFromItsViewport() {
        verifyHeaderReentry(UiStyle.Clean, releaseDuringOverscroll = true)
    }

    @Test
    fun materialPhoneHostedPlaylistHeaderReentersContinuouslyDuringPullAndReverse() {
        verifyHeaderReentry(UiStyle.Material, hostedScene = true)
    }

    @Test
    fun cleanPhoneHostedPlaylistHeaderReentersContinuouslyDuringPullAndReverse() {
        verifyHeaderReentry(UiStyle.Clean, hostedScene = true)
    }

    @Test
    fun materialPhoneHostedFastFlingAndReverseKeepVisibleSongsInOrder() {
        verifyHostedFastFlingAndReverse(UiStyle.Material)
    }

    @Test
    fun cleanPhoneHostedFastFlingAndReverseKeepVisibleSongsInOrder() {
        verifyHostedFastFlingAndReverse(UiStyle.Clean)
    }

    @Test
    fun materialOrdinaryBrowsingPlacesUpdatedSongOrderImmediately() {
        verifyOrdinaryBrowsingPlacesUpdatedOrderImmediately(UiStyle.Material)
    }

    @Test
    fun cleanOrdinaryBrowsingPlacesUpdatedSongOrderImmediately() {
        verifyOrdinaryBrowsingPlacesUpdatedOrderImmediately(UiStyle.Clean)
    }

    @Test
    fun materialSearchOpensAndClosesWithoutJumpingTheHeaderActionsOrSongs() {
        verifySearchTransitionContinuity(UiStyle.Material)
    }

    @Test
    fun cleanSearchOpensAndClosesWithoutJumpingTheHeaderActionsOrSongs() {
        verifySearchTransitionContinuity(UiStyle.Clean)
    }

    @Test
    fun materialSearchKeepsFocusAndKeyboardWhileFilteringAndClearsThemOnClose() {
        verifySearchFocusAndKeyboard(UiStyle.Material)
    }

    @Test
    fun cleanSearchKeepsFocusAndKeyboardWhileFilteringAndClearsThemOnClose() {
        verifySearchFocusAndKeyboard(UiStyle.Clean)
    }

    private fun verifySearchTransitionContinuity(style: UiStyle) {
        configureAutoShowKeyboard(false)
        createAndShowPlaylist(style, hostedScene = true)
        val samples = mutableListOf<SearchFrameSample>()
        composeRule.mainClock.autoAdvance = false
        try {
            samples += captureSearchFrame("search-before", 0)
            searchToggle().performClick()
            samples += captureSearchFrame("search-open", 0)
            repeat(SearchTransitionFrameCount) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                samples += captureSearchFrame("search-open", frame + 1)
            }
            assertEquals("opening search did not compose one input", 1, searchFields().fetchSemanticsNodes().size)
            searchToggle().performClick()
            samples += captureSearchFrame("search-close", 0)
            repeat(SearchTransitionFrameCount) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                samples += captureSearchFrame("search-close", frame + 1)
            }
            assertTrue("closing search left its input composed", allSearchFields().fetchSemanticsNodes().isEmpty())
        } finally {
            requireNotNull(evidenceDirectory).resolve("search-transition-frames.txt").writeText(
                "style=$style, hosted=true, autoShowKeyboard=false\n" + samples.joinToString("\n")
            )
            composeRule.mainClock.autoAdvance = true
        }
        val diagnostics = "evidence=${evidenceDirectory?.absolutePath}\n" + samples.joinToString("\n")
        val tolerance = PixelToleranceDp * oneDpPx
        val initial = samples.first()
        val opened = samples.last { it.phase == "search-open" }
        val closed = samples.last()
        assertTrue(
            "search did not expand the header and move the following content: $diagnostics",
            opened.firstSong.top - initial.firstSong.top > 32f * oneDpPx
        )
        listOf("search-open", "search-close").forEach { phase ->
            val transition = if (phase == "search-open") {
                listOf(initial) + samples.filter { it.phase == phase }
            } else {
                listOf(opened) + samples.filter { it.phase == phase }
            }
            val movingFrames = transition.zipWithNext().count { (previous, next) ->
                abs(next.firstSong.top - previous.firstSong.top) > tolerance
            }
            assertTrue("search content snapped instead of progressing through layout frames: $phase\n$diagnostics", movingFrames >= 3)
            transition.zipWithNext().forEach { (previous, next) ->
                val rowMovement = next.firstSong.top - previous.firstSong.top
                val actionMovement = next.playButton.top - previous.playButton.top
                assertTrue(
                    "search header changed the following content by a large single-frame jump: $previous\n$next\n$diagnostics",
                    abs(rowMovement) <= SearchMaximumFrameStepDp * oneDpPx
                )
                assertEquals("search detached the playback actions from the first song: $diagnostics", rowMovement, actionMovement, tolerance)
                assertTrue(
                    "search content reversed direction during its visibility transition: $diagnostics",
                    if (phase == "search-open") rowMovement >= -tolerance else rowMovement <= tolerance
                )
            }
        }
        samples.forEach { sample ->
            assertEquals("search moved the header cover: $diagnostics", initial.cover.top, sample.cover.top, tolerance)
            assertEquals("search changed the list viewport origin: $diagnostics", initial.listTop, sample.listTop, tolerance)
            assertTrue("search visibility changed the logical scroll anchor: $diagnostics", sample.firstVisibleIndex == 0 && sample.scrollOffsetPx <= tolerance)
            assertTrue("disabled automatic keyboard unexpectedly focused the search input: $diagnostics", !sample.inputFocused)
            assertEquals("disabled automatic keyboard unexpectedly opened IME: $diagnostics", false, sample.imeVisible)
        }
        assertEquals("closing search did not restore the playback actions: $diagnostics", initial.playButton.top, closed.playButton.top, tolerance)
        assertEquals("closing search did not restore the first song: $diagnostics", initial.firstSong.top, closed.firstSong.top, tolerance)
    }

    private fun verifySearchFocusAndKeyboard(style: UiStyle) {
        configureAutoShowKeyboard(true)
        createAndShowPlaylist(style, hostedScene = false)
        val beforeOpening = captureSettledFrame("search-before-keyboard")
        searchToggle().performClick()
        composeRule.waitUntil(UiTimeoutMs) {
            searchFields().fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.Focused) == true && imeVisible() == true
        }
        assertEquals("opening search should start with an empty query", "", composeRule.onNode(searchFieldMatcher())
            .assertIsFocused().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val afterOpening = captureSettledFrame("search-after-keyboard")
        val tolerance = PixelToleranceDp * oneDpPx
        assertEquals("opening IME changed the first visible item", 0, afterOpening.firstVisibleIndex)
        assertEquals("opening IME changed the logical scroll offset", 0f, afterOpening.scrollOffsetPx, tolerance)
        assertEquals("opening IME moved the header cover", requireNotNull(beforeOpening.coverTop), requireNotNull(afterOpening.coverTop), tolerance)
        val query = "song$SongCount"
        composeRule.onNode(searchFieldMatcher()).performTextInput(query)
        composeRule.waitUntil(UiTimeoutMs) {
            captureSongBounds(playlistList().fetchSemanticsNode().boundsInRoot)
                .map { it.index } == listOf(songs.lastIndex)
        }
        composeRule.onNode(searchFieldMatcher()).assertIsFocused().assertTextEquals(query)
        assertEquals("filtering dismissed the keyboard", true, imeVisible())
        captureSettledFrame("search-filtered")
        searchToggle().performClick()
        composeRule.waitUntil(UiTimeoutMs) {
            allSearchFields().fetchSemanticsNodes().isEmpty() && imeVisible() == false &&
                captureSongBounds(playlistList().fetchSemanticsNode().boundsInRoot)
                    .any { it.index == 0 }
        }
        searchToggle().performClick()
        composeRule.waitUntil(UiTimeoutMs) {
            searchFields().fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.Focused) == true && imeVisible() == true
        }
        assertEquals("reopening search should clear the previous query", "", composeRule.onNode(searchFieldMatcher())
            .assertIsFocused().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertTrue(
            "closing and reopening search retained its old filter",
            captureSongBounds(playlistList().fetchSemanticsNode().boundsInRoot).any { it.index == 0 }
        )
        searchToggle().performClick()
        composeRule.waitUntil(UiTimeoutMs) { allSearchFields().fetchSemanticsNodes().isEmpty() && imeVisible() == false }
        composeRule.mainClock.autoAdvance = false
        try {
            searchToggle().performClick()
            repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
            searchToggle().performClick()
            composeRule.mainClock.advanceTimeBy(800L)
            composeRule.waitForIdle()
            assertTrue("rapidly closing search left its input composed", allSearchFields().fetchSemanticsNodes().isEmpty())
            assertEquals("cancelled search autofocus reopened IME", false, imeVisible())
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun configureAutoShowKeyboard(enabled: Boolean) {
        runBlocking {
            previousAutoShowKeyboard = AppContainer.settingsRepo.autoShowKeyboardFlow.first()
            AppContainer.settingsRepo.setAutoShowKeyboard(enabled)
        }
    }

    private fun searchToggle(): SemanticsNodeInteraction = composeRule.onNode(
        hasContentDescription(context.getString(CoreCommonR.string.cd_search_songs))
    )

    private fun searchFieldMatcher(): SemanticsMatcher = hasSetTextAction().and(
        SemanticsMatcher("visible search field") { node ->
            node.layoutInfo.isPlaced && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f
        }
    )

    private fun searchFields() = composeRule.onAllNodes(searchFieldMatcher())

    private fun allSearchFields() = composeRule.onAllNodes(hasSetTextAction())

    private fun imeVisible(): Boolean? = composeRule.runOnUiThread {
        hostView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) }
    }

    private fun captureSearchFrame(phase: String, frame: Int): SearchFrameSample {
        val layout = captureFrame(
            phase,
            frame,
            captureAllSongs = true,
            captureScreenshot = phase == "search-before" || frame in 1..3 || frame == SearchTransitionFrameCount
        )
        val playButton = composeRule.onNode(
            hasContentDescription(context.getString(CoreCommonR.string.player_play_all))
                .or(hasContentDescription(context.getString(CoreCommonR.string.player_shuffle_play)))
        ).fetchSemanticsNode()
        return SearchFrameSample(
            phase = phase,
            frame = frame,
            testClockTimeMs = layout.testClockTimeMs,
            listTop = layout.listTop,
            firstVisibleIndex = layout.firstVisibleIndex,
            scrollOffsetPx = layout.scrollOffsetPx,
            cover = requireNotNull(coverBounds()),
            playButton = Rect(
                left = playButton.positionInRoot.x,
                top = playButton.positionInRoot.y,
                right = playButton.positionInRoot.x + playButton.size.width,
                bottom = playButton.positionInRoot.y + playButton.size.height
            ),
            firstSong = layout.songs.single { it.index == 0 },
            inputFocused = allSearchFields().fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Focused) == true
            },
            imeVisible = imeVisible()
        )
    }

    private fun captureSettledFrame(phase: String): HeaderFrameSample {
        val previousAutoAdvance = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = false
        return try {
            captureFrame(phase, 0, captureAllSongs = true)
        } finally {
            composeRule.mainClock.autoAdvance = previousAutoAdvance
        }
    }

    private fun verifyOrdinaryBrowsingPlacesUpdatedOrderImmediately(style: UiStyle) {
        createAndShowPlaylist(style, hostedScene = true)
        playlistList().performScrollToIndex(StartingItemIndex)
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        val initial = captureFrame("plain-order-before", 0, captureAllSongs = true)
        val upperIndex = 3
        val lowerIndex = 4
        val upperSlot = initial.songs.single { it.index == upperIndex }
        val lowerSlot = initial.songs.single { it.index == lowerIndex }
        val initialAnchor = initial.songs.single { it.index == StartingItemIndex - 2 }
        val tolerance = PixelToleranceDp * oneDpPx
        assertTrue(
            "the order-update fixture did not expose both complete non-anchor songs: $initial",
            abs(upperSlot.visibleTop - upperSlot.top) <= tolerance &&
                abs(upperSlot.visibleBottom - upperSlot.bottom) <= tolerance &&
                abs(lowerSlot.visibleTop - lowerSlot.top) <= tolerance &&
                abs(lowerSlot.visibleBottom - lowerSlot.bottom) <= tolerance
        )
        val newOrder = songs.toMutableList().apply {
            this[upperIndex] = songs[lowerIndex]
            this[lowerIndex] = songs[upperIndex]
        }
        val samples = mutableListOf<OrdinaryOrderFrame>()
        try {
            runBlocking {
                withTimeout(RepositoryTimeoutMs) {
                    assertTrue(
                        "owned playlist rejected its expected-order update",
                        repository.reorderSongs(
                            playlistId = requireNotNull(ownedPlaylistId),
                            newOrder = newOrder.map { it.identity() },
                            expectedOrder = songs.map { it.identity() }
                        )
                    )
                }
            }
            repeat(OrderUpdateFrameCount) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                samples += OrdinaryOrderFrame(
                    layout = captureFrame("plain-order-after", frame + 1, captureAllSongs = true),
                    originalUpperLabel = songRowNumber(songs[upperIndex]),
                    originalLowerLabel = songRowNumber(songs[lowerIndex]),
                    repositoryOrder = repository.playlists.value
                        .single { it.id == ownedPlaylistId }.songs.map { it.id }
                )
            }
        } finally {
            requireNotNull(evidenceDirectory).resolve("plain-order-frames.txt").writeText(
                "style=$style, initial=$initial, expectedOrder=${newOrder.map { it.id }}\n" +
                    samples.joinToString("\n")
            )
            composeRule.mainClock.autoAdvance = true
        }
        val diagnostics = "evidence=${evidenceDirectory?.absolutePath}\n" + samples.joinToString("\n")
        val consumed = samples.firstOrNull {
            it.originalUpperLabel == lowerIndex + 1 && it.originalLowerLabel == upperIndex + 1
        }
        assertTrue("UI did not consume the new index labels in sampled frames: $diagnostics", consumed != null)
        val firstUpdated = requireNotNull(consumed)
        assertEquals("repository did not publish the requested song order: $diagnostics", newOrder.map { it.id }, firstUpdated.repositoryOrder)
        assertEquals("updating non-anchor songs changed the first visible item: $diagnostics", initial.firstVisibleIndex, firstUpdated.layout.firstVisibleIndex)
        assertEquals("updating non-anchor songs changed the scroll offset: $diagnostics", initial.scrollOffsetPx, firstUpdated.layout.scrollOffsetPx, tolerance)
        val placedUpper = firstUpdated.layout.songs.single { it.index == upperIndex }
        val placedLower = firstUpdated.layout.songs.single { it.index == lowerIndex }
        val placedAnchor = firstUpdated.layout.songs.single { it.index == initialAnchor.index }
        assertTrue(
            "new row numbers were shown while ordinary browsing retained the previous placement: $diagnostics",
            abs(placedUpper.top - lowerSlot.top) <= tolerance &&
                abs(placedLower.top - upperSlot.top) <= tolerance
        )
        assertEquals("non-anchor order update moved the anchor row: $diagnostics", initialAnchor.top, placedAnchor.top, tolerance)
    }

    private fun songRowNumber(song: SongItem): Int? {
        val row = composeRule.onNode(
            hasClickAction().and(hasAnyDescendant(hasText(song.name))),
            useUnmergedTree = true
        ).fetchSemanticsNode()
        fun number(node: SemanticsNode): Int? {
            node.config.getOrNull(SemanticsProperties.Text)
                ?.firstNotNullOfOrNull { it.text.toIntOrNull() }?.let { return it }
            return node.children.firstNotNullOfOrNull { number(it) }
        }
        return number(row)
    }

    private fun verifyHostedFastFlingAndReverse(style: UiStyle) {
        createAndShowPlaylist(style, hostedScene = true)
        val list = playlistList()
        list.performScrollToIndex(1)
        composeRule.waitForIdle()
        val root = composeRule.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val samples = mutableListOf<HeaderFrameSample>()
        var pointerDown = false
        composeRule.mainClock.autoAdvance = false
        fun sample(phase: String, frame: Int) {
            samples += captureFrame(phase, frame, captureAllSongs = true)
        }
        fun drag(phase: String, distanceDp: Float, frameCount: Int) {
            val start = Offset(
                x = listBounds.center.x - rootBounds.left,
                y = listBounds.top - rootBounds.top +
                    listBounds.height * if (distanceDp > 0f) 0.3f else 0.6f
            )
            root.performTouchInput { down(start) }
            pointerDown = true
            repeat(frameCount) { frame ->
                root.performTouchInput {
                    moveTo(
                        start + Offset(0f, distanceDp * oneDpPx * (frame + 1) / frameCount),
                        delayMillis = FrameDurationMs
                    )
                }
                composeRule.mainClock.advanceTimeByFrame()
                sample(phase, frame + 1)
            }
            root.performTouchInput { up() }
            pointerDown = false
        }
        fun release(phase: String, frameCount: Int) {
            repeat(frameCount) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                sample(phase, frame + 1)
            }
        }
        try {
            sample("fast-start", 0)
            drag("fast-up", distanceDp = -144f, frameCount = 3)
            release("fast-up-release", frameCount = 6)
            drag("fast-down", distanceDp = 120f, frameCount = 3)
            release("fast-down-release", frameCount = 30)
            drag("fast-second-pull", distanceDp = 180f, frameCount = 4)
            release("fast-second-release", frameCount = 12)
            drag("fast-final-up", distanceDp = -144f, frameCount = 3)
            release("fast-final-release", frameCount = 12)
        } finally {
            requireNotNull(evidenceDirectory).resolve("fast-frames.txt").writeText(
                "style=$style, hosted=true, widthDp=360, heightDp=780\n" +
                    samples.joinToString("\n") + "\nflings:\n" + flingSamples.joinToString("\n")
            )
            if (pointerDown) root.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
        val diagnostics = "evidence=${evidenceDirectory?.absolutePath}\n" +
            samples.joinToString("\n") + "\nflings:\n" + flingSamples.joinToString("\n")
        val tolerance = PixelToleranceDp * oneDpPx
        assertTrue(
            "fast upward fling never moved past the header and first song: $diagnostics",
            samples.any { it.phase == "fast-up-release" && it.firstVisibleIndex >= 3 }
        )
        assertTrue("fixture song order changed during the gesture: $diagnostics", samples.all { it.repositoryOrderUnchanged })
        assertTrue("visible song geometry was not captured: $diagnostics", samples.all { it.songs.size >= 2 })
        samples.forEach { frame ->
            val ordered = frame.songs.sortedBy { it.index }
            ordered.zipWithNext().forEach { (previous, next) ->
                assertEquals("visible song identities skipped an item: $frame\n$diagnostics", previous.index + 1, next.index)
                assertTrue(
                    "visible songs crossed or separated during scrolling: $frame\n$diagnostics",
                    abs(next.top - previous.bottom) <= tolerance
                )
            }
            if (!frame.overscrollInProgress && frame.firstVisibleIndex >= 2) {
                assertTrue(
                    "first song detached from its lazy scroll position: $frame\n$diagnostics",
                    frame.firstSongTop != null &&
                        abs(frame.firstSongTop - (frame.listTop - frame.scrollOffsetPx)) <= tolerance
                )
            }
        }
        samples.zipWithNext().forEach { (previous, next) ->
            val previousByIndex = previous.songs.associateBy { it.index }
            val movements = next.songs.mapNotNull { song ->
                previousByIndex[song.index]?.let { before -> song.top - before.top }
            }
            if (movements.size >= 2) {
                assertTrue(
                    "visible songs moved by different distances in the same frame: $previous\n$next\n$diagnostics",
                    requireNotNull(movements.maxOrNull()) - requireNotNull(movements.minOrNull()) <= tolerance
                )
            }
        }
    }

    private fun verifyHeaderReentry(
        style: UiStyle,
        releaseDuringOverscroll: Boolean = false,
        hostedScene: Boolean = false
    ) {
        createAndShowPlaylist(style, hostedScene)
        val list = playlistList()
        val restingList = list.fetchSemanticsNode().boundsInRoot
        val restingCover = coverBounds()
        checkNotNull(restingCover)
        coverInsetPx = restingCover.top - restingList.top
        list.performScrollToIndex(StartingItemIndex)
        composeRule.waitForIdle()
        val root = composeRule.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val start = Offset(
            x = listBounds.center.x - rootBounds.left,
            y = listBounds.top - rootBounds.top + listBounds.height * 0.12f
        )
        val distance = (384f * oneDpPx).coerceAtMost(listBounds.height * 0.72f)
        val step = distance / GestureFrameCount
        val samples = mutableListOf<HeaderFrameSample>()
        var pointerDown = false
        composeRule.mainClock.autoAdvance = false
        try {
            samples += captureFrame("start", 0)
            root.performTouchInput { down(start) }
            pointerDown = true
            repeat(GestureFrameCount) { frame ->
                root.performTouchInput {
                    moveTo(start + Offset(0f, step * (frame + 1)), delayMillis = FrameDurationMs)
                }
                composeRule.mainClock.advanceTimeByFrame()
                samples += captureFrame("pull", frame + 1)
            }
            if (releaseDuringOverscroll) {
                root.performTouchInput {
                    moveTo(start + Offset(0f, distance), delayMillis = HoldDurationMs)
                }
                composeRule.mainClock.advanceTimeBy(HoldDurationMs)
                samples += captureFrame("hold", 1)
            }
            val reverseFrameCount = if (releaseDuringOverscroll) ShortReverseFrameCount else GestureFrameCount
            val reverseStep = if (releaseDuringOverscroll) ShortReverseStepDp * oneDpPx else step
            repeat(reverseFrameCount) { frame ->
                root.performTouchInput {
                    moveTo(
                        start + Offset(0f, distance - reverseStep * (frame + 1)),
                        delayMillis = FrameDurationMs
                    )
                }
                composeRule.mainClock.advanceTimeByFrame()
                samples += captureFrame("reverse", frame + 1)
            }
            root.performTouchInput { up() }
            pointerDown = false
            val releaseFrameCount = if (releaseDuringOverscroll) ShortReverseReleaseFrameCount else ReleaseFrameCount
            repeat(releaseFrameCount) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                samples += captureFrame("release", frame + 1)
            }
        } finally {
            requireNotNull(evidenceDirectory).resolve("frames.txt").writeText(
                "style=$style, shortReverse=$releaseDuringOverscroll, stepPx=$step, coverInsetPx=$coverInsetPx\n" +
                    samples.joinToString("\n") + "\nflings:\n" + flingSamples.joinToString("\n")
            )
            if (pointerDown) root.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }

        val diagnostics = "evidence=${evidenceDirectory?.absolutePath}\n" +
            samples.joinToString("\n") + "\nflings:\n" + flingSamples.joinToString("\n")
        assertEquals("fixture did not start at the second song: $diagnostics", StartingItemIndex, samples.first().firstVisibleIndex)
        assertTrue("pull did not bring the header back into view: $diagnostics", samples.any { it.phase == "pull" && it.firstVisibleIndex == 0 })
        if (releaseDuringOverscroll) {
            assertTrue(
                "short reverse did not release with a visible overscroll offset: $diagnostics",
                samples.last { it.phase == "reverse" }.overscrollInProgress
            )
            assertTrue(
                "short reverse did not produce a reverse fling velocity: $diagnostics",
                flingSamples.any { it.incoming.y < -oneDpPx }
            )
            assertTrue(
                "released reverse fling did not leave the logical top: $diagnostics",
                samples.any {
                    it.phase == "release" && it.isAwayFromTop(PixelToleranceDp * oneDpPx)
                }
            )
            assertTrue(
                "reverse fling scrolled the header away while its viewport still had overscroll translation: $diagnostics",
                samples.none {
                    it.phase == "release" && it.overscrollInProgress &&
                        it.isAwayFromTop(PixelToleranceDp * oneDpPx)
                }
            )
        } else {
            assertTrue("reverse gesture did not collapse the header again: $diagnostics", samples.any { it.phase == "reverse" && it.firstVisibleIndex >= 2 })
        }
        assertTrue(
            "pull produced overscroll before reaching the playlist start: $diagnostics",
            samples.none { it.phase == "pull" && it.firstVisibleIndex > 0 && it.overscrollInProgress }
        )
        samples.filter { !it.overscrollInProgress }.forEach { sample ->
            if (sample.firstVisibleIndex >= 2) {
                val rowTop = sample.firstSongTop
                assertTrue("first visible song was missing: $sample\n$diagnostics", rowTop != null)
                assertTrue(
                    "first visible song left an empty gap above the list: $sample\n$diagnostics",
                    checkNotNull(rowTop) <= sample.listTop + PixelToleranceDp * oneDpPx
                )
            } else if (sample.firstVisibleIndex == 0) {
                val expectedTop = sample.listTop - sample.scrollOffsetPx + coverInsetPx
                assertTrue("header cover was missing while its item was visible: $sample\n$diagnostics", sample.coverTop != null)
                assertTrue(
                    "header cover jumped away from its lazy item position: $sample\n$diagnostics",
                    abs(checkNotNull(sample.coverTop) - expectedTop) <= PixelToleranceDp * oneDpPx
                )
            }
        }
    }

    private fun createAndShowPlaylist(style: UiStyle, hostedScene: Boolean) {
        val token = UUID.randomUUID().toString().take(8)
        val hostLabel = if (hostedScene) "hosted" else "direct"
        val directory = File(context.cacheDir, "playlist-header-scroll-${style.name.lowercase()}-$hostLabel-$token")
        check(directory.mkdirs())
        evidenceDirectory = directory
        val cover = File(directory, "fixture-cover.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(AndroidColor.rgb(232, 64, 176))
            cover.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        coverFile = cover
        val background = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setPixel(x, y, if ((x / 4 + y / 4) % 2 == 0) AndroidColor.DKGRAY else AndroidColor.GRAY)
                }
            }
        }
        backgroundBitmap = background
        val backgroundImage = File(directory, "fixture-background.png")
        backgroundImage.outputStream().use { output ->
            check(background.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        backgroundFile = backgroundImage
        val baseId = System.currentTimeMillis() * 1_000L
        songs = List(SongCount) { index ->
            SongItem(
                id = baseId + index,
                name = "header-$token-song-${index + 1}",
                artist = "synthetic artist",
                album = "header-scroll-$token",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = cover.absolutePath
            )
        }
        val playlist = runBlocking {
            withTimeout(RepositoryTimeoutMs) {
                assertTrue("local playlist repository failed to initialize", repository.awaitInitialized())
                repository.createPlaylistWithPreparedSongs("Header$token", songs)
            }
        }
        playlistName = playlist.name
        ownedPlaylistId = playlist.id
        runBlocking {
            previousBackgroundUri = AppContainer.settingsRepo.backgroundImageUriFlow.first()
            AppContainer.settingsRepo.setBackgroundImageUri(backgroundImage.toURI().toString())
            backgroundWasChanged = true
        }
        val recordingFactory = object : OverscrollFactory {
            override fun createOverscrollEffect(): OverscrollEffect {
                val actual = AdvancedGlassOverscrollFactory.createOverscrollEffect()
                overscrollEffects += actual
                return object : OverscrollEffect by actual {
                    override suspend fun applyToFling(
                        velocity: Velocity,
                        performFling: suspend (Velocity) -> Velocity
                    ) {
                        val wasInProgress = actual.isInProgress
                        actual.applyToFling(velocity) { forwarded ->
                            flingSamples += FlingVelocitySample(
                                testClockTimeMs = composeRule.mainClock.currentTime,
                                incoming = velocity,
                                forwarded = forwarded,
                                overscrollBeforeFling = wasInProgress
                            )
                            performFling(forwarded)
                        }
                    }
                }
            }

            override fun equals(other: Any?): Boolean = this === other

            override fun hashCode(): Int = System.identityHashCode(this)
        }
        screenVisible.value = true
        composeRule.setContent {
            if (!fixtureVisible.value) return@setContent
            if (hostedScene) {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    screenWidthDp = 360
                    screenHeightDp = 780
                    smallestScreenWidthDp = 360
                }
                FittedTestViewport(360.dp, 780.dp, fontScale = 1f) {
                    CompositionLocalProvider(
                        LocalConfiguration provides configuration,
                        LocalHasCustomBackground provides true
                    ) {
                        RenderFixture(style, background, playlist.id, recordingFactory, hostedScene = true)
                    }
                }
            } else {
                RenderFixture(style, background, playlist.id, recordingFactory, hostedScene = false)
            }
        }
        screenMounted = true
        if (hostedScene) {
            composeRule.onNodeWithText(OpenFixtureLabel).performClick()
        }
        composeRule.waitUntil(UiTimeoutMs) {
            composeRule.onAllNodesWithText(playlist.name).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun RenderFixture(
        style: UiStyle,
        background: Bitmap,
        playlistId: Long,
        recordingFactory: OverscrollFactory,
        hostedScene: Boolean
    ) {
        NeriTheme(
            followSystemDark = false,
            forceDark = true,
            dynamicColor = false,
            seedColorHex = "C78F67",
            uiStyle = style,
            animateColors = false
        ) {
            oneDpPx = with(LocalDensity.current) { 1.dp.toPx() }
            hostView = LocalView.current
            val backgroundBackdrop = rememberAdvancedGlassBackdrop()
            val contentBackdrop = rememberAdvancedGlassBackdrop()
            val controller = AdvancedGlassController(
                sdkInt = Build.VERSION.SDK_INT,
                advancedBlurEnabled = true,
                enhancedAdvancedBlurEnabled = true,
                backendReady = true
            )
            AdvancedGlassHost(
                controller = controller,
                backgroundBackdrop = backgroundBackdrop,
                contentBackdrop = contentBackdrop
            ) {
                CompositionLocalProvider(LocalOverscrollFactory provides recordingFactory) {
                    Box(Modifier.fillMaxSize()) {
                        Image(
                            bitmap = background.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize().captureAdvancedGlassBackdrop(backgroundBackdrop)
                        )
                        Box(Modifier.fillMaxSize().captureAdvancedGlassBackdrop(contentBackdrop)) {
                            val navController = rememberNavController()
                            val route = if (hostedScene) Destinations.Library.route else FixtureRoute
                            NavHost(navController, startDestination = route) {
                                composable(route) {
                                    if (screenVisible.value) {
                                        if (hostedScene) {
                                            HostedPlaylistScene(playlistId, controller, recordingFactory)
                                        } else {
                                            LocalPlaylistDetailScreen(
                                                playlistId = playlistId,
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
                }
            }
        }
    }

    @Composable
    private fun HostedPlaylistScene(
        playlistId: Long,
        controller: AdvancedGlassController,
        recordingFactory: OverscrollFactory
    ) {
        var selected by remember { mutableStateOf<LibrarySelectedItem?>(null) }
        val transition = rememberHostPredictiveBackTransition(
            targetState = libraryNavigationScene(selected, emptyList()),
            backEnabled = selected != null,
            backTargetState = libraryNavigationScene(null, emptyList()),
            onBack = { selected = null },
            label = "playlist_header_host"
        )
        val renderer = AppNavigationSceneRenderer(
            advancedGlassController = controller,
            backgroundImageUri = requireNotNull(backgroundFile).toURI().toString(),
            backgroundImageBlur = 0f,
            effectiveBackgroundImageAlpha = 1f,
            coherentFeedbackEnabled = false,
            currentRoute = Destinations.Library.route,
            visibleNavigationRoutes = setOf(Destinations.Library.route),
            mainTabNavigationMotion = {
                resolveMainTabNavigationMotionState(MainTabBackgroundMotion.NONE, 0f)
            }
        )
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            transition.AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                contentKey = { it.item },
                transitionSpec = {
                    advancedGlassHostNavigationTransition(
                        forward = targetState.navigationDepth > initialState.navigationDepth,
                        coherentFeedbackEnabled = false,
                        targetContentZIndex = targetState.navigationDepth.toFloat()
                    ).using(SizeTransform(clip = true))
                }
            ) { scene ->
                val motion = transition.animateAdvancedGlassSceneMotion(
                    sceneState = scene,
                    coherentFeedbackEnabled = false,
                    navigationDepth = { it.navigationDepth },
                    label = "playlist_header_host_scene"
                )
                renderer.RenderMainTabNavigationScene(
                    revealTopFraction = motion.revealTopFraction,
                    contentTranslationYFraction = motion.contentTranslationYFraction,
                    contentScale = motion.contentScale,
                    sceneDepth = scene.navigationDepth
                ) {
                    CompositionLocalProvider(LocalOverscrollFactory provides recordingFactory) {
                        if (scene.item is LibrarySelectedItem.Local) {
                            LocalPlaylistDetailScreen(
                                playlistId = playlistId,
                                onBack = { selected = null },
                                onDeleted = { screenVisible.value = false },
                                offlineMode = true
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Button(onClick = { selected = LibrarySelectedItem.Local(playlistId) }) {
                                    Text(OpenFixtureLabel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun captureFrame(
        phase: String,
        frame: Int,
        captureAllSongs: Boolean = false,
        captureScreenshot: Boolean = true
    ): HeaderFrameSample {
        val testClockTimeMs = composeRule.mainClock.currentTime
        val list = playlistList().fetchSemanticsNode()
        val position = list.config[VerticalScrollAxisRange].value()
        val index = floor(position / LazyItemSemanticsStep).toInt()
        val row = songs.getOrNull(index - 2)?.let { song ->
            composeRule.onAllNodes(
                hasClickAction().and(hasAnyDescendant(hasText(song.name))),
                useUnmergedTree = true
            ).fetchSemanticsNodes().singleOrNull()
        }
        val cover = coverBounds()
        val sample = HeaderFrameSample(
            phase = phase,
            frame = frame,
            testClockTimeMs = testClockTimeMs,
            firstVisibleIndex = index,
            scrollOffsetPx = position - index * LazyItemSemanticsStep,
            listTop = list.boundsInRoot.top,
            firstSongTop = row?.positionInRoot?.y,
            coverTop = cover?.top,
            overscrollInProgress = composeRule.runOnUiThread { overscrollEffects.any { it.isInProgress } },
            songs = if (captureAllSongs) captureSongBounds(list.boundsInRoot) else emptyList(),
            repositoryOrderUnchanged = if (captureAllSongs) {
                repository.playlists.value.singleOrNull { it.id == ownedPlaylistId }
                    ?.songs?.map { it.id } == songs.map { it.id }
            } else {
                true
            }
        )
        if (captureScreenshot) {
            val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
            requireNotNull(evidenceDirectory).resolve("$phase-${frame.toString().padStart(3, '0')}.png")
                .outputStream().use { output ->
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
        }
        assertEquals("frame capture advanced a paused animation clock", testClockTimeMs, composeRule.mainClock.currentTime)
        return sample
    }

    private fun captureSongBounds(viewport: Rect): List<SongFrameBounds> {
        val names = songs.mapIndexed { index, song -> song.name to index }.toMap()
        fun songIndex(node: SemanticsNode): Int? {
            node.config.getOrNull(SemanticsProperties.Text)
                ?.firstNotNullOfOrNull { names[it.text] }?.let { return it }
            return node.children.firstNotNullOfOrNull { songIndex(it) }
        }
        return composeRule.onAllNodes(
            hasClickAction().and(hasAnyDescendant(hasText("header-", substring = true))),
            useUnmergedTree = true
        ).fetchSemanticsNodes().mapNotNull { node ->
            val index = songIndex(node) ?: return@mapNotNull null
            val visible = node.boundsInRoot
            if (!node.layoutInfo.isPlaced || visible.bottom <= viewport.top ||
                visible.top >= viewport.bottom || visible.width <= 0f || visible.height <= 0f
            ) return@mapNotNull null
            SongFrameBounds(
                index = index,
                top = node.positionInRoot.y,
                bottom = node.positionInRoot.y + node.size.height,
                visibleTop = visible.top,
                visibleBottom = visible.bottom
            )
        }
    }

    private fun coverBounds(): Rect? = composeRule.onAllNodes(
        hasContentDescription(playlistName),
        useUnmergedTree = true
    ).fetchSemanticsNodes().singleOrNull()?.let { node ->
        Rect(
            left = node.positionInRoot.x,
            top = node.positionInRoot.y,
            right = node.positionInRoot.x + node.size.width,
            bottom = node.positionInRoot.y + node.size.height
        )
    }

    private fun playlistList(): SemanticsNodeInteraction = composeRule.onNode(
        hasScrollToIndexAction().and(SemanticsMatcher.keyIsDefined(VerticalScrollAxisRange))
    )

    private data class HeaderFrameSample(
        val phase: String,
        val frame: Int,
        val testClockTimeMs: Long,
        val firstVisibleIndex: Int,
        val scrollOffsetPx: Float,
        val listTop: Float,
        val firstSongTop: Float?,
        val coverTop: Float?,
        val overscrollInProgress: Boolean,
        val songs: List<SongFrameBounds> = emptyList(),
        val repositoryOrderUnchanged: Boolean = true
    ) {
        fun isAwayFromTop(tolerancePx: Float): Boolean =
            firstVisibleIndex > 0 || scrollOffsetPx > tolerancePx
    }

    private data class SongFrameBounds(
        val index: Int,
        val top: Float,
        val bottom: Float,
        val visibleTop: Float,
        val visibleBottom: Float
    )

    private data class OrdinaryOrderFrame(
        val layout: HeaderFrameSample,
        val originalUpperLabel: Int?,
        val originalLowerLabel: Int?,
        val repositoryOrder: List<Long>
    )

    private data class SearchFrameSample(
        val phase: String,
        val frame: Int,
        val testClockTimeMs: Long,
        val listTop: Float,
        val firstVisibleIndex: Int,
        val scrollOffsetPx: Float,
        val cover: Rect,
        val playButton: Rect,
        val firstSong: SongFrameBounds,
        val inputFocused: Boolean,
        val imeVisible: Boolean?
    )

    private data class FlingVelocitySample(
        val testClockTimeMs: Long,
        val incoming: Velocity,
        val forwarded: Velocity,
        val overscrollBeforeFling: Boolean
    )

    private companion object {
        const val FixtureRoute = "playlist-header-scroll"
        const val OpenFixtureLabel = "Open fixture playlist"
        const val SongCount = 31
        const val StartingItemIndex = 3
        const val OrderUpdateFrameCount = 24
        const val SearchTransitionFrameCount = 48
        const val SearchMaximumFrameStepDp = 16f
        const val GestureFrameCount = 32
        const val ReleaseFrameCount = 8
        const val ShortReverseReleaseFrameCount = 40
        const val ShortReverseFrameCount = 3
        const val ShortReverseStepDp = 12f
        const val HoldDurationMs = 128L
        const val FrameDurationMs = 16L
        const val LazyItemSemanticsStep = 500f
        const val PixelToleranceDp = 2f
        const val UiTimeoutMs = 5_000L
        const val RepositoryTimeoutMs = 10_000L
    }
}
