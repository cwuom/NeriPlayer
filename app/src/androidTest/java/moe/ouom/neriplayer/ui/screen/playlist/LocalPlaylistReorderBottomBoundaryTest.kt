package moe.ouom.neriplayer.ui.screen.playlist

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties.SelectableGroup
import androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollFactory
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegionRegistry
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.navigation.AppBottomBarPresentation
import moe.ouom.neriplayer.ui.navigation.AppMiniPlayerPresentation
import moe.ouom.neriplayer.ui.navigation.AppNavigationScaffold
import moe.ouom.neriplayer.ui.navigation.LocalBottomTabBarBoundsInRoot
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerBoundsInRoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
class LocalPlaylistReorderBottomBoundaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { LocalPlaylistRepository.getInstance(context) }
    private val screenVisible = mutableStateOf(false)
    private val nowPlayingVisible = mutableStateOf(false)
    private var ownedPlaylistId: Long? = null
    private var screenMounted = false
    private var observedTabBounds: Rect? = null
    private var observedMiniPlayerBounds: Rect? = null
    private var oneDpPx = 0f
    private var dragSlopPx = 0f
    private var tabSelectionCount = 0
    private var miniPlayerExpansionCount = 0
    private val receivedSystemPointer = AtomicReference<ReceivedSystemPointer?>(null)
    private val ownedArtworkFiles = mutableListOf<File>()
    private lateinit var glassContentBackdrop: AdvancedGlassBackdrop
    private lateinit var glassBackgroundBackdrop: AdvancedGlassBackdrop
    private lateinit var glassRegionRegistry: AdvancedGlassRegionRegistry

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun removeOwnedPlaylist() {
        composeRule.mainClock.autoAdvance = true
        try {
            val playlistId = ownedPlaylistId ?: return
            runBlocking {
                withTimeout(RepositoryTimeoutMs) { repository.deletePlaylist(playlistId) }
            }
            if (screenMounted) {
                // 先等待页面按自有歌单删除退出，避免卸载时又写回使用记录
                composeRule.waitUntil(UiTimeoutMs) { !screenVisible.value }
            }
            AppContainer.playlistUsageRepo.removeEntry(playlistId, "local")
        } finally {
            ownedArtworkFiles.forEach { it.delete() }
        }
    }

    @Test
    fun plainHostWithoutMiniPlayerStartsOnlyBelowMeasuredTabBottom() {
        verifyTabBoundary(baseBlurRequested = false, withMiniPlayer = false)
    }

    @Test
    fun plainHostWithMiniPlayerStartsOnlyBelowMeasuredMiniPlayerBottom() {
        verifyTabBoundary(baseBlurRequested = false, withMiniPlayer = true)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun glassOverlayWithoutMiniPlayerStartsOnlyBelowMeasuredTabBottom() {
        verifyTabBoundary(baseBlurRequested = true, withMiniPlayer = false)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun glassOverlayWithMiniPlayerStartsOnlyBelowMeasuredMiniPlayerBottom() {
        verifyTabBoundary(baseBlurRequested = true, withMiniPlayer = true)
    }

    @Test
    fun androidTouchBelowMiniPlayerKeepsDragUnderPlaybackControls() {
        verifyTabBoundary(baseBlurRequested = false, withMiniPlayer = true, systemInput = true)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun glassOverlayWithMiniPlayerKeepsSameDragStableWhenReversingUpward() {
        verifyTabBoundary(baseBlurRequested = true, withMiniPlayer = true, reverseAfterBottomHold = true)
    }

    @Test
    fun hiddenBottomTabsProvideNoReorderBoundary() {
        createAndShowPlaylist(baseBlurRequested = false, withMiniPlayer = true)
        composeRule.runOnIdle {
            assertTrue("visible tabs were never measured", observedTabBounds != null)
            assertTrue("visible MiniPlayer was never measured", observedMiniPlayerBounds != null)
            nowPlayingVisible.value = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertNull(observedTabBounds)
            assertNull(observedMiniPlayerBounds)
        }
    }

    private fun verifyTabBoundary(
        baseBlurRequested: Boolean,
        withMiniPlayer: Boolean,
        systemInput: Boolean = false,
        reverseAfterBottomHold: Boolean = false
    ) {
        val songs = createAndShowPlaylist(baseBlurRequested, withMiniPlayer, observeSystemInput = systemInput)
        val originalOrder = songs.map { it.id }
        val heldSong = songs[DraggedSongIndex]
        playlistList().performScrollToIndex(FirstVisibleSongIndex + FixedHeaderCount)
        songRow(heldSong).assertIsDisplayed().performTouchInput { longClick() }
        composeRule.waitForIdle()
        if (baseBlurRequested) assertRealBlurActive(withMiniPlayer)

        val tabBounds = bottomTabs().fetchSemanticsNode().boundsInRoot
        val miniPlayerBounds = if (withMiniPlayer) miniPlayer(songs.first()).fetchSemanticsNode().boundsInRoot else null
        val lowerControlsBounds = miniPlayerBounds ?: tabBounds
        composeRule.runOnIdle {
            val observed = requireNotNull(observedTabBounds)
            assertEquals("host measured the offline banner instead of the tabs", tabBounds.top, observed.top, 1f)
            assertEquals("host included the system navigation inset in the tabs", tabBounds.bottom, observed.bottom, 1f)
            assertEquals("host did not remove the left navigation inset", tabBounds.left, observed.left, 1f)
            assertEquals("host did not remove the right navigation inset", tabBounds.right, observed.right, 1f)
            if (miniPlayerBounds == null) {
                assertNull(observedMiniPlayerBounds)
            } else {
                val observedMini = requireNotNull(observedMiniPlayerBounds)
                assertEquals("host did not measure the actual MiniPlayer top", miniPlayerBounds.top, observedMini.top, 1f)
                assertEquals("host did not measure the actual MiniPlayer bottom", miniPlayerBounds.bottom, observedMini.bottom, 1f)
            }
        }
        val root = composeRule.onRoot(useUnmergedTree = true)
        val rootNode = root.fetchSemanticsNode()
        val rootBounds = rootNode.boundsInRoot
        val rootOriginInWindow = rootNode.positionInWindow
        val windowOriginOnScreen = rootNode.positionOnScreen - rootOriginInWindow
        val handleBounds = songHandle(heldSong).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val start = handleBounds.center - rootBounds.topLeft
        val initialCardBounds = rawSongBounds(heldSong)
        val activationPointer = start + Offset(0f, dragSlopPx)
        val aboveControls = Offset(
            start.x,
            minOf(lowerControlsBounds.top, playlistList().fetchSemanticsNode().boundsInRoot.bottom) -
                4f * oneDpPx - rootBounds.top
        )
        val insideControls = aboveControls.copy(y = lowerControlsBounds.bottom - oneDpPx - rootBounds.top)
        val belowControls = aboveControls.copy(y = lowerControlsBounds.bottom + 4f * oneDpPx - rootBounds.top)
        val fixtureDiagnostics = "glass=$baseBlurRequested, mini=$withMiniPlayer, systemInput=$systemInput, " +
            "tabs=$tabBounds, list=${playlistList().fetchSemanticsNode().boundsInRoot}, " +
            "miniBounds=$miniPlayerBounds, rootInWindow=$rootOriginInWindow, windowOnScreen=$windowOriginOnScreen, " +
            "above=$aboveControls, inside=$insideControls, below=$belowControls"
        val nativeInputTrace = mutableListOf<String>()
        var diagnostics = fixtureDiagnostics
        assertTrue("the control bottom is outside the touchable root: $diagnostics", belowControls.y < rootBounds.height)
        composeRule.waitUntil(UiTimeoutMs) {
            coverPixelCount(initialCardBounds.top, initialCardBounds.bottom) >= MinimumCoverPixels
        }
        composeRule.mainClock.autoAdvance = false
        var pointerDown = false
        var downTime = 0L
        var lastPointer = start
        fun injectTouch(action: Int, pointer: Offset): Offset {
            val inWindow = pointer + rootOriginInWindow
            val onScreen = inWindow + windowOriginOnScreen
            val previousReceivedPointer = receivedSystemPointer.get()
            val eventTime = SystemClock.uptimeMillis()
            val traceIndex = nativeInputTrace.size
            nativeInputTrace += "action=$action, injected=$pointer@$eventTime"
            diagnostics = "$fixtureDiagnostics, nativeInputs=$nativeInputTrace"
            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                onScreen.x,
                onScreen.y,
                0
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue(
                    "Android rejected the drag input action=$action, point=$onScreen: $diagnostics",
                    InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
                )
            } finally {
                event.recycle()
            }
            if (action == MotionEvent.ACTION_CANCEL) return pointer
            composeRule.waitUntil(UiTimeoutMs) {
                val received = receivedSystemPointer.get()
                received != null && received !== previousReceivedPointer && received.uptimeMillis >= eventTime &&
                    received.pressed == (action != MotionEvent.ACTION_UP)
            }
            val received = requireNotNull(receivedSystemPointer.get())
            val actualPointer = received.positionInRoot - rootBounds.topLeft
            nativeInputTrace[traceIndex] += ", received=$actualPointer@${received.uptimeMillis}"
            diagnostics = "$fixtureDiagnostics, nativeInputs=$nativeInputTrace"
            return actualPointer
        }
        fun movePointer(pointer: Offset): Offset {
            val actualPointer = if (systemInput) {
                injectTouch(MotionEvent.ACTION_MOVE, pointer)
            } else {
                root.performTouchInput { moveTo(pointer, delayMillis = 16) }
                pointer
            }
            lastPointer = pointer
            return actualPointer
        }
        fun finishPointer(cancelled: Boolean) {
            if (systemInput) {
                injectTouch(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, lastPointer)
            } else {
                root.performTouchInput { if (cancelled) cancel() else up() }
            }
            pointerDown = false
        }
        try {
            downTime = SystemClock.uptimeMillis()
            if (systemInput) {
                pointerDown = true
                injectTouch(MotionEvent.ACTION_DOWN, start)
            } else {
                root.performTouchInput { down(start) }
                pointerDown = true
            }
            val actualActivationPointer = movePointer(activationPointer)
            advanceFrames(2)
            val activatedCardBounds = rawSongBounds(heldSong)
            val activatedCover = captureCover(activatedCardBounds.top, activatedCardBounds.bottom)
            assertTrue("the activated card cover was not drawn", activatedCover.pixelCount >= MinimumCoverPixels)
            val activatedCoverBounds = requireNotNull(activatedCover.bounds)
            val beforeHold = scrollPosition()
            val actualAboveControls = movePointer(aboveControls)
            if (systemInput) assertTrue(
                "Android delivered the above-control hold inside the playback controls: $diagnostics",
                actualAboveControls.y + rootBounds.top < lowerControlsBounds.top
            )
            advanceFrames(90)
            assertEquals(
                "drag scrolled before reaching the playback controls: $diagnostics",
                beforeHold,
                scrollPosition(),
                ScrollTolerance
            )
            assertCardFollowsPointer(heldSong, activatedCardBounds, actualActivationPointer, actualAboveControls, diagnostics)
            assertCoverFollowsPointer(activatedCoverBounds, actualActivationPointer, actualAboveControls, diagnostics)
            assertEquals("hold persisted before release", originalOrder, currentOrder())
            if (reverseAfterBottomHold) saveDragScreenshot("above-controls")

            val actualInsideControls = movePointer(insideControls)
            if (systemInput) assertTrue(
                "Android delivered the control-interior hold outside the playback controls: $diagnostics",
                actualInsideControls.y + rootBounds.top in lowerControlsBounds.top..lowerControlsBounds.bottom
            )
            advanceFrames(90)
            assertEquals(
                "drag scrolled while the finger was still inside the playback controls: $diagnostics",
                beforeHold,
                scrollPosition(),
                ScrollTolerance
            )
            assertEquals("control-interior hold persisted before release", originalOrder, currentOrder())
            assertEquals("dragging across the tabs selected a navigation tab", 0, tabSelectionCount)
            assertEquals("dragging over the MiniPlayer expanded playback", 0, miniPlayerExpansionCount)
            assertCardFollowsPointer(heldSong, activatedCardBounds, actualActivationPointer, actualInsideControls, diagnostics)
            assertControlsStayAboveDraggedCard(tabBounds, miniPlayerBounds, diagnostics)

            val actualBelowControls = movePointer(belowControls)
            if (systemInput) assertTrue(
                "Android delivered the below-control hold inside the playback controls: $diagnostics",
                actualBelowControls.y + rootBounds.top > lowerControlsBounds.bottom
            )
            val samples = mutableListOf(scrollPosition())
            repeat(2) {
                advanceFrames(60)
                samples += scrollPosition()
                assertCardFollowsPointer(heldSong, activatedCardBounds, actualActivationPointer, actualBelowControls, diagnostics)
                assertTrue(
                    "the held card stopped following below the playback controls: $diagnostics",
                    rawSongBounds(heldSong).bottom > lowerControlsBounds.bottom
                )
                assertControlsStayAboveDraggedCard(tabBounds, miniPlayerBounds, diagnostics)
                if (baseBlurRequested) assertRealBlurActive(withMiniPlayer)
                assertEquals("control-edge hold committed while the pointer was down", originalOrder, currentOrder())
            }
            assertTrue("crossing the control bottom did not start downward scrolling: $samples, $diagnostics", samples[1] > samples[0] + ScrollTolerance)
            assertTrue("downward scrolling stopped while held below the control bottom: $samples, $diagnostics", samples[2] > samples[1] + ScrollTolerance)
            if (reverseAfterBottomHold) {
                saveDragScreenshot("under-controls")
                val listBounds = playlistList().fetchSemanticsNode().boundsInRoot
                val nearTop = Offset(start.x, listBounds.top + 4f * oneDpPx - rootBounds.top)
                movePointer(nearTop)
                var previousScroll = scrollPosition()
                var stalledFrames = 0
                var maxStalledFrames = 0
                repeat(180) { frame ->
                    advanceFrames(1)
                    val cardBounds = rawSongBounds(heldSong)
                    assertEquals(
                        "the held card moved away from the top after reversing at frame=$frame: $diagnostics",
                        listBounds.top,
                        cardBounds.top,
                        4f * oneDpPx
                    )
                    val currentScroll = scrollPosition()
                    if (frame >= 2) {
                        assertTrue(
                            "upward drag jumped toward the bottom at frame=$frame, previous=$previousScroll, current=$currentScroll: $diagnostics",
                            currentScroll <= previousScroll + ScrollTolerance
                        )
                        stalledFrames = if (currentScroll >= previousScroll - ScrollTolerance) stalledFrames + 1 else 0
                        maxStalledFrames = maxOf(maxStalledFrames, stalledFrames)
                        assertTrue(
                            "upward drag stalled for $maxStalledFrames frames at frame=$frame: $diagnostics",
                            maxStalledFrames <= 2
                        )
                    }
                    previousScroll = currentScroll
                    assertTrue(
                        "the held cover was not drawn at the top at frame=$frame: $diagnostics",
                        coverPixelCount(listBounds.top, listBounds.top + cardBounds.height) >= MinimumCoverPixels
                    )
                    if ((frame + 1) % 60 == 0) {
                        assertEquals("reverse hold persisted before release", originalOrder, currentOrder())
                        if (baseBlurRequested) assertRealBlurActive(withMiniPlayer)
                    }
                }
                assertTrue("the reverse hold did not scroll upward", previousScroll < samples.last() - ScrollTolerance)
                saveDragScreenshot("upward-after-reverse")
            }
            finishPointer(cancelled = false)
            advanceFrames(2)
            composeRule.mainClock.autoAdvance = true
            composeRule.waitUntil(RepositoryTimeoutMs) { currentOrder() != originalOrder }
            val committedOrder = currentOrder()
            assertEquals(originalOrder.size, committedOrder.size)
            assertEquals(originalOrder.toSet(), committedOrder.toSet())
            assertEquals(committedOrder.size, committedOrder.distinct().size)
            if (reverseAfterBottomHold) {
                assertTrue("released handle did not persist the upward reverse move", committedOrder.indexOf(heldSong.id) < DraggedSongIndex)
            } else {
                assertTrue("released handle did not persist a downward move", committedOrder.indexOf(heldSong.id) > DraggedSongIndex)
            }
            assertEquals("drag release selected a navigation tab", 0, tabSelectionCount)
            assertEquals("drag release expanded the MiniPlayer", 0, miniPlayerExpansionCount)
        } finally {
            if (pointerDown) finishPointer(cancelled = true)
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun createAndShowPlaylist(
        baseBlurRequested: Boolean,
        withMiniPlayer: Boolean,
        observeSystemInput: Boolean = false
    ): List<SongItem> {
        val token = UUID.randomUUID().toString().take(8)
        val baseId = System.currentTimeMillis() * 1_000L
        val draggedCover = createOwnedCover(token)
        val songs = List(SongCount) { index ->
            SongItem(
                id = baseId + index,
                name = "tab-boundary-$token-song-${index + 1}",
                artist = "synthetic artist",
                album = "tab-boundary-$token",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = draggedCover.takeIf { index == DraggedSongIndex }
            )
        }
        val playlist = runBlocking {
            withTimeout(RepositoryTimeoutMs) {
                assertTrue(repository.awaitInitialized())
                repository.createPlaylistWithPreparedSongs("UI$token", songs)
            }
        }
        ownedPlaylistId = playlist.id
        screenVisible.value = true
        var observerOriginInRoot = Offset.Zero
        val systemInputObserver = if (observeSystemInput) Modifier
            .onGloballyPositioned { observerOriginInRoot = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()?.let { change ->
                            // 原生输入可能经过坐标外推，断言基准必须使用实际送达的位置
                            receivedSystemPointer.set(
                                ReceivedSystemPointer(
                                    change.position + observerOriginInRoot, change.uptimeMillis, change.pressed
                                )
                            )
                        }
                    }
                }
            } else Modifier
        composeRule.setContent {
            val glassController = remember(baseBlurRequested) {
                AdvancedGlassController(
                    sdkInt = Build.VERSION.SDK_INT,
                    advancedBlurEnabled = baseBlurRequested,
                    enhancedAdvancedBlurEnabled = false,
                    backendReady = true
                )
            }
            CompositionLocalProvider(
                LocalOverscrollFactory provides AdvancedGlassOverscrollFactory
            ) {
                MaterialTheme {
                    val backgroundBackdrop = rememberAdvancedGlassBackdrop()
                    val contentBackdrop = rememberAdvancedGlassBackdrop()
                    glassBackgroundBackdrop = backgroundBackdrop
                    glassContentBackdrop = contentBackdrop
                    with(LocalDensity.current) {
                        oneDpPx = 1.dp.toPx()
                        dragSlopPx = 24.dp.toPx()
                    }
                    AdvancedGlassHost(
                        controller = glassController,
                        backgroundBackdrop = backgroundBackdrop,
                        contentBackdrop = contentBackdrop
                    ) {
                        glassRegionRegistry = requireNotNull(LocalAdvancedGlassBackdrops.current).regionRegistry
                        Box(Modifier.fillMaxSize().then(systemInputObserver)) {
                            Box(
                                Modifier.fillMaxSize().captureAdvancedGlassBackdrop(backgroundBackdrop)
                                    .background(MaterialTheme.colorScheme.background)
                            )
                            AppNavigationScaffold(
                                bottomBar = AppBottomBarPresentation(
                                    items = listOf(Destinations.Home to Icons.Outlined.Home, Destinations.Library to Icons.Outlined.LibraryMusic),
                                    currentDestination = null,
                                    showNowPlaying = nowPlayingVisible.value,
                                    offlineMode = true,
                                    alwaysUseNewTabStyle = false,
                                    backgroundImageUri = null
                                ),
                                miniPlayer = AppMiniPlayerPresentation(
                                    song = songs.first().takeIf { withMiniPlayer },
                                    coverUrl = null,
                                    visualCoverUrl = null,
                                    songVisualKey = null,
                                    visualCoverSongKey = null,
                                    enableBlur = baseBlurRequested
                                ),
                                baseBlurRequested = baseBlurRequested,
                                snackbarHostState = remember { SnackbarHostState() },
                                onMainTabSelected = { tabSelectionCount++ },
                                onExpandNowPlaying = { miniPlayerExpansionCount++ }
                            ) {
                                observedTabBounds = LocalBottomTabBarBoundsInRoot.current
                                observedMiniPlayerBounds = LocalMiniPlayerBoundsInRoot.current
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
                }
            }
        }
        screenMounted = true
        composeRule.waitUntil(UiTimeoutMs) {
            observedTabBounds != null && (!withMiniPlayer || observedMiniPlayerBounds != null) &&
                (!baseBlurRequested || glassContentBackdrop.hasActiveBlur) &&
                composeRule.onAllNodesWithText(playlist.name).fetchSemanticsNodes().isNotEmpty()
        }
        return playlist.songs
    }

    private fun assertRealBlurActive(withMiniPlayer: Boolean) = composeRule.runOnIdle {
        assertTrue("the glass fixture fell back instead of blurring content", glassContentBackdrop.hasActiveBlur)
        assertTrue("the glass fixture fell back instead of blurring the background", glassBackgroundBackdrop.hasActiveBlur)
        assertTrue(
            "the tabs did not register their real blur region",
            glassRegionRegistry.regions.any { it.role == AdvancedGlassRole.BottomNavigation && it.boundsInWindow.height > 0f }
        )
        if (withMiniPlayer) {
            assertTrue(
                "the MiniPlayer did not register its real blur region",
                glassRegionRegistry.regions.any { it.role == AdvancedGlassRole.MiniPlayer && it.boundsInWindow.height > 0f }
            )
        }
    }

    private fun bottomTabs(): SemanticsNodeInteraction = composeRule.onNode(
        SemanticsMatcher.keyIsDefined(SelectableGroup).and(
            hasAnyDescendant(hasContentDescription(context.getString(CoreCommonR.string.nav_home)))
        ),
        useUnmergedTree = true
    )

    private fun songRow(song: SongItem): SemanticsNodeInteraction = composeRule.onNode(
        hasClickAction().and(hasAnyDescendant(hasText(song.name))),
        useUnmergedTree = true
    )

    private fun songHandle(song: SongItem): SemanticsNodeInteraction = composeRule.onNode(
        hasContentDescription(context.getString(CoreCommonR.string.common_drag_handle)).and(
            hasAnyAncestor(hasClickAction().and(hasAnyDescendant(hasText(song.name))))
        ),
        useUnmergedTree = true
    )

    private fun miniPlayer(song: SongItem): SemanticsNodeInteraction = composeRule.onNode(
        hasClickAction().and(hasAnyDescendant(hasText(song.name))),
        useUnmergedTree = true
    )

    private fun rawSongBounds(song: SongItem): Rect {
        val node = songRow(song).fetchSemanticsNode()
        val origin = node.positionInRoot
        return Rect(origin.x, origin.y, origin.x + node.size.width, origin.y + node.size.height)
    }

    private fun assertCardFollowsPointer(
        song: SongItem,
        initialBounds: Rect,
        start: Offset,
        pointer: Offset,
        diagnostics: String
    ) {
        val expectedCenterY = initialBounds.center.y + pointer.y - start.y
        assertEquals(
            "the card stopped following the pointer below the list viewport: $diagnostics",
            expectedCenterY,
            rawSongBounds(song).center.y,
            4f * oneDpPx
        )
    }

    private fun coverPixelCount(topInRoot: Float, bottomInRoot: Float): Int {
        return captureCover(topInRoot, bottomInRoot).pixelCount
    }

    private fun assertControlsStayAboveDraggedCard(tabBounds: Rect, miniBounds: Rect?, diagnostics: String) {
        assertEquals(
            "the dragged song was drawn above the tabs: $diagnostics",
            0,
            controlCoverPixelCount(tabBounds)
        )
        miniBounds?.let {
            assertEquals(
                "the dragged song was drawn above the MiniPlayer: $diagnostics",
                0,
                controlCoverPixelCount(it)
            )
        }
    }

    private fun controlCoverPixelCount(bounds: Rect): Int {
        val inset = (8f * oneDpPx).coerceAtMost(minOf(bounds.width, bounds.height) / 4f)
        return captureCover(
            bounds.top + inset,
            bounds.bottom - inset,
            bounds.left + inset,
            bounds.right - inset
        ).pixelCount
    }

    private fun assertCoverFollowsPointer(
        activatedBounds: Rect,
        activationPointer: Offset,
        pointer: Offset,
        diagnostics: String
    ) {
        val rootBounds = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val delta = pointer.y - activationPointer.y
        val expectedTop = (activatedBounds.top + delta).coerceAtLeast(rootBounds.top)
        val expectedBottom = (activatedBounds.bottom + delta).coerceAtMost(rootBounds.bottom)
        val tolerance = 4f * oneDpPx
        val sample = captureCover(expectedTop - tolerance, expectedBottom + tolerance)
        assertTrue("the cover was not painted at the held position: $diagnostics", sample.pixelCount >= MinimumCoverPixels)
        val actual = requireNotNull(sample.bounds)
        assertEquals("the cover top did not follow the pointer: $diagnostics", expectedTop, actual.top, tolerance)
        assertEquals("the cover bottom did not follow the pointer: $diagnostics", expectedBottom, actual.bottom, tolerance)
        assertEquals(
            "the rendered cover center did not follow the pointer: $diagnostics",
            (expectedTop + expectedBottom) / 2f,
            actual.center.y,
            tolerance
        )
    }

    private fun captureCover(
        topInRoot: Float,
        bottomInRoot: Float,
        leftInRoot: Float? = null,
        rightInRoot: Float? = null
    ): CoverSample {
        val root = composeRule.onRoot(useUnmergedTree = true)
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val image = root.captureToImage().asAndroidBitmap()
        val scaleX = image.width / rootBounds.width
        val scaleY = image.height / rootBounds.height
        val top = floor((topInRoot - rootBounds.top) * scaleY).toInt().coerceIn(0, image.height)
        val bottom = ceil((bottomInRoot - rootBounds.top) * scaleY).toInt().coerceIn(top, image.height)
        val left = leftInRoot?.let {
            floor((it - rootBounds.left) * scaleX).toInt().coerceIn(0, image.width)
        } ?: 0
        val right = rightInRoot?.let {
            ceil((it - rootBounds.left) * scaleX).toInt().coerceIn(left, image.width)
        } ?: image.width
        var matches = 0
        var leftPixel = image.width
        var topPixel = image.height
        var rightPixel = -1
        var bottomPixel = -1
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = image.getPixel(x, y)
                if (abs(Color.red(pixel) - Color.red(DraggedCoverColor)) <= CoverChannelTolerance &&
                    abs(Color.green(pixel) - Color.green(DraggedCoverColor)) <= CoverChannelTolerance &&
                    abs(Color.blue(pixel) - Color.blue(DraggedCoverColor)) <= CoverChannelTolerance
                ) {
                    matches++
                    leftPixel = minOf(leftPixel, x)
                    topPixel = minOf(topPixel, y)
                    rightPixel = maxOf(rightPixel, x)
                    bottomPixel = maxOf(bottomPixel, y)
                }
            }
        }
        val bounds = if (matches == 0) null else Rect(
            rootBounds.left + leftPixel / scaleX,
            rootBounds.top + topPixel / scaleY,
            rootBounds.left + (rightPixel + 1) / scaleX,
            rootBounds.top + (bottomPixel + 1) / scaleY
        )
        return CoverSample(bounds, matches)
    }

    private data class CoverSample(val bounds: Rect?, val pixelCount: Int)

    private data class ReceivedSystemPointer(
        val positionInRoot: Offset,
        val uptimeMillis: Long,
        val pressed: Boolean
    )

    private fun saveDragScreenshot(name: String) {
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "playlist-overlay-$name.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun createOwnedCover(token: String): String {
        val file = File(context.cacheDir, "playlist-bottom-boundary-$token.png")
        ownedArtworkFiles += file
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(DraggedCoverColor)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file.toURI().toString()
    }

    private fun playlistList(): SemanticsNodeInteraction = composeRule.onNode(
        hasScrollToIndexAction().and(SemanticsMatcher.keyIsDefined(VerticalScrollAxisRange))
    )

    private fun scrollPosition(): Float = playlistList().fetchSemanticsNode().config[VerticalScrollAxisRange].value()

    private fun currentOrder(): List<Long> = repository.playlists.value.single { it.id == ownedPlaylistId }.songs.map { it.id }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private companion object {
        const val FixtureRoute = "reorder-bottom-boundary-fixture"
        const val SongCount = 120
        const val FirstVisibleSongIndex = 57
        const val DraggedSongIndex = 59
        const val FixedHeaderCount = 2
        const val RepositoryTimeoutMs = 10_000L
        const val UiTimeoutMs = 15_000L
        const val ScrollTolerance = 0.01f
        const val DraggedCoverColor = 0xFF18D6C8.toInt()
        const val MinimumCoverPixels = 100
        const val CoverChannelTolerance = 4
    }
}
