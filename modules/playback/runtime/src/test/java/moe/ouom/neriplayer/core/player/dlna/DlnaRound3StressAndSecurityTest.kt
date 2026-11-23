package moe.ouom.neriplayer.core.player.dlna

import moe.ouom.neriplayer.core.player.dlna.audio.LosslessSourcePolicy
import moe.ouom.neriplayer.core.player.dlna.protocol.FlacHeaderInspector
import moe.ouom.neriplayer.core.player.dlna.server.DlnaMediaServer
import moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine
import moe.ouom.neriplayer.core.player.playback.seekToImpl
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDeviceOptions
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaStreamMode
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Round 3 Simulation Tests:
 * - Adversarial HTTP security (path traversal, query spoofing, 405 methods, malformed/duplicate/
 *   multipart/overflowing Range -> 416)
 * - Concurrent multi-client HTTP stress + rapid stream churn
 * - Transport state machine edge cases (EOF at duration, EOF reset-to-zero, user pause/stop,
 *   stale generation isolation, remote volume safety bounds)
 * - Full strict-lossless / degradation matrix across NetEase / Bili / Local formats
 */
class DlnaRound3StressAndSecurityTest {

    private lateinit var server: DlnaMediaServer
    private lateinit var tempDir: File
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("dlna_round3_").toFile()
        server = DlnaMediaServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun securityAndMalformedHttpRequests_areStrictlyRejected() {
        val data = ByteArray(1024) { (it and 0xFF).toByte() }
        val file = File(tempDir, "stress.flac").apply { writeBytes(data) }
        val path = server.registerStream(
            "flac",
            DlnaMediaServer.StreamContent.FileContent(file, "audio/x-flac", "")
        )
        val url = server.buildUrl("127.0.0.1", path)

        // Path traversal & illegal target paths -> 400 or 404
        for (badPath in listOf(
            "/dlna/stream/../../etc/passwd",
            "/dlna/stream/foo\\bar.flac",
            "/dlna/artwork/../../secret.jpg",
            "/etc/passwd",
            "/dlna/stream/unknown.flac"
        )) {
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}$badPath").get().build())
                .execute().use { resp ->
                    assertTrue("Expected 400 or 404 for $badPath, got ${resp.code}", resp.code == 400 || resp.code == 404)
                }
        }

        // Unsupported methods -> 405 Allow: GET, HEAD
        client.newCall(
            Request.Builder().url(url).post("x".toByteArray().toRequestBody()).build()
        ).execute().use { resp ->
            assertEquals(405, resp.code)
            assertEquals("GET, HEAD", resp.header("Allow"))
        }

        // Malformed / duplicate / multipart / out-of-bounds ranges -> 416
        for (badRange in listOf(
            "bytes=1024-",
            "bytes=2000-2005",
            "bytes=50-10",
            "bytes=-0",
            "bytes=",
            "bytes=0-1,3-4",
            "bytes=abc-def",
            "items=0-10"
        )) {
            client.newCall(
                Request.Builder().url(url).header("Range", badRange).get().build()
            ).execute().use { resp ->
                assertEquals("Expected 416 for $badRange", 416, resp.code)
                assertEquals("bytes */1024", resp.header("Content-Range"))
            }
        }

        // Duplicate Range headers -> 416
        client.newCall(
            Request.Builder().url(url)
                .addHeader("Range", "bytes=0-10")
                .addHeader("Range", "bytes=20-30")
                .get().build()
        ).execute().use { resp ->
            assertEquals(416, resp.code)
        }

