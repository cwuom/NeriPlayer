package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.settings.PlaybackControlSize

class StartupOnboardingProgressTest {

    @Test
    fun lyricPreviewKeepsActiveLineAtViewportCenter() {
        val lineHeights = listOf(20, 28, 20, 20, 20)
        val spacing = 6
        val offset = resolveOnboardingLyricActiveCenterOffset(
            viewportHeightPx = 160,
            lineHeightsPx = lineHeights,
            lineSpacingPx = spacing,
            activeIndex = 2
        )
        val firstTop = ((160 - (lineHeights.sum() + spacing * 4)) / 2)
            .coerceAtLeast(0)
        val activeTop = firstTop + lineHeights.take(2).sum() + spacing * 2 + offset
        assertEquals(160 / 2, activeTop + lineHeights[2] / 2)
    }

    @Test
    fun enhancedBlurPromptRequiresSupportedDeviceAndImportedBackground() {
        assertEquals(
            true,
            shouldPromptStartupEnhancedAdvancedBlur(
                advancedBlurAvailable = true,
                enhancedAdvancedBlurEnabled = false,
                backgroundImageImported = true
            )
        )
        assertEquals(
            false,
            shouldPromptStartupEnhancedAdvancedBlur(
                advancedBlurAvailable = false,
                enhancedAdvancedBlurEnabled = false,
                backgroundImageImported = true
            )
        )
        assertEquals(
            false,
            shouldPromptStartupEnhancedAdvancedBlur(
                advancedBlurAvailable = true,
                enhancedAdvancedBlurEnabled = true,
                backgroundImageImported = true
            )
        )
        assertEquals(
            false,
            shouldPromptStartupEnhancedAdvancedBlur(
                advancedBlurAvailable = true,
                enhancedAdvancedBlurEnabled = false,
                backgroundImageImported = false
            )
        )
    }

    @Test
    fun progressMatchesTheCurrentStepAcrossTheFlow() {
        assertEquals(1f / 9f, calculateStartupOnboardingProgress(0, 9), 0.0001f)
        assertEquals(4f / 9f, calculateStartupOnboardingProgress(3, 9), 0.0001f)
        assertEquals(8f / 9f, calculateStartupOnboardingProgress(7, 9), 0.0001f)
        assertEquals(1f, calculateStartupOnboardingProgress(8, 9), 0.0001f)
    }

    @Test
    fun progressStaysWithinTheIndicatorRange() {
        assertEquals(0f, calculateStartupOnboardingProgress(-4, 6), 0.0001f)
        assertEquals(1f, calculateStartupOnboardingProgress(12, 6), 0.0001f)
        assertEquals(0f, calculateStartupOnboardingProgress(0, 0), 0.0001f)
    }

    @Test
    fun onboardingGlassHandoffKeepsExactlyOneSceneActive() {
        assertEquals(
            2,
            resolveStartupOnboardingGlassOwnerStepIndex(
                fromStepIndex = 2,
                toStepIndex = 3,
                enteringAlphaProgress = 0f,
                running = true,
                preparingIncomingScene = true
            )
        )
        assertEquals(
            2,
            resolveStartupOnboardingGlassOwnerStepIndex(
                fromStepIndex = 2,
                toStepIndex = 3,
                enteringAlphaProgress =
                    STARTUP_ONBOARDING_GLASS_OWNER_HANDOFF_PROGRESS - 0.01f,
                running = true,
                preparingIncomingScene = false
            )
        )
        assertEquals(
            3,
            resolveStartupOnboardingGlassOwnerStepIndex(
                fromStepIndex = 2,
                toStepIndex = 3,
                enteringAlphaProgress = STARTUP_ONBOARDING_GLASS_OWNER_HANDOFF_PROGRESS,
                running = true,
                preparingIncomingScene = false
            )
        )
        assertEquals(
            3,
            resolveStartupOnboardingGlassOwnerStepIndex(
                fromStepIndex = null,
                toStepIndex = 3,
                enteringAlphaProgress = 0f,
                running = false,
                preparingIncomingScene = false
            )
        )
    }

