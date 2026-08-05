package moe.ouom.neriplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `visual cover keeps previous image while new cover resolves`() {
        assertEquals(
            "old-cover",
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = null,
                previousVisualCoverUrl = "old-cover",
                hasCurrentSong = true,
                clearDelayElapsed = false
            )
        )
    }

    @Test
    fun `visual cover clears after grace period or when playback stops`() {
        assertEquals(
            null,
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = null,
                previousVisualCoverUrl = "old-cover",
                hasCurrentSong = true,
                clearDelayElapsed = true
            )
        )
        assertEquals(
            null,
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = null,
                previousVisualCoverUrl = "old-cover",
                hasCurrentSong = false,
                clearDelayElapsed = false
            )
        )
    }

    @Test
    fun `visual cover prefers current non blank image`() {
        assertEquals(
            "new-cover",
            resolvePlaybackVisualCoverUrl(
                currentCoverUrl = "  new-cover  ",
                previousVisualCoverUrl = "old-cover",
                hasCurrentSong = true,
                clearDelayElapsed = false
            )
        )
    }

    @Test
    fun `cover seed is ignored until it belongs to the visual cover`() {
        assertNull(
            resolveActiveCoverSeedHex(
                visualCoverUrl = "new-cover",
                sampledCoverUrl = "old-cover",
                sampledSeedHex = "112233"
            )
        )
        assertEquals(
            "445566",
            resolveActiveCoverSeedHex(
                visualCoverUrl = "new-cover",
                sampledCoverUrl = "new-cover",
                sampledSeedHex = "445566"
            )
        )
    }

    @Test
    fun `cover seed follows the normalized cover cache key`() {
        assertEquals(
            "778899",
            resolveActiveCoverSeedHex(
                visualCoverUrl = "https://p1.music.126.net/cover.jpg?param=140y140",
                sampledCoverUrl = "https://p2.music.126.net/cover.jpg?param=500y500",
                sampledSeedHex = "778899"
            )
        )
    }
}
