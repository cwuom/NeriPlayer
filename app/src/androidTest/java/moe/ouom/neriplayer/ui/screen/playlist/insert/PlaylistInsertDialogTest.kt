package moe.ouom.neriplayer.ui.screen.playlist.insert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollBackdrop
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollFactory
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassOverscrollBackdrop
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PlaylistInsertDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val artworkFiles = mutableListOf<File>()
    private val gestureEffects = mutableListOf<OverscrollEffect>()
    private val gestureFlings = mutableListOf<GestureFling>()
    private lateinit var parentBackdropOffset: MutableState<Float>
    private var restingPreviewTopPx = 0f
    private var compactHeaderGapPx = 0f

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun removeSyntheticArtwork() {
        composeRule.mainClock.autoAdvance = true
        artworkFiles.forEach { it.delete() }
    }

    @Test
    fun previewShowsRealMetadataAndFinalPositionsWithoutCommitting() {
        val songs = demoSongs()
        val selectedKeys = setOf(songs[1].stableKey(), songs[4].stableKey())
        var confirmed: PlaylistInsertPreview? = null
        showDialog(songs, selectedKeys) { confirmed = it }

        replaceInput("2")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        savePreviewScreenshot()

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_title)).assertIsDisplayed()
        composeRule.onNodeWithText("星河漫游").assertIsDisplayed()
        composeRule.onNodeWithText("青岚").assertIsDisplayed()
        composeRule.onNodeWithTag("playlist-insert-artwork-${songs[1].stableKey()}").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val pixels = composeRule.onNodeWithTag("playlist-insert-artwork-${songs[1].stableKey()}")
                .captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 8].toArgb() == 0xFF6976BC.toInt()
        }
        savePreviewScreenshot()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.runOnIdle {
            assertNull(confirmed)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), songs.map { it.id })
        }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        composeRule.onNodeWithText("晨间列车").assertIsDisplayed()
        composeRule.onNodeWithText("4").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        savePreviewScreenshot()

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).performClick()
        composeRule.runOnIdle {
            val result = requireNotNull(confirmed)
            assertEquals(2, result.startPosition)
            assertEquals(listOf(songs[1].stableKey(), songs[4].stableKey()), result.movedKeys)
            assertEquals(
                listOf(songs[0], songs[1], songs[4], songs[2], songs[3], songs[5]).map { it.stableKey() },
                result.orderedKeys
            )
        }
    }

    @Test
    fun changingInputRequiresAnotherPreviewBeforeConfirmation() {
        val songs = demoSongs()
        var confirmed: PlaylistInsertPreview? = null
        showDialog(songs, setOf(songs[2].stableKey())) { confirmed = it }
        replaceInput("2")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_edit_position)).performClick()
        composeRule.onNode(hasSetTextAction()).assertTextContains("2")
        replaceInput("4")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.runOnIdle { assertNull(confirmed) }

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).performClick()
        composeRule.runOnIdle { assertEquals(4, confirmed?.startPosition) }
    }

    @Test
    fun changingSourceInvalidatesPreviewAndUsesTheNewSnapshot() {
        val songs = demoSongs()
        val source = mutableStateOf(songs)
        val selectedKeys = setOf(songs[2].stableKey())
        var confirmed: PlaylistInsertPreview? = null
        composeRule.setContent {
            MaterialTheme {
                PlaylistInsertDialog(source.value, selectedKeys, {}, { confirmed = it }, offlineMode = true)
            }
        }
        replaceInput("2")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()

        composeRule.runOnIdle { source.value = songs.reversed() }
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_stale)).assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.runOnIdle { assertNull(confirmed) }
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).performClick()
        composeRule.runOnIdle { assertEquals(songs.reversed().map { it.stableKey() }, confirmed?.sourceKeys) }
    }

    @Test
    fun emptyAndOutOfRangeCannotBeConfirmed() {
        val songs = demoSongs()
        var confirmCount = 0
        showDialog(songs, setOf(songs[0].stableKey())) { confirmCount += 1 }

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
        replaceInput("0")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
        replaceInput("7")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, confirmCount) }
    }

    @Test
    fun removingSelectedSongInvalidatesAndDisablesPreview() {
        val songs = demoSongs()
        val source = mutableStateOf(songs)
        var confirmCount = 0
        composeRule.setContent {
            MaterialTheme {
                PlaylistInsertDialog(
                    source.value,
                    setOf(songs[2].stableKey()),
                    {},
                    { confirmCount += 1 },
                    offlineMode = true
                )
            }
        }
        replaceInput("2")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.runOnIdle { source.value = songs.filterNot { it == songs[2] } }

        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).assertIsNotEnabled()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_invalid_selection)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, confirmCount) }
    }

    @Test
    fun longSelectionCanReachTheFollowingContextAndKeepConfirmAvailable() {
        val coverUrl = syntheticCover(100, 0xFF75A5B9.toInt())
        val songs = (1L..80L).map { index ->
            SongItem(
                id = index,
                name = "曲目 $index",
                artist = "测试歌手 $index",
                album = "preview-test",
                albumId = 1L,
                durationMs = 180_000L,
                coverUrl = coverUrl
            )
        }
        val selectedKeys = songs.subList(10, 70).mapTo(mutableSetOf()) { it.stableKey() }
        var confirmed: PlaylistInsertPreview? = null
        showDialog(songs, selectedKeys) { confirmed = it }
        replaceInput("5")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(67)
        composeRule.onNodeWithText("曲目 5").assertIsDisplayed()
        composeRule.onNodeWithText("65").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(confirmed) }
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action))
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            val result = requireNotNull(confirmed)
            assertEquals(60, result.movedKeys.size)
            assertEquals(songs.subList(10, 70).map { it.stableKey() }, result.movedKeys)
            assertEquals(5, result.startPosition)
            assertEquals(songs.map { it.stableKey() }.toSet(), result.orderedKeys.toSet())
        }
    }

    @Test
    fun previewIncludesFiveSongsOnEachSideAndReportsTheRemainingCounts() {
        val coverUrl = syntheticCover(200, 0xFF947BAF.toInt())
        val songs = (1L..20L).map { index ->
            SongItem(
                id = index,
                name = "上下文曲目 $index",
                artist = "上下文歌手",
                album = "preview-test",
                albumId = 1L,
                durationMs = 180_000L,
                coverUrl = coverUrl
            )
        }
        val selectedKeys = songs.subList(8, 10).mapTo(mutableSetOf()) { it.stableKey() }
        showDialog(songs, selectedKeys) {}
        replaceInput("9")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithText(
            context.resources.getQuantityString(CoreCommonR.plurals.playlist_insert_omitted_before, 3, 3)
        ).assertIsDisplayed()
        for (index in 4..8) {
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(index - 2)
            composeRule.onNodeWithText("上下文曲目 $index").assertIsDisplayed()
        }
        for (index in 11..15) {
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
            composeRule.onNodeWithText("上下文曲目 $index").assertIsDisplayed()
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(16)
        composeRule.onNodeWithText(
            context.resources.getQuantityString(CoreCommonR.plurals.playlist_insert_omitted_after, 5, 5)
        ).assertIsDisplayed()
        composeRule.onNodeWithText("上下文曲目 3").assertDoesNotExist()
        composeRule.onNodeWithText("上下文曲目 16").assertDoesNotExist()
    }

    @Test
    fun firstPositionPreviewRestoresScrollingAfterTopPullAndReverseRelease() {
        verifyFirstPositionBoundaryGesture(top = true)
    }

    @Test
    fun firstPositionPreviewRestoresScrollingAfterBottomPullAndReverseRelease() {
        verifyFirstPositionBoundaryGesture(top = false)
    }

    @Test
    fun pullingPreviewDoesNotMoveParentPlaylistBackdrop() {
        val songs = gestureSongs()
        showGestureDialog(songs, setOf(songs[40].stableKey())) {}
        replaceInput("1")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        val list = composeRule.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(0)
        captureRestingPreviewGeometry()
        val restingLabelY = boundaryLabelY(top = true)
        composeRule.runOnIdle { assertEquals(0f, parentBackdropOffset.value, 0.01f) }
        composeRule.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            list.performTouchInput {
                down(Offset(center.x, height * 0.2f))
                pointerDown = true
                moveTo(Offset(center.x, height * 0.75f), delayMillis = 64)
            }
            advanceGestureFrames(2)
            assertTrue("dialog content did not visibly pull down", boundaryLabelY(top = true) - restingLabelY > 1f)
            composeRule.runOnIdle { assertTrue(gestureEffects.last().isInProgress) }
            savePreviewScreenshot("playlist-insert-preview-pulling.png")
            composeRule.runOnIdle {
                assertEquals("dialog pull changed the parent playlist header fill", 0f, parentBackdropOffset.value, 0.01f)
            }
            list.performTouchInput {
                up()
                pointerDown = false
            }
            repeat(10) {
                advanceGestureFrames(1)
                composeRule.runOnIdle {
                    assertEquals("dialog return changed the parent playlist header fill", 0f, parentBackdropOffset.value, 0.01f)
                }
            }
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            assertPreviewHasNoOverscroll()
            assertEquals(restingLabelY, boundaryLabelY(top = true), 1f)
        } finally {
            if (pointerDown) list.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun existingParentBackdropSurvivesPreviewEditingAndDismissal() {
        val songs = gestureSongs()
        var confirmations = 0
        showGestureDialog(songs, setOf(songs[40].stableKey()), initialBackdropOffset = 37f) {
            confirmations += 1
        }
        composeRule.runOnIdle { assertEquals(37f, parentBackdropOffset.value, 0.01f) }
        replaceInput("1")

        repeat(2) { stage ->
            composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
            val list = composeRule.onNode(hasScrollToIndexAction())
            list.performScrollToIndex(0)
            val restingLabelY = boundaryLabelY(top = true)
            val previewEffect = gestureEffects.last()
            composeRule.runOnIdle { assertEquals(37f, parentBackdropOffset.value, 0.01f) }
            composeRule.mainClock.autoAdvance = false
            var pointerDown = false
            try {
                list.performTouchInput {
                    down(Offset(center.x, height * 0.2f))
                    pointerDown = true
                    moveTo(Offset(center.x, height * 0.75f), delayMillis = 64)
                }
                advanceGestureFrames(2)
                assertTrue("preview did not visibly pull down", boundaryLabelY(top = true) - restingLabelY > 1f)
                composeRule.runOnIdle {
                    assertTrue(previewEffect.isInProgress)
                    assertEquals("preview overwrote an existing parent displacement", 37f, parentBackdropOffset.value, 0.01f)
                }
                list.performTouchInput {
                    up()
                    pointerDown = false
                }
                advanceGestureFrames(2)
                composeRule.runOnIdle {
                    assertTrue("preview returned before exercising active-node cleanup", previewEffect.isInProgress)
                    assertEquals(37f, parentBackdropOffset.value, 0.01f)
                }
                composeRule.onNodeWithText(
                    text(if (stage == 0) CoreCommonR.string.playlist_insert_edit_position else CoreCommonR.string.action_cancel)
                ).performClick()
                composeRule.runOnIdle { assertEquals(37f, parentBackdropOffset.value, 0.01f) }
            } finally {
                if (pointerDown) list.performTouchInput { cancel() }
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertFalse("removed preview effect retained an active displacement", previewEffect.isInProgress)
                assertEquals("preview removal cleared the parent backdrop", 37f, parentBackdropOffset.value, 0.01f)
                assertEquals(0, confirmations)
            }
            if (stage == 0) {
                composeRule.onNode(hasSetTextAction()).assertIsDisplayed().assertTextContains("1")
            } else {
                composeRule.onNode(isDialog()).assertDoesNotExist()
            }
        }
    }

    @Test
    fun longPreviewAcceptsRapidOppositeSwipesInsideTheRealDialog() {
        val songs = gestureSongs()
        var confirmations = 0
        showGestureDialog(songs, songs.subList(10, 70).mapTo(mutableSetOf()) { it.stableKey() }) {
            confirmations += 1
        }
        replaceInput("11")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        captureRestingPreviewGeometry()
        composeRule.mainClock.autoAdvance = false
        repeat(3) {
            val beforeUp = previewScrollPosition()
            swipePreview(upward = true)
            advanceGestureFrames(2)
            val afterUp = previewScrollPosition()
            assertTrue("dialog preview did not scroll up: $beforeUp -> $afterUp", afterUp > beforeUp)
            assertPreviewHasNoOverscroll()

            val beforeDown = previewScrollPosition()
            swipePreview(upward = false)
            advanceGestureFrames(2)
            val afterDown = previewScrollPosition()
            assertTrue("dialog preview ignored reverse swipe: $beforeDown -> $afterDown", afterDown < beforeDown)
            assertPreviewHasNoOverscroll()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertPreviewHasNoOverscroll()
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, confirmations) }
    }

    @Test
    fun repeatedDownwardPullsAtPositionOneLeaveNoPersistentHeaderGap() {
        val songs = gestureSongs()
        showGestureDialog(songs, setOf(songs[40].stableKey())) {}
        replaceInput("1")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        captureRestingPreviewGeometry()
        val restingLabelY = boundaryLabelY(top = true)
        assertCompactFirstPositionHeader()
        composeRule.mainClock.autoAdvance = false
        repeat(3) {
            swipePreview(upward = false)
            advanceGestureFrames(2)
            assertTrue("real dialog pull did not visibly move its own content", boundaryLabelY(top = true) - restingLabelY > 1f)
            composeRule.runOnIdle {
                assertTrue(gestureEffects.last().isInProgress)
            }
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        savePreviewScreenshot()
        assertPreviewHasNoOverscroll()
        assertEquals("preview content did not return to its resting position", restingLabelY, boundaryLabelY(top = true), 1f)
        assertCompactFirstPositionHeader()
    }

    private fun verifyFirstPositionBoundaryGesture(top: Boolean) {
        val songs = gestureSongs()
        var confirmations = 0
        showGestureDialog(songs, setOf(songs[40].stableKey())) { confirmations += 1 }
        replaceInput("1")
        composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_preview_action)).performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.playlist_insert_position_summary, 1))
            .assertIsDisplayed()
        val list = composeRule.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(if (top) 0 else 9)
        captureRestingPreviewGeometry()
        val restingLabelY = boundaryLabelY(top)
        if (top) assertCompactFirstPositionHeader()
        composeRule.mainClock.autoAdvance = false
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val direction = if (top) 1f else -1f
        val start = Offset(bounds.width / 2f, bounds.height * if (top) 0.2f else 0.8f)
        val pulled = start.copy(y = bounds.height * if (top) 0.75f else 0.25f)
        var pointerDown = false
        try {
            list.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(pulled, delayMillis = 64)
            }
            advanceGestureFrames(2)
            val visibleOffset = boundaryLabelY(top) - restingLabelY
            val visibleDistance = abs(visibleOffset)
            assertTrue("dialog boundary pull did not create visible overscroll", visibleDistance > 1f)
            assertTrue("dialog content moved toward the wrong edge", direction * visibleOffset > 0f)
            composeRule.runOnIdle {
                assertTrue(gestureEffects.last().isInProgress)
            }
            val beforeReverse = previewScrollPosition()
            val reverse = pulled.copy(y = pulled.y - direction * (visibleDistance + 40f))
            list.performTouchInput { moveTo(reverse, delayMillis = 16) }
            advanceGestureFrames(2)
            val afterReverse = previewScrollPosition()
            assertTrue(
                "dialog reverse drag remained trapped at the edge: $beforeReverse -> $afterReverse",
                if (top) afterReverse > beforeReverse else afterReverse < beforeReverse
            )
            assertPreviewHasNoOverscroll()
            list.performTouchInput {
                repeat(6) { sample ->
                    moveTo(reverse.copy(y = reverse.y - direction * 12f * (sample + 1)), delayMillis = 10)
                }
            }
            val beforeRelease = previewScrollPosition()
            list.performTouchInput {
                up()
                pointerDown = false
            }
            advanceGestureFrames(8)
            val afterFling = previewScrollPosition()
            composeRule.runOnIdle {
                val release = requireNotNull(gestureFlings.lastOrNull())
                assertTrue("dialog release had wrong velocity: $release", direction * release.incoming.y < -100f)
                assertTrue(
                    "dialog effect did not forward reverse velocity: $release",
                    direction * requireNotNull(release.forwarded).y < -100f
                )
            }
            assertTrue(
                "dialog reverse release stopped instead of scrolling: " +
                    "$beforeRelease -> $afterFling; flings=$gestureFlings",
                if (top) afterFling > beforeRelease else afterFling < beforeRelease
            )
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            assertPreviewHasNoOverscroll()
            if (top) {
                list.performScrollToIndex(0)
                assertCompactFirstPositionHeader()
            }
            composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_confirm_action)).assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(0, confirmations) }
        } finally {
            if (pointerDown) list.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun showGestureDialog(
        songs: List<SongItem>,
        selectedKeys: Set<String>,
        initialBackdropOffset: Float = 0f,
        onConfirm: (PlaylistInsertPreview) -> Unit
    ) {
        val factory = object : OverscrollFactory {
            override fun equals(other: Any?): Boolean = this === other

            override fun hashCode(): Int = System.identityHashCode(this)

            override fun createOverscrollEffect(): OverscrollEffect {
                val actual = AdvancedGlassOverscrollFactory.createOverscrollEffect()
                gestureEffects += actual
                return object : OverscrollEffect by actual {
                    override suspend fun applyToFling(
                        velocity: Velocity,
                        performFling: suspend (Velocity) -> Velocity
                    ) {
                        val sample = GestureFling(velocity)
                        gestureFlings += sample
                        actual.applyToFling(velocity) { forwarded ->
                            sample.forwarded = forwarded
                            performFling(forwarded)
                        }
                    }
                }
            }
        }
        composeRule.setContent {
            val visible = remember { mutableStateOf(true) }
            parentBackdropOffset = remember { mutableStateOf(initialBackdropOffset) }
            compactHeaderGapPx = with(LocalDensity.current) { 24.dp.toPx() }
            CompositionLocalProvider(
                LocalOverscrollFactory provides factory,
                LocalAdvancedGlassOverscrollBackdrop provides AdvancedGlassOverscrollBackdrop(
                    color = androidx.compose.ui.graphics.Color.Blue,
                    offsetY = parentBackdropOffset
                )
            ) {
                MaterialTheme {
                    if (visible.value) {
                        PlaylistInsertDialog(songs, selectedKeys, { visible.value = false }, onConfirm, offlineMode = true)
                    }
                }
            }
        }
    }

    private fun swipePreview(upward: Boolean) {
        composeRule.onNode(hasScrollToIndexAction()).performTouchInput {
            swipe(
                start = Offset(center.x, height * if (upward) 0.75f else 0.25f),
                end = Offset(center.x, height * if (upward) 0.25f else 0.75f),
                durationMillis = 80
            )
        }
    }

    private fun previewScrollPosition(): Float = composeRule.onNode(hasScrollToIndexAction())
        .fetchSemanticsNode().config[VerticalScrollAxisRange].value()

    private fun captureRestingPreviewGeometry() {
        restingPreviewTopPx = composeRule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().positionInRoot.y
    }

    private fun boundaryLabelY(top: Boolean): Float {
        val label = if (top) text(CoreCommonR.string.playlist_insert_at_start)
        else context.resources.getQuantityString(CoreCommonR.plurals.playlist_insert_omitted_after, 74, 74)
        return composeRule.onNodeWithText(label).fetchSemanticsNode().positionInRoot.y
    }

    private fun advanceGestureFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private fun assertPreviewHasNoOverscroll() {
        val previewTop = composeRule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().positionInRoot.y
        assertEquals("preview retains a displaced viewport", restingPreviewTopPx, previewTop, 1f)
        composeRule.runOnIdle {
            assertFalse("preview effect remains in progress", gestureEffects.last().isInProgress)
        }
    }

    private fun assertCompactFirstPositionHeader() {
        val rangeBottom = composeRule.onNodeWithText(
            context.getString(CoreCommonR.string.playlist_insert_preview_range, 1, 1)
        ).fetchSemanticsNode().boundsInRoot.bottom
        val firstLabelTop = composeRule.onNodeWithText(text(CoreCommonR.string.playlist_insert_at_start))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "preview retains an empty gap above the playlist start: ${firstLabelTop - rangeBottom}px",
            firstLabelTop - rangeBottom <= compactHeaderGapPx
        )
    }

    private fun gestureSongs(): List<SongItem> {
        val coverUrl = syntheticCover(300, 0xFF73A9A3.toInt())
        return (1L..80L).map { index ->
            SongItem(
                id = index,
                name = "手势曲目 $index",
                artist = "手势测试歌手",
                album = "preview-test",
                albumId = 1L,
                durationMs = 180_000L,
                coverUrl = coverUrl
            )
        }
    }

    private data class GestureFling(val incoming: Velocity, var forwarded: Velocity? = null)

    private fun showDialog(
        songs: List<SongItem>,
        selectedKeys: Set<String>,
        onConfirm: (PlaylistInsertPreview) -> Unit
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlaylistInsertDialog(songs, selectedKeys, {}, onConfirm, offlineMode = true)
            }
        }
    }

    private fun replaceInput(input: String) {
        composeRule.onNode(hasSetTextAction()).performTextReplacement(input)
    }

    private fun text(resourceId: Int): String = context.getString(resourceId)

    private fun savePreviewScreenshot(name: String = "playlist-insert-preview.png") {
        composeRule.waitForIdle()
        val screenshot = composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(context.cacheDir, name).outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun demoSongs(): List<SongItem> {
        val titles = listOf("雨后散步", "星河漫游", "晨间列车", "橘色黄昏", "海风来信", "午夜回声")
        val artists = listOf("小岛", "青岚", "白川", "山野", "夏眠", "月见")
        val colors = listOf(0xFF8BAF91, 0xFF6976BC, 0xFF75A5B9, 0xFFD69562, 0xFF73A9A3, 0xFF947BAF)
        return titles.mapIndexed { index, title ->
            SongItem(
                id = index + 1L,
                name = title,
                artist = artists[index],
                album = "preview-test",
                albumId = 1L,
                durationMs = 180_000L,
                coverUrl = syntheticCover(index, colors[index].toInt())
            )
        }
    }

    private fun syntheticCover(index: Int, color: Int): String {
        val file = File(context.cacheDir, "playlist-insert-test-cover-$index.png")
        artworkFiles += file
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
        paint.alpha = 90
        canvas.drawCircle(96f, 28f, 30f, paint)
        paint.alpha = 180
        canvas.drawCircle(37f, 93f, 48f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.toURI().toString()
    }
}
