package moe.ouom.neriplayer.ui.feedback

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFeedbackPolicyTest {
    @Test
    fun foregroundPrefersSnackbarWhenHostExists() {
        assertEquals(
            FeedbackDelivery.Snackbar,
            resolveFeedbackDelivery(
                isForeground = true,
                hasSnackbarHost = true,
                preferSnackbar = true,
                forceToast = false
            )
        )
    }

    @Test
    fun foregroundUsesStyledToastWhenToastIsForced() {
        assertEquals(
            FeedbackDelivery.StyledToast,
            resolveFeedbackDelivery(
                isForeground = true,
                hasSnackbarHost = true,
                preferSnackbar = true,
                forceToast = true
            )
        )
    }

    @Test
    fun backgroundAlwaysUsesSystemToast() {
        assertEquals(
            FeedbackDelivery.SystemToast,
            resolveFeedbackDelivery(
                isForeground = false,
                hasSnackbarHost = true,
                preferSnackbar = true,
                forceToast = false
            )
        )
    }

    @Test
    fun styledToastViewIsDisabledForBackgroundAndroidRAndAbove() {
        assertFalse(
            canUseStyledToastView(
                isForeground = false,
                sdkInt = Build.VERSION_CODES.R
            )
        )
    }

    @Test
    fun styledToastViewIsAllowedForForeground() {
        assertTrue(
            canUseStyledToastView(
                isForeground = true,
                sdkInt = Build.VERSION_CODES.R
            )
        )
    }

    @Test
    fun duplicateFeedbackWithinWindowIsSkipped() {
        assertTrue(
            isDuplicateFeedbackMessage(
                last = FeedbackDedupState("已复制", shownAtMs = 1_000L),
                message = "已复制",
                nowMs = 2_000L
            )
        )
    }

    @Test
    fun duplicateFeedbackAfterWindowIsAllowed() {
        assertFalse(
            isDuplicateFeedbackMessage(
                last = FeedbackDedupState("已复制", shownAtMs = 1_000L),
                message = "已复制",
                nowMs = 3_000L
            )
        )
    }
}
