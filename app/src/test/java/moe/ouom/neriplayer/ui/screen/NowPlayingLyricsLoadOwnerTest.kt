package moe.ouom.neriplayer.ui.screen

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LoadedLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingFastLyricsResult
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadRequest
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingLyricsLoadOwnerTest {
    private val context = Mockito.mock(Context::class.java)
    private val song = SongItem(41L, "Current", "Artist", "Album", 1L, 60_000L, null)

    @Test
    fun `new request cancels old background publication`() = runTest {
        val oldBackground = CompletableDeferred<LoadedLyricsState>()
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) =
                fastState(request.song?.name.orEmpty())

            override suspend fun readBackground(
                request: NowPlayingLyricsLoadRequest,
                fast: NowPlayingFastLyricsResult
            ): LoadedLyricsState = if (request.song?.name == "Old") {
                oldBackground.await()
            } else {
                lyricState("new background")
            }
        }
        val owner = NowPlayingLyricsLoadOwner(lyricState("initial"), this, stages)
        owner.reload(request(song.copy(name = "Old")))
        runCurrent()
        assertEquals("Old", owner.state.rawLyrics)
        assertFalse(owner.secondaryResolved)

        owner.reload(request(song.copy(name = "New")))
        runCurrent()
        assertEquals("new background", owner.state.rawLyrics)
        assertTrue(owner.secondaryResolved)

        oldBackground.complete(lyricState("stale background"))
        runCurrent()
        assertEquals("new background", owner.state.rawLyrics)
    }

    @Test
    fun `empty refresh retains rendered lyrics and disposal rejects late publish`() = runTest {
        val empty = lyricState(null)
        val stages = object : NowPlayingLyricsStages {
            override suspend fun readFast(request: NowPlayingLyricsLoadRequest) =
                NowPlayingFastLyricsResult(empty, null, null, false, false)

            override suspend fun readBackground(
                request: NowPlayingLyricsLoadRequest,
                fast: NowPlayingFastLyricsResult
            ) = empty
        }
        val owner = NowPlayingLyricsLoadOwner(lyricState("visible"), this, stages)
        owner.reload(request(song))
        runCurrent()
        assertEquals("visible", owner.state.rawLyrics)
        assertTrue(owner.secondaryResolved)

        owner.dispose()
        owner.publish(1L, song, lyricState("late"), "background")
        assertEquals("visible", owner.state.rawLyrics)
    }

    private fun request(song: SongItem) = NowPlayingLyricsLoadRequest(
        context, song, null, true, LyricSourcePreference.Automatic, null
    )

    private fun fastState(text: String) = NowPlayingFastLyricsResult(
        lyricState(text), null, null, false, false
    )

    private fun lyricState(text: String?): LoadedLyricsState = buildNowPlayingFastLyricsState(text, null, null)
}
