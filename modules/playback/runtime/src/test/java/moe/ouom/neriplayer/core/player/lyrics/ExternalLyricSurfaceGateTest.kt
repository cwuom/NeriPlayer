package moe.ouom.neriplayer.core.player.lyrics

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import moe.ouom.neriplayer.core.player.audio.output.PlaybackSoundOwner
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import org.mockito.Mockito.`when`
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import moe.ouom.neriplayer.core.player.service.lyrics.LiveLyricNotificationBridge
import moe.ouom.neriplayer.core.player.service.lyrics.LiveUpdateFrame
import moe.ouom.neriplayer.data.local.media.displayName
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

class ExternalLyricSurfaceGateTest {

    private val manager = PlayerManager
    private val previousIoScope = manager.ioScope
    private val previousFlags = surfaces.map { (_, flag) -> flag.get() }
    private val previousLoadJob = manager.externalBluetoothLyricsLoadJob
    private val previousTranslationJob = manager.externalBluetoothTranslationLoadJob
    private val previousSongKey = manager.externalBluetoothLyricsSongKey
    private val previousPreferredSource = manager.externalBluetoothPreferredLyricSource
    private val previousLyrics = manager.externalBluetoothLyrics
    private val previousTranslatedLyrics = manager.floatingTranslatedLyrics
    private val previousTranslationMatches = manager.floatingTranslationMatchesByIndex
    private val previousLine = manager._externalBluetoothLyricLineFlow.value
    private val previousTranslatedLine = manager._floatingTranslatedLyricLineFlow.value
    private val previousPayload = manager._externalBluetoothLyricPayloadFlow.value
    private val previousSuperIsland = manager.xiaomiSuperIslandLyricBridge
    private val previousLiveUpdate = manager.liveLyricNotificationBridge
    private val loadScope = TestScope()
    private val song = SongItem(
        id = 42L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null
    )

    @Before
    fun detachLyricSurfaces() {
        surfaces.forEach { (_, flag) -> flag.set(false) }
        manager.ioScope = loadScope
        manager.externalBluetoothLyricsLoadJob = null
        manager.externalBluetoothTranslationLoadJob = null
        manager.xiaomiSuperIslandLyricBridge = null
        manager.liveLyricNotificationBridge = null
    }

    @After
    fun restoreLyricSurfaces() {
        loadScope.cancel()
        surfaces.zip(previousFlags).forEach { (surface, enabled) -> surface.second.set(enabled) }
        manager.ioScope = previousIoScope
        manager.externalBluetoothLyricsLoadJob = previousLoadJob
        manager.externalBluetoothTranslationLoadJob = previousTranslationJob
        manager.externalBluetoothLyricsSongKey = previousSongKey
        manager.externalBluetoothPreferredLyricSource = previousPreferredSource
        manager.externalBluetoothLyrics = previousLyrics
        manager.floatingTranslatedLyrics = previousTranslatedLyrics
        manager.floatingTranslationMatchesByIndex = previousTranslationMatches
        manager._externalBluetoothLyricLineFlow.value = previousLine
        manager._floatingTranslatedLyricLineFlow.value = previousTranslatedLine
        manager._externalBluetoothLyricPayloadFlow.value = previousPayload
        manager.xiaomiSuperIslandLyricBridge = previousSuperIsland
        manager.liveLyricNotificationBridge = previousLiveUpdate
    }

    @Test
    fun `lyrics are not loaded while every external lyric surface is disabled`() {
        manager.syncExternalBluetoothLyrics(song)

        assertNull(manager.externalBluetoothLyricsLoadJob)
        assertEquals(song.stableKey(), manager.externalBluetoothLyricsSongKey)
    }

    @Test
    fun `any enabled surface loads lyrics until the song is cleared`() {
        surfaces.forEach { (name, flag) ->
            flag.set(true)

            manager.syncExternalBluetoothLyrics(song)
            val load = manager.externalBluetoothLyricsLoadJob
            assertEquals(name, true, load?.isActive)

            manager.syncExternalBluetoothLyrics(null)
            assertEquals(name, true, load?.isCancelled)
            assertNull(name, manager.externalBluetoothLyricsLoadJob)
            assertNull(name, manager.externalBluetoothLyricsSongKey)

            flag.set(false)
        }
    }

