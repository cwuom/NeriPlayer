package moe.ouom.neriplayer.data.auth.youtube

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeCookieRotationOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAuthRotationWorkerTest {
    @Test
    fun retriesTransientFailuresTwiceBeforeReturningToThePeriodicSchedule() {
        val failures = listOf(
            YouTubeCookieRotationOutcome.NetworkError,
            YouTubeCookieRotationOutcome.Rejected(408),
            YouTubeCookieRotationOutcome.Rejected(429),
            YouTubeCookieRotationOutcome.Rejected(503)
        )
        failures.forEach { failure ->
            assertTrue(shouldRetryPeriodicRotation(failure, 0))
            assertTrue(shouldRetryPeriodicRotation(failure, 1))
            assertFalse(shouldRetryPeriodicRotation(failure, 2))
        }
    }

    @Test
    fun doesNotStartARetryForInvalidCredentialsOrRecentlyCheckedSessions() {
        listOf(
            YouTubeCookieRotationOutcome.Rejected(401),
            YouTubeCookieRotationOutcome.Rejected(403),
            YouTubeCookieRotationOutcome.Throttled,
            YouTubeCookieRotationOutcome.Unchanged,
            YouTubeCookieRotationOutcome.Skipped
        ).forEach { outcome ->
            assertFalse(shouldRetryPeriodicRotation(outcome, 0))
        }
        assertTrue(shouldRetryPeriodicRotation(YouTubeCookieRotationOutcome.Throttled, 1))
    }
}
