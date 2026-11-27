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
import moe.ouom.neriplayer.core.player.dlna.server.DlnaMediaServer
import moe.ouom.neriplayer.core.player.dlna.session.DLNA_IDLE_POLL_INTERVAL_MS
import moe.ouom.neriplayer.core.player.dlna.session.DLNA_POLL_INTERVAL_MS
import moe.ouom.neriplayer.core.player.dlna.session.DlnaGenaRenewalScheduler
import moe.ouom.neriplayer.core.player.dlna.session.dlnaPollIntervalMs
import moe.ouom.neriplayer.core.player.dlna.session.DLNA_EXTERNAL_RESUME_GRACE_MS
import moe.ouom.neriplayer.core.player.dlna.session.DlnaNetworkAndLockController
import moe.ouom.neriplayer.core.player.dlna.session.followTransport
import moe.ouom.neriplayer.core.player.dlna.session.resolveRemotePlayingForHandoff
import moe.ouom.neriplayer.core.player.dlna.session.shouldAdoptExternalResume
import moe.ouom.neriplayer.core.player.runtime.refresh.YouTubePlaybackRecoveryStrategy
import moe.ouom.neriplayer.core.player.url.resolveYouTubeRequireDirect
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
}
