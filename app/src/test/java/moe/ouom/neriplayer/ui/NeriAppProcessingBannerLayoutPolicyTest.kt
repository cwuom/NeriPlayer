package moe.ouom.neriplayer.ui

import java.io.File
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
        val source = source("app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt")
        val contentLayout = source
            .substringAfter("LocalMiniPlayerHeight provides bottomBarLayoutInsets.screenBottomInset")

        assertTrue(contentLayout.contains("Box(\n                                modifier = Modifier"))
        assertTrue(contentLayout.contains(".managedProcessingRevealGesture("))
        assertTrue(contentLayout.contains("visible = managedProcessingBannerActive &&"))
        assertTrue(contentLayout.contains("!managedProcessingBannerCollapsed"))
        assertTrue(contentLayout.contains(".align(Alignment.TopCenter)"))
        assertTrue(contentLayout.contains(".captureAdvancedGlassBackdrop(contentGlassBackdrop)"))
        assertFalse(contentLayout.contains(".weight(1f)\n                                        .fillMaxWidth()"))
    }

    @Test
    fun processingBannerDragUsesAccumulatedDistance() {
        val source = source("app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt")
        val banner = source
            .substringAfter("private fun ManagedLibraryProcessingBanner(")
            .substringBefore("@Composable\nfun NeriApp(")

        assertTrue(banner.contains("accumulatedDragPx += dragAmount"))
        assertTrue(banner.contains("MANAGED_LIBRARY_PROCESSING_DRAG_THRESHOLD.toPx()"))
        assertTrue(banner.contains("accumulatedDragPx <= -dragThresholdPx"))
    }

    @Test
    fun processingBannerCollapseAndExpandUseHeightAndContentAnimations() {
        val source = source("app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt")
        val banner = source
            .substringAfter("private fun ManagedLibraryProcessingBanner(")
            .substringBefore("@Composable\nfun NeriApp(")

        assertTrue(banner.contains(".animateContentSize("))
        assertTrue(source.contains("expandVertically("))
        assertTrue(source.contains("shrinkVertically("))
        assertTrue(source.contains("slideInVertically("))
        assertTrue(source.contains("slideOutVertically("))
        assertTrue(source.contains("fadeIn("))
        assertTrue(source.contains("fadeOut("))
        assertTrue(source.contains("onExpand = {"))
    }

    @Test
    fun collapsedRevealGestureOnlyConsumesARealTopDownDrag() {
        val source = source("app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt")
        val gesture = source
            .substringAfter("private fun Modifier.managedProcessingRevealGesture(")
            .substringBefore("@Composable\nprivate fun ManagedLibraryProcessingBanner(")

        assertTrue(gesture.contains("awaitFirstDown("))
        assertTrue(gesture.contains("requireUnconsumed = false"))
        assertTrue(gesture.contains("pass = PointerEventPass.Initial"))
        assertTrue(gesture.contains("down.position.y > edgePx"))
        assertTrue(gesture.contains("thresholdPx = thresholdPx"))
        assertTrue(gesture.contains("shouldExpandManagedProcessingBannerFromDrag"))
        assertTrue(gesture.contains("change.consume()"))
        assertTrue(gesture.contains("onExpand()"))
        assertTrue(source.contains("enabled = interactive"))
        assertTrue(source.contains("if (interactive)"))
        assertTrue(source.contains("interactive = managedProcessingBannerActive"))
    }

    @Test
    fun processingBannerRetainsContentDuringExitAnimation() {
        val source = source("app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt")
        val contentLayout = source
            .substringAfter("val managedLibraryProcessingState by")
            .substringBefore("val homeHostRuntimeState = rememberHomeHostRuntimeState()")

        assertTrue(contentLayout.contains("managedProcessingBannerDisplayState"))
        assertTrue(contentLayout.contains("managedProcessingBannerDisplayProgress"))
        assertTrue(
            contentLayout.contains(
                "LaunchedEffect(managedLibraryProcessingState, managedMigrationProgress)"
            )
        )
        assertTrue(contentLayout.contains("managedLibraryProcessingState.takeIf"))

        val visibility = source
            .substringAfter("AnimatedVisibility(\n                                    visible = managedProcessingBannerActive")
            .substringBefore("ManagedLibraryProcessingBanner(")
        assertTrue(visibility.contains("slideOutVertically("))
        assertTrue(visibility.contains("fadeOut("))
        assertTrue(visibility.contains("shrinkVertically("))
    }

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
