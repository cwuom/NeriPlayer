package moe.ouom.neriplayer.core.player.report

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.runtime.report.PlatformPlayReportEvent
import moe.ouom.neriplayer.core.player.runtime.report.PlatformPlayReportTracker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliPlayHistoryTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformPlayHistoryReporterTest {
    private var elapsed = 0L
    private val port = FakePort()

    @Test
    fun `netease listen over thirty seconds is scrobbled once with listened seconds`() = runTest {
        val reporter = reporter(PlayHistorySyncFlags(netease = true))
        play(reporter, netease(1L), listenMs = 95_000)
        reporter.onPlayerState(netease(2L), playing = true, positionMs = 0L)
        drain()

        assertEquals(listOf(1L to 95L), port.scrobbles)
    }

    @Test
    fun `short skips and disabled switches never reach netease`() = runTest {
        val enabled = reporter(PlayHistorySyncFlags(netease = true))
        play(enabled, netease(1L), listenMs = 12_000)
        enabled.onPlayerState(netease(2L), playing = true, positionMs = 0L)

        val disabled = reporter(PlayHistorySyncFlags(bili = true))
        play(disabled, netease(3L), listenMs = 90_000)
        disabled.onPlayerState(null, playing = false, positionMs = 0L)
        drain()

        assertTrue(port.scrobbles.isEmpty())
    }

    @Test
    fun `signed out netease account is skipped`() = runTest {
        port.neteaseLogin = false
        val reporter = reporter(PlayHistorySyncFlags(netease = true))
        play(reporter, netease(1L), listenMs = 60_000)
        reporter.onPlayerState(null, playing = false, positionMs = 0L)
        drain()

        assertTrue(port.scrobbles.isEmpty())
    }

    @Test
    fun `bili progress is reported in order and finishes with the completed marker`() = runTest {
        val song = bili(durationMs = 40_000)
        val reporter = reporter(PlayHistorySyncFlags(bili = true))
        reporter.onPlayerState(song, playing = true, positionMs = 0L)
        elapsed += 16_000
        reporter.onPlayerState(song, playing = true, positionMs = 16_000L)
        elapsed += 23_000
        reporter.onPlayerState(song, playing = true, positionMs = 39_000L)
        reporter.onPlayerState(netease(9L), playing = true, positionMs = 0L)
        drain()

        assertEquals(listOf(16L, 39L, -1L), port.biliReports.map { it.second })
        assertEquals(1, port.biliResolutions)
        assertTrue(port.scrobbles.isEmpty())
    }

    @Test
    fun `bili song without a resolvable target is dropped`() = runTest {
        port.biliTarget = null
        val reporter = reporter(PlayHistorySyncFlags(bili = true))
        play(reporter, bili(), listenMs = 20_000)
        drain()

        assertTrue(port.biliReports.isEmpty())
    }

    @Test
    fun `netease policy accepts a finished short song only when half of it was heard`() {
        val shortSong = netease(1L).copy(durationMs = 40_000L)

        assertTrue(shouldScrobbleNetease(finished(shortSong, listenedMs = 20_000L, completed = true)))
        assertFalse(shouldScrobbleNetease(finished(shortSong, listenedMs = 19_000L, completed = true)))
        assertFalse(shouldScrobbleNetease(finished(shortSong, listenedMs = 25_000L, completed = false)))
        assertFalse(
            shouldScrobbleNetease(PlatformPlayReportEvent.Heartbeat(shortSong, positionMs = 0L, listenedMs = 60_000L))
        )
    }

    @Test
    fun `platform target follows the song source rather than the audio fallback`() {
        assertEquals(PlatformPlayHistoryTarget.Netease(7L), platformPlayHistoryTarget(netease(7L)))
        assertEquals(PlatformPlayHistoryTarget.Bili, platformPlayHistoryTarget(bili()))
        assertNull(platformPlayHistoryTarget(netease(7L).copy(channelId = "local", album = "Local")))
        assertEquals(-1L, biliPlayedSeconds(finished(bili(), listenedMs = 1L, completed = true)))
    }

    private val reporters = mutableListOf<PlatformPlayHistoryReporter>()

    // 关闭队列后消费者会把已排队的请求发完再退出，测试结束时不会留下挂起的协程
    private fun TestScope.drain() {
        reporters.forEach(PlatformPlayHistoryReporter::close)
        advanceUntilIdle()
    }

    private fun TestScope.reporter(flags: PlayHistorySyncFlags) = PlatformPlayHistoryReporter(
        scope = this,
        port = port,
        songKey = { "${it.channelId}:${it.id}" },
        tracker = PlatformPlayReportTracker(songKey = { "${it.channelId}:${it.id}" }, nowElapsedMs = { elapsed })
    ).also {
        it.flags = flags
        reporters += it
    }

    private fun play(reporter: PlatformPlayHistoryReporter, song: SongItem, listenMs: Long) {
        reporter.onPlayerState(song, playing = true, positionMs = 0L)
        elapsed += listenMs
        reporter.onPlayerState(song, playing = true, positionMs = listenMs)
    }

    private fun finished(song: SongItem, listenedMs: Long, completed: Boolean) =
        PlatformPlayReportEvent.Finished(song, positionMs = listenedMs, listenedMs = listenedMs, completed = completed)

    private fun netease(id: Long) = SongItem(
        id = id, name = "n$id", artist = "a", album = "Netease", albumId = 0L,
        durationMs = 240_000L, coverUrl = null, channelId = "netease"
    )

    private fun bili(durationMs: Long = 240_000L) = SongItem(
        id = 170001L, name = "video", artist = "up", album = "Bilibili|279786|BV17x411w7KC", albumId = 0L,
        durationMs = durationMs, coverUrl = null, channelId = "bilibili", audioId = "170001", subAudioId = "279786"
    )

    private class FakePort : PlatformPlayHistoryPort {
        var neteaseLogin = true
        var biliTarget: BiliPlayHistoryTarget? = BiliPlayHistoryTarget(170001L, 279786L, "BV17x411w7KC")
        val scrobbles = mutableListOf<Pair<Long, Long>>()
        val biliReports = mutableListOf<Pair<BiliPlayHistoryTarget, Long>>()
        var biliResolutions = 0

        override fun neteaseSignedIn(): Boolean = neteaseLogin
        override suspend fun biliSignedIn(): Boolean = true
        override suspend fun scrobbleNetease(songId: Long, listenedSec: Long) {
            scrobbles += songId to listenedSec
        }

        override suspend fun resolveBiliTarget(song: SongItem): BiliPlayHistoryTarget? {
            biliResolutions++
            return biliTarget
        }

        override suspend fun reportBiliProgress(target: BiliPlayHistoryTarget, playedSec: Long) {
            biliReports += target to playedSec
        }
    }
}
