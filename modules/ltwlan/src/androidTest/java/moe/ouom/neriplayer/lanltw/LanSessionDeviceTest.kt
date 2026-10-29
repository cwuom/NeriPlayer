package moe.ouom.neriplayer.lanltw

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.ouom.neriplayer.data.model.lan.LanPermissions
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.lanltw.playback.LanPlaybackPort
import moe.ouom.neriplayer.lanltw.session.LanSessionManager
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanSessionDeviceTest {
    private val song = ListenTogetherTrack("netease:123", "netease", "123", name = "Synthetic test track", artist = "Test", durationMs = 120000)
    private inner class Port : LanPlaybackPort {
        val plays = MutableStateFlow(0)
        val gain = MutableStateFlow(1f)
        var playing = false
        var position = 0.0
        var ended = false
        override suspend fun resolveSharedAudioSources(track: ListenTogetherTrack) = listOf("https://m.music.126.net/synthetic.mp3")
        override fun currentTrack() = song
        override fun positionSeconds() = position
        override fun isPlaying() = playing
        override fun supported() = true
        override suspend fun prepare(track: ListenTogetherTrack, positionSeconds: Double): Boolean { playing = false; position = positionSeconds; return track.stableKey == song.stableKey }
        override fun ready(track: ListenTogetherTrack) = track.stableKey == song.stableKey
        override fun play() { playing = true; plays.value++ }
        override fun pause() { playing = false }
        override fun seek(positionSeconds: Double) { position = positionSeconds }
        override suspend fun setMediaVolume(value: Float) { gain.value = value }
        override fun begin(onCommand: (String, ListenTogetherTrack?, Double) -> Unit, onStop: () -> Unit) { ended = false }
        override fun end() { playing = false; ended = true }
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    @Test fun hostAndMemberConvergeEnforcePermissionsAndReleaseResources() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient()
        val hostPort = Port(); val memberPort = Port()
        val host = LanSessionManager(context, http, hostPort, { false }, {})
        val member = LanSessionManager(context, http, memberPort, { false }, {})
        try {
            main { host.host("Host") }
            val hosting = withTimeout(30000) { host.state.first { it.synced && it.room != null } }
            main { member.join("127.0.0.1:${hosting.port}", hosting.room!!.id, "Member") }
            val joined = withTimeout(30000) { member.state.first { it.synced && it.room?.members?.size == 2 } }
            withTimeout(15000) { hostPort.plays.first { it > 0 }; memberPort.plays.first { it > 0 } }
            val previousRevision = joined.room!!.playback.revision
            main { member.requestPlayback("SEEK", song, 10.0) }
            withTimeout(5000) { member.state.first { it.error == "DENIED" } }
            assertEquals(previousRevision, member.state.value.room!!.playback.revision)
            main { host.setPermissions(LanPermissions(playPause = true, seek = true)) }
            withTimeout(5000) { member.state.first { it.room?.permissions?.seek == true } }
            main { member.requestPlayback("SEEK", song, 10.0) }
            withTimeout(10000) { member.state.first { (it.room?.playback?.revision ?: 0) > previousRevision } }
            main { host.setVolume(.5f) }
            withTimeout(5000) { memberPort.gain.first { it == .5f } }
            main { host.setVolume(.4f, member.state.value.selfId) }
            withTimeout(5000) { memberPort.gain.first { kotlin.math.abs(it - .4f) < .001f } }
            main { member.nudge(50) }
            assertEquals(50, member.state.value.nudgeMs)
        } finally {
            main { member.leave(); host.leave() }
            withTimeout(10000) { member.state.first { !it.active }; host.state.first { !it.active } }
            assertTrue(memberPort.ended); assertTrue(hostPort.ended)
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
}