    @Test
    fun onboardingLayerWaitsForTheFirstRenderedFramesBeforeStartingTransition() {
        val controller = StartupOnboardingLayerTransitionController(
            scope = CoroutineScope(Job()),
            initialStepIndex = 0
        )
        try {
            controller.request(1)
            controller.onContainerWidthChanged(1080)

            assertEquals(1, controller.visibleScenes.size)
            assertEquals(0, controller.activeGlassStepIndex)

            controller.onInitialSceneFrameRendered()

            assertEquals(2, controller.visibleScenes.size)
            assertEquals(0, controller.activeGlassStepIndex)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun onboardingLayerExposesTheRunningLockWhileAnIncomingScenePrepares() {
        val controller = StartupOnboardingLayerTransitionController(
            scope = CoroutineScope(Job()),
            initialStepIndex = 0
        )
        try {
            controller.onContainerWidthChanged(1080)
            controller.onInitialSceneFrameRendered()

            assertEquals(false, controller.isRunning)

            controller.request(1)

            assertEquals(true, controller.isRunning)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun navigationIsBlockedWhileTransitioningOrFinishing() {
        assertEquals(
            true,
            canNavigateStartupOnboardingStep(
                finishing = false,
                transitionRunning = false
            )
        )
        assertEquals(
            false,
            canNavigateStartupOnboardingStep(
                finishing = false,
                transitionRunning = true
            )
        )
        assertEquals(
            false,
            canNavigateStartupOnboardingStep(
                finishing = true,
                transitionRunning = false
            )
        )
        assertEquals(
            true,
            canNavigateStartupOnboardingBack(
                finishing = false,
                transitionRunning = true,
                canReverseTransition = true
            )
        )
        assertEquals(
            false,
            canNavigateStartupOnboardingBack(
                finishing = false,
                transitionRunning = true,
                canReverseTransition = false
            )
        )
    }

    @Test
    fun onboardingLayerReversesAnInFlightTransitionFromItsCurrentProgress() {
        val reversed = reverseStartupOnboardingLayerTransitionProgress(
            enteringTranslationProgress = 0.4f,
            exitingTranslationProgress = 0.6f,
            enteringAlphaProgress = 0.7f,
            exitingAlphaProgress = 0.3f
        )

        assertEquals(1f - 0.6f * 5f / 7f, reversed.enteringTranslation, 0.0001f)
        assertEquals((1f - 0.4f) * 7f / 5f, reversed.exitingTranslation, 0.0001f)
        assertEquals(0.7f, reversed.enteringAlpha, 0.0001f)
        assertEquals(0.3f, reversed.exitingAlpha, 0.0001f)
    }

    @Test
    fun onboardingLayerReplacesASecondPendingTargetBeforeAnimationStarts() {
        val controller = StartupOnboardingLayerTransitionController(
            scope = CoroutineScope(Job()),
            initialStepIndex = 0
        )
        try {
            controller.onContainerWidthChanged(1080)
            controller.onInitialSceneFrameRendered()
            controller.request(1)
            controller.request(2)

            assertEquals(
                listOf(0, 2),
                controller.visibleScenes.map(StartupOnboardingLayerScene::stepIndex)
            )
            assertEquals(0, controller.activeGlassStepIndex)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun onboardingStepSceneMotionMatchesTheOriginalSlideDistances() {
        assertEquals(
            IntOffset(200, 0),
            resolveStartupOnboardingLayerSceneMotion(
                scene = StartupOnboardingLayerScene(
                    stepIndex = 1,
                    phase = StartupOnboardingLayerScenePhase.Entering,
                    direction = 1
                ),
                widthPx = 1000,
                enteringTranslationProgress = 0f,
                exitingTranslationProgress = 0f,
                enteringAlphaProgress = 0f,
                exitingAlphaProgress = 0f
            ).offset
        )
        assertEquals(
            IntOffset(-200, 0),
            resolveStartupOnboardingLayerSceneMotion(
                scene = StartupOnboardingLayerScene(
                    stepIndex = 1,
                    phase = StartupOnboardingLayerScenePhase.Entering,
                    direction = -1
                ),
                widthPx = 1000,
                enteringTranslationProgress = 0f,
                exitingTranslationProgress = 0f,
                enteringAlphaProgress = 0f,
                exitingAlphaProgress = 0f
            ).offset
        )
        assertEquals(
            IntOffset(-142, 0),
            resolveStartupOnboardingLayerSceneMotion(
                scene = StartupOnboardingLayerScene(
                    stepIndex = 0,
                    phase = StartupOnboardingLayerScenePhase.Exiting,
                    direction = 1
                ),
                widthPx = 1000,
                enteringTranslationProgress = 1f,
                exitingTranslationProgress = 1f,
                enteringAlphaProgress = 1f,
                exitingAlphaProgress = 1f
            ).offset
        )
        assertEquals(
            IntOffset.Zero,
            resolveStartupOnboardingLayerSceneMotion(
                scene = StartupOnboardingLayerScene(
                    stepIndex = 1,
                    phase = StartupOnboardingLayerScenePhase.Settled
                ),
                widthPx = 1000,
                enteringTranslationProgress = 1f,
                exitingTranslationProgress = 1f,
                enteringAlphaProgress = 1f,
                exitingAlphaProgress = 1f
            ).offset
        )
    }

    @Test
    fun permissionResultDoesNotAdvanceTheFlow() {
        assertEquals(false, shouldAdvanceStartupOnboarding(true, false))
        assertEquals(false, shouldAdvanceStartupOnboarding(false, true))
        assertEquals(true, shouldAdvanceStartupOnboarding(false, false))
    }

    @Test
    fun notificationPermissionWarningRequiresTwoDismissalsBeforeSkipping() {
        assertEquals(
            true,
            shouldShowStartupNotificationPermissionWarning(
                permissionSupported = true,
                permissionGranted = false,
                attempts = 0
            )
        )
        assertEquals(
            true,
            shouldShowStartupNotificationPermissionWarning(
                permissionSupported = true,
                permissionGranted = false,
                attempts = 1
            )
        )
        assertEquals(
            false,
            shouldShowStartupNotificationPermissionWarning(
                permissionSupported = true,
                permissionGranted = false,
                attempts = 2
            )
        )
        assertEquals(true, hasFinishedStartupNotificationPermissionWarning(2))
    }

    @Test
    fun notificationPermissionWarningIsNotShownWhenUnsupportedOrGranted() {
        assertEquals(
            false,
            shouldShowStartupNotificationPermissionWarning(
                permissionSupported = false,
                permissionGranted = false,
                attempts = 0
            )
        )
        assertEquals(
            false,
            shouldShowStartupNotificationPermissionWarning(
                permissionSupported = true,
                permissionGranted = true,
                attempts = 0
            )
        )
    }

    @Test
    fun coverPreviewShowsMoreLinesWhenLyricsAreSmaller() {
        assertEquals(8, resolveOnboardingCoverPreviewLineCount(0.5f))
        assertEquals(3, resolveOnboardingCoverPreviewLineCount(1.0f))
        assertEquals(3, resolveOnboardingCoverPreviewLineCount(1.6f))
    }

    @Test
    fun playbackPreviewKeepsThreeDefaultLinesWithRoomForTheLyricArea() {
        assertEquals(
            112f,
            resolveOnboardingPlaybackPreviewLyricHeight(1.0f).value,
            0.001f
        )
        assertEquals(
            472f,
            resolveOnboardingPlaybackPreviewHeight(1.0f).value,
            0.001f
        )
        assertEquals(
            136f,
            resolveOnboardingPlaybackPreviewLyricHeight(0.5f).value,
            0.001f
        )
    }

    @Test
    fun playbackSourceFallbackRequiresConfirmationOnlyWhenEnabling() {
        assertEquals(true, shouldConfirmStartupPlaybackSourceFallback(true, false))
        assertEquals(false, shouldConfirmStartupPlaybackSourceFallback(false, true))
        assertEquals(false, shouldConfirmStartupPlaybackSourceFallback(true, true))
    }

    @Test
    fun noPlatformWarningOnlyAppliesWhenEveryPlatformIsMissing() {
        assertEquals(
            true,
            shouldWarnStartupNoPlatformConnected(
                biliState = SavedCookieAuthState.Missing,
                neteaseState = SavedCookieAuthState.Missing,
                youTubeState = YouTubeAuthState.Missing
            )
        )
        assertEquals(
            false,
            shouldWarnStartupNoPlatformConnected(
                biliState = SavedCookieAuthState.Valid,
                neteaseState = SavedCookieAuthState.Missing,
                youTubeState = YouTubeAuthState.Missing
            )
        )
        assertEquals(
            false,
            shouldWarnStartupNoPlatformConnected(
                biliState = SavedCookieAuthState.Checking,
                neteaseState = SavedCookieAuthState.Missing,
                youTubeState = YouTubeAuthState.Missing
            )
        )
    }

    @Test
    fun coverPreviewKeepsTheActiveLyricCenteredAsLinesShrink() {
        val defaultWindow = resolveOnboardingCoverPreviewWindow(
            totalLineCount = 12,
            lineCount = 3
        )
        val compact = resolveOnboardingCoverPreviewWindow(
            totalLineCount = 12,
            lineCount = 8
        )
        val medium = resolveOnboardingCoverPreviewWindow(
            totalLineCount = 12,
            lineCount = 6
        )

        assertEquals(1, defaultWindow.activeIndex)
        assertEquals(0, defaultWindow.startIndex)
        assertEquals(0, compact.startIndex)
        assertEquals(4, compact.activeIndex)
        assertEquals(0, medium.startIndex)
        assertEquals(3, medium.activeIndex)
    }

    @Test
    fun playbackPreviewKeepsToolbarHeightScaledWithControlSize() {
        assertEquals(
            64f,
            resolveOnboardingPlaybackToolbarHeight(
                controlSize = PlaybackControlSize.MEDIUM,
                docked = true
            ).value,
            0.001f
        )
        assertEquals(
            69.6f,
            resolveOnboardingPlaybackToolbarHeight(
                controlSize = PlaybackControlSize.LARGE,
                docked = false
            ).value,
            0.001f
        )
    }
}
