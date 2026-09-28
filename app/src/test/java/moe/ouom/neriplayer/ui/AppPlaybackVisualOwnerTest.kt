package moe.ouom.neriplayer.ui

import android.content.Context
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.ui.playback.visual.BackdropAccentRequest
import moe.ouom.neriplayer.ui.playback.visual.BackdropAccentSampleSource
import moe.ouom.neriplayer.ui.playback.visual.PlaybackVisualCoverRequest
import moe.ouom.neriplayer.ui.playback.visual.PlaybackVisualCoverState
import moe.ouom.neriplayer.ui.playback.visual.backdropAccentColor
import moe.ouom.neriplayer.ui.playback.visual.backdropAccentRequest
import moe.ouom.neriplayer.ui.playback.visual.hasCurrentOrRetainedVisualCover
import moe.ouom.neriplayer.ui.playback.visual.isCurrentAccentRequest
import moe.ouom.neriplayer.ui.playback.visual.loadBackdropAccent
import moe.ouom.neriplayer.ui.playback.visual.playbackVisualCoverRequest
import moe.ouom.neriplayer.ui.playback.visual.resolveActiveCoverSeedHex
import moe.ouom.neriplayer.ui.playback.visual.resolvePlaybackVisualCoverState
import moe.ouom.neriplayer.ui.playback.visual.shouldClearRetainedPlaybackVisualCoverAfterGrace
import moe.ouom.neriplayer.ui.playback.visual.shouldCommitRetainedPlaybackVisualCoverClear
import moe.ouom.neriplayer.ui.playback.visual.shouldRetainNowPlayingBlurCover
import moe.ouom.neriplayer.ui.playback.visual.shouldScheduleRetainedPlaybackVisualCoverClear
import moe.ouom.neriplayer.util.media.CoverArtColorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class AppPlaybackVisualOwnerTest {
    @Test
    fun accentLoadingEmitsCachedAndFreshSamplesButDropsStaleRequests() = runTest {
        val cached = CoverArtColorSample("#111111", 0xFF111111.toInt())
        val loaded = CoverArtColorSample("#222222", 0xFF222222.toInt())
        val samples = mutableListOf<CoverArtColorSample?>()
        val source = object : BackdropAccentSampleSource {
            override fun cached(coverUrl: String): CoverArtColorSample? = cached
            override suspend fun load(
                context: Context, coverUrl: String, offlineMode: Boolean
            ): CoverArtColorSample? = loaded
        }
        val context = mock(Context::class.java)

        loadBackdropAccent(source, context, "cover", false, { true }) { samples.add(it) }
        assertEquals(listOf(cached, loaded), samples)

        samples.clear()
        loadBackdropAccent(source, context, "cover", true, { false }) { samples.add(it) }
        assertEquals(emptyList<CoverArtColorSample>(), samples)

        val emptySource = object : BackdropAccentSampleSource {
            override fun cached(coverUrl: String): CoverArtColorSample? = null
            override suspend fun load(
                context: Context, coverUrl: String, offlineMode: Boolean
            ): CoverArtColorSample? = null
        }
        loadBackdropAccent(emptySource, context, "missing", false, { true }) { samples.add(it) }
        assertTrue(samples.isEmpty())
    }

    @Test
    fun missingAccentCoverClearsOnlyAfterTheGracePeriodForTheCurrentRequest() = runTest {
        val samples = mutableListOf<CoverArtColorSample?>()
        val source = object : BackdropAccentSampleSource {
            override fun cached(coverUrl: String): CoverArtColorSample? = null
            override suspend fun load(
                context: Context, coverUrl: String, offlineMode: Boolean
            ): CoverArtColorSample? = null
        }
        val context = mock(Context::class.java)

        loadBackdropAccent(source, context, null, false, { true }) { samples.add(it) }
        assertEquals(listOf(null), samples)
        samples.clear()
        loadBackdropAccent(source, context, null, false, { false }) { samples.add(it) }
        assertTrue(samples.isEmpty())
    }

    @Test
    fun retainedCoverClearsOnlyAfterPlaybackEndsWithoutAReplacement() {
        fun shouldClear(
            songKey: String? = null,
            retained: String? = "old-cover",
            requested: String? = null,
            elapsed: Boolean = true
        ) = shouldClearRetainedPlaybackVisualCoverAfterGrace(
            currentSongKey = songKey,
            retainedCoverUrl = retained,
            requestedCoverUrl = requested,
            clearDelayElapsed = elapsed
        )

        assertTrue(shouldClear())
        assertTrue(shouldClear(songKey = "", requested = " "))
        assertFalse(shouldClear(songKey = "playing"))
        assertFalse(shouldClear(elapsed = false))
        assertFalse(shouldClear(retained = null))
        assertFalse(shouldClear(retained = " "))
        assertFalse(shouldClear(requested = "new-cover"))
    }

    @Test
    fun blurredCoverIsRetainedForAnActiveSongOrPendingReplacement() {
        assertFalse(shouldRetainNowPlayingBlurCover(null, "song", null))
        assertFalse(shouldRetainNowPlayingBlurCover(" ", "song", null))
        assertFalse(shouldRetainNowPlayingBlurCover("old", null, null))
        assertTrue(shouldRetainNowPlayingBlurCover("old", "song", null))
        assertTrue(shouldRetainNowPlayingBlurCover("old", null, "new"))
    }

    @Test
    fun unresolvedCoverKeepsPreviousOwnerButNotAfterPlaybackStops() {
        val previous = PlaybackVisualCoverState("cover-a", "song-a")
        assertEquals(
            previous,
            resolvePlaybackVisualCoverState(null, previous, "song-b", true)
        )
        assertEquals(
            PlaybackVisualCoverState(null, null),
            resolvePlaybackVisualCoverState(null, previous, null, false)
        )
        assertEquals(
            PlaybackVisualCoverState(null, null),
            resolvePlaybackVisualCoverState(null, null, "song-b", true)
        )
        assertEquals(
            PlaybackVisualCoverState("cover-b", "song-b"),
            resolvePlaybackVisualCoverState(" cover-b ", previous, "song-b", true)
        )
    }

    @Test
    fun retainedCoverGraceIgnoresAReplacedSongOrCover() {
        val oldState = PlaybackVisualCoverState("old-cover", "old-song")
        assertTrue(hasCurrentOrRetainedVisualCover(null, oldState))
        assertTrue(
            hasCurrentOrRetainedVisualCover(
                "new-song",
                PlaybackVisualCoverState(null, null)
            )
        )
        assertFalse(hasCurrentOrRetainedVisualCover(null, PlaybackVisualCoverState(null, null)))
        assertTrue(shouldScheduleRetainedPlaybackVisualCoverClear(oldState, null))
        assertFalse(shouldScheduleRetainedPlaybackVisualCoverClear(oldState, "new-cover"))
        assertFalse(
            shouldScheduleRetainedPlaybackVisualCoverClear(
                PlaybackVisualCoverState(null, null), null
            )
        )

        fun canCommit(
            requestSongKey: String? = null,
            latestSongKey: String? = null,
            latestCoverUrl: String? = null,
            latestState: PlaybackVisualCoverState = oldState
        ) = shouldCommitRetainedPlaybackVisualCoverClear(
            requestSongKey = requestSongKey,
            stateAtStart = oldState,
            latestSongKey = latestSongKey,
            latestCoverUrl = latestCoverUrl,
            latestState = latestState
        )

        assertTrue(canCommit())
        assertFalse(canCommit(requestSongKey = "old-song"))
        assertFalse(canCommit(latestSongKey = "new-song"))
        assertFalse(canCommit(latestCoverUrl = "new-cover"))
        assertFalse(canCommit(latestState = PlaybackVisualCoverState("new-cover", "new-song")))
    }

    @Test
    fun sampledSeedBelongsToVisibleCoverOrItsCurrentSong() {
        assertNull(resolveActiveCoverSeedHex(null, "sample", "seed"))
        assertNull(resolveActiveCoverSeedHex("visual", null, "seed"))
        assertNull(resolveActiveCoverSeedHex("visual", "other", "seed"))
        assertEquals("seed", resolveActiveCoverSeedHex("same", "same", "seed"))
        assertEquals(
            "seed",
            resolveActiveCoverSeedHex(
                "visual", "other", "seed",
                currentSongKey = "song",
                sampledSongKey = "song"
            )
        )
        assertNull(resolveActiveCoverSeedHex("same", "same", null))
    }

    @Test
    fun staleAccentSamplesCannotReplaceTheCurrentCover() {
        assertTrue(isCurrentAccentRequest("cover", "song", "cover", "song"))
        assertFalse(isCurrentAccentRequest("cover", "song", "next", "song"))
        assertFalse(isCurrentAccentRequest("cover", "song", "cover", "next"))
        assertTrue(isCurrentAccentRequest(null, null, null, null))
        assertNull(backdropAccentColor(null, true))
        val sample = CoverArtColorSample("#336699", 0xFF336699.toInt())
        assertNotNull(backdropAccentColor(sample, true))
        assertNotNull(backdropAccentColor(sample, false))
        assertEquals(
            BackdropAccentRequest("cover", "song", true, 2, false),
            backdropAccentRequest(" cover ", "song", true, 2, false)
        )
        assertNull(backdropAccentRequest("  ", null, false, 0, true).coverUrl)
        assertEquals(
            PlaybackVisualCoverRequest("cover", "song"),
            playbackVisualCoverRequest(" cover ", "song")
        )
        assertEquals(
            PlaybackVisualCoverRequest(null, null),
            playbackVisualCoverRequest(null, null)
        )
        assertEquals(
            PlaybackVisualCoverRequest(null, "song"),
            playbackVisualCoverRequest("  ", "song")
        )
    }
}
