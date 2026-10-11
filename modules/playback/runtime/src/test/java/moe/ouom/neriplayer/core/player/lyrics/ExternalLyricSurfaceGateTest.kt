package moe.ouom.neriplayer.core.player.lyrics

import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
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
import moe.ouom.neriplayer.data.local.media.displayName
import org.mockito.Mockito.verify
import org.mockito.Mockito.mock
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

            verify(bridge).sendLyric(songTitle = song.displayName(), line = second, secondaryLyric = null)
        } finally {
            manager._isPlayingFlow.value = previousPlaying
        }
    }

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