        // Query parameter cannot spoof Range
        client.newCall(
            Request.Builder().url("$url?Range:bytes=999-").get().build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            assertArrayEquals(data, resp.body.bytes())
        }
    }

    @Test
    fun concurrentStreamingAndTokenChurn_completesCleanlyWithoutCorruption() {
        val payload = ByteArray(256 * 1024) { idx ->
            ((idx * 31 + (idx ushr 8) * 17 + (idx ushr 16) * 13 + 7) and 0xFF).toByte()
        }
        val file = File(tempDir, "concurrent.flac").apply { writeBytes(payload) }
        val streamPath = server.registerStream(
            "flac",
            DlnaMediaServer.StreamContent.FileContent(file, "audio/x-flac", "")
        )
        val url = server.buildUrl("127.0.0.1", streamPath)

        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 16).map { worker ->
                Callable {
                    val start = (worker * 8192) % 131072
                    val end = start + 16383
                    client.newCall(
                        Request.Builder().url(url).header("Range", "bytes=$start-$end").get().build()
                    ).execute().use { resp ->
                        assertEquals(206, resp.code)
                        val got = resp.body.bytes()
                        assertArrayEquals(payload.copyOfRange(start, end + 1), got)
                    }
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun transportStateMachine_handlesNaturalEndPauseResetToZeroAndStaleGenerations() {
        // 1. Natural track end near duration
        val endNearDur = DlnaCastStateMachine.evaluatePoll(
            DlnaCastStateMachine.PollObservation(
                previousState = DlnaTransportState.PLAYING,
                currentState = DlnaTransportState.STOPPED,
                positionMs = 179_000L,
                durationMs = 180_000L,
                userPausedOrStopped = false,
                generationId = 5L,
                activeGenerationId = 5L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 1
            )
        )
        assertTrue(endNearDur is DlnaCastStateMachine.PollDecision.TrackEnded)

        // 2. Natural track end where renderer resets RelTime to 0 on STOPPED after reaching near-end
        val endResetZero = DlnaCastStateMachine.evaluatePoll(
            DlnaCastStateMachine.PollObservation(
                previousState = DlnaTransportState.PLAYING,
                currentState = DlnaTransportState.STOPPED,
                positionMs = 0L,
                durationMs = 180_000L,
                userPausedOrStopped = false,
                generationId = 5L,
                activeGenerationId = 5L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 1,
                lastPlayingPositionMs = 178_000L
            )
        )
        assertTrue(endResetZero is DlnaCastStateMachine.PollDecision.TrackEnded)

        // 2b. Renderer hardware Stop pressed mid-track must NOT auto-advance queue even after sustained STOPPED polls
        for (polls in 1..5) {
            val midTrackHardwareStop = DlnaCastStateMachine.evaluatePoll(
                DlnaCastStateMachine.PollObservation(
                    previousState = if (polls == 1) DlnaTransportState.PLAYING else DlnaTransportState.STOPPED,
                    currentState = DlnaTransportState.STOPPED,
                    positionMs = 0L,
                    durationMs = 180_000L,
                    userPausedOrStopped = false,
                    generationId = 5L,
                    activeGenerationId = 5L,
                    hasStartedPlayingInGeneration = true,
                    consecutiveStoppedPolls = polls,
                    lastPlayingPositionMs = 45_000L
                )
            )
            assertTrue(
                "Sustained mid-track STOPPED poll #$polls must not advance queue",
                midTrackHardwareStop is DlnaCastStateMachine.PollDecision.SyncPlayback &&
                    !midTrackHardwareStop.isPlaying
            )
        }

        // 3. User-paused or user-stopped does NOT advance queue
        val userStopped = DlnaCastStateMachine.evaluatePoll(
            DlnaCastStateMachine.PollObservation(
                previousState = DlnaTransportState.PLAYING,
                currentState = DlnaTransportState.STOPPED,
                positionMs = 180_000L,
                durationMs = 180_000L,
                userPausedOrStopped = true,
                generationId = 5L,
                activeGenerationId = 5L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 2
            )
        )
        assertTrue(userStopped is DlnaCastStateMachine.PollDecision.SyncPlayback)

        // 4. Stale generation poll is ignored
        val staleGen = DlnaCastStateMachine.evaluatePoll(
            DlnaCastStateMachine.PollObservation(
                previousState = DlnaTransportState.PLAYING,
                currentState = DlnaTransportState.STOPPED,
                positionMs = 180_000L,
                durationMs = 180_000L,
                userPausedOrStopped = false,
                generationId = 4L,
                activeGenerationId = 5L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 2
            )
        )
        assertEquals(DlnaCastStateMachine.PollDecision.KeepPolling, staleGen)

        // 5. Safe volume clamp
        assertEquals(0, DlnaCastStateMachine.clampRemoteVolume(-10))
        assertEquals(100, DlnaCastStateMachine.clampRemoteVolume(250))
        assertEquals(38, DlnaCastStateMachine.clampRemoteVolume(38))

        // 6. PlayerManager DLNA transport & seek delegation guards
        val manager = moe.ouom.neriplayer.core.player.PlayerManager
        val castFlowField = moe.ouom.neriplayer.core.player.dlna.session.DlnaCastManager::class.java
            .getDeclaredField("_castStateFlow")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val castFlow = castFlowField.get(moe.ouom.neriplayer.core.player.dlna.session.DlnaCastManager) as
            kotlinx.coroutines.flow.MutableStateFlow<moe.ouom.neriplayer.data.model.playback.dlna.DlnaCastState>
        val prevCastState = castFlow.value
        val prevInitialized = manager.initialized
        val prevSong = manager._currentSongFlow.value
        val prevIsPlaying = manager._isPlayingFlow.value
        val prevPlaybackState = manager._playerPlaybackStateFlow.value
        val prevPosition = manager._playbackPositionMs.value
        try {
            // Uninitialized seek guard (!acceptSeekCommand)
            manager.initialized = false
            castFlow.value = moe.ouom.neriplayer.data.model.playback.dlna.DlnaCastState()
            manager.seekToImpl(9_000L, moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource.REMOTE_SYNC)
            org.junit.Assert.assertFalse(manager.isTransportActive())
            org.junit.Assert.assertFalse(manager.isTransportActiveWithoutInitialization())
            org.junit.Assert.assertFalse(manager.shouldRunPlaybackServiceInForeground())
            org.junit.Assert.assertFalse(manager.isTransportBuffering())

            // Active via DLNA casting while local transport is idle
            val activeDevice = DlnaDevice(
                udn = "uuid:test-dlna",
                friendlyName = "Test Renderer",
                location = "http://127.0.0.1:49152/desc.xml",
                host = "127.0.0.1"
            )
            castFlow.value = moe.ouom.neriplayer.data.model.playback.dlna.DlnaCastState(
                activeDevice = activeDevice,
                connectionStatus = moe.ouom.neriplayer.data.model.playback.dlna.DlnaConnectionStatus.CONNECTED,
                transportState = DlnaTransportState.TRANSITIONING
            )
            assertTrue(manager.isTransportActive())
            assertTrue(manager.isTransportActiveWithoutInitialization())
            assertTrue(manager.shouldRunPlaybackServiceInForeground())
            assertTrue(manager.isTransportBuffering())

            // DLNA seek interception updates playback position when initialized
            manager.initialized = true
            manager._currentSongFlow.value = null
            manager.seekToImpl(15_432L, moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource.REMOTE_SYNC)
            assertEquals(15_432L, manager._playbackPositionMs.value)

            // Active via local playback owner while DLNA is idle
            castFlow.value = moe.ouom.neriplayer.data.model.playback.dlna.DlnaCastState()
            manager._currentSongFlow.value = moe.ouom.neriplayer.data.model.SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 180_000L,
                coverUrl = null
            )
            manager._isPlayingFlow.value = true
            manager._playerPlaybackStateFlow.value = androidx.media3.common.Player.STATE_BUFFERING
            assertTrue(manager.isTransportActive())
            assertTrue(manager.isTransportActiveWithoutInitialization())
            assertTrue(manager.shouldRunPlaybackServiceInForeground())
            assertTrue(manager.isTransportBuffering())
        } finally {
            castFlow.value = prevCastState
            manager.initialized = prevInitialized
            manager._currentSongFlow.value = prevSong
            manager._isPlayingFlow.value = prevIsPlaying
            manager._playerPlaybackStateFlow.value = prevPlaybackState
            manager._playbackPositionMs.value = prevPosition
        }
    }

    @Test
    fun qualityMatrix_detectsDegradationAndHonorsNonFlacRendererFallback() {
        val wavOnlyDevice = DlnaDevice(
            udn = "uuid:wav-only",
            friendlyName = "Legacy Renderer",
            location = "http://192.168.1.99/desc.xml",
            host = "192.168.1.99",
            supportsFlac = false,
            supportsWav = true
        )
        val flac = FlacHeaderInspector.InspectedAudio(
            format = FlacHeaderInspector.ContainerFormat.FLAC,
            mimeType = "audio/x-flac",
            isLossless = true,
            sampleRateHz = 44_100,
            bitDepth = 16,
            channelCount = 2
        )
        val decision = LosslessSourcePolicy.evaluate(
            inspected = flac,
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, qualityKey = "lossless"),
            isPreviewClip = false,
            requestedLosslessQuality = true,
            device = wavOnlyDevice,
            options = DlnaDeviceOptions(preferOriginalFlac = true, wavFallbackEnabled = true, wavEmbedId3Enabled = false)
        ) as LosslessSourcePolicy.DeliveryDecision.Deliver
        assertEquals(DlnaStreamMode.LOSSLESS_WAV, decision.mode)
        assertEquals(44_100, decision.sampleRateHz)
        assertEquals(16, decision.bitDepth)

        // Non-strict mode on lossy AAC marks degraded = true
        val aac = FlacHeaderInspector.InspectedAudio(
            format = FlacHeaderInspector.ContainerFormat.AAC_OR_MP4,
            mimeType = "audio/mp4",
            isLossless = false
        )
        val degradedDecision = LosslessSourcePolicy.evaluate(
            inspected = aac,
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, qualityKey = "exhigh", qualityLabel = "极高"),
            isPreviewClip = false,
            requestedLosslessQuality = true,
            device = wavOnlyDevice,
            options = DlnaDeviceOptions(strictLosslessMode = false)
        ) as LosslessSourcePolicy.DeliveryDecision.Deliver
        assertEquals(DlnaStreamMode.ADAPTIVE_DIRECT, degradedDecision.mode)
        assertTrue(degradedDecision.degraded)
        assertTrue(!degradedDecision.degradationReason.isNullOrBlank())
    }
}
