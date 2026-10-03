package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.ui.banner.shouldExpandManagedProcessingBannerFromDrag
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriAppProcessingBannerLayoutPolicyTest {
    @Test
    fun collapsedBannerExpandsOnlyForTopDownVerticalDrag() {
        assertTrue(
            shouldExpandManagedProcessingBannerFromDrag(
                startY = 40f,
                totalX = 3f,
                totalY = 24f,
                edgePx = 96f,
                thresholdPx = 24f
            )
        )
        assertFalse(
            shouldExpandManagedProcessingBannerFromDrag(
                startY = 97f,
                totalX = 0f,
                totalY = 40f,
                edgePx = 96f,
                thresholdPx = 24f
            )
        )
        assertFalse(
            shouldExpandManagedProcessingBannerFromDrag(
                startY = 40f,
                totalX = 32f,
                totalY = 24f,
                edgePx = 96f,
                thresholdPx = 24f
            )
        )
        assertFalse(
            shouldExpandManagedProcessingBannerFromDrag(
                startY = 40f,
                totalX = 0f,
                totalY = 23.9f,
                edgePx = 96f,
                thresholdPx = 24f
            )
        )
    }

    @Test
    fun processingBannerOverlaysExpandedContentWithoutReservingCollapsedSpace() {
        val source = scaffoldSource()
        val contentLayout = source
            .substringAfter("private fun AppManagedProcessingLayer(")

        assertTrue(contentLayout.contains("Box(\n"))
        assertTrue(contentLayout.contains(".managedProcessingRevealGesture("))
        assertTrue(contentLayout.contains("visible = presentation.visible"))
        assertTrue(contentLayout.contains(".align(Alignment.TopCenter)"))
        assertTrue(contentLayout.contains("content(layoutInsets)"))
        assertTrue(
            contentLayout.contains("Modifier.captureAdvancedGlassBackdrop(backdrops.content)")
        )
        assertFalse(contentLayout.contains(".weight(1f)\n                                        .fillMaxWidth()"))
    }

    @Test
    fun contentCaptureIncludesDragOverlayAndKeepsMiniPlayerOutside() {
        val contentLayer = scaffoldSource()
            .substringAfter("private fun AppManagedProcessingLayer(")
            .substringBefore("private fun managedProcessingBannerEnterTransition()")
        val app = source("app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt")
        val overlay = source("app/src/main/java/moe/ouom/neriplayer/ui/navigation/AppDragOverlay.kt")
            .substringAfter("internal fun AppDragOverlayHost(")

        assertTrue(contentLayer.contains("val backdrops = LocalAdvancedGlassBackdrops.current"))
        assertEquals(1, Regex("captureAdvancedGlassBackdrop\\(").findAll(contentLayer).count())
        assertTrue(
            Regex(
                "AppDragOverlayHost\\(modifier = Modifier.fillMaxSize\\(\\)" +
                    "\\.then\\(contentCaptureModifier\\)\\) \\{\\s*" +
                    "content\\(layoutInsets\\)\\s*\\}\\s*AppMiniPlayerOverlay\\("
            ).containsMatchIn(contentLayer)
        )
        assertFalse(app.contains(".captureAdvancedGlassBackdrop(contentGlassBackdrop)"))
        assertTrue(app.contains(".captureAdvancedGlassBackdrop(backgroundGlassBackdrop)"))
        assertTrue(overlay.contains("modifier = modifier"))
        assertTrue(
            Regex("\\.drawWithContent \\{\\s*drawContent\\(\\)\\s*overlay.draw\\(this\\)\\s*\\}")
                .containsMatchIn(overlay)
        )
    }

    @Test
    fun processingBannerDragUsesAccumulatedDistance() {
        val banner = bannerSource()
            .substringAfter("internal fun ManagedLibraryProcessingBanner(")

        assertTrue(banner.contains("accumulatedDragPx += dragAmount"))
        assertTrue(banner.contains("MANAGED_LIBRARY_PROCESSING_DRAG_THRESHOLD.toPx()"))
        assertTrue(banner.contains("accumulatedDragPx > -thresholdPx"))
    }

    @Test
    fun processingBannerCollapseAndExpandUseHeightAndContentAnimations() {
        val source = scaffoldSource()
        val banner = bannerSource()
            .substringAfter("internal fun ManagedLibraryProcessingBanner(")

        assertTrue(banner.contains(".animateContentSize("))
        assertTrue(source.contains("expandVertically("))
        assertTrue(source.contains("shrinkVertically("))
        assertTrue(source.contains("slideInVertically("))
        assertTrue(source.contains("slideOutVertically("))
        assertTrue(source.contains("fadeIn("))
        assertTrue(source.contains("fadeOut("))
        assertTrue(source.contains("onExpand = owner::expand"))
    }

    @Test
    fun processingBannerUsesOverallMigrationFractionWithoutStageReset() {
        val source = bannerSource()
        val fractionPolicy = source
            .substringAfter("internal fun managedProcessingFraction(")
            .substringBefore("internal data class ManagedProcessingBytes(")
        val banner = source.substringAfter("internal fun ManagedLibraryProcessingBanner(")

        assertTrue(fractionPolicy.contains("migrationProgress?.fraction"))
        assertTrue(fractionPolicy.contains("val stageFraction"))
        assertTrue(banner.contains("val animatedProgressFraction by animateFloatAsState("))
        assertTrue(banner.contains("progress = { animatedProgressFraction }"))
    }

    @Test
    fun collapsedRevealGestureOnlyConsumesARealTopDownDrag() {
        val source = scaffoldSource()
        val bannerSource = bannerSource()
        val gesture = bannerSource
            .substringAfter("internal fun Modifier.managedProcessingRevealGesture(")
            .substringBefore("@Composable\ninternal fun ManagedLibraryProcessingBanner(")

        assertTrue(gesture.contains("awaitFirstDown("))
        assertTrue(gesture.contains("requireUnconsumed = false"))
        assertTrue(gesture.contains("pass = PointerEventPass.Initial"))
        assertTrue(gesture.contains("down.position.y <= edgePx"))
        assertTrue(gesture.contains("thresholdPx = thresholdPx"))
        assertTrue(gesture.contains("shouldExpandManagedProcessingBannerFromDrag"))
        assertTrue(gesture.contains("change.consume()"))
        assertTrue(gesture.contains("onExpand()"))
        assertTrue(bannerSource.contains("enabled = interactive"))
        assertTrue(bannerSource.contains("if (interactive)"))
        assertTrue(source.contains("interactive = presentation.visible"))
    }

    @Test
    fun processingBannerRetainsContentDuringExitAnimation() {
        val source = scaffoldSource()
        assertTrue(source.contains("owner.observe(currentState, currentProgress)"))
        assertTrue(source.contains("exit = managedProcessingBannerExitTransition()"))
        assertTrue(source.contains("slideOutVertically("))
        assertTrue(source.contains("fadeOut("))
        assertTrue(source.contains("shrinkVertically("))
    }

    private fun bannerSource(): String =
        source("app/src/main/java/moe/ouom/neriplayer/ui/banner/AppStatusBanners.kt")

    private fun scaffoldSource(): String =
        source("app/src/main/java/moe/ouom/neriplayer/ui/navigation/AppNavigationScaffold.kt")

    private fun source(path: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
