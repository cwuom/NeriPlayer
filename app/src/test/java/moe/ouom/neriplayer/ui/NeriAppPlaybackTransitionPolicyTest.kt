package moe.ouom.neriplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NeriAppPlaybackTransitionPolicyTest {

    @Test
    fun `cover seed warmup is deferred only for uncached mini player transitions`() {
        assertEquals(
            180L,
            resolveCoverSeedWarmupDelayMillis(
                showNowPlaying = false,
                dynamicColorEnabled = true,
                hasCachedSample = false
            )
        )
    }

    @Test
    fun `cover seed warmup is immediate when now playing is already visible`() {
        assertEquals(
            0L,
            resolveCoverSeedWarmupDelayMillis(
                showNowPlaying = true,
                dynamicColorEnabled = true,
                hasCachedSample = false
            )
        )
    }

    @Test
    fun `cover seed warmup is skipped when dynamic color is disabled or cache is warm`() {
        assertEquals(
            0L,
            resolveCoverSeedWarmupDelayMillis(
                showNowPlaying = false,
                dynamicColorEnabled = false,
                hasCachedSample = false
            )
        )
        assertEquals(
            0L,
            resolveCoverSeedWarmupDelayMillis(
                showNowPlaying = false,
                dynamicColorEnabled = true,
                hasCachedSample = true
            )
        )
    }
}
