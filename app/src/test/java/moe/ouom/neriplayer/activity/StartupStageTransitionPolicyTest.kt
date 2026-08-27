package moe.ouom.neriplayer.activity

import java.io.File
import moe.ouom.neriplayer.core.startup.StartupStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStageTransitionPolicyTest {
    @Test
    fun onboardingDefersOnlyDuringStartupBootstrap() {
        assertTrue(shouldDeferStartupStageContent(StartupStage.Onboarding))
        assertTrue(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Loading
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Loading,
                disclaimerWasShown = true
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Disclaimer
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Main
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Main,
                previousStage = StartupStage.Onboarding
            )
        )
        assertFalse(shouldDeferStartupStageContent(StartupStage.Loading))
        assertFalse(shouldDeferStartupStageContent(StartupStage.Disclaimer))
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Main,
                previousStage = StartupStage.Loading
            )
        )
    }

    @Test
    fun startupStageMotionRemainsEnabledWithLightweightLoadingIndicator() {
        val activitySource = source(
            "app/src/main/java/moe/ouom/neriplayer/activity/MainActivity.kt"
        )
        val transitionSource = source(
            "app/src/main/java/moe/ouom/neriplayer/activity/StartupStageTransition.kt"
        )

        assertTrue(activitySource.contains("AnimatedContent("))
        assertTrue(activitySource.contains("label = \"AppStageTransition\""))
        assertTrue(transitionSource.contains("AnimatedContent("))
        assertTrue(transitionSource.contains("label = \"startup_stage_content_gate\""))
        val loadingContent = activitySource.substringAfter("internal fun StartupLoadingContent(")
            .substringBefore("private fun StartupCrashReportDialog(")
        assertTrue(loadingContent.contains("showProgress: Boolean? = null"))
        assertTrue(loadingContent.contains("indicatorDelayMillis: Long"))
        assertTrue(loadingContent.contains("if (progressVisible)"))
        assertTrue(activitySource.contains("STARTUP_LOADING_INDICATOR_DELAY_MS = 1_000L"))
    }

    @Test
    fun startupSettingsReadTimeoutFailsOpenToAVisibleSafeStage() {
        assertNull(resolveStartupSetting(value = null, readTimedOut = false))
        assertEquals(false, resolveStartupSetting(value = null, readTimedOut = true))
        assertEquals(true, resolveStartupSetting(value = true, readTimedOut = true))
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
