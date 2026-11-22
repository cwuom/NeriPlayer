package moe.ouom.neriplayer.core.player.lan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.lan.LanFileResource
import moe.ouom.neriplayer.data.model.lan.LanSharedLyricLine
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanPlaybackArtworkTest {
    private val metadata = LanSharedPresentation("Song", "Artist", "Album", lyricOffsetMs = 80)
    private val cover = LanFileResource("Cover", "a".repeat(64), 16, "image/png")
    private val lyrics = metadata.copy(lyrics = listOf(LanSharedLyricLine("Line", 0, 2_000)))

    @Test fun coverIsPublishedDuringLyricsResolutionAndSurvivesItsCompletion() = runTest {
        val pendingLyrics = CompletableDeferred<LanSharedPresentation>()
        val artwork = MutableStateFlow<LanFileResource?>(null)
        val updates = mutableListOf<LanSharedPresentation>()
        backgroundScope.launch {
            lanPresentationUpdates(metadata, pendingLyrics::await, artwork.filterNotNull()).collect { updates += it }
        }
        runCurrent()

        artwork.value = cover
        runCurrent()
        assertEquals(metadata.copy(cover = cover), updates.last())

        pendingLyrics.complete(lyrics)
        runCurrent()
        assertEquals(lyrics.copy(cover = cover), updates.last())
    }

    @Test fun lyricsArePublishedWithNoCoverAndSurviveLaterArtworkUpdates() = runTest {
        val artwork = MutableStateFlow<LanFileResource?>(null)
        val updates = mutableListOf<LanSharedPresentation>()
        backgroundScope.launch {
            lanPresentationUpdates(metadata, { lyrics }, artwork.filterNotNull()).collect { updates += it }
        }
        runCurrent()
        assertEquals(lyrics, updates.last())

        artwork.value = cover
        runCurrent()
        assertEquals(lyrics.copy(cover = cover), updates.last())
    }
}
