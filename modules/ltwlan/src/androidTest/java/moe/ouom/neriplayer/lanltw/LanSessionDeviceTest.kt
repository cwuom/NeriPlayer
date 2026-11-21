package moe.ouom.neriplayer.lanltw

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.ouom.neriplayer.data.model.lan.LanFileResource
import moe.ouom.neriplayer.data.model.lan.LanPermissions
import moe.ouom.neriplayer.data.model.lan.LanSharedLyricLine
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation
import moe.ouom.neriplayer.data.model.lan.LanStreamResource
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.lanltw.media.LanMediaDownload
import moe.ouom.neriplayer.lanltw.media.LanMediaServer
import moe.ouom.neriplayer.lanltw.media.LanPresentationCodec
import moe.ouom.neriplayer.lanltw.media.LanReadableMedia
import moe.ouom.neriplayer.lanltw.playback.LanPlaybackPort
import moe.ouom.neriplayer.lanltw.playback.LanPreparedSource
import moe.ouom.neriplayer.lanltw.session.LanSessionManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanSessionDeviceTest {
    private data class MediaEndpoint(val url: HttpUrl, val port: Int, val token: String)

    private val song = ListenTogetherTrack("netease:123", "netease", "123", name = "Synthetic test track", artist = "Test", durationMs = 120000)
    private val stream = LanStreamResource("12345678-1234-1234-1234-123456789abc", "audio/mpeg")
    private inner class Port(initiallyPlaying: Boolean = false) : LanPlaybackPort {
        override val preparedMediaId: String = stream.mediaId
        val plays = MutableStateFlow(0)
        val gain = MutableStateFlow(1f)
        val receivedPresentation = MutableStateFlow<LanSharedPresentation?>(null)
        val presentationUpdates = MutableStateFlow<LanSharedPresentation?>(null)
        val endpoint = CompletableDeferred<MediaEndpoint>()
        var mediaServer: LanMediaServer? = null
        var playing = initiallyPlaying
        var position = 0.0
        var ended = true
        var audioFailure: (String) -> Unit = {}
        override fun watchAudioFailure(handler: (String) -> Unit) { audioFailure = handler }
        override suspend fun prepareSharedSource(track: ListenTogetherTrack, refresh: Boolean) = LanPreparedSource(track, stream)
        override fun sharedPresentationUpdates(track: ListenTogetherTrack, mediaId: String) =
            presentationUpdates.filterNotNull()
        override fun sharedPresentation(track: ListenTogetherTrack, presentation: LanSharedPresentation?) {
            receivedPresentation.value = presentation
        }
        override fun hostMediaServer(server: LanMediaServer) { mediaServer = server }
        override fun mediaEndpoint(controlUrl: String, port: Int, token: String) {
            endpoint.complete(MediaEndpoint(controlUrl.toHttpUrl(), port, token))
        }
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
        override fun begin(onCommand: (String, ListenTogetherTrack?, Double) -> Unit, onStop: () -> Unit) {
            ended = false
            playing = false
        }
        override fun end() {
            if (ended) return
            playing = false
            ended = true
        }
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    @Test fun systemAudioInterruptionsPreserveTheRoomAndResumeOnlyAfterLocalInput() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient()
        val hostPort = Port()
        val guestPort = Port()
        val host = LanSessionManager(context, http, hostPort, { false }, {},
            loadPermissions = { LanPermissions(playPause = false) })
        val guest = LanSessionManager(context, http, guestPort, { false }, {})
        try {
            main { host.host("Host") }
            val hosting = withTimeout(30_000) {
                host.state.first { state ->
                    val playback = state.room?.playback
                    state.synced && playback?.track?.stableKey == song.stableKey && !playback.preparing
                }
            }
            val roomId = checkNotNull(hosting.room).id
            main { guest.join("127.0.0.1:${hosting.port}", roomId, "Member") }
            withTimeout(30_000) { guest.state.first { it.synced && it.room?.members?.size == 2 } }
            main {
                hostPort.audioFailure("AUDIO_SUSPENDED")
                guestPort.audioFailure("AUDIO_SUSPENDED")
                host.setVolume(.4f)
            }
            withTimeout(5_000) { guestPort.gain.first { it == .4f } }
            assertFalse(host.state.value.audioSuspended)
            assertFalse(guest.state.value.audioSuspended)
            main { host.requestPlayback("PLAY_PAUSE", song, 0.0) }
            withTimeout(10_000) { hostPort.plays.first { it == 1 }; guestPort.plays.first { it == 1 } }

            main {
                guestPort.audioFailure("AUDIO_SUSPENDED")
                guestPort.audioFailure("AUDIO_SUSPENDED")
            }
            withTimeout(5_000) { guest.state.first { it.synced && it.audioSuspended } }
            main {
                assertTrue(hostPort.playing)
                assertFalse(guestPort.playing)
                host.requestPlayback("PAUSE", song, 0.0)
            }
            withTimeout(5_000) { guest.state.first { it.room?.playback?.playing == false } }
            main { host.requestPlayback("PLAY_PAUSE", song, 0.0) }
            withTimeout(10_000) { hostPort.plays.first { it == 2 } }
            assertNull(withTimeoutOrNull(1_000) { guestPort.plays.first { it > 1 } })
            val revision = checkNotNull(host.state.value.room).playback.revision
            main { guest.requestPlayback("PLAY_PAUSE", song, 0.0) }
            withTimeout(10_000) { guestPort.plays.first { it == 2 } }
            assertEquals(revision, checkNotNull(host.state.value.room).playback.revision)

            main { hostPort.audioFailure("AUDIO_SUSPENDED") }
            withTimeout(10_000) {
                guest.state.first { it.synced && it.room?.playback?.playing == false }
                while (withContext(Dispatchers.Main) { guestPort.playing }) delay(20)
            }
            assertTrue(host.state.value.audioSuspended)
            assertEquals(roomId, checkNotNull(guest.state.value.room).id)
            assertEquals(2, checkNotNull(host.state.value.room).members.size)
            main { host.requestPlayback("PLAY_PAUSE", song, 0.0) }
            withTimeout(10_000) { hostPort.plays.first { it == 3 }; guestPort.plays.first { it == 3 } }
            assertFalse(host.state.value.audioSuspended)
        } finally {
            main { guest.leave(); host.leave() }
            withTimeout(10_000) { guest.state.first { !it.active }; host.state.first { !it.active } }
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
    @Test fun hostAndMemberConvergeEnforcePermissionsAndReleaseResources() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient()
        val hostPort = Port().apply { position = 31.0 }
        val memberPort = Port()
        val host = LanSessionManager(context, http, hostPort, { false }, {},
            loadPermissions = { LanPermissions(playPause = false, seek = false) })
        val member = LanSessionManager(context, http, memberPort, { false }, {})
        try {
            main { host.host("Host") }
            val hosting = withTimeout(30000) { host.state.first { it.synced && it.room != null } }
            main { member.join("127.0.0.1:${hosting.port}", hosting.room!!.id, "Member") }
            withTimeout(30000) { member.state.first { it.synced && it.room?.members?.size == 2 } }
            val prepared = withTimeout(15000) { member.state.mapNotNull { it.room?.playback }.first {
                it.track?.stableKey == song.stableKey && !it.preparing
            } }
            assertFalse(prepared.playing)
            assertEquals(31.0, prepared.positionSeconds, .001)
            assertEquals(0, hostPort.plays.value)
            assertEquals(0, memberPort.plays.value)
            main { host.requestPlayback("PLAY_PAUSE", song, 31.0) }
            withTimeout(15000) { hostPort.plays.first { it > 0 }; memberPort.plays.first { it > 0 } }
            val playing = withTimeout(5000) { member.state.first { it.room?.playback?.playing == true } }
            val previousRevision = checkNotNull(playing.room).playback.revision
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

    @Test fun memberVolumeAccessFollowsLiveGrantsAndKeepsDeviceLevelsSeparate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient()
        val hostPort = Port()
        val memberPort = Port()
        val otherPort = Port()
        val host = LanSessionManager(context, http, hostPort, { false }, {})
        val member = LanSessionManager(context, http, memberPort, { false }, {})
        val other = LanSessionManager(context, http, otherPort, { false }, {})
        val sessions = listOf(host, member, other)
        try {
            main { host.host("Host") }
            val hosting = withTimeout(30_000) { host.state.first { it.synced && it.room != null } }
            val hostId = checkNotNull(hosting.selfId)
            val roomId = checkNotNull(hosting.room).id
            val address = "127.0.0.1:${hosting.port}"
            main { member.join(address, roomId, "Member"); other.join(address, roomId, "Other") }
            withTimeout(30_000) {
                for (session in sessions) session.state.first { it.synced && it.room?.members?.size == 3 }
            }
            val memberId = checkNotNull(member.state.value.selfId)
            val otherId = checkNotNull(other.state.value.selfId)

            suspend fun awaitVolumes(group: Float, hostGain: Float, memberGain: Float, otherGain: Float) {
                val levels = mapOf(hostId to hostGain, memberId to memberGain, otherId to otherGain)
                withTimeout(10_000) {
                    for (session in sessions) session.state.mapNotNull { it.room }.first { room ->
                        room.volume == group && room.members.associate { it.id to it.volume } == levels
                    }
                    hostPort.gain.first { it == hostGain }
                    memberPort.gain.first { it == memberGain }
                    otherPort.gain.first { it == otherGain }
                }
            }
            suspend fun expectDenied(target: String?) {
                main {
                    member.clearError()
                    member.setVolume(.9f, target)
                }
                withTimeout(5_000) { member.state.first { it.error == "DENIED" } }
            }
            suspend fun grantVolume(enabled: Boolean) {
                main { host.setPermissions(checkNotNull(host.state.value.room).permissions.copy(volume = enabled)) }
                withTimeout(5_000) { member.state.first { it.room?.permissions?.volume == enabled } }
            }

            assertFalse(checkNotNull(member.state.value.room).permissions.volume)
            main { host.setVolume(.5f) }
            awaitVolumes(.5f, .5f, .5f, .5f)
            expectDenied(null)
            expectDenied(hostId)
            main { member.setVolume(.25f, memberId) }
            awaitVolumes(.5f, .5f, .25f, .5f)

            grantVolume(true)
            main { member.setVolume(.75f) }
            awaitVolumes(.75f, .75f, .75f, .75f)
            main { member.setVolume(.4f, hostId) }
            awaitVolumes(.75f, .4f, .75f, .75f)
            expectDenied(otherId)

            grantVolume(false)
            expectDenied(null)
            expectDenied(hostId)
            main { member.setVolume(.3f, memberId) }
            awaitVolumes(.75f, .4f, .3f, .75f)
        } finally {
            main { other.leave(); member.leave(); host.leave() }
            withTimeout(10_000) { for (session in sessions) session.state.first { !it.active } }
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun lateCoverReachesPlayingMemberAndLateJoinerThroughAuthorizedHttp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient()
        val hostPort = Port(initiallyPlaying = true)
        val memberPort = Port()
        val latePort = Port()
        val host = LanSessionManager(context, http, hostPort, { false }, {})
        val member = LanSessionManager(context, http, memberPort, { false }, {})
        val late = LanSessionManager(context, http, latePort, { false }, {})
        val directory = File(context.cacheDir, "lan-cover-test-${UUID.randomUUID()}")
        try {
            main { host.host("Host") }
            val hosting = withTimeout(30_000) { host.state.first { it.synced && it.room != null } }
            val roomId = checkNotNull(hosting.room).id
            val address = "127.0.0.1:${hosting.port}"
            main { member.join(address, roomId, "Member") }
            withTimeout(15_000) { memberPort.plays.first { it > 0 } }

            val initial = LanSharedPresentation(song.name, song.artist, "",
                lyrics = listOf(LanSharedLyricLine("Opening line", 0, 1500)))
            main { hostPort.presentationUpdates.value = initial }
            withTimeout(15_000) { memberPort.receivedPresentation.first { it == initial } }

            val coverBytes = coverPng()
            val (source, digest) = LanReadableMedia.fingerprint { coverBytes.inputStream() }
            val cover = LanFileResource(UUID.randomUUID().toString(), digest, source.byteLength, "image/png")
            val updated = initial.copy(cover = cover)
            main {
                checkNotNull(hostPort.mediaServer).publishBorrowed(cover.mediaId, source, cover.mimeType)
                hostPort.presentationUpdates.value = updated
            }
            suspend fun verifyCover(port: Port) = withTimeout(15_000) {
                port.receivedPresentation.first { it == updated }
                assertArrayEquals(coverBytes, fetchCover(http, port.endpoint.await(), cover, directory))
            }
            verifyCover(memberPort)
            main { late.join(address, roomId, "Late") }
            verifyCover(latePort)
        } finally {
            main { late.leave(); member.leave(); host.leave() }
            withTimeout(10_000) {
                for (session in listOf(late, member, host)) session.state.first { !it.active }
            }
            directory.deleteRecursively()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private suspend fun fetchCover(http: OkHttpClient, endpoint: MediaEndpoint, cover: LanFileResource, directory: File): ByteArray {
        val file = LanMediaDownload(http).fetchResource(endpoint.url, endpoint.port, endpoint.token,
            cover, directory, LanPresentationCodec.MAX_BYTES)
        return try { withContext(Dispatchers.IO) { file.readBytes() } } finally { file.delete() }
    }

    private fun coverPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
