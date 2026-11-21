package moe.ouom.neriplayer.lanltw

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.lan.LanStreamResource
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.lanltw.core.LanClock
import moe.ouom.neriplayer.lanltw.playback.LanPlaybackPort
import moe.ouom.neriplayer.lanltw.playback.LanPreparedSource
import moe.ouom.neriplayer.lanltw.session.LanSessionManager
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanPausedSessionDeviceTest {
    private val song = ListenTogetherTrack("netease:123", "netease", "123", name = "Fixture", artist = "Test", durationMs = 120_000)
    private val stream = LanStreamResource("12345678-1234-1234-1234-123456789abc", "audio/mpeg")

    private inner class Port : LanPlaybackPort {
        val playing = MutableStateFlow(false)
        override val preparedMediaId = stream.mediaId
        override fun currentTrack() = song
        override fun positionSeconds() = 0.0
        override fun isPlaying() = playing.value
        override fun supported() = true
        override suspend fun prepareSharedSource(track: ListenTogetherTrack, refresh: Boolean) = LanPreparedSource(track, stream)
        override suspend fun prepare(track: ListenTogetherTrack, positionSeconds: Double) = track == song
        override fun ready(track: ListenTogetherTrack) = track == song
        override fun play() { playing.value = true }
        override fun pause() { playing.value = false }
        override fun seek(positionSeconds: Double) = Unit
        override suspend fun setMediaVolume(value: Float) = Unit
        override fun begin(onCommand: (String, ListenTogetherTrack?, Double) -> Unit, onStop: () -> Unit) = Unit
        override fun end() = pause()
    }

    @Test(timeout = 75_000)
    fun pausedHostAndMemberKeepTheirConnectionsAcrossMissedHeartbeatPeriods() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient()
        val skippedMs = AtomicLong()
        val clock = LanClock { SystemClock.elapsedRealtimeNanos() / 1_000_000.0 + skippedMs.get() }
        val hostPort = Port()
        val memberPort = Port()
        val host = LanSessionManager(context, http, hostPort, { false }, {}, clock = clock)
        val member = LanSessionManager(context, http, memberPort, { false }, {}, clock = clock)
        suspend fun awaitBothPlaying(value: Boolean) = withTimeout(10_000L) {
            hostPort.playing.first { it == value }
            memberPort.playing.first { it == value }
        }
        try {
            withContext(Dispatchers.Main) { host.host("Host") }
            val hosting = withTimeout(15_000L) { host.state.first { it.synced && it.room?.playback?.track == song && !it.preparing } }
            val roomId = checkNotNull(hosting.room).id
            withContext(Dispatchers.Main) { member.join("127.0.0.1:${hosting.port}", roomId, "Member") }
            val joined = withTimeout(15_000L) { member.state.first { it.synced && it.room?.members?.size == 2 && !it.preparing } }
            val hostId = checkNotNull(hosting.selfId)
            val memberId = checkNotNull(joined.selfId)

            fun assertOriginalRoom() {
                for ((manager, selfId) in listOf(host to hostId, member to memberId)) {
                    val state = manager.state.value
                    assertEquals("room for $selfId (${state.phase}, ${state.error})", roomId, state.room?.id)
                    assertEquals(selfId, state.selfId)
                    assertTrue(state.synced)
                    assertEquals(setOf(hostId, memberId), state.room?.members?.map { it.id }?.toSet())
                }
            }
            repeat(2) {
                // 模拟暂停后进程重新得到调度，两个角色和网络线程共享同一单调时间
                withContext(Dispatchers.Main) { skippedMs.addAndGet(90_000) }
                delay(5_500L)
                assertOriginalRoom()
                assertFalse(host.state.value.room!!.playback.playing)
                assertFalse(member.state.value.room!!.playback.playing)
                assertFalse(hostPort.playing.value)
                assertFalse(memberPort.playing.value)
            }

            withContext(Dispatchers.Main) { host.requestPlayback("PLAY_PAUSE", song, 0.0) }
            awaitBothPlaying(true)
            withContext(Dispatchers.Main) { member.requestPlayback("PAUSE", song, 0.0) }
            awaitBothPlaying(false)
            withContext(Dispatchers.Main) { member.requestPlayback("PLAY_PAUSE", song, 0.0) }
            awaitBothPlaying(true)
            assertOriginalRoom()
        } finally {
            withContext(Dispatchers.Main) { member.leave(); host.leave() }
            try {
                withTimeout(10_000L) { member.state.first { !it.active }; host.state.first { !it.active } }
            } finally {
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }
}
