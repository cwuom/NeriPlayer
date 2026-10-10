package moe.ouom.neriplayer.core.player.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerManagerExternalLyricSyncTest {
    private val pausedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private lateinit var previousIoScope: CoroutineScope

    @Before
    fun setUp() {
        PlayerTestEnvironment.install()
        previousIoScope = PlayerManager.ioScope
        PlayerManager.ioScope = pausedScope
    }

    @After
    fun tearDown() {
        with(PlayerManager) {
            externalBluetoothLyricsLoadJob = null
            externalBluetoothTranslationLoadJob = null
            externalBluetoothLyricsEnabled = false
            externalBluetoothTranslationEnabled = false
            externalBluetoothLyrics = emptyList()
            externalBluetoothLyricsSongKey = null
            _currentSongFlow.value = null
            clearExternalBluetoothLyricLine()
            ioScope = previousIoScope
        }
        pausedScope.cancel()
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `next external lyric line is measured only for the song that owns the lyrics`() {
        PlayerManager.externalBluetoothLyrics = lyrics
        PlayerManager.externalBluetoothLyricsSongKey = song.stableKey()
        assertNull(PlayerManager.msUntilNextExternalLyricLine(500L))

        PlayerManager._currentSongFlow.value = otherSong
        assertNull(PlayerManager.msUntilNextExternalLyricLine(500L))

        PlayerManager._currentSongFlow.value = song
        val untilSecondLine = requireNotNull(PlayerManager.msUntilNextExternalLyricLine(200L))
        assertTrue(untilSecondLine in 1L..2_000L)
        assertEquals(untilSecondLine - 100L, PlayerManager.msUntilNextExternalLyricLine(300L))
    }

    @Test
    fun `disabled translations clear the published lyric line`() {
        PlayerManager._externalBluetoothLyricLineFlow.value = "stale"
        PlayerManager._floatingTranslatedLyricLineFlow.value = "stale translation"

        PlayerManager.syncExternalTranslatedLyrics(song)

        assertNull(PlayerManager._externalBluetoothLyricLineFlow.value)
        assertNull(PlayerManager._floatingTranslatedLyricLineFlow.value)
        assertEquals(ExternalBluetoothLyricPayload(), PlayerManager._externalBluetoothLyricPayloadFlow.value)
        assertNull(PlayerManager.externalBluetoothTranslationLoadJob)
    }

    @Test
    fun `missing song clears the line even when translations are enabled`() {
        PlayerManager.externalBluetoothTranslationEnabled = true
        PlayerManager._externalBluetoothLyricLineFlow.value = "stale"

        PlayerManager.syncExternalTranslatedLyrics(null)

        assertNull(PlayerManager._externalBluetoothLyricLineFlow.value)
        assertNull(PlayerManager.externalBluetoothLyricsLoadJob)
    }

    @Test
    fun `translations for a song without loaded lyrics reload the lyrics first`() {
        PlayerManager.externalBluetoothTranslationEnabled = true
        PlayerManager.externalBluetoothLyrics = lyrics
        PlayerManager.externalBluetoothLyricsSongKey = otherSong.stableKey()

        PlayerManager.syncExternalTranslatedLyrics(song)

        assertEquals(song.stableKey(), PlayerManager.externalBluetoothLyricsSongKey)
        assertTrue(PlayerManager.externalBluetoothLyrics.isEmpty())
        assertTrue(PlayerManager.externalBluetoothLyricsLoadJob?.isActive == true)
        assertNull(PlayerManager.externalBluetoothTranslationLoadJob)
    }

    @Test
    fun `translations for the loaded song start a translation load and replace the previous one`() {
        PlayerManager.externalBluetoothTranslationEnabled = true
        PlayerManager.externalBluetoothLyrics = lyrics
        PlayerManager.externalBluetoothLyricsSongKey = song.stableKey()

        PlayerManager.syncExternalTranslatedLyrics(song)
        val firstLoad = PlayerManager.externalBluetoothTranslationLoadJob
        PlayerManager.syncExternalTranslatedLyrics(song)

        assertTrue(firstLoad?.isCancelled == true)
        assertTrue(PlayerManager.externalBluetoothTranslationLoadJob?.isActive == true)
        assertEquals(lyrics, PlayerManager.externalBluetoothLyrics)
        assertNull(PlayerManager.externalBluetoothLyricsLoadJob)
    }

    private val lyrics = listOf(
        LyricEntry(text = "first", startTimeMs = 0L, endTimeMs = 2_000L),
        LyricEntry(text = "second", startTimeMs = 2_000L, endTimeMs = 4_000L)
    )

    private val song = SongItem(
        id = 7_100_001L,
        name = "External",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null
    )

    private val otherSong = song.copy(id = 7_100_002L, name = "Other")
}