    @Test
    fun `clearing the external line resets every published lyric value`() {
        manager._externalBluetoothLyricLineFlow.value = "line"
        manager._floatingTranslatedLyricLineFlow.value = "translation"
        manager._externalBluetoothLyricPayloadFlow.value = ExternalBluetoothLyricPayload("line", "translation")

        manager.clearExternalBluetoothLyricLine()

        assertNull(manager._externalBluetoothLyricLineFlow.value)
        assertNull(manager._floatingTranslatedLyricLineFlow.value)
        assertEquals(ExternalBluetoothLyricPayload(), manager._externalBluetoothLyricPayloadFlow.value)
    }

    @Test
    fun `releasing live lyric surfaces switches the island and live update off`() {
        manager.xiaomiSuperIslandLyricEnabled = true
        manager.liveUpdateLyricEnabled = true

        manager.releaseLiveLyricSurfaces()

        assertFalse(manager.xiaomiSuperIslandLyricEnabled)
        assertFalse(manager.liveUpdateLyricEnabled)
        assertNull(manager.xiaomiSuperIslandLyricBridge)
        assertNull(manager.liveLyricNotificationBridge)
    }

    @Test
    fun `live lyric surfaces apply the lyric offset in the same direction as other surfaces`() {
        val bridge = mock(LiveLyricNotificationBridge::class.java)
        val previousPlaying = manager._isPlayingFlow.value
        manager.liveLyricNotificationBridge = bridge
        manager.liveUpdateLyricEnabled = true
        manager._isPlayingFlow.value = true
        val first = LyricEntry(text = "first", startTimeMs = 0L, endTimeMs = 10_000L)
        val second = LyricEntry(text = "second", startTimeMs = 10_000L, endTimeMs = 20_000L)
        manager.externalBluetoothLyrics = listOf(first, second)
        try {
            manager.publishLiveLyricSurfaces(song, positionMs = 8_500L, lyricOffsetMs = 2_000L)

            val frame = capturePublishedFrame(bridge)
            assertEquals("second", frame.lyric)
            assertNull(frame.translation)
            assertEquals(song.displayName(), frame.songTitle)
            assertEquals(song.stableKey(), frame.songKey)
            assertEquals(8_500L, frame.positionMs)
            assertEquals(true, frame.playing)
        } finally {
            manager._isPlayingFlow.value = previousPlaying
        }
    }

    @Test
    fun `live update keeps now playing during intros instead of cancelling`() {
        val bridge = mock(LiveLyricNotificationBridge::class.java)
        val previousPlaying = manager._isPlayingFlow.value
        manager.liveLyricNotificationBridge = bridge
        manager.liveUpdateLyricEnabled = true
        manager._isPlayingFlow.value = true
        manager.externalBluetoothLyrics = listOf(LyricEntry(text = "first", startTimeMs = 10_000L, endTimeMs = 20_000L))
        try {
            manager.publishLiveLyricSurfaces(song, positionMs = 1_000L, lyricOffsetMs = 0L)

            val frame = capturePublishedFrame(bridge)
            assertNull(frame.lyric)
            assertEquals(song.displayName(), frame.songTitle)
            verify(bridge, never()).clear()
        } finally {
            manager._isPlayingFlow.value = previousPlaying
        }
    }

    @Test
    fun `buffering with a play intent keeps the live update in its playing state`() {
        val bridge = mock(LiveLyricNotificationBridge::class.java)
        val previousPlaying = manager._isPlayingFlow.value
        val previousControl = manager._playbackControlPlayingFlow.value
        manager.liveLyricNotificationBridge = bridge
        manager.liveUpdateLyricEnabled = true
        manager._isPlayingFlow.value = false
        manager._playbackControlPlayingFlow.value = true
        manager.externalBluetoothLyrics = listOf(LyricEntry(text = "first", startTimeMs = 0L, endTimeMs = 20_000L))
        try {
            manager.publishLiveLyricSurfaces(song, positionMs = 1_000L, lyricOffsetMs = 0L)

            val frame = capturePublishedFrame(bridge)
            assertEquals("first", frame.lyric)
            assertEquals(true, frame.playing)
            assertFalse(frame.advancing)
        } finally {
            manager._isPlayingFlow.value = previousPlaying
            manager._playbackControlPlayingFlow.value = previousControl
        }
    }

