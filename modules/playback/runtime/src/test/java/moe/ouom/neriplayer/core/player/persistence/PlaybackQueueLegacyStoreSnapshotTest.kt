package moe.ouom.neriplayer.core.player.persistence

import com.google.gson.Gson
import java.io.File
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playback.PersistedPlaybackState
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackQueueLegacyStoreSnapshotTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val gson = Gson()
    private val queue = PersistedState(
        playlist = listOf(
            PersistedSongItem(
                id = 1, name = "Song", artist = "Artist", album = "Album", albumId = 3,
                durationMs = 180_000, coverUrl = null, channelId = "netease", audioId = "1"
            )
        ),
        index = 0,
        mediaUrl = "https://old.example/song.flac",
        positionMs = 10_000,
        shouldResumePlayback = false,
        repeatMode = 0,
        shuffleEnabled = false
    )
    private val playback = PersistedPlaybackState(
        index = 0,
        mediaUrl = "https://new.example/song.flac",
        positionMs = 42_000,
        shouldResumePlayback = true,
        repeatMode = 2,
        shuffleEnabled = true
    )
    private lateinit var stateFile: File
    private lateinit var playbackFile: File
    private lateinit var store: PlaybackQueueLegacyStore

    @Before
    fun createStore() {
        val directory = temporary.newFolder()
        stateFile = File(directory, "player_state.json")
        playbackFile = File(directory, "player_playback_state.json")
        store = PlaybackQueueLegacyStore(stateFile, playbackFile, gson)
    }

    @Test
    fun `nothing is restored without a queue snapshot`() {
        playbackFile.writeText(gson.toJson(playback))

        assertNull(store.read())
    }

    @Test
    fun `a queue snapshot alone keeps its embedded playback position`() {
        stateFile.writeText(gson.toJson(queue))

        assertEquals(queue, store.read())
    }

    @Test
    fun `a separate playback snapshot overrides the embedded position`() {
        stateFile.writeText(gson.toJson(queue))
        playbackFile.writeText(gson.toJson(playback))

        val restored = store.read()

        assertEquals(queue.withPlaybackState(playback), restored)
        assertEquals(42_000L, restored?.positionMs)
    }

    @Test
    fun `an unreadable playback snapshot falls back to the queue snapshot`() {
        stateFile.writeText(gson.toJson(queue))
        playbackFile.writeText("[1, 2]")

        assertEquals(queue, store.read())
    }

    @Test
    fun `an unknown lyric source from a newer build restores the song without a source`() {
        val withSource = queue.copy(
            playlist = queue.playlist.map { it.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC, matchedSongId = "9") }
        )
        stateFile.writeText(gson.toJson(withSource).replace("\"QQ_MUSIC\"", "\"FUTURE_PLATFORM\""))

        val restored = store.read()

        assertEquals(
            withSource.copy(playlist = withSource.playlist.map { it.copy(matchedLyricSource = null) }),
            restored
        )
    }

    @Test
    fun `last modified reports the newest existing snapshot file`() {
        assertEquals(0L, store.lastModified())

        stateFile.writeText("{}")
        stateFile.setLastModified(5_000L)
        assertEquals(5_000L, store.lastModified())

        playbackFile.writeText("{}")
        playbackFile.setLastModified(9_000L)
        assertEquals(9_000L, store.lastModified())

        stateFile.delete()
        assertEquals(9_000L, store.lastModified())

        stateFile.writeText("{}")
        stateFile.setLastModified(12_000L)
        assertEquals(12_000L, store.lastModified())
    }
}
