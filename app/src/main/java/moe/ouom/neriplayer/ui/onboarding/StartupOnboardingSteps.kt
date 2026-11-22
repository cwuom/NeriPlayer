package moe.ouom.neriplayer.ui.onboarding

import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthState

internal enum class StartupChapter {
    Look,
    Privacy,
    Connect,
    Playback,
    Personalize,
    Ready
}

/**
 * 外观三步不涉及任何数据处理, 放在同意隐私说明之前, 让用户先用自己的语言读说明
 */
internal enum class StartupStep(val chapter: StartupChapter) {
    Language(StartupChapter.Look),
    Style(StartupChapter.Look),
    Motion(StartupChapter.Look),
    Privacy(StartupChapter.Privacy),
    Platforms(StartupChapter.Connect),
    PlaybackSources(StartupChapter.Connect),
    Permissions(StartupChapter.Connect),
    PlaybackControls(StartupChapter.Playback),
    Lyrics(StartupChapter.Playback),
    Personalize(StartupChapter.Personalize),
    BackupRestore(StartupChapter.Personalize),
    LearningGuide(StartupChapter.Ready);

    val requiresConsent: Boolean
        get() = ordinal > Privacy.ordinal
}

internal enum class StartupChoice(val step: StartupStep) {
    Language(StartupStep.Language),
    Style(StartupStep.Style),
    Motion(StartupStep.Motion)
}

internal data class StartupChapterSegment(
    val chapter: StartupChapter,
    val firstStepIndex: Int,
    val stepCount: Int
)

internal fun startupChapterSegments(
    steps: List<StartupStep> = StartupStep.entries
): List<StartupChapterSegment> {
    val segments = mutableListOf<StartupChapterSegment>()
    steps.forEachIndexed { index, step ->
        val last = segments.lastOrNull()
        if (last != null && last.chapter == step.chapter) {
            segments[segments.lastIndex] = last.copy(stepCount = last.stepCount + 1)
        } else {
            segments += StartupChapterSegment(step.chapter, index, 1)
        }
    }
    return segments
}

internal fun calculateStartupOnboardingProgress(stepIndex: Int, stepCount: Int): Float {
    if (stepCount <= 0) return 0f
    return ((stepIndex + 1).toFloat() / stepCount).coerceIn(0f, 1f)
}

internal fun shouldAdvanceStartupOnboarding(
    permissionRequestActive: Boolean,
    permissionNavigationBlocked: Boolean
): Boolean = !permissionRequestActive && !permissionNavigationBlocked

internal const val STARTUP_STEP_TAP_GUARD_MILLIS = 250L

// 只拦截切页后极短时间内的重复点按, 防止双击越过一步; 不等动画收尾, 连续点按都会生效
internal fun canNavigateStartupOnboardingStep(
    finishing: Boolean,
    millisSinceLastStepChange: Long
): Boolean = !finishing && millisSinceLastStepChange >= STARTUP_STEP_TAP_GUARD_MILLIS

// 返回允许打断动画, 切页本身可逆
internal fun canNavigateStartupOnboardingBack(
    finishing: Boolean,
    stepIndex: Int
): Boolean = !finishing && stepIndex > 0

internal fun canLeaveStartupStepForward(
    step: StartupStep,
    disclaimerAccepted: Boolean
): Boolean = step != StartupStep.Privacy || disclaimerAccepted

internal fun canEnterStartupStep(
    step: StartupStep,
    disclaimerAccepted: Boolean
): Boolean = !step.requiresConsent || disclaimerAccepted

// 同意要等完整说明收起才写入, 这段时间里用户可能已经返回上一步, 那时不能再把人拉去下一步
internal fun shouldAdvanceAfterStartupConsent(currentStep: StartupStep): Boolean =
    currentStep == StartupStep.Privacy

/** 已经做过的选择收进顶部胶囊, 当前步骤和完成页用各自的大卡片承接同一个共享元素 */
internal fun isStartupChoiceInRail(choice: StartupChoice, currentStep: StartupStep): Boolean =
    currentStep.ordinal > choice.step.ordinal && currentStep != StartupStep.LearningGuide

internal const val STARTUP_PRIVACY_COUNTDOWN_SECONDS = 5

internal fun resolveStartupPrivacyCountdownProgress(
    remainingSeconds: Int,
    totalSeconds: Int = STARTUP_PRIVACY_COUNTDOWN_SECONDS
): Float {
    if (totalSeconds <= 0) return 1f
    return (1f - remainingSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
}

internal const val STARTUP_NOTIFICATION_PERMISSION_WARNING_ATTEMPTS = 2

internal fun shouldShowStartupNotificationPermissionWarning(
    permissionSupported: Boolean,
    permissionGranted: Boolean,
    attempts: Int
): Boolean = permissionSupported && !permissionGranted &&
    attempts < STARTUP_NOTIFICATION_PERMISSION_WARNING_ATTEMPTS

internal fun hasFinishedStartupNotificationPermissionWarning(
    attempts: Int
): Boolean = attempts >= STARTUP_NOTIFICATION_PERMISSION_WARNING_ATTEMPTS

internal fun shouldWarnStartupNoPlatformConnected(
    biliState: SavedCookieAuthState,
    neteaseState: SavedCookieAuthState,
    youTubeState: YouTubeAuthState
): Boolean = biliState == SavedCookieAuthState.Missing &&
    neteaseState == SavedCookieAuthState.Missing &&
    youTubeState == YouTubeAuthState.Missing

internal fun shouldPromptStartupEnhancedAdvancedBlur(
    advancedBlurAvailable: Boolean,
    enhancedAdvancedBlurEnabled: Boolean,
    backgroundImageImported: Boolean
): Boolean = advancedBlurAvailable && backgroundImageImported && !enhancedAdvancedBlurEnabled

/**
 * 步骤切换时新旧两页的玻璃卡片都要保持活跃
 * 只激活目标页会让旧页卡片在淡出前先失去底板, 前进后退时看起来一闪
 */
internal fun resolveStartupGlassNavigationOwners(currentStep: StartupStep, targetStep: StartupStep): Set<Any> =
    linkedSetOf(currentStep, targetStep)
