package moe.ouom.neriplayer.ui.screen.nowplaying

import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatMode
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.onboarding.StartupPreviewPlaybackSource
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingShuffleMode.HEARTBEAT
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingShuffleMode.OFF
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingShuffleMode.SHUFFLE
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingShuffleModeTest {

    @Test
    fun `netease songs cycle default shuffle heartbeat and back`() {
        assertEquals(SHUFFLE, nextNowPlayingShuffleMode(OFF, heartbeatAvailable = true))
        assertEquals(HEARTBEAT, nextNowPlayingShuffleMode(SHUFFLE, heartbeatAvailable = true))
        assertEquals(OFF, nextNowPlayingShuffleMode(HEARTBEAT, heartbeatAvailable = true))
    }

    @Test
    fun `other sources only toggle shuffle`() {
        assertEquals(SHUFFLE, nextNowPlayingShuffleMode(OFF, heartbeatAvailable = false))
        assertEquals(OFF, nextNowPlayingShuffleMode(SHUFFLE, heartbeatAvailable = false))
    }

    @Test
    fun `heartbeat wins over shuffle only where heartbeat is available`() {
        // 旧版本可以同时开着随机和心动，按钮显示心动，再点一次两者一起关掉
        assertEquals(HEARTBEAT, resolveNowPlayingShuffleMode(true, true, heartbeatAvailable = true))
        // 非网易云歌曲上心动不生效，按钮按随机开关显示
        assertEquals(SHUFFLE, resolveNowPlayingShuffleMode(true, true, heartbeatAvailable = false))
        assertEquals(OFF, resolveNowPlayingShuffleMode(false, true, heartbeatAvailable = false))
        assertEquals(OFF, resolveNowPlayingShuffleMode(false, false, heartbeatAvailable = true))
    }

    @Test
    fun `applying a mode leaves shuffle and heartbeat matching the button`() {
        val source = StartupPreviewPlaybackSource(song, emptyList())

        tap(source, heartbeatAvailable = true)
        assertState(source, shuffle = true, heartbeat = false)
        tap(source, heartbeatAvailable = true)
        assertState(source, shuffle = false, heartbeat = true)
        tap(source, heartbeatAvailable = true)
        assertState(source, shuffle = false, heartbeat = false)
    }

    @Test
    fun `tapping on another source also turns off a hidden heartbeat`() {
        val source = StartupPreviewPlaybackSource(song, emptyList())
        source.setHeartbeatEnabled(true)

        tap(source, heartbeatAvailable = false)
        assertState(source, shuffle = true, heartbeat = false)
    }

    private fun tap(source: NowPlayingPlaybackSource, heartbeatAvailable: Boolean) {
        val shuffle = source.shuffleModeFlow.value
        val heartbeat = source.heartbeatModeFlow.value == HeartbeatMode.ON
        val current = resolveNowPlayingShuffleMode(shuffle, heartbeat, heartbeatAvailable)
        source.applyShuffleMode(nextNowPlayingShuffleMode(current, heartbeatAvailable), shuffle, heartbeat)
    }

    private fun assertState(source: NowPlayingPlaybackSource, shuffle: Boolean, heartbeat: Boolean) {
        assertEquals(shuffle, source.shuffleModeFlow.value)
        assertEquals(if (heartbeat) HeartbeatMode.ON else HeartbeatMode.OFF, source.heartbeatModeFlow.value)
    }

    private val song = SongItem(
        id = 1L, name = "song", artist = "artist", album = "", albumId = 0L,
        durationMs = 1_000L, coverUrl = null
    )
}
