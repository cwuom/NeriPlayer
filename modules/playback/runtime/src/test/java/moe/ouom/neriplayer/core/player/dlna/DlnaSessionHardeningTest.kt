package moe.ouom.neriplayer.core.player.dlna

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.core.player.dlna.audio.LosslessSourceResolver
import moe.ouom.neriplayer.core.player.dlna.audio.LosslessWavDecoder
import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpControlClient
import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpXmlParser
import moe.ouom.neriplayer.core.player.dlna.server.DlnaLanAddressResolver
import moe.ouom.neriplayer.core.player.dlna.server.DlnaMediaServer
import moe.ouom.neriplayer.core.player.dlna.session.DLNA_IDLE_POLL_INTERVAL_MS
import moe.ouom.neriplayer.core.player.dlna.session.DLNA_POLL_INTERVAL_MS
import moe.ouom.neriplayer.core.player.dlna.session.DLNA_STARTUP_PAUSE_GRACE_MS
import moe.ouom.neriplayer.core.player.dlna.session.DelegatingLanSocketFactory
import moe.ouom.neriplayer.core.player.dlna.session.DlnaCastStateMachine
import moe.ouom.neriplayer.core.player.dlna.session.DlnaGenaRenewalScheduler
import moe.ouom.neriplayer.core.player.dlna.session.DlnaRendererReachability
import moe.ouom.neriplayer.core.player.dlna.session.dlnaPollIntervalMs
import moe.ouom.neriplayer.core.player.dlna.session.DLNA_EXTERNAL_RESUME_GRACE_MS
import moe.ouom.neriplayer.core.player.dlna.session.DlnaNetworkAndLockController
import moe.ouom.neriplayer.core.player.dlna.session.followTransport
import moe.ouom.neriplayer.core.player.dlna.session.isMidTrackPause
import moe.ouom.neriplayer.core.player.dlna.session.isStartupPauseGraceActive
import moe.ouom.neriplayer.core.player.dlna.session.resolveRemotePlayingForHandoff
import moe.ouom.neriplayer.core.player.dlna.session.shouldAdoptExternalResume
import moe.ouom.neriplayer.core.player.dlna.session.shouldSendGaplessNextUriToRenderer
import moe.ouom.neriplayer.core.player.runtime.refresh.YouTubePlaybackRecoveryStrategy
import moe.ouom.neriplayer.core.player.url.resolveYouTubeRequireDirect
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class DlnaSessionHardeningTest {

    @Test
    fun handoff_honorsPauseMadeOnTheRendererItself() {
        // 渲染器上直接暂停：App 侧意图仍是播放，但切回本机时不能自动外放
        assertFalse(resolveRemotePlayingForHandoff(DlnaTransportState.PAUSED_PLAYBACK, desiredRemotePlaying = true))
        assertFalse(resolveRemotePlayingForHandoff(DlnaTransportState.STOPPED, desiredRemotePlaying = true))
        assertTrue(resolveRemotePlayingForHandoff(DlnaTransportState.PLAYING, desiredRemotePlaying = false))
        // 状态未稳定时才退回到意图
        assertTrue(resolveRemotePlayingForHandoff(DlnaTransportState.TRANSITIONING, desiredRemotePlaying = true))
        assertFalse(resolveRemotePlayingForHandoff(DlnaTransportState.UNKNOWN, desiredRemotePlaying = false))
    }

    @Test
    fun youtubeCast_alwaysRequiresDirectStream() {
        // 渲染器拿到的是单个文件，HLS 播放列表里的相对分片地址会被解析到 /dlna/stream/ 下导致 404
        assertTrue(resolveYouTubeRequireDirect(strategy = null, requireDirectOverride = true))
        val lenientRecovery = YouTubePlaybackRecoveryStrategy("high", requireDirect = false, preferM4a = true)
        assertTrue(resolveYouTubeRequireDirect(lenientRecovery, requireDirectOverride = true))
        // 本地播放保持原行为
        assertFalse(resolveYouTubeRequireDirect(strategy = null, requireDirectOverride = false))
        assertFalse(resolveYouTubeRequireDirect(lenientRecovery, requireDirectOverride = false))
        assertTrue(resolveYouTubeRequireDirect(lenientRecovery.copy(requireDirect = true), requireDirectOverride = false))
    }

    @Test
    fun externalResume_onRendererClearsAppPauseLatchAfterGrace() {
        val pausedAt = 100_000L
        fun adopt(
            transport: DlnaTransportState? = DlnaTransportState.PLAYING,
            loaded: Boolean = true,
            userPaused: Boolean = true,
            desired: Boolean = false,
            now: Long = pausedAt + DLNA_EXTERNAL_RESUME_GRACE_MS
        ) = shouldAdoptExternalResume(transport, loaded, userPaused, desired, now, pausedAt)

        // 用户在渲染器上恢复了 App 侧暂停的曲目：要解除锁存，否则不预取、到曲尾也不推进队列
        assertTrue(adopt())
        // 刚按暂停时在途的轮询仍可能读到 PLAYING，不能把用户的暂停覆盖掉
        assertFalse(adopt(now = pausedAt + DLNA_EXTERNAL_RESUME_GRACE_MS - 1))
        assertFalse(adopt(transport = DlnaTransportState.PAUSED_PLAYBACK))
        assertFalse(adopt(transport = null))
        // 队列已结束、渲染器上不是我们装载的曲目
        assertFalse(adopt(loaded = false))
        // 本来就在播放意图下，无需处理
        assertFalse(adopt(userPaused = false, desired = true))
    }

    @Test
    fun castLocks_followObservedTransport() {
        val locks = mock(DlnaNetworkAndLockController::class.java)
        locks.followTransport(DlnaTransportState.PLAYING, desiredRemotePlaying = false)
        verify(locks).acquireCastLocks(false)

        locks.followTransport(DlnaTransportState.PAUSED_PLAYBACK, desiredRemotePlaying = false)
        verify(locks).releaseCastLocks()

        // 用户想播放、渲染器暂时停着（切歌间隙）时不释放
        val playingIntent = mock(DlnaNetworkAndLockController::class.java)
        playingIntent.followTransport(DlnaTransportState.STOPPED, desiredRemotePlaying = true)
        playingIntent.followTransport(DlnaTransportState.TRANSITIONING, desiredRemotePlaying = false)
        verify(playingIntent, never()).releaseCastLocks()
    }

    @Test
    fun midTrackPause_isDistinguishedFromEndOfTrackPause() {
        val paused = DlnaTransportState.PAUSED_PLAYBACK
        assertTrue(isMidTrackPause(paused, positionMs = 60_000L, durationMs = 180_000L))
        // 部分渲染器在自然结束时上报 PAUSED_PLAYBACK，这是切下一首的时机，不能当作用户暂停
        assertFalse(isMidTrackPause(paused, positionMs = 178_000L, durationMs = 180_000L))
        assertFalse(isMidTrackPause(paused, positionMs = 60_000L, durationMs = 0L))
        assertFalse(isMidTrackPause(paused, positionMs = null, durationMs = 180_000L))
        assertFalse(isMidTrackPause(DlnaTransportState.PLAYING, positionMs = 60_000L, durationMs = 180_000L))
    }

    @Test
    fun castLocks_releaseOnRendererSidePauseButNotAtTrackEnd() {
        val midTrack = mock(DlnaNetworkAndLockController::class.java)
        midTrack.followTransport(DlnaTransportState.PAUSED_PLAYBACK, desiredRemotePlaying = true, confirmedMidTrackPause = true)
        verify(midTrack).releaseCastLocks()

        val trackEnd = mock(DlnaNetworkAndLockController::class.java)
        trackEnd.followTransport(DlnaTransportState.PAUSED_PLAYBACK, desiredRemotePlaying = true, confirmedMidTrackPause = false)
        verify(trackEnd, never()).releaseCastLocks()
    }

    @Test
    fun polling_slowsDownOnlyWhenUserPaused() {
        assertEquals(DLNA_IDLE_POLL_INTERVAL_MS, dlnaPollIntervalMs(userPausedOrStopped = true, desiredRemotePlaying = false))
        assertEquals(DLNA_POLL_INTERVAL_MS, dlnaPollIntervalMs(userPausedOrStopped = false, desiredRemotePlaying = true))
        assertEquals(DLNA_POLL_INTERVAL_MS, dlnaPollIntervalMs(userPausedOrStopped = true, desiredRemotePlaying = true))
    }

    @Test
    fun genaRenewal_renewsBeforeExpiryAndStopsWhenStaleOrFailed() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val renewCalls = mutableListOf<Pair<String, Int>>()
        var current = true
        var failNext = false
        val scheduler = DlnaGenaRenewalScheduler(scope, { _, sid, timeout ->
            renewCalls += sid to timeout
            if (failNext) Result.failure(IllegalStateException("412")) else Result.success(120)
        })

        scheduler.start("http://192.168.1.50/evt", "uuid:sid-1", timeoutSeconds = 300) { current }
        scope.advanceTimeBy(DlnaGenaRenewalScheduler.renewDelayMs(300) - 1)
        scope.runCurrent()
        assertTrue(renewCalls.isEmpty())
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals(listOf("uuid:sid-1" to 300), renewCalls)

        // 下一次按渲染器实际授予的 120 秒计算
        scope.advanceTimeBy(DlnaGenaRenewalScheduler.renewDelayMs(120) + 1)
        scope.runCurrent()
        assertEquals("uuid:sid-1" to 120, renewCalls.last())

        failNext = true
        scope.advanceTimeBy(DlnaGenaRenewalScheduler.renewDelayMs(120) + 1)
        scope.runCurrent()
        val afterFailure = renewCalls.size
        scope.advanceTimeBy(60L * 60L * 1000L)
        scope.runCurrent()
        assertEquals(afterFailure, renewCalls.size)

        failNext = false
        scheduler.start("http://192.168.1.50/evt", "uuid:sid-2", timeoutSeconds = 300) { current }
        current = false
        scope.advanceTimeBy(60L * 60L * 1000L)
        scope.runCurrent()
        assertTrue(renewCalls.none { it.first == "uuid:sid-2" })
    }

    @Test
    fun genaRenewDelay_isClamped() {
        assertEquals(225_000L, DlnaGenaRenewalScheduler.renewDelayMs(300))
        assertEquals(15_000L, DlnaGenaRenewalScheduler.renewDelayMs(5))
        assertEquals(30L * 60L * 1000L, DlnaGenaRenewalScheduler.renewDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun renewGena_sendsSidWithoutCallbackOrNt() {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            server.enqueue(MockResponse.Builder().code(200).setHeader("TIMEOUT", "Second-1800").build())
            val granted = UpnpControlClient().renewGena(server.url("/evt").toString(), "uuid:sid-1", 300)
            assertEquals(1800, granted.getOrThrow())
            val request = server.takeRequest()
            assertEquals("SUBSCRIBE", request.method)
            assertEquals("uuid:sid-1", request.headers["SID"])
            assertEquals("Second-300", request.headers["TIMEOUT"])
            assertNull(request.headers["CALLBACK"])
            assertNull(request.headers["NT"])
        }
    }

    @Test
    fun xmlParser_rejectsAnyDtdEvenIfPlatformIgnoresSecureFeatures() {
        val entityExpansion = """
            <?xml version="1.0"?>
            <!DOCTYPE root [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">]>
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device><UDN>uuid:x</UDN><friendlyName>&b;</friendlyName></device></root>
        """.trimIndent()
        assertTrue(UpnpXmlParser.containsDtdDeclaration(entityExpansion))
        assertTrue(UpnpXmlParser.containsDtdDeclaration("<x><!entity y \"z\"></x>"))
        assertNull(UpnpXmlParser.parseDeviceDescription(entityExpansion, "http://192.168.1.50/desc.xml"))
        assertFalse(UpnpXmlParser.containsDtdDeclaration("<root><title>&lt;!DOCTYPE&gt;</title></root>"))
    }

    @Test
    fun mediaServer_headerReadGivesUpAfterDeadline() {
        val server = DlnaMediaServer()
        val request = "GET /dlna/stream/x HTTP/1.1\r\nHost: a\r\n\r\n".toByteArray(Charsets.US_ASCII)

        val ok = server.readHeaderBlock(BufferedInputStream(ByteArrayInputStream(request)))
        assertEquals("GET /dlna/stream/x HTTP/1.1\r\nHost: a", ok)

        // 慢速逐字节发送的连接：总时限到了就放弃，而不是一直占着工作线程
        val expired = server.readHeaderBlock(
            BufferedInputStream(ByteArrayInputStream(request)),
            deadlineNanos = System.nanoTime() - 1L
        )
        assertNull(expired)
    }

    @Test
    fun wavFallback_estimatesPcmSizeAndBudget() {
        // 1 分钟 44.1kHz/16bit/立体声 ≈ 10.58 MB
        assertEquals(60_000L * 44_100L / 1000L * 2L * 2L, LosslessWavDecoder.estimatePcmBytes(60_000L, 44_100, 2, 16))
        // 24bit 按 3 字节打包
        assertEquals(1_000L * 192_000L / 1000L * 2L * 3L, LosslessWavDecoder.estimatePcmBytes(1_000L, 192_000, 2, 24))
        assertNull(LosslessWavDecoder.estimatePcmBytes(0L, 44_100, 2, 16))
        assertNull(LosslessWavDecoder.estimatePcmBytes(60_000L, null, 2, 16))

        val gib = 1024L * 1024L * 1024L
        assertEquals(LosslessSourceResolver.MAX_WAV_FALLBACK_PCM_BYTES, LosslessSourceResolver.wavFallbackPcmBudgetBytes(64L * gib))
        assertEquals(
            gib - LosslessSourceResolver.STAGING_FREE_SPACE_RESERVE_BYTES,
            LosslessSourceResolver.wavFallbackPcmBudgetBytes(gib)
        )
        assertEquals(0L, LosslessSourceResolver.wavFallbackPcmBudgetBytes(10L))
        // 一小时 24/192 立体声约 4.1 GB，必须在解码前就被拒绝
        val hourHiRes = LosslessWavDecoder.estimatePcmBytes(3_600_000L, 192_000, 2, 24)!!
        assertTrue(hourHiRes > LosslessSourceResolver.wavFallbackPcmBudgetBytes(64L * gib))
    }

    @Test
    fun lanSubnetMatch_ignoresCellularAndTunnelInterfacesAndPicksWifiSubnet() {
        val candidates = listOf(
            DlnaLanAddressResolver.LanSubnetCandidate(
                ifaceName = "rmnet_data0",
                localIpv4 = byteArrayOf(10, 88.toByte(), 0, 5),
                hostAddress = "10.88.0.5",
                prefixLength = 8
            ),
            DlnaLanAddressResolver.LanSubnetCandidate(
                ifaceName = "tun0",
                localIpv4 = byteArrayOf(192.toByte(), 168.toByte(), 31, 9),
                hostAddress = "192.168.31.9",
                prefixLength = 24
            ),
            DlnaLanAddressResolver.LanSubnetCandidate(
                ifaceName = "wlan0",
                localIpv4 = byteArrayOf(192.toByte(), 168.toByte(), 31, 20),
                hostAddress = "192.168.31.20",
                prefixLength = 24
            )
        )
        val matched = DlnaLanAddressResolver.selectSubnetMatchedLanIpv4(
            targetIpv4 = byteArrayOf(192.toByte(), 168.toByte(), 31, 88),
            candidates = candidates
        )
        assertEquals("192.168.31.20", matched)
        assertTrue(DlnaLanAddressResolver.isNonLanTunnelOrCellularInterface("v4-rmnet_data1"))
        assertTrue(DlnaLanAddressResolver.isNonLanTunnelOrCellularInterface("ccmni0"))
        assertFalse(DlnaLanAddressResolver.isNonLanTunnelOrCellularInterface("swlan0"))
    }

    @Test
    fun upnpControlClient_routesThroughProvidedLanSocketFactory() {
        var createdSockets = 0
        val countingFactory = object : javax.net.SocketFactory() {
            private val base = getDefault()
            override fun createSocket(): java.net.Socket {
                createdSockets++
                return base.createSocket()
            }
            override fun createSocket(host: String?, port: Int) = base.createSocket(host, port).also { createdSockets++ }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) =
                base.createSocket(host, port, localHost, localPort).also { createdSockets++ }
            override fun createSocket(host: InetAddress?, port: Int) = base.createSocket(host, port).also { createdSockets++ }
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) =
                base.createSocket(address, port, localAddress, localPort).also { createdSockets++ }
        }
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            server.enqueue(MockResponse.Builder().code(200).setHeader("TIMEOUT", "Second-300").build())
            val client = UpnpControlClient(DelegatingLanSocketFactory { countingFactory })
            val granted = client.renewGena(server.url("/evt").toString(), "uuid:sid-lan", 300)
            assertEquals(300, granted.getOrThrow())
            assertTrue(createdSockets >= 1)
        }
    }

    @Test
    fun rendererReachability_keepsSessionAliveWhileMediaServerStreamsAudio() {
        val reachability = DlnaRendererReachability()
        // 息屏期间 SOAP 轮询被省电策略拖慢失败，但渲染器仍在持续从 DlnaMediaServer 拉取音频流
        for (sec in 0..120 step 10) {
            val nowMs = sec * 1_000L
            assertFalse(
                reachability.record(
                    reachable = false,
                    nowMs = nowMs,
                    lastPeerActivityAtMs = nowMs - 2_000L
                )
            )
        }
        // 当渲染器真正断电、流拉取与 SOAP 均停止超过窗口后，才判定失联释放锁
        val quietStartMs = 200_000L
        for (i in 0 until DlnaRendererReachability.DEFAULT_MIN_CONSECUTIVE_FAILURES - 1) {
            assertFalse(reachability.record(reachable = false, nowMs = quietStartMs + i * 5_000L, lastPeerActivityAtMs = 120_000L))
        }
        assertTrue(
            reachability.record(
                reachable = false,
                nowMs = quietStartMs + DlnaRendererReachability.DEFAULT_MIN_UNREACHABLE_MS,
                lastPeerActivityAtMs = 120_000L
            )
        )
    }

    @Test
    fun midTrackPause_andStateMachine_protectTrackEndZeroResetAndStartupTransition() {
        val paused = DlnaTransportState.PAUSED_PLAYBACK
        // 山灵 EM7 自然播完时上报 PAUSED_PLAYBACK 且 RelTime 归零：属于曲尾切歌窗口，绝不能当曲中暂停释放保活锁
        assertFalse(
            isMidTrackPause(
                transport = paused,
                positionMs = 0L,
                durationMs = 240_000L,
                startupGraceActive = false,
                lastPlayingPositionMs = 237_500L,
                elapsedSinceLastPlayingPollMs = 1_000L
            )
        )
        // 新曲目刚发 Play、渲染器尚停留在上一首的 PAUSED_PLAYBACK(00:00) 时：处于启动宽限窗口，既不释放锁也不反向置为暂停
        val graceActive = isStartupPauseGraceActive(
            hasStartedPlayingInGeneration = false,
            playCommandSentInGeneration = true,
            elapsedSincePlayCommandMs = 2_000L
        )
        assertTrue(graceActive)
        assertFalse(isMidTrackPause(paused, positionMs = 0L, durationMs = 240_000L, startupGraceActive = graceActive))

        val startupDecision = DlnaCastStateMachine.evaluatePoll(
            DlnaCastStateMachine.PollObservation(
                previousState = paused,
                currentState = paused,
                positionMs = 0L,
                durationMs = 240_000L,
                userPausedOrStopped = false,
                generationId = 2L,
                activeGenerationId = 2L,
                hasStartedPlayingInGeneration = false,
                consecutiveStoppedPolls = 0,
                playCommandSentInGeneration = true,
                elapsedSincePlayCommandMs = 2_000L
            )
        )
        assertEquals(DlnaCastStateMachine.PollDecision.KeepPolling, startupDecision)

        // 超过启动宽限窗口仍为 PAUSED_PLAYBACK（例如用户在渲染器面板直接按了暂停）：正常同步暂停并允许释放锁
        val expiredGrace = isStartupPauseGraceActive(
            hasStartedPlayingInGeneration = false,
            playCommandSentInGeneration = true,
            elapsedSincePlayCommandMs = DLNA_STARTUP_PAUSE_GRACE_MS + 1_000L
        )
        assertFalse(expiredGrace)
        assertTrue(isMidTrackPause(paused, positionMs = 0L, durationMs = 240_000L, startupGraceActive = expiredGrace))
    }

    @Test
    fun mediaServer_recordsPeerActivityWhenStreamingAudioAndClearsOnStop() {
        val tempFile = java.nio.file.Files.createTempFile("dlna-peer-activity", ".flac").toFile()
        val server = DlnaMediaServer()
        try {
            tempFile.writeBytes(ByteArray(32 * 1024) { (it and 0x7F).toByte() })
            server.start()
            assertEquals(0L, server.lastPeerActivityBootMs)
            val streamPath = server.registerStream(
                extension = "flac",
                content = DlnaMediaServer.StreamContent.FileContent(
                    file = tempFile,
                    contentType = "audio/flac",
                    contentFeatures = ""
                )
            )
            val url = java.net.URI.create(server.buildUrl("127.0.0.1", streamPath)).toURL()
            val bytes = url.openStream().use { it.readBytes() }
            assertEquals(32 * 1024, bytes.size)
            assertTrue(server.lastPeerActivityBootMs > 0L)
            server.stop()
            assertEquals(0L, server.lastPeerActivityBootMs)
        } finally {
            server.stop()
            tempFile.delete()
        }
    }

    @Test(timeout = 8_000L)
    fun mediaServer_handlesConcurrentRangeSeeksWhileMultipleStreamsStayOpen() {
        val tempFile = java.nio.file.Files.createTempFile("dlna-range-seek", ".flac").toFile()
        val server = DlnaMediaServer()
        val stalledSockets = mutableListOf<java.net.Socket>()
        try {
            val payload = ByteArray(512 * 1024) { ((it * 31 + 7) and 0xFF).toByte() }
            tempFile.writeBytes(payload)
            val port = server.start()
            val streamPath = server.registerStream(
                extension = "flac",
                content = DlnaMediaServer.StreamContent.FileContent(
                    file = tempFile,
                    contentType = "audio/flac",
                    contentFeatures = ""
                )
            )
            // 模拟渲染器保持 4 条未读完的长流连接（TCP 发送缓冲写满阻塞），验证后续多次 Range 拖动进度不会死锁饥饿
            repeat(4) {
                val sock = java.net.Socket("127.0.0.1", port).apply {
                    soTimeout = 3_000
                    receiveBufferSize = 4096
                }
                stalledSockets += sock
                val req = "GET $streamPath HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                sock.getOutputStream().flush()
                val headerBuf = ByteArray(128)
                val readCount = sock.getInputStream().read(headerBuf)
                assertTrue(readCount > 0)
            }
            val url = java.net.URI.create(server.buildUrl("127.0.0.1", streamPath)).toURL()
            val offsets = listOf(0, 65_536, 131_072, 262_144, 393_216, 500_000)
            for (start in offsets) {
                val end = (start + 4095).coerceAtMost(payload.lastIndex)
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 3_000
                    setRequestProperty("Range", "bytes=$start-$end")
                }
                try {
                    assertEquals(206, conn.responseCode)
                    val slice = conn.inputStream.use { it.readBytes() }
                    assertEquals(end - start + 1, slice.size)
                    for (i in slice.indices) {
                        assertEquals(payload[start + i], slice[i])
                    }
                } finally {
                    conn.disconnect()
                }
            }
        } finally {
            stalledSockets.forEach { runCatching { it.close() } }
            server.stop()
            tempFile.delete()
        }
    }

    @Test
    fun shanlingEm7_trackEndPauseAtOneSecond_triggersTrackEndedAndPreservesStartPauseSafety() {
        val paused = DlnaTransportState.PAUSED_PLAYBACK
        // 山灵 EM7 自然播完一首 4:00 曲目后会进入 PAUSED_PLAYBACK 并将 RelTime 拉回 00:00:01 (1000ms)
        assertTrue(
            DlnaCastStateMachine.isPausedAtTrackEnd(
                positionMs = 1_000L,
                durationMs = 240_000L,
                lastPlayingPositionMs = 235_200L,
                elapsedSinceLastPlayingPollMs = 1_100L
            )
        )
        assertFalse(
            isMidTrackPause(
                transport = paused,
                positionMs = 1_000L,
                durationMs = 240_000L,
                startupGraceActive = false,
                lastPlayingPositionMs = 235_200L,
                elapsedSinceLastPlayingPollMs = 1_100L
            )
        )
        val firstEndPoll = DlnaCastStateMachine.evaluatePoll(
            DlnaCastStateMachine.PollObservation(
                previousState = DlnaTransportState.PLAYING,
                currentState = paused,
                positionMs = 1_000L,
                durationMs = 240_000L,
                userPausedOrStopped = false,
                generationId = 5L,
                activeGenerationId = 5L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 0,
                lastPlayingPositionMs = 235_200L,
                consecutiveEndPausedPolls = 1,
                elapsedSinceLastPlayingPollMs = 1_100L
            )
        )
        assertEquals(DlnaCastStateMachine.PollDecision.KeepPolling, firstEndPoll)

        val secondEndPoll = DlnaCastStateMachine.evaluatePoll(
            DlnaCastStateMachine.PollObservation(
                previousState = paused,
                currentState = paused,
                positionMs = 1_000L,
                durationMs = 240_000L,
                userPausedOrStopped = false,
                generationId = 5L,
                activeGenerationId = 5L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 0,
                lastPlayingPositionMs = 235_200L,
                consecutiveEndPausedPolls = 2,
                elapsedSinceLastPlayingPollMs = 2_100L
            )
        )
        assertEquals(DlnaCastStateMachine.PollDecision.TrackEnded(5L), secondEndPoll)

        // 反例：歌曲刚开播到 1s 时用户在山灵 EM7 面板按暂停，绝不能误判为曲尾切歌
        assertFalse(
            DlnaCastStateMachine.isPausedAtTrackEnd(
                positionMs = 1_000L,
                durationMs = 240_000L,
                lastPlayingPositionMs = 1_000L,
                elapsedSinceLastPlayingPollMs = 300L
            )
        )
        val genuineStartPause = DlnaCastStateMachine.evaluatePoll(
            DlnaCastStateMachine.PollObservation(
                previousState = DlnaTransportState.PLAYING,
                currentState = paused,
                positionMs = 1_000L,
                durationMs = 240_000L,
                userPausedOrStopped = false,
                generationId = 5L,
                activeGenerationId = 5L,
                hasStartedPlayingInGeneration = true,
                consecutiveStoppedPolls = 0,
                lastPlayingPositionMs = 1_000L,
                consecutiveEndPausedPolls = 0,
                elapsedSinceLastPlayingPollMs = 300L
            )
        )
        assertEquals(
            DlnaCastStateMachine.PollDecision.SyncPlayback(
                isPlaying = false,
                positionMs = 1_000L,
                durationMs = 240_000L,
                markStartedPlaying = false
            ),
            genuineStartPause
        )
    }

    @Test
    fun shouldSendGaplessNextUriToRenderer_skipsShanlingEm7WhileKeepingStandardRenderers() {
        val em7 = DlnaDevice(
            udn = "uuid:em7",
            friendlyName = "Shanling EM7",
            manufacturer = "Shanling",
            modelName = "EM7",
            location = "http://192.168.1.50:49152/description.xml",
            host = "192.168.1.50",
            avTransportControlUrl = "http://192.168.1.50:49152/ctl"
        )
        val standardRenderer = em7.copy(
            udn = "uuid:wiim",
            friendlyName = "WiiM Pro",
            manufacturer = "Linkplay",
            modelName = "WiiM Pro"
        )
        assertFalse(shouldSendGaplessNextUriToRenderer(em7))
        assertTrue(shouldSendGaplessNextUriToRenderer(standardRenderer))
    }
}
