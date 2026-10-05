package moe.ouom.neriplayer.ui.screen.playlist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
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
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassHost
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRegionRegistry
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.effect.glass.rememberAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.navigation.AppBottomBarPresentation
import moe.ouom.neriplayer.ui.navigation.AppMiniPlayerPresentation
import moe.ouom.neriplayer.ui.navigation.AppNavigationScaffold
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class LocalPlaylistReorderBlurSamplingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { LocalPlaylistRepository.getInstance(context) }
    private val screenVisible = mutableStateOf(false)
    private var ownedPlaylistId: Long? = null
    private val artworkFiles = mutableListOf<File>()
    private lateinit var contentBackdrop: AdvancedGlassBackdrop
    private lateinit var backgroundBackdrop: AdvancedGlassBackdrop
    private lateinit var regionRegistry: AdvancedGlassRegionRegistry
    private var oneDpPx = 0f
    private var navigationActions = 0

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun removeOwnedFixture() {
        composeRule.mainClock.autoAdvance = true
        try {
            val id = ownedPlaylistId ?: return
            runBlocking { withTimeout(RepositoryTimeoutMs) { repository.deletePlaylist(id) } }
            composeRule.waitUntil(UiTimeoutMs) { !screenVisible.value }
            AppContainer.playlistUsageRepo.removeEntry(id, "local")
        } finally {
            artworkFiles.forEach { it.delete() }
        }
    }

    @Test
    fun defaultMiniPlayerBlursTheDraggedCheckerCover() = verifyBlurSampling(true, AdvancedBlurQuality.Default)

    @Test
    fun defaultBottomTabsBlurTheDraggedCheckerCover() = verifyBlurSampling(false, AdvancedBlurQuality.Default)

    @Test
    fun lowMiniPlayerBlursTheDraggedCheckerCover() = verifyBlurSampling(true, AdvancedBlurQuality.Low)

    @Test
    fun lowBottomTabsBlurTheDraggedCheckerCover() = verifyBlurSampling(false, AdvancedBlurQuality.Low)

    private fun verifyBlurSampling(withMiniPlayer: Boolean, quality: AdvancedBlurQuality) {
        val songs = showPlaylist(withMiniPlayer, quality)
        val heldSong = songs[HeldSongIndex]
        val originalOrder = songs.map { it.id }
        playlistList().performScrollToIndex(FirstVisibleSongIndex + FixedHeaderCount)
        songRow(heldSong).assertIsDisplayed().performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertBlurActive(withMiniPlayer, quality)

        val root = composeRule.onRoot(useUnmergedTree = true)
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val controls = if (withMiniPlayer) miniPlayer(songs.first()) else bottomTabs()
        val controlBounds = controls.fetchSemanticsNode().boundsInRoot
        val handleBounds = songHandle(heldSong).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val start = handleBounds.center - rootBounds.topLeft
        val activation = start + Offset(0f, 24f * oneDpPx)
        composeRule.waitUntil(UiTimeoutMs) {
            findCheckerCover(snapshot(), songRow(heldSong).fetchSemanticsNode().boundsInRoot) != null
        }
        composeRule.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            root.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(activation, delayMillis = 16)
            }
            advanceFrames(3)
            val sharpImage = snapshot()
            val row = songRow(heldSong).fetchSemanticsNode()
            val rowOrigin = row.positionInRoot
            val rowBounds = Rect(rowOrigin.x, rowOrigin.y, rowOrigin.x + row.size.width, rowOrigin.y + row.size.height)
            val sharpCover = requireNotNull(findCheckerCover(sharpImage, rowBounds))
            val inset = 6f * oneDpPx
            val sharpStats = analyze(sharpImage, Rect(sharpCover.left + inset, sharpCover.top + inset, sharpCover.right - inset, sharpCover.bottom - inset))
            val sharpNormalizedGradient = sharpStats.gradient / sharpStats.chroma.coerceAtLeast(1.0)
            assertTrue("the reference checker was already smoothed or not loaded: $sharpStats", sharpNormalizedGradient > 0.015)

            val roiTop = controlBounds.top + (if (withMiniPlayer) 3f else 4f) * oneDpPx
            val roiBottom = controlBounds.top + (if (withMiniPlayer) 7f else 10f) * oneDpPx
            val roi = Rect(sharpCover.left + inset, roiTop, sharpCover.right - inset, roiBottom)
            assertTrue("the checker sample overlaps the rounded control corner", roi.left > controlBounds.left + 24f * oneDpPx)
            assertSampleRegionCovers(withMiniPlayer, roi)
            val beforeImage = snapshot()
            val before = analyze(beforeImage, roi)
            val target = activation + Offset(0f, roi.center.y - sharpCover.center.y)
            assertTrue("the sample moved past the downward scroll boundary", target.y + rootBounds.top < controlBounds.bottom)
            val initialScroll = scrollPosition()
            val fixtureName = "${quality.storageValue}-${if (withMiniPlayer) "mini" else "tabs"}"
            saveScreenshot(beforeImage, "playlist-reorder-blur-$fixtureName-before.png")
            root.performTouchInput { moveTo(target, delayMillis = 16) }
            advanceFrames(90)
            assertBlurActive(withMiniPlayer, quality)
            assertSampleRegionCovers(withMiniPlayer, roi)
            val afterImage = snapshot()
            saveScreenshot(afterImage, "playlist-reorder-blur-$fixtureName-after.png")
            val after = analyze(afterImage, roi, reference = beforeImage)
            val chromaDelta = after.chroma - before.chroma
            val normalizedGradient = after.gradient / chromaDelta.coerceAtLeast(1.0)
            val evidence = "sample=$fixtureName, roi=$roi, sharp=$sharpStats, before=$before, " +
                "after=$after, chromaDelta=$chromaDelta, gradientNorm=$normalizedGradient, sharpNorm=$sharpNormalizedGradient"
            Log.i(LogTag, evidence)
            assertEquals("sampling moved the playlist", initialScroll, scrollPosition(), 0.01f)
            assertEquals("sampling committed while the handle was down", originalOrder, currentOrder())
            assertEquals("sampling activated a playback or tab control", 0, navigationActions)
            assertTrue("the dragged cover did not reach the active glass sample: $evidence", chromaDelta > 6.0)
            assertTrue("the glass did not filter the dragged checker frequency: $evidence", normalizedGradient < sharpNormalizedGradient * 0.5)
        } finally {
            if (pointerDown) root.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun assertSampleRegionCovers(withMiniPlayer: Boolean, roi: Rect) {
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val windowOffset = root.positionInWindow - root.positionInRoot
        val topLeft = roi.topLeft + windowOffset
        val bottomRight = roi.bottomRight + windowOffset - Offset(0.5f, 0.5f)
        val role = if (withMiniPlayer) AdvancedGlassRole.MiniPlayer else AdvancedGlassRole.BottomNavigation
        composeRule.runOnIdle {
            assertTrue(
                "the checker sample was outside the active $role region: $roi",
                regionRegistry.regions.any {
                    it.role == role && it.boundsInWindow.contains(topLeft) && it.boundsInWindow.contains(bottomRight)
                }
            )
        }
    }

    private fun showPlaylist(withMiniPlayer: Boolean, quality: AdvancedBlurQuality): List<SongItem> {
        val token = UUID.randomUUID().toString().take(8)
        val cover = createChecker(token)
        val baseId = System.currentTimeMillis() * 1_000L
        val songs = List(80) { index ->
            SongItem(
                id = baseId + index,
                name = "blur-$token-${index + 1}",
                artist = "synthetic artist",
                album = "blur-$token",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = cover.takeIf { index == HeldSongIndex }
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
        composeRule.setContent {
            val background = rememberAdvancedGlassBackdrop()
            val content = rememberAdvancedGlassBackdrop()
            backgroundBackdrop = background
            contentBackdrop = content
            oneDpPx = with(LocalDensity.current) { 1.dp.toPx() }
            MaterialTheme {
                AdvancedGlassHost(
                    controller = remember(quality) {
                        AdvancedGlassController(Build.VERSION.SDK_INT, true, false, true, advancedBlurQuality = quality)
                    },
                    backgroundBackdrop = background,
                    contentBackdrop = content
                ) {
                    regionRegistry = requireNotNull(LocalAdvancedGlassBackdrops.current).regionRegistry
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().captureAdvancedGlassBackdrop(background).background(Color(0xFFF0F0F0)))
                        AppNavigationScaffold(
                            bottomBar = AppBottomBarPresentation(
                                items = listOf(Destinations.Home to Icons.Outlined.Home, Destinations.Library to Icons.Outlined.LibraryMusic),
                                currentDestination = null,
                                showNowPlaying = false,
                                offlineMode = true,
                                alwaysUseNewTabStyle = false,
                                backgroundImageUri = null
                            ),
                            miniPlayer = AppMiniPlayerPresentation(
                                song = songs.first().takeIf { withMiniPlayer },
                                coverUrl = null, visualCoverUrl = null, songVisualKey = null, visualCoverSongKey = null, enableBlur = true
                            ),
                            baseBlurRequested = true,
                            snackbarHostState = remember { SnackbarHostState() },
                            onMainTabSelected = { navigationActions++ },
                            onExpandNowPlaying = { navigationActions++ }
                        ) {
                            val navController = rememberNavController()
                            NavHost(navController, startDestination = FixtureRoute) {
                                composable(FixtureRoute) {
                                    if (screenVisible.value) LocalPlaylistDetailScreen(
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
        composeRule.waitUntil(UiTimeoutMs) {
            contentBackdrop.hasActiveBlur && composeRule.onAllNodesWithText(playlist.name).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        return playlist.songs
    }

    private fun assertBlurActive(withMiniPlayer: Boolean, quality: AdvancedBlurQuality) = composeRule.runOnIdle {
        assertTrue("the content backdrop did not enable real blur", contentBackdrop.hasActiveBlur)
        assertTrue("the background backdrop did not enable real blur", backgroundBackdrop.hasActiveBlur)
        if (quality == AdvancedBlurQuality.Low) {
            assertTrue("low quality did not install a local blur plan", contentBackdrop.localBlurPlan != null)
        } else {
            assertTrue("default quality did not install the full blur effect", contentBackdrop.renderEffect != null)
        }
        assertTrue("the tabs did not register an active blur region", regionRegistry.regions.any { it.role == AdvancedGlassRole.BottomNavigation && it.boundsInWindow.width > 0f })
        if (withMiniPlayer) assertTrue("the MiniPlayer did not register an active blur region", regionRegistry.regions.any { it.role == AdvancedGlassRole.MiniPlayer && it.boundsInWindow.width > 0f })
    }

    private fun snapshot(): Bitmap = composeRule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()

    private fun saveScreenshot(image: Bitmap, name: String) {
        File(context.cacheDir, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun findCheckerCover(image: Bitmap, bounds: Rect): Rect? {
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in floor(bounds.top - root.top).toInt().coerceAtLeast(0) until ceil(bounds.bottom - root.top).toInt().coerceAtMost(image.height)) {
            for (x in floor(bounds.left - root.left).toInt().coerceAtLeast(0) until ceil(bounds.right - root.left).toInt().coerceAtMost(image.width)) {
                val pixel = image.getPixel(x, y)
                if (matchesColor(pixel, CheckerFirstColor) || matchesColor(pixel, CheckerSecondColor)) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right < left) null else Rect(left + root.left, top + root.top, right + 1f + root.left, bottom + 1f + root.top)
    }

    private fun matchesColor(pixel: Int, color: Int): Boolean =
        abs(AndroidColor.red(pixel) - AndroidColor.red(color)) <= 5 &&
            abs(AndroidColor.green(pixel) - AndroidColor.green(color)) <= 5 &&
            abs(AndroidColor.blue(pixel) - AndroidColor.blue(color)) <= 5

    private fun analyze(image: Bitmap, bounds: Rect, reference: Bitmap? = null): PixelStats {
        val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val left = ceil(bounds.left - root.left).toInt().coerceIn(0, image.width - 2)
        val right = floor(bounds.right - root.left).toInt().coerceIn(left + 2, image.width)
        val top = ceil(bounds.top - root.top).toInt().coerceIn(0, image.height - 1)
        val bottom = floor(bounds.bottom - root.top).toInt().coerceIn(top + 1, image.height)
        var chroma = 0.0
        var gradient = 0.0
        var count = 0
        for (y in top until bottom) for (x in left until right - 1) {
            val pixel = image.getPixel(x, y)
            val next = image.getPixel(x + 1, y)
            val original = reference?.getPixel(x, y) ?: 0
            val originalNext = reference?.getPixel(x + 1, y) ?: 0
            chroma += AndroidColor.red(pixel) - (AndroidColor.green(pixel) + AndroidColor.blue(pixel)) / 2.0
            val redDelta = AndroidColor.red(pixel) - AndroidColor.red(original)
            val greenDelta = AndroidColor.green(pixel) - AndroidColor.green(original)
            val blueDelta = AndroidColor.blue(pixel) - AndroidColor.blue(original)
            gradient += (abs(redDelta - (AndroidColor.red(next) - AndroidColor.red(originalNext))) +
                abs(greenDelta - (AndroidColor.green(next) - AndroidColor.green(originalNext))) +
                abs(blueDelta - (AndroidColor.blue(next) - AndroidColor.blue(originalNext)))) / 3.0
            count++
        }
        return PixelStats(chroma / count, gradient / count)
    }

    private fun createChecker(token: String): String {
        val file = File(context.cacheDir, "playlist-blur-checker-$token.png")
        artworkFiles += file
        val image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint()
        for (y in 0 until 16) for (x in 0 until 16) {
            paint.color = if ((x + y) % 2 == 0) CheckerFirstColor else CheckerSecondColor
            canvas.drawRect(x * 8f, y * 8f, (x + 1) * 8f, (y + 1) * 8f, paint)
        }
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        return file.toURI().toString()
    }

    private fun songRow(song: SongItem): SemanticsNodeInteraction = composeRule.onNode(hasClickAction().and(hasAnyDescendant(hasText(song.name))), useUnmergedTree = true)
    private fun miniPlayer(song: SongItem): SemanticsNodeInteraction = songRow(song)
    private fun songHandle(song: SongItem): SemanticsNodeInteraction = composeRule.onNode(hasContentDescription(context.getString(CoreCommonR.string.common_drag_handle)).and(hasAnyAncestor(hasClickAction().and(hasAnyDescendant(hasText(song.name))))), useUnmergedTree = true)
    private fun bottomTabs(): SemanticsNodeInteraction = composeRule.onNode(SemanticsMatcher.keyIsDefined(SelectableGroup).and(hasAnyDescendant(hasContentDescription(context.getString(CoreCommonR.string.nav_home)))), useUnmergedTree = true)
    private fun playlistList(): SemanticsNodeInteraction = composeRule.onNode(hasScrollToIndexAction().and(SemanticsMatcher.keyIsDefined(VerticalScrollAxisRange)))
    private fun scrollPosition(): Float = playlistList().fetchSemanticsNode().config[VerticalScrollAxisRange].value()
    private fun currentOrder(): List<Long> = repository.playlists.value.single { it.id == ownedPlaylistId }.songs.map { it.id }
    private fun advanceFrames(count: Int) = repeat(count) {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }
    private data class PixelStats(val chroma: Double, val gradient: Double)

    private companion object {
        const val FixtureRoute = "playlist-blur-sampling-fixture"
        const val LogTag = "PlaylistDragBlur"
        const val FirstVisibleSongIndex = 37
        const val HeldSongIndex = 39
        const val FixedHeaderCount = 2
        const val UiTimeoutMs = 15_000L
        const val RepositoryTimeoutMs = 10_000L
        const val CheckerFirstColor = 0xFFF014A0.toInt()
        const val CheckerSecondColor = 0xFFF0D000.toInt()
    }
}
