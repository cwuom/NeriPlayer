package moe.ouom.neriplayer.core.api.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLyricMatchPolicyTest {

    @Test
    fun durationPolicyAcceptsASlightlyDifferentVersion() {
        assertTrue(
            isExternalLyricDurationCompatible(
                expectedDurationMs = 180_000L,
                candidateDurationMs = 190_000L
            )
        )
    }

    @Test
    fun durationPolicyStillRejectsADifferentArrangement() {
        assertFalse(
            isExternalLyricDurationCompatible(
                expectedDurationMs = 180_000L,
                candidateDurationMs = 205_000L
            )
        )
    }
}
