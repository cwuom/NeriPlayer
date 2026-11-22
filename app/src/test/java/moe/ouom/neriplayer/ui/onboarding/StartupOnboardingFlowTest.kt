package moe.ouom.neriplayer.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupOnboardingFlowTest {

    @Test
    fun `language, style and motion come first and privacy precedes every data step`() {
        val steps = StartupStep.entries
        assertEquals(
            listOf(StartupStep.Language, StartupStep.Style, StartupStep.Motion, StartupStep.Privacy),
            steps.take(4)
        )
        assertEquals(StartupStep.LearningGuide, steps.last())
        steps.filter { it.ordinal <= StartupStep.Privacy.ordinal }.forEach { assertFalse(it.requiresConsent) }
        steps.filter { it.ordinal > StartupStep.Privacy.ordinal }.forEach { assertTrue(it.requiresConsent) }
    }

    @Test
    fun `steps behind the privacy notice stay locked until it is accepted`() {
        assertTrue(canEnterStartupStep(StartupStep.Motion, disclaimerAccepted = false))
        assertTrue(canEnterStartupStep(StartupStep.Privacy, disclaimerAccepted = false))
        assertFalse(canEnterStartupStep(StartupStep.Platforms, disclaimerAccepted = false))
        assertTrue(canEnterStartupStep(StartupStep.Platforms, disclaimerAccepted = true))
        assertFalse(canLeaveStartupStepForward(StartupStep.Privacy, disclaimerAccepted = false))
        assertTrue(canLeaveStartupStepForward(StartupStep.Privacy, disclaimerAccepted = true))
        assertTrue(canLeaveStartupStepForward(StartupStep.Style, disclaimerAccepted = false))
    }

    @Test
    fun `chapters group consecutive steps for the segmented progress`() {
        val segments = startupChapterSegments()
        assertEquals(StartupChapter.entries, segments.map(StartupChapterSegment::chapter))
        assertEquals(StartupStep.entries.size, segments.sumOf(StartupChapterSegment::stepCount))
        assertEquals(StartupChapterSegment(StartupChapter.Look, 0, 3), segments.first())
        assertEquals(StartupChapterSegment(StartupChapter.Privacy, 3, 1), segments[1])
    }

    @Test
    fun `a choice joins the rail after its own step and leaves it on the finish page`() {
        assertFalse(isStartupChoiceInRail(StartupChoice.Language, StartupStep.Language))
        assertTrue(isStartupChoiceInRail(StartupChoice.Language, StartupStep.Style))
        assertFalse(isStartupChoiceInRail(StartupChoice.Style, StartupStep.Style))
        assertTrue(isStartupChoiceInRail(StartupChoice.Motion, StartupStep.Privacy))
        StartupChoice.entries.forEach { choice ->
            assertFalse(isStartupChoiceInRail(choice, StartupStep.LearningGuide))
        }
    }

    @Test
    fun `privacy countdown progress fills the button as seconds pass`() {
        assertEquals(0f, resolveStartupPrivacyCountdownProgress(STARTUP_PRIVACY_COUNTDOWN_SECONDS), 0.0001f)
        assertEquals(0.6f, resolveStartupPrivacyCountdownProgress(2, 5), 0.0001f)
        assertEquals(1f, resolveStartupPrivacyCountdownProgress(0), 0.0001f)
        assertEquals(1f, resolveStartupPrivacyCountdownProgress(3, 0), 0.0001f)
    }

    @Test
    fun `notification permission confirmation ignores taps outside the dialog`() {
        assertFalse(StartupNotificationWarningDialogProperties.dismissOnClickOutside)
        assertTrue(StartupNotificationWarningDialogProperties.dismissOnBackPress)
    }
}
