package moe.ouom.neriplayer.ui.onboarding

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthState

class StartupOnboardingProgressTest {

    @Test
    fun stepTransitionKeepsBothPagesGlassActive() {
        // 旧页卡片在淡出期间必须保留玻璃底板, 否则前进后退会闪一下
        assertEquals(
            setOf<Any>(StartupStep.Privacy, StartupStep.Platforms),
            resolveStartupGlassNavigationOwners(StartupStep.Privacy, StartupStep.Platforms)
        )
        assertEquals(
            setOf<Any>(StartupStep.Platforms, StartupStep.Privacy),
            resolveStartupGlassNavigationOwners(StartupStep.Platforms, StartupStep.Privacy)
        )
        assertEquals(
            setOf<Any>(StartupStep.Lyrics),
            resolveStartupGlassNavigationOwners(StartupStep.Lyrics, StartupStep.Lyrics)
        )
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
    fun forwardTapsAreOnlyGuardedRightAfterAStepChange() {
        // 连续点按继续不能被吞掉: 守卫只覆盖切页后的极短窗口, 与动画是否收尾无关
        assertEquals(
            true,
            canNavigateStartupOnboardingStep(
                finishing = false,
                millisSinceLastStepChange = STARTUP_STEP_TAP_GUARD_MILLIS
            )
        )
        assertEquals(
            false,
            canNavigateStartupOnboardingStep(
                finishing = false,
                millisSinceLastStepChange = STARTUP_STEP_TAP_GUARD_MILLIS - 1
            )
        )
        assertEquals(
            false,
            canNavigateStartupOnboardingStep(
                finishing = true,
                millisSinceLastStepChange = 10_000L
            )
        )
        assertEquals(true, canNavigateStartupOnboardingBack(finishing = false, stepIndex = 2))
        assertEquals(false, canNavigateStartupOnboardingBack(finishing = false, stepIndex = 0))
        assertEquals(false, canNavigateStartupOnboardingBack(finishing = true, stepIndex = 2))
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
    fun simulatedPlaybackLoopsInsideTheLyricWindow() {
        // 预览一直开着也要有歌词在走, 越过最后一句就回到第一句
        assertEquals(1_500L, advanceStartupPreviewPosition(1_000L, 500L, loopStartMs = 0L, loopEndMs = 10_000L))
        assertEquals(500L, advanceStartupPreviewPosition(9_800L, 700L, loopStartMs = 0L, loopEndMs = 10_000L))
        assertEquals(2_000L, advanceStartupPreviewPosition(500L, 0L, loopStartMs = 2_000L, loopEndMs = 6_000L))
        assertEquals(1_000L, advanceStartupPreviewPosition(1_000L, -50L, loopStartMs = 0L, loopEndMs = 10_000L))
        assertEquals(42L, advanceStartupPreviewPosition(42L, 100L, loopStartMs = 5L, loopEndMs = 5L))
    }

    @Test
    fun simulatedLyricsStartPlayingFromTheFirstLine() {
        val lyrics = buildStartupPreviewLyrics(listOf("a", "b", "c"))
        assertEquals(3, lyrics.size)
        assertTrue(STARTUP_PREVIEW_START_MS in lyrics.first().startTimeMs until lyrics.first().endTimeMs)
        lyrics.zipWithNext { previous, next -> assertEquals(previous.endTimeMs, next.startTimeMs) }
        assertTrue(lyrics.last().endTimeMs < STARTUP_PREVIEW_DURATION_MS)
    }

    @Test
    fun ambientDriftLoopsWithoutJumpAtPhaseRestart() {
        // phase 从 2π 回到 0 时光斑位置要首尾相接
        listOf(0f, 2.1f).forEach { wobble ->
            val start = startupAmbientDrift(0f, wobble)
            val end = startupAmbientDrift(2f * PI.toFloat(), wobble)
            assertEquals(start.x, end.x, 1e-4f)
            assertEquals(start.y, end.y, 1e-4f)
        }
    }

    @Test
    fun consentOnlyAdvancesWhileUserStaysOnPrivacy() {
        assertTrue(shouldAdvanceAfterStartupConsent(StartupStep.Privacy))
        assertFalse(shouldAdvanceAfterStartupConsent(StartupStep.Motion))
    }
}
