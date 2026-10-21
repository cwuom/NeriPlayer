package moe.ouom.neriplayer.lyrics.lyricon

import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.CachedRemotePlayer
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.lyrics.output.LyriconLyricsLoader
import moe.ouom.neriplayer.lyrics.output.LyriconPlaybackOutput
import moe.ouom.neriplayer.lyrics.output.LyriconPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class LyriconTranslationPublicationTest {
    private val remote = mock(RemotePlayer::class.java)
    private val player = CachedRemotePlayer(remote)
    private val publications = mutableListOf<Publication>()

    @Before
    fun setUp() {
        LyriconManager.release()
        val provider = mock(LyriconProvider::class.java)
        `when`(provider.player).thenReturn(player)
        LyriconManager::class.java.getDeclaredField("provider").apply {
            isAccessible = true
            set(null, provider)
        }
        LyriconManager.setEnabled(true)
        doAnswer { invocation ->
            publications += Publication(invocation.getArgument(0), player.lastDisplayTranslation)
            true
        }.`when`(remote).setSong(any(Song::class.java))
    }

    @After
    fun tearDown() {
        LyriconManager.release()
    }

    @Test
    fun `delayed translations are enabled when complete lyrics first reach the player after each song change`() = runTest {
        val ready = List(2) { CompletableDeferred<Unit>() }
        val output = LyriconPlaybackOutput(
            loader = LyriconLyricsLoader { song, _, publish ->
                ready[song.id.toInt() - 1].await()
                publish(listOf(line("original")), listOf(line("translated")), null)
            },
            stableKey = { it.id.toString() },
            sameIdentity = { song, current -> song.id == current?.id },
        )

        for (id in 1L..2L) {
            val song = song(id)
            output.publishSong(song)
            output.syncSong(this, song, LyriconPreferences(enabled = true))
            runCurrent()
            ready[id.toInt() - 1].complete(Unit)
            runCurrent()

            val publication = publications.last()
            assertEquals(id.toString(), publication.song?.id)
            assertEquals("original", publication.song?.lyrics?.single()?.text)
            assertEquals("translated", publication.song?.lyrics?.single()?.translation)
            assertEquals(true, publication.displayTranslation)
        }
        assertEquals(listOf(false, true, false, true), publications.map { it.displayTranslation })
    }

    @Test
    fun `a song without translation clears the previous translation mode before publication`() {
        LyriconManager.updateSong(song(1), listOf(line("original")), listOf(line("translated")))
        LyriconManager.updateSong(song(2), listOf(line("plain")), emptyList())

        val publication = publications.last()
        assertEquals("2", publication.song?.id)
        assertEquals("plain", publication.song?.lyrics?.single()?.text)
        assertEquals(null, publication.song?.lyrics?.single()?.translation)
        assertEquals(false, publication.displayTranslation)
    }

    private fun line(text: String) = LyricEntry(text = text, startTimeMs = 0, endTimeMs = 1_000)

    private fun song(id: Long) = SongItem(
        id = id, name = "song$id", artist = "artist", album = "album", albumId = 0,
        durationMs = 10_000, coverUrl = null,
    )

    private data class Publication(val song: Song?, val displayTranslation: Boolean?)
}
