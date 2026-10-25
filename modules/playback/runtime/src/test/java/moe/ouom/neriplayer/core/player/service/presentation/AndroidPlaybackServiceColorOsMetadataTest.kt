package moe.ouom.neriplayer.core.player.service.presentation

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.lyrics.coloros.ColorOsLyricRequest
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPlaybackServiceColorOsMetadataTest {
    private val song = SongItem(7_200_001L, "Song", "Artist", "netease", 0L, 60_000L, null)
    private val otherSong = song.copy(id = 7_200_002L, name = "Other")
    private val lines = listOf(LyricEntry("first", 2_000L, 4_000L))
    private val timelineOwner = PlayerManager.colorOsLyricTimelineOwner
    private val previousSong = PlayerManager.currentSongFlow.value
    private val previousEnabled = PlayerManager.colorOsLockscreenLyricsEnabled
    private val previousBluetoothPayload = PlayerManager.externalBluetoothLyricPayloadFlow.value
    private val context: Context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        override fun getPackageName(): String = "moe.ouom.neriplayer.adaptertest"
    }
    private lateinit var source: AndroidPlaybackServicePresentationSource

    @Before
    fun setUp() {
        PlayerTestEnvironment.install()
        PlayerManager._currentSongFlow.value = null
        PlayerManager.colorOsLockscreenLyricsEnabled = false
        timelineOwner.begin(null)
        source = AndroidPlaybackServicePresentationSource(context)
    }

    @After
    fun tearDown() {
        timelineOwner.begin(null)
        PlayerManager._currentSongFlow.value = previousSong
        PlayerManager.colorOsLockscreenLyricsEnabled = previousEnabled
        PlayerManager._externalBluetoothLyricPayloadFlow.value = previousBluetoothPayload
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `disabled metadata omits a loaded timeline and toggling removes the cached payload`() {
        PlayerManager._currentSongFlow.value = song
        publish(song)
        assertFalse(source.metadata().colorOsLyricsEnabled)
        assertNull(source.metadata().colorOsLyricInfo)

        PlayerManager.colorOsLockscreenLyricsEnabled = true
        val enabled = source.metadata()
        assertTrue(enabled.colorOsLyricsEnabled)
        assertEquals("Song", JSONObject(requireNotNull(enabled.colorOsLyricInfo)).getString("songName"))
        PlayerManager.colorOsLockscreenLyricsEnabled = false
        assertNull(source.metadata().colorOsLyricInfo)
        PlayerManager.colorOsLockscreenLyricsEnabled = true
        assertSame(enabled.colorOsLyricInfo, source.metadata().colorOsLyricInfo)
    }

    @Test
    fun `enabled metadata requires both a current song and its authoritative timeline`() {
        PlayerManager.colorOsLockscreenLyricsEnabled = true
        assertNull(source.metadata().colorOsLyricInfo)
        assertNull(source.metadata().colorOsLyricInfo)
        publish(song)
        assertNull(source.metadata().colorOsLyricInfo)

        PlayerManager._currentSongFlow.value = song
        val metadata = JSONObject(requireNotNull(source.metadata().colorOsLyricInfo))
        assertEquals(song.stableKey(), metadata.getString("songId"))
        assertEquals("moe.ouom.neriplayer.adaptertest", metadata.getString("provider"))
        timelineOwner.begin(song.stableKey())
        assertNull(source.metadata().colorOsLyricInfo)
        assertNull(source.metadata().colorOsLyricInfo)
    }

    @Test
    fun `a timeline from another track never attaches to current metadata`() {
        PlayerManager.colorOsLockscreenLyricsEnabled = true
        PlayerManager._currentSongFlow.value = song
        publish(otherSong)
        assertNull(source.metadata().colorOsLyricInfo)

        PlayerManager._currentSongFlow.value = otherSong
        val otherMetadata = JSONObject(requireNotNull(source.metadata().colorOsLyricInfo))
        assertEquals(otherSong.stableKey(), otherMetadata.getString("trackKey"))
        assertEquals("Other", otherMetadata.getString("songName"))
        PlayerManager._currentSongFlow.value = song
        assertNull(source.metadata().colorOsLyricInfo)
        publish(song)
        assertEquals("Song", JSONObject(requireNotNull(source.metadata().colorOsLyricInfo)).getString("songName"))
    }

    @Test
    fun `adapter reuses full payload across line changes and updates translation offset identity and clear`() {
        PlayerManager.colorOsLockscreenLyricsEnabled = true
        PlayerManager._currentSongFlow.value = song
        val request = publish(song)
        val original = requireNotNull(source.metadata().colorOsLyricInfo)
        assertEquals("[00:02.000]first\n", JSONObject(original).getString("lyric"))
        PlayerManager._externalBluetoothLyricPayloadFlow.value = ExternalBluetoothLyricPayload("next line")
        val nextLine = source.metadata()
        assertEquals("next line", nextLine.payload.lyric)
        assertSame(original, nextLine.colorOsLyricInfo)

        val translations = listOf(LyricEntry("第一句", 2_000L, 4_000L))
        assertTrue(timelineOwner.publish(request, lines, translations, 0L))
        val translated = requireNotNull(source.metadata().colorOsLyricInfo)
        assertNotSame(original, translated)
        assertEquals("[00:02.000]第一句\n", JSONObject(translated).getString("translationLyric"))
        timelineOwner.updateOffset(500L)
        val offset = JSONObject(requireNotNull(source.metadata().colorOsLyricInfo))
        assertEquals("[00:01.500]first\n", offset.getString("lyric"))
        assertEquals("[00:01.500]第一句\n", offset.getString("translationLyric"))

        PlayerManager._currentSongFlow.value = song.copy(customName = "Display name", customArtist = "Display artist")
        val renamed = requireNotNull(source.metadata().colorOsLyricInfo)
        assertEquals("Display name", JSONObject(renamed).getString("songName"))
        assertEquals("Display artist", JSONObject(renamed).getString("artist"))
        assertEquals(request.generation, JSONObject(renamed).getLong("sessionGeneration"))
        assertSame(renamed, source.metadata().colorOsLyricInfo)
        timelineOwner.clear()
        assertNull(source.metadata().colorOsLyricInfo)
        assertTrue(timelineOwner.publish(request, emptyList(), emptyList(), 0L))
        assertNull(source.metadata().colorOsLyricInfo)
    }

    private fun publish(track: SongItem): ColorOsLyricRequest {
        val request = timelineOwner.begin(track.stableKey())
        assertTrue(timelineOwner.publish(request, lines, emptyList(), 0L))
        return request
    }
}