    @Test
    fun `a seek while paused reaches the live update card that is still shown`() {
        val bridge = mock(LiveLyricNotificationBridge::class.java)
        val previousPlaying = manager._isPlayingFlow.value
        val previousControl = manager._playbackControlPlayingFlow.value
        manager.liveLyricNotificationBridge = bridge
        manager.liveUpdateLyricEnabled = true
        manager._isPlayingFlow.value = false
        manager._playbackControlPlayingFlow.value = false
        manager.externalBluetoothLyrics = listOf(LyricEntry(text = "first", startTimeMs = 0L, endTimeMs = 200_000L))
        try {
            manager.publishLiveLyricSurfaces(song, positionMs = 90_000L, lyricOffsetMs = 0L)

            val frame = capturePublishedFrame(bridge)
            assertEquals(90_000L, frame.positionMs)
            assertFalse(frame.playing)
        } finally {
            manager._isPlayingFlow.value = previousPlaying
            manager._playbackControlPlayingFlow.value = previousControl
        }
    }

    @Test
    fun `artwork reaches the live update bridge while it is switched off`() {
        val bridge = mock(LiveLyricNotificationBridge::class.java)
        manager.liveLyricNotificationBridge = bridge
        manager.liveUpdateLyricEnabled = false

        manager.updateLiveUpdateArtwork(song, artwork = null, coverSource = "cover://neri")

        verify(bridge).setArtwork(song.stableKey(), null, "cover://neri")
    }

    @Test
    fun `live update frames carry the playback speed`() {
        val bridge = mock(LiveLyricNotificationBridge::class.java)
        val previousSoundOwner = manager.playbackSoundOwner
        val soundOwner = mock(PlaybackSoundOwner::class.java)
        `when`(soundOwner.state).thenReturn(MutableStateFlow(PlaybackSoundState(speed = 1.5f)))
        manager.liveLyricNotificationBridge = bridge
        manager.liveUpdateLyricEnabled = true
        manager.playbackSoundOwner = soundOwner
        try {
            manager.publishLiveUpdateNowPlaying(song, positionMs = 1_000L)

            assertEquals(1.5f, capturePublishedFrame(bridge).playbackSpeed)
        } finally {
            manager.playbackSoundOwner = previousSoundOwner
        }
    }

    @Test
    fun `clearing the lyric only collapses the live update once the song is gone`() {
        val bridge = mock(LiveLyricNotificationBridge::class.java)
        val previousSong = manager._currentSongFlow.value
        manager.liveLyricNotificationBridge = bridge
        manager.liveUpdateLyricEnabled = true
        try {
            manager._currentSongFlow.value = song
            manager.clearLiveUpdateLyric()
            assertNull(capturePublishedFrame(bridge).lyric)
            verify(bridge, never()).clear()

            manager._currentSongFlow.value = null
            manager.clearLiveUpdateLyric()
            verify(bridge).clear()
        } finally {
            manager._currentSongFlow.value = previousSong
        }
    }

    private fun capturePublishedFrame(bridge: LiveLyricNotificationBridge): LiveUpdateFrame =
        mockingDetails(bridge).invocations
            .filter { it.method.name == "publish" }
            .map { it.arguments.single() as LiveUpdateFrame }
            .single()

    private class SurfaceFlag(val get: () -> Boolean, val set: (Boolean) -> Unit)

    private companion object {
        val surfaces = listOf(
            "bluetooth lyrics" to SurfaceFlag(
                { PlayerManager.externalBluetoothLyricsEnabled },
                { PlayerManager.externalBluetoothLyricsEnabled = it }
            ),
            "bluetooth translation" to SurfaceFlag(
                { PlayerManager.externalBluetoothTranslationEnabled },
                { PlayerManager.externalBluetoothTranslationEnabled = it }
            ),
            "dynamic island" to SurfaceFlag(
                { PlayerManager.dynamicIslandLyricsEnabled },
                { PlayerManager.dynamicIslandLyricsEnabled = it }
            ),
            "ColorOS lockscreen" to SurfaceFlag(
                { PlayerManager.colorOsLockscreenLyricsEnabled },
                { PlayerManager.colorOsLockscreenLyricsEnabled = it }
            ),
            "xiaomi super island" to SurfaceFlag(
                { PlayerManager.xiaomiSuperIslandLyricEnabled },
                { PlayerManager.xiaomiSuperIslandLyricEnabled = it }
            ),
            "live update" to SurfaceFlag(
                { PlayerManager.liveUpdateLyricEnabled },
                { PlayerManager.liveUpdateLyricEnabled = it }
            ),
            "status bar" to SurfaceFlag(
                { PlayerManager.statusBarLyricsEnable },
                { PlayerManager.statusBarLyricsEnable = it }
            ),
            "floating lyrics" to SurfaceFlag(
                { PlayerManager.floatingLyricsEnabled },
                { PlayerManager.floatingLyricsEnabled = it }
            )
        )
    }
}
